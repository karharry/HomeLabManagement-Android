package com.techloungegr.homelabmanagement.session

import com.techloungegr.homelabmanagement.model.*
import com.techloungegr.homelabmanagement.net.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*

/** macOS as a managed target over SSH. Shares Docker/Tailscale/users pages with the Linux session. */
class MacSession(machine: Machine, scope: CoroutineScope) : LinuxSession(machine, scope) {

    override fun baseNav(): List<NavItem> = sortNav(listOf(NavItem.Dashboard, NavItem.Storage, NavItem.Power, NavItem.Processes, NavItem.Services,
        NavItem.Startup, NavItem.Software, NavItem.Updates, NavItem.Logs, NavItem.Users, NavItem.Console, NavItem.Alerts))

    override fun extraNav(n: MutableSet<NavItem>) {
        if ("DOCKER" in features) n.add(NavItem.Containers)
        if ("TAILSCALE" in features) n.add(NavItem.Tailscale)
        if ("WIREGUARD" in features) n.add(NavItem.Vpn)
    }

    override suspend fun poll(): Snapshot {
        val text = sh(Scripts.get("mac_SAMPLE_SCRIPT"))
        val sec = sections(text)
        val host = sec["HOST"]
        if (!sec.containsKey("END") || host == null || host.size < 5) throw SshError("Unexpected reply from the machine. Is a POSIX shell available over SSH?")
        val os = "${host[1]} ${host[2]}"; val up = host[3].trim().toDoubleOrNull() ?: 0.0; val memBytes = host[4].trim().toDoubleOrNull() ?: 0.0
        val page = sec["PAGESIZE"]?.firstOrNull()?.trim()?.toDoubleOrNull() ?: 4096.0
        val pages = HashMap<String, Double>()
        for (l in sec["VMSTAT"].orEmpty()) { val i = l.indexOf(':'); if (i > 0) l.substring(i + 1).trim().trimEnd('.').toDoubleOrNull()?.let { pages[l.substring(0, i).trim()] = it } }
        val used = ((pages["Pages active"] ?: 0.0) + (pages["Pages wired down"] ?: 0.0) + (pages["Pages occupied by compressor"] ?: 0.0)) * page
        push(memHistory, if (memBytes > 0) used / memBytes * 100 else null)
        var cpu: Double? = null
        sec["TOP"]?.firstOrNull()?.let { line -> line.split(',').firstOrNull { "idle" in it }?.substringBefore('%')?.trim()?.toDoubleOrNull()?.let { cpu = (100 - it).coerceIn(0.0, 100.0) } }
        push(cpuHistory, cpu)
        val fs = ArrayList<StorageItem>()
        for (l in sec["DF"].orEmpty()) {
            val f = l.split(' ').filter { it.isNotEmpty() }
            if (f.size < 9 || !f[0].startsWith("/dev/")) continue
            val s1 = f[1].toLongOrNull() ?: continue; val u1 = f[2].toLongOrNull() ?: continue
            val mount = f.drop(8).joinToString(" ")
            if ((mount.startsWith("/System/Volumes") && mount != "/System/Volumes/Data") || mount == "/Volumes/Recovery") continue
            fs.add(StorageItem(mount, mount, s1 * 1024, u1 * 1024, f[0]))
        }
        snapStorage = fs
        services = sec["LAUNCHD"].orEmpty().mapNotNull { l ->
            val f = l.split('\t'); if (f.size < 3 || f[2].isEmpty() || f[2].lowercase() == "label") null
            else { val run = f[0] !in listOf("", "-"); ServiceRow(f[2], f[2], f[2], if (run) "running" else "stopped", run, canControl = !f[2].startsWith("com.apple.")) }
        }.sortedBy { it.name }
        var rx = 0.0; var tx = 0.0
        val skip = listOf("lo", "gif", "stf", "utun", "awdl", "llw", "bridge", "anpi", "ap1", "p2p", "vmnet")
        for (l in sec["NETSTAT"].orEmpty()) {
            val f = l.split(' ').filter { it.isNotEmpty() }
            if (f.size < 9 || !f[2].startsWith("<Link") || skip.any { f[0].startsWith(it) }) continue
            val ib = f.getOrNull(f.size - 5)?.toDoubleOrNull(); val ob = f.getOrNull(f.size - 2)?.toDoubleOrNull()
            if (ib != null && ob != null) { rx += ib; tx += ob }
        }
        val (rr, tr) = netRates(rx, tx)
        features.clear(); features.addAll(sec["FEAT"].orEmpty().map { it.trim() }.filter { it.isNotEmpty() })
        refreshNav()
        alerts = emptyList()
        return Snapshot(host[0], os, up, cpu, used, memBytes, rr, tr, null, fs, alerts, cpuHistory.toList(), memHistory.toList())
    }

    override suspend fun reboot() { sh("shutdown -r now", sudo = true) }
    override suspend fun shutdown() { sh("shutdown -h now", sudo = true) }

    override suspend fun loadExtraPage(nav: NavItem, query: String): Page = when (nav) {
        NavItem.Power -> powerPage()
        NavItem.Startup -> startupPage()
        NavItem.Software -> softwarePage()
        else -> super.loadExtraPage(nav, query)
    }

    override suspend fun loadPage(nav: NavItem, query: String): Page = when (nav) {
        NavItem.Cpu, NavItem.Gpu, NavItem.ScheduledTasks, NavItem.Pihole, NavItem.LocalDns, NavItem.Pikvm, NavItem.RaspberryPi, NavItem.Vms -> Page(listOf(Note("Not available for macOS.")))
        else -> super.loadPage(nav, query)
    }

    override suspend fun processesPage(): Page {
        val list = sh(Scripts.get("mac_PROCESSES_SCRIPT")).lines().mapNotNull { raw ->
            val f = raw.trim().split(Regex("\\s+"), 4); if (f.size != 4) return@mapNotNull null
            ProcessRow(f[0].toLongOrNull() ?: return@mapNotNull null, f[3].substringAfterLast('/'), f[1].toDoubleOrNull() ?: return@mapNotNull null, (f[2].toDoubleOrNull() ?: return@mapNotNull null) * 1024)
        }
        return processList(list, memAsBytes = true) { p -> guarded { sh("kill -9 ${p.pid}", sudo = true, merge = true).ifBlank { null } } }
    }

    override suspend fun servicesPage(): Page = serviceList(services) { s, v ->
        guarded {
            val cmd = if (v == "stop") "launchctl kickstart -k gui/$(id -u) ${shQuote(s.name)} 2>&1 || launchctl stop ${shQuote(s.name)}" else "launchctl kickstart -k gui/$(id -u) ${shQuote(s.name)}"
            sh(cmd, sudo = true, merge = true).takeIf { it.lowercase().contains("error") }
        }
    }

    private fun kv(lines: List<String>, key: String): String? = lines.firstNotNullOfOrNull { l ->
        val t = l.trim(); if ((t.startsWith(key) || t.startsWith("\"$key\"")) && '=' in t) t.substringAfter('=').trim() else null
    }

    private suspend fun powerPage(): Page {
        val sec = sections(sh(Scripts.get("mac_POWER_SCRIPT")))
        val therm = sec["THERM"].orEmpty(); val batt = sec["BATT"].orEmpty()
        val src = Regex("'([^']+)'").find(sec["PS"]?.firstOrNull().orEmpty())?.groupValues?.get(1)
        val speed = kv(therm, "CPU_Speed_Limit")?.toDoubleOrNull(); val sched = kv(therm, "CPU_Scheduler_Limit")?.toDoubleOrNull()
        val max = kv(batt, "MaxCapacity")?.toDoubleOrNull() ?: 0.0
        val b = ArrayList<Block>()
        b.add(Stats(listOf("Power source" to (src ?: "–"), "CPU speed limit" to (speed?.let { "${it.toInt()}%" } ?: "–"), "Scheduler limit" to (sched?.let { "${it.toInt()}%" } ?: "–"))))
        if (max > 0) {
            val cur = kv(batt, "CurrentCapacity")?.toDoubleOrNull() ?: 0.0; val design = kv(batt, "DesignCapacity")?.toDoubleOrNull() ?: 0.0
            b.add(Gauge("Battery", (cur / max).toFloat().coerceIn(0f, 1f), "${(cur / max * 100).toInt()}%${if (kv(batt, "IsCharging") == "Yes") " · charging" else ""}"))
            b.add(Stats(listOfNotNull(kv(batt, "CycleCount")?.let { "Cycles" to it }, if (design > 0) "Health" to "${(max / design * 100).coerceAtMost(100.0).toInt()}%" else null)))
        }
        return Page(b, refreshMs = 10_000)
    }

    private suspend fun startupPage(): Page {
        val text = sh(Scripts.get("mac_STARTUP_SCRIPT"), sudo = true, merge = true)
        val parts = text.split("##DISABLED")
        val disabled = HashSet<String>()
        parts.getOrNull(1)?.lines()?.forEach { l -> val t = l.trim(); if (t.startsWith("\"") && "=> disabled" in t) { val e = t.indexOf('"', 1); if (e > 0) disabled.add(t.substring(1, e)) } }
        val items = parts[0].lines().mapNotNull { l -> val f = l.split('|'); if (f.size < 3 || f[0] != "ITEM") null else Triple(f[2], f[1], f.getOrNull(3).orEmpty()) }.sortedBy { it.first.lowercase() }
        return Page(items.map { (label, domain, scope) ->
            val dis = label in disabled
            Item(label, label, scope, badge = if (dis) "Disabled" else "Enabled", badgeLevel = if (dis) Level.Warning else Level.Info,
                actions = listOf(Act(if (dis) "Enable" else "Disable", confirm = if (!dis) "Disable $label at startup?" else null) {
                    guarded { sh("launchctl ${if (dis) "enable" else "disable"} $domain/${shQuote(label)}", sudo = true, merge = true).ifBlank { null } } }))
        }, filterable = true)
    }

    private suspend fun softwarePage(): Page {
        val list = sh(Scripts.get("mac_SOFTWARE_SCRIPT")).lines().mapNotNull { l ->
            val f = l.split('|'); if (f.size < 2) null else if (f[0] == "APP") Triple(f[1], f.getOrNull(2).orEmpty(), "Application")
            else if (f[0] == "BREW") f[1].split(" ", limit = 2).let { Triple(it[0], it.getOrElse(1) { "" }, "Homebrew") } else null
        }.sortedBy { it.first.lowercase() }
        return Page(list.map { Item("${it.third}-${it.first}", it.first, it.third, it.second) }, filterable = true)
    }

    override suspend fun updatesPage(): Page {
        val parts = sh(Scripts.get("mac_UPDATES_SCRIPT")).split("---BREW---")
        val sw = if ("no new software available" in parts[0].lowercase()) emptyList() else parts[0].lines().map { it.trim() }.filter { it.startsWith("* Label:") }.map { it.removePrefix("* Label:").trim() }
        val brew = ArrayList<Pair<String, String>>()
        (parseJson(parts.getOrNull(1)) as? JsonObject)?.let { o ->
            for (key in listOf("formulae", "casks")) o.list(key).forEach { r ->
                val name = r.str("name") ?: (r["name"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.content } ?: return@forEach
                val inst = r.strList("installed_versions").joinToString(", "); val cur = r.str("current_version") ?: ""
                brew.add(name to if (inst.isNotEmpty()) "$inst → $cur" else cur)
            }
        }
        val items = sw.map { Item("sw-$it", it, "macOS", badge = if ("security" in it.lowercase()) "Security" else "", badgeLevel = Level.Critical) } + brew.map { Item("brew-${it.first}", it.first, "Homebrew", it.second) }
        val up = Act("brew upgrade", confirm = "Run “brew upgrade”? macOS system updates still need softwareupdate run manually.") {
            guarded { sh("brew upgrade 2>&1", merge = true).takeLast(3000) } }
        return Page(if (items.isEmpty()) listOf(Note("Everything is up to date.")) else items, topActions = if (brew.isEmpty()) emptyList() else listOf(up), filterable = true)
    }

    override suspend fun logsPage(query: String): Page {
        val q = query.trim()
        val cmd = if (q.isEmpty()) "log show --last 15m --style compact --predicate 'messageType == 16 OR messageType == 17' 2>/dev/null | tail -300"
        else "log show --last 1h --style compact --predicate ${shQuote("process == \"$q\"")} 2>/dev/null | tail -300"
        return Page(listOf(Mono(sh(cmd, sudo = true, merge = true).ifBlank { "(no log lines)" })), queryHint = "Process name (blank for errors/faults)")
    }

    override suspend fun usersPage(): Page {
        val rows = sh("who").lines().mapNotNull { raw -> val f = raw.split(' ').filter { it.isNotEmpty() }; if (f.size < 2) null else Item("${f[0]}-${f[1]}", f[0], f[1], f.drop(2).joinToString(" ")) }
        return Page(if (rows.isEmpty()) listOf(Note("Nobody is logged in.")) else rows)
    }

    override suspend fun vpnPage(): Page {
        val parts = sh(Scripts.get("mac_WIREGUARD_STATUS_SCRIPT"), sudo = true, merge = true).split("---DUMP---")
        val names = parts[0].lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("/") }
        val tunnels = WireGuard.tunnels(names, parts.getOrNull(1).orEmpty())
        val wgdir = "\$(brew --prefix 2>/dev/null)/etc/wireguard"
        return Page(tunnels.map { t ->
            Item(t.name, t.name, if (t.up) "${t.endpoint} · rx ${Fmt.bytes(t.rx)} · tx ${Fmt.bytes(t.tx)}" else "", badge = if (t.up) "Connected" else "Disconnected", badgeLevel = if (t.up) Level.Info else Level.Warning,
                actions = listOf(if (t.up) Act("Disconnect") { guarded { sh("wg-quick down ${t.name}", sudo = true, merge = true).takeIf { it.lowercase().contains("error") } } }
                else Act("Connect") { guarded { sh("wg-quick up ${t.name}", sudo = true, merge = true).takeIf { it.lowercase().contains("error") } } },
                    Act("Remove", confirm = "Remove tunnel ${t.name}?", danger = true) { guarded { sh("wg-quick down ${t.name} 2>/dev/null; rm -f \"$wgdir/${t.name}.conf\"", sudo = true, merge = true); null } }))
        }, forms = listOf(FormAct("Add tunnel", listOf(Field("name", "Name"), Field("conf", "Config", multiline = true))) { f ->
            guarded {
                val name = f["name"].orEmpty().trim(); if (!WireGuard.validName(name)) return@guarded "Use letters, numbers, dashes and underscores only."
                val b64 = java.util.Base64.getEncoder().encodeToString(f["conf"].orEmpty().toByteArray())
                sh("mkdir -p \"$wgdir\"; echo $b64 | base64 -D > \"$wgdir/$name.conf\"; chmod 600 \"$wgdir/$name.conf\"", sudo = true, merge = true).ifBlank { null }
            }
        }), error = if (tunnels.isEmpty()) "No tunnels yet." else null)
    }
}
