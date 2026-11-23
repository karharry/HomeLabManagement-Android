package com.techloungegr.homelabmanagement.session

import com.techloungegr.homelabmanagement.data.Store
import com.techloungegr.homelabmanagement.model.*
import com.techloungegr.homelabmanagement.net.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Debian/Ubuntu, Raspberry Pi OS and Arch over SSH + POSIX shell (also the base for Bazzite/CachyOS). */
open class LinuxSession(machine: Machine, scope: CoroutineScope) : SshSession(machine, scope) {
    protected var prevCpu: Pair<Double, Double>? = null
    protected var prevCores: Map<String, Pair<Double, Double>> = emptyMap()
    protected var cores: List<Pair<String, Double>> = emptyList()
    protected var cpuName = ""; protected var cpuMhz = 0.0; protected var nproc = 1; protected var virt = false
    protected val features = HashSet<String>()
    protected var services: List<ServiceRow> = emptyList()
    private var prevRapl: Pair<Double, Long>? = null
    protected var lastPower = 0L
    protected var power: Double? = null
    protected var snapStorage: List<StorageItem> = emptyList()
    protected var alerts: List<AlertItem> = emptyList()

    override fun baseNav(): List<NavItem> = sortNav(buildList {
        addAll(listOf(NavItem.Dashboard, NavItem.Storage, NavItem.Cpu, NavItem.Processes, NavItem.Services, NavItem.ScheduledTasks,
            NavItem.Updates, NavItem.Logs, NavItem.Users, NavItem.Console, NavItem.Alerts))
    })

    protected fun refreshNav() {
        val n = HashSet<NavItem>(baseNav())
        if ("NVIDIA" in features) n.add(NavItem.Gpu)
        if ("DOCKER" in features) n.add(NavItem.Containers)
        if ("PIHOLE" in features) { n.add(NavItem.Pihole); n.add(NavItem.LocalDns) }
        if ("PIKVM" in features) n.add(NavItem.Pikvm)
        if ("TAILSCALE" in features) n.add(NavItem.Tailscale)
        if ("RASPI" in features) n.add(NavItem.RaspberryPi)
        if ("WIREGUARD" in features || "PROTONVPN" in features) n.add(NavItem.Vpn)
        if ("LIBVIRT" in features) n.add(NavItem.Vms)
        extraNav(n)
        setNav(n)
    }
    protected open fun extraNav(n: MutableSet<NavItem>) {}

    private fun cpuTicks(f: List<String>): Pair<Double, Double>? {
        val v = f.filter { it.isNotEmpty() }.mapNotNull { it.toDoubleOrNull() }
        if (v.size < 4) return null
        val idle = v[3] + (if (v.size > 4) v[4] else 0.0)
        val total = v.take(8).sum()
        return (total - idle) to total
    }

    override suspend fun poll(): Snapshot {
        val text = sh(Scripts.get("linux_SAMPLE_SCRIPT"))
        val sec = sections(text)
        val host = sec["HOST"]
        if (!sec.containsKey("END") || host == null || host.size < 6)
            throw SshError("Unexpected reply from the machine. Is a POSIX shell available over SSH?")
        val hostName = host[0]; val os = host[1]; val up = host[4].toDoubleOrNull() ?: 0.0
        nproc = host.getOrNull(5)?.trim()?.toIntOrNull() ?: 1

        var total = 0.0; var avail = 0.0
        for (l in sec["MEM"].orEmpty()) {
            val i = l.indexOf(':'); if (i < 0) continue
            val kb = l.substring(i + 1).trim().split(Regex("\\s+")).firstOrNull()?.toDoubleOrNull() ?: continue
            when (l.substring(0, i)) { "MemTotal" -> total = kb * 1024; "MemAvailable" -> avail = kb * 1024 }
        }
        val used = maxOf(0.0, total - avail)
        push(memHistory, if (total > 0) used / total * 100 else null)

        val fs = ArrayList<StorageItem>(); val seen = HashSet<String>()
        for (l in sec["DF"].orEmpty()) {
            val f = l.trim().split(Regex("\\s+"), 7)
            if (f.size < 7) continue
            val size = f[2].toLongOrNull() ?: continue; val u = f[3].toLongOrNull() ?: continue
            if (size <= 0 || f[0].startsWith("/dev/loop") || f[6].startsWith("/var/lib/docker") || f[6].startsWith("/snap") || f[1] == "zfs") continue
            if (!seen.add(f[0] + f[6])) continue
            fs.add(StorageItem(f[6], f[6], size, u, f[0]))
        }
        for (l in sec["ZPOOL"].orEmpty()) {
            val f = l.split('\t'); if (f.size < 5) continue
            val size = f[1].toLongOrNull() ?: continue; val alloc = f[2].toLongOrNull() ?: continue
            fs.add(StorageItem("zfs-${f[0]}", "ZFS ${f[0]}", size, alloc, f[4], f[4] == "ONLINE", f[4]))
        }
        snapStorage = fs

        services = parseServices(sec["SVC"].orEmpty()).sortedBy { it.name }
        alerts = services.filter { it.state == "failed" }.map { AlertItem("svc-${it.name}", Level.Critical, "Service failed: ${it.name}") }

        var cpu: Double? = null
        val stat = sec["STAT"].orEmpty()
        stat.firstOrNull { it.startsWith("cpu ") }?.let { line ->
            cpuTicks(line.split(' ').drop(1))?.let { t ->
                val p = prevCpu
                if (p != null && t.second > p.second) cpu = ((t.first - p.first) / (t.second - p.second) * 100).coerceIn(0.0, 100.0)
                prevCpu = t
            }
        }
        push(cpuHistory, cpu)
        val ci = sec["CPUINFO"].orEmpty()
        cpuName = ci.getOrNull(0)?.trim().orEmpty(); cpuMhz = ci.getOrNull(1)?.trim()?.toDoubleOrNull() ?: 0.0

        val coreTicks = LinkedHashMap<String, Pair<Double, Double>>(); val newCores = ArrayList<Pair<String, Double>>()
        for (l in stat) {
            val f = l.split(' '); val name = f[0]
            if (!name.startsWith("cpu") || name == "cpu" || !name.substring(3).all { it.isDigit() } || name.length == 3) continue
            val t = cpuTicks(f.drop(1)) ?: continue
            coreTicks[name] = t
            val p = prevCores[name]
            newCores.add(name to if (p != null && t.second > p.second) ((t.first - p.first) / (t.second - p.second) * 100).coerceIn(0.0, 100.0) else 0.0)
        }
        prevCores = coreTicks; cores = newCores.sortedBy { it.first.length * 1000 + it.first.hashCode().let { _ -> 0 } }.sortedWith(compareBy({ it.first.length }, { it.first }))

        var rx = 0.0; var tx = 0.0
        val skip = listOf("veth", "docker", "br-", "virbr", "tap", "tailscale", "wg")
        for (l in sec["NET"].orEmpty()) {
            val i = l.indexOf(':'); if (i < 0) continue
            val n = l.substring(0, i).trim()
            if (n == "lo" || skip.any { n.startsWith(it) }) continue
            val f = l.substring(i + 1).trim().split(Regex("\\s+")).mapNotNull { it.toDoubleOrNull() }
            if (f.size >= 9) { rx += f[0]; tx += f[8] }
        }
        val (rr, tr) = netRates(rx, tx)

        features.clear(); features.addAll(sec["FEAT"].orEmpty().map { it.trim() }.filter { it.isNotEmpty() })
        virt = "VTX" in features
        refreshNav()
        if (System.currentTimeMillis() - lastPower > 10_000) { lastPower = System.currentTimeMillis(); samplePower() }
        extraPoll()
        return Snapshot(hostName, os, up, cpu, used, total, rr, tr, power, fs, alerts, cpuHistory.toList(), memHistory.toList())
    }

    protected open suspend fun extraPoll() {}

    private fun parseServices(lines: List<String>): List<ServiceRow> = lines.mapNotNull { line ->
        var f = line.split(' ').filter { it.isNotEmpty() }
        if (f.isNotEmpty() && (f[0] == "●" || f[0] == "*")) f = f.drop(1)
        if (f.size < 4 || !f[0].endsWith(".service")) return@mapNotNull null
        val name = f[0].removeSuffix(".service")
        if ('@' in name && f[2] != "failed") return@mapNotNull null
        ServiceRow(name, name, name, f[2], f[2] == "active" && f[3] == "running")
    }

    private suspend fun samplePower() {
        try {
            val out = sh(Scripts.get("linux_POWER_SCRIPT"), sudo = true, merge = true)
            val parts = out.split("---GPU---")
            val rapl = parts.getOrNull(0)?.trim().orEmpty(); val gpu = parts.getOrNull(1)?.trim()?.toDoubleOrNull()
            var cpuW: Double? = null
            rapl.toDoubleOrNull()?.let { e ->
                val now = System.currentTimeMillis(); val p = prevRapl
                if (p != null) { val dt = (now - p.second) / 1000.0; if (dt > 0.5 && e >= p.first) cpuW = (e - p.first) / 1_000_000 / dt }
                prevRapl = e to now
            }
            power = if (cpuW != null || gpu != null) (cpuW ?: 0.0) + (gpu ?: 0.0) else null
        } catch (_: Exception) {}
    }

    override suspend fun reboot() { sh("systemctl reboot", sudo = true) }
    override suspend fun shutdown() { sh("systemctl poweroff", sudo = true) }

    // ------------------------------------------------------------------ pages
    override suspend fun loadPage(nav: NavItem, query: String): Page = when (nav) {
        NavItem.Storage -> storagePage(snapStorage)
        NavItem.Cpu -> cpuPage()
        NavItem.Gpu -> gpuPage()
        NavItem.Processes -> processesPage()
        NavItem.Services -> servicesPage()
        NavItem.ScheduledTasks -> scheduledPage()
        NavItem.Updates -> updatesPage()
        NavItem.Logs -> logsPage(query)
        NavItem.Users -> usersPage()
        NavItem.Containers -> containersPage()
        NavItem.Pihole -> piholePage()
        NavItem.LocalDns -> localDnsPage()
        NavItem.Pikvm -> pikvmPage()
        NavItem.Tailscale -> tailscalePage()
        NavItem.RaspberryPi -> raspberryPage()
        NavItem.Vpn -> vpnPage()
        NavItem.Vms -> vmsPage()
        NavItem.Alerts -> alertsPage(alerts)
        NavItem.History -> historyPage(query.ifBlank { "24h" })
        else -> loadExtraPage(nav, query)
    }
    protected open suspend fun loadExtraPage(nav: NavItem, query: String): Page = Page(listOf(Note("Not available.")))

    protected open suspend fun cpuPage(): Page {
        val blocks = ArrayList<Block>()
        blocks.add(Stats(listOf("Model" to cpuName.ifBlank { "–" }, "Threads" to nproc.toString(), "Clock" to Fmt.mhz(cpuMhz),
            "Virtualization" to if (virt) "Yes" else "No")))
        blocks.add(Chart("Total load", cpuHistory.toList(), "%", 100f))
        blocks.add(Heading("Per-core load"))
        cores.forEach { (n, u) -> blocks.add(Gauge(n.uppercase(), (u / 100).toFloat(), "${u.toInt()}%")) }
        return Page(blocks, refreshMs = 3000)
    }

    protected open suspend fun gpuPage(): Page {
        val out = sh(Scripts.get("linux_GPU_DETAIL_SCRIPT"))
        val rows = out.lines().filter { it.isNotBlank() }.map { l -> l.split(",").map { it.trim() } }.filter { it.size >= 16 }
        if (rows.isEmpty()) return Page(error = "No NVIDIA GPU data (nvidia-smi returned nothing).")
        return Page(rows.map { f ->
            val used = f[4].toDoubleOrNull(); val tot = f[5].toDoubleOrNull()
            Item(f[0], f[1], "Driver ${f[14]} · ${f[15]}", "${f[2]}% util", progress = f[2].toFloatOrNull()?.div(100),
                detail = "VRAM ${used?.toInt()} / ${tot?.toInt()} MiB · ${f[6]}°C · ${f[7]} / ${f[8]} W · fan ${f[9]}% · core ${f[10]}/${f[11]} MHz · mem ${f[12]}/${f[13]} MHz")
        }, refreshMs = 3000)
    }

    protected open suspend fun processesPage(): Page {
        val text = sh(Scripts.get("linux_PROCESSES_SCRIPT"))
        val list = text.lines().mapNotNull { raw ->
            val f = raw.trim().split(Regex("\\s+"), 5); if (f.size != 5) return@mapNotNull null
            val pid = f[0].toLongOrNull() ?: return@mapNotNull null
            ProcessRow(pid, f[4], f[2].toDoubleOrNull() ?: return@mapNotNull null, f[3].toDoubleOrNull() ?: return@mapNotNull null, f[1])
        }
        return processList(list) { p -> guarded { sh("kill -9 ${p.pid}", sudo = true, merge = true).ifBlank { null } } }
    }

    protected fun processList(list: List<ProcessRow>, memAsBytes: Boolean = false, kill: suspend (ProcessRow) -> String?): Page =
        Page(list.map { p ->
            Item(p.pid.toString(), p.name, listOf("PID ${p.pid}", p.user).filter { it.isNotBlank() }.joinToString(" · "),
                "%.1f%% CPU · %s".format(p.cpu, if (memAsBytes) Fmt.bytes(p.mem) else "%.1f%% mem".format(p.mem)), progress = (p.cpu / 100).toFloat().coerceIn(0f, 1f),
                actions = listOf(Act("Kill", confirm = "Kill ${p.name} (PID ${p.pid})?", danger = true) { kill(p) }))
        }, filterable = true, refreshMs = 5000)

    protected fun serviceList(list: List<ServiceRow>, control: suspend (ServiceRow, String) -> String?): Page =
        Page(list.map { s ->
            Item(s.id, s.displayName, s.name.takeIf { it != s.displayName }.orEmpty(), s.startMode, badge = s.state,
                badgeLevel = if (s.isRunning) Level.Info else if (s.state == "failed") Level.Critical else Level.Warning,
                actions = if (!s.canControl) emptyList() else listOf("start", "stop", "restart").map { v ->
                    Act(v.replaceFirstChar { it.uppercase() }, confirm = if (v != "start") "${v.replaceFirstChar { it.uppercase() }} ${s.displayName}?" else null, danger = v == "stop") { control(s, v) }
                })
        }, filterable = true)

    protected open suspend fun servicesPage(): Page {
        val text = sh(Scripts.get("linux_SAMPLE_SCRIPT"))
        services = parseServices(sections(text)["SVC"].orEmpty()).sortedBy { it.name }
        return serviceList(services) { s, v -> guarded { val o = sh("systemctl $v ${shQuote(s.name + ".service")}", sudo = true, merge = true); o.trim().ifBlank { null } } }
    }

    protected open suspend fun scheduledPage(): Page {
        val parts = sh(Scripts.get("linux_SCHEDULED_SCRIPT"), sudo = true, merge = true).split("---TIMERS---")
        val cron = parts[0].lines().filter { it.isNotBlank() && !it.trim().startsWith("#") }
        val timers = parts.getOrNull(1)?.lines()?.filter { it.isNotBlank() }.orEmpty()
        val b = ArrayList<Block>()
        b.add(Heading("Cron jobs (${cron.size})")); cron.forEach { b.add(Mono(it)) }
        b.add(Heading("Systemd timers (${timers.size})")); timers.forEach { b.add(Mono(it)) }
        return Page(b)
    }

    protected open suspend fun updatesPage(): Page {
        val arch = machine.kind == MachineKind.arch
        val cmd = if (arch) "pacman -Qu 2>/dev/null" else "apt list --upgradable 2>/dev/null | tail -n +2"
        val text = sh(cmd, sudo = true, merge = false)
        data class U(val name: String, val ver: String, val sec: Boolean)
        val list = text.lines().mapNotNull { line ->
            if (arch) { val f = line.trim().split(Regex("\\s+")); if (f.size < 4) null else U(f[0], f[3], false) }
            else { if ('/' !in line) null else { val rest = line.substringAfter('/').trim().split(Regex("\\s+")); if (rest.size < 2) null else U(line.substringBefore('/'), rest[1], "security" in rest[0].lowercase()) } }
        }
        val up = Act("Upgrade all", confirm = "Upgrade all ${list.size} packages now?") {
            guarded { sh(if (arch) "pacman -Syu --noconfirm" else "export DEBIAN_FRONTEND=noninteractive; apt-get update && apt-get -y upgrade", sudo = true, merge = true).takeLast(3000) }
        }
        return Page(if (list.isEmpty()) listOf(Note("Everything is up to date.")) else list.map { Item(it.name, it.name, it.ver, badge = if (it.sec) "Security" else "", badgeLevel = Level.Critical) },
            topActions = if (list.isEmpty()) emptyList() else listOf(up), filterable = true)
    }

    protected open suspend fun logsPage(query: String): Page {
        val unit = query.trim()
        val cmd = if (unit.isEmpty()) "journalctl -n 300 --no-pager" else "journalctl -u ${shQuote(unit)} -n 300 --no-pager"
        return Page(listOf(Mono(sh(cmd, sudo = true, merge = true).ifBlank { "(no log lines)" })), queryHint = "Service/unit name (blank for everything)")
    }

    protected open suspend fun usersPage(): Page {
        val rows = sh("who").lines().mapNotNull { raw ->
            val f = raw.split(' ').filter { it.isNotEmpty() }; if (f.size < 2) null
            else Item("${f[0]}-${f[1]}", f[0], f[1] + (f.firstOrNull { it.startsWith("(") }?.let { " · ${it.trim('(', ')')}" } ?: ""), f.drop(2).filter { !it.startsWith("(") }.joinToString(" "))
        }
        return Page(if (rows.isEmpty()) listOf(Note("Nobody is logged in.")) else rows)
    }

    protected suspend fun dockerRun(script: String): String = sh(script, sudo = true, merge = true, r = aux)

    protected open suspend fun containersPage(): Page {
        val text = try { dockerRun(Scripts.get("docker_SAMPLE_SCRIPT")) } catch (e: Exception) { return Page(error = e.message) }
        val list = Docker.parse(text)
        if (list.isEmpty() && !text.contains("{")) return Page(error = text.replace("---STATS---", "").trim().take(300).ifBlank { "No containers." })
        return Page(list.map { c ->
            val verbs = if (c.running) listOf("stop", "restart") else listOf("start")
            Item(c.name, c.name, c.image + if (c.ports.isNotBlank()) " · ${c.ports}" else "", listOfNotNull(c.cpu, c.memory).joinToString(" · "),
                badge = c.state, badgeLevel = if (c.running) Level.Info else Level.Warning, detail = c.status,
                actions = verbs.map { v -> Act(v.replaceFirstChar { it.uppercase() }, confirm = if (v != "start") "${v.replaceFirstChar { it.uppercase() }} ${c.name}?" else null) {
                    guarded { dockerRun("docker $v ${shQuote(c.name)}").takeIf { !it.contains(c.name) } }
                } } + Act("Logs") { guarded { dockerRun("docker logs --tail 300 ${shQuote(c.name)}").ifBlank { "(no output)" } } })
        }, filterable = true, refreshMs = 10_000, error = if (list.isEmpty()) "No containers." else null)
    }

    // ---- Pi-hole ----
    private var pihole: PiholeClient? = null
    private fun piholeClient(pw: String) = pihole ?: PiholeClient(machine.host, pw).also { pihole = it }

    private suspend fun piholePage(): Page {
        val text = try { sh(Scripts.get("linux_PIHOLE_STATUS_SCRIPT")) } catch (e: Exception) { "" }
        val parts = text.split("---JSON---"); val low = parts[0].lowercase()
        var enabled: Boolean? = if ("enabled" in low || "disabled" in low) "disabled" !in low else null
        var q: Double? = null; var b: Double? = null; var pct: Double? = null; var dom: Double? = null
        parseJson(parts.getOrNull(1))?.let { q = it.num("dns_queries_today"); b = it.num("ads_blocked_today"); pct = it.num("ads_percentage_today"); dom = it.num("domains_being_blocked") }
        var err: String? = null
        val pw = Store.piholePasswordOf(id)
        if (pw.isNotEmpty()) try {
            val c = piholeClient(pw); val s = c.get("stats/summary"); val qq = s["queries"]; val g = s["gravity"]
            qq.num("total")?.let { q = it }; qq.num("blocked")?.let { b = it }; qq.num("percent_blocked")?.let { pct = it }; g.num("domains_being_blocked")?.let { dom = it }
            try { c.get("dns/blocking").str("blocking")?.let { enabled = it.lowercase() == "enabled" } } catch (_: Exception) {}
        } catch (e: Exception) { err = e.message }
        else if (enabled == null) err = "Couldn't read Pi-hole stats over SSH. If this is Pi-hole v6, add its web admin password in the machine settings — v6 needs the API for stats."
        val acts = listOf(Act(if (enabled == true) "Disable for 5 minutes" else "Enable", confirm = if (enabled == true) "Disable blocking for 5 minutes?" else null) {
            guarded { sh(if (enabled == true) "pihole disable 5m" else "pihole enable", sudo = true, merge = true); null } },
            Act("Disable for 1 hour", confirm = "Disable blocking for 1 hour?") { guarded { sh("pihole disable 1h", sudo = true, merge = true); null } })
        return Page(listOfNotNull(
            Stats(listOf("Blocking" to (if (enabled == true) "Enabled" else if (enabled == false) "Disabled" else "Unknown"), "Queries today" to (q?.toLong()?.toString() ?: "–"),
                "Blocked" to (b?.toLong()?.toString() ?: "–"), "Percent blocked" to (pct?.let { "%.1f%%".format(it) } ?: "–"), "On blocklist" to (dom?.toLong()?.toString() ?: "–"))),
            err?.let { Note(it, warn = true) }), topActions = acts, refreshMs = 10_000)
    }

    private fun extractHosts(d: JsonElement?): List<String> {
        if (d is JsonArray) return d.mapNotNull { (it as? JsonPrimitive)?.content }
        for (path in listOf(listOf("config", "dns", "hosts"), listOf("dns", "hosts"), listOf("hosts"))) {
            var n: JsonElement? = d
            for (k in path) n = if (n is JsonObject) n[k] else null
            if (n is JsonArray) return n.mapNotNull { (it as? JsonPrimitive)?.content }
        }
        return emptyList()
    }

    private suspend fun dnsRecords(): List<Pair<String, String>> {
        val pw = Store.piholePasswordOf(id)
        val lines = if (pw.isNotEmpty()) extractHosts(piholeClient(pw).get("config/dns/hosts")) else sh("cat /etc/pihole/custom.list 2>/dev/null", sudo = true, merge = true).lines()
        return lines.mapNotNull { l -> val t = l.trim(); if (t.isEmpty() || t.startsWith("#")) null else t.split(Regex("\\s+")).let { if (it.size < 2) null else it[0] to it[1] } }.sortedBy { it.second.lowercase() }
    }

    private suspend fun localDnsPage(): Page {
        val recs = try { dnsRecords() } catch (e: Exception) { return Page(error = e.message) }
        val pw = Store.piholePasswordOf(id)
        suspend fun setHosts(f: (List<String>) -> List<String>) {
            val cur = extractHosts(piholeClient(pw).get("config/dns/hosts"))
            piholeClient(pw).patch("config", buildJsonObject { put("config", buildJsonObject { put("dns", buildJsonObject { put("hosts", JsonArray(f(cur).map { JsonPrimitive(it) })) }) }) })
        }
        val add = FormAct("Add record", listOf(Field("domain", "Domain"), Field("ip", "IP address"))) { f ->
            guarded {
                val d = f["domain"].orEmpty().trim(); val ip = f["ip"].orEmpty().trim()
                if (d.isEmpty() || ip.isEmpty()) return@guarded "Enter both a domain and an IP address."
                if (pw.isNotEmpty()) setHosts { l -> l.filter { !it.trim().endsWith(" $d") } + "$ip $d"; }.let { null }
                else sh("pihole -a hostrecord ${shQuote(d)} ${shQuote(ip)}", sudo = true, merge = true).takeIf { it.lowercase().contains("error") }
            }
        }
        return Page(recs.map { (ip, dom) -> Item(dom, dom, ip, actions = listOf(Act("Remove", confirm = "Remove $dom?", danger = true) {
            guarded {
                if (pw.isNotEmpty()) { setHosts { l -> l.filter { !it.trim().endsWith(" $dom") } }; null }
                else sh("pihole -a hostrecord ${shQuote(dom)}", sudo = true, merge = true).takeIf { it.lowercase().contains("error") }
            } })) }, forms = listOf(add), filterable = true, error = if (recs.isEmpty()) "No local DNS records." else null)
    }

    private suspend fun pikvmPage(): Page {
        val out = sh(Scripts.get("linux_PIKVM_STATUS_SCRIPT")).lines().map { it.trim() }
        val active = out.getOrNull(0) == "active"; val rw = out.getOrNull(1) == "rw"
        return Page(listOf(Stats(listOf("kvmd" to if (active) "Running" else "Not running", "Root filesystem" to if (rw) "Writable" else "Read-only"))),
            topActions = listOf(Act(if (rw) "Make read-only" else "Make writable", confirm = "Change the root filesystem mode?") { guarded { sh(if (rw) "ro" else "rw", sudo = true, merge = true); null } },
                Act("Restart kvmd", confirm = "Restart kvmd?") { guarded { sh("systemctl restart kvmd", sudo = true, merge = true); null } }))
    }

    protected open suspend fun tailscalePage(): Page {
        val s = try { Tailscale.parse(sh("tailscale status --json")) } catch (e: Exception) { return Page(error = e.message) }
            ?: return Page(error = "Couldn't read Tailscale status.")
        return Page(listOf<Block>(Stats(listOf("State" to s.state, "Devices" to s.peers.size.toString()))) + s.peers.map {
            Item(it.id, it.host + if (it.self) " (this device)" else "", "${it.ip} · ${it.os}", badge = if (it.exit) "Exit node" else if (it.online) "Online" else "Offline",
                badgeLevel = if (it.online) Level.Info else Level.Warning)
        }, filterable = true)
    }

    private suspend fun raspberryPage(): Page {
        val l = sh(Scripts.get("linux_RASPI_SCRIPT")).lines()
        val temp = Regex("temp=([0-9.]+)").find(l.getOrNull(0).orEmpty())?.groupValues?.get(1)?.toDoubleOrNull()
        val clk = Regex("=([0-9]+)").find(l.getOrNull(1).orEmpty())?.groupValues?.get(1)?.toDoubleOrNull()?.div(1_000_000)
        val hex = l.getOrNull(2).orEmpty().substringAfter('=').trim().removePrefix("0x")
        val v = hex.toIntOrNull(16) ?: 0
        val flags = buildList { if (v and 1 != 0) add("Under-voltage"); if (v and 2 != 0) add("ARM frequency capped"); if (v and 4 != 0) add("Currently throttled"); if (v and 8 != 0) add("Soft temperature limit active") }
        return Page(listOf(Stats(listOf("SoC temperature" to Fmt.temp(temp), "ARM clock" to Fmt.mhz(clk), "Throttling" to if (flags.isEmpty()) "None" else flags.joinToString(", ")))), refreshMs = 5000)
    }

    // ---- VPN (WireGuard + ProtonVPN CLI) ----
    private var protonStatus: String? = null
    protected open suspend fun vpnPage(): Page {
        val blocks = ArrayList<Block>(); val forms = ArrayList<FormAct>()
        if ("WIREGUARD" in features) {
            val parts = sh(Scripts.get("linux_WIREGUARD_STATUS_SCRIPT"), sudo = true, merge = true).split("---DUMP---")
            val names = parts[0].lines().map { it.trim() }.filter { it.isNotEmpty() }
            val tunnels = WireGuard.tunnels(names, parts.getOrNull(1).orEmpty())
            blocks.add(Heading("WireGuard"))
            tunnels.forEach { t ->
                blocks.add(Item(t.name, t.name, if (t.up) "${t.endpoint} · handshake ${t.handshake} · rx ${Fmt.bytes(t.rx)} · tx ${Fmt.bytes(t.tx)}" else "",
                    badge = if (t.up) "Connected" else "Disconnected", badgeLevel = if (t.up) Level.Info else Level.Warning,
                    actions = listOf(if (t.up) Act("Disconnect") { guarded { sh("wg-quick down ${t.name}", sudo = true, merge = true).takeIf { o -> o.lowercase().contains("error") } } }
                    else Act("Connect") { guarded { sh("wg-quick up ${t.name}", sudo = true, merge = true).takeIf { o -> o.lowercase().contains("error") } } },
                        Act("Remove", confirm = "Remove tunnel ${t.name}?", danger = true) { guarded { sh("wg-quick down ${t.name} 2>/dev/null; rm -f /etc/wireguard/${t.name}.conf", sudo = true, merge = true); null } })))
            }
            if (tunnels.isEmpty()) blocks.add(Note("No tunnels configured."))
            forms.add(FormAct("Add WireGuard tunnel", listOf(Field("name", "Name"), Field("conf", "Config", multiline = true))) { f ->
                guarded {
                    val name = f["name"].orEmpty().trim()
                    if (!WireGuard.validName(name)) return@guarded "Use letters, numbers, dashes and underscores only."
                    val b64 = java.util.Base64.getEncoder().encodeToString(f["conf"].orEmpty().toByteArray())
                    sh("echo $b64 | base64 -d > /tmp/wg-$name.conf\ninstall -m 600 /tmp/wg-$name.conf /etc/wireguard/$name.conf\nrm -f /tmp/wg-$name.conf\n", sudo = true, merge = true).ifBlank { null }
                }
            })
        }
        if ("PROTONVPN" in features) {
            blocks.add(Heading("ProtonVPN CLI"))
            blocks.add(Mono(protonStatus ?: sh("protonvpn-cli status", merge = true).also { protonStatus = it }))
            forms.add(FormAct("Proton login", listOf(Field("user", "Username"), Field("pw", "Password", secret = true))) { f ->
                guarded { sh("printf '%s\\n%s\\n' ${shQuote(f["user"].orEmpty())} ${shQuote(f["pw"].orEmpty())} | protonvpn-cli login ${shQuote(f["user"].orEmpty())}", merge = true).takeLast(500) }
            })
            blocks.add(Act("Connect fastest") { guarded { sh("protonvpn-cli c -f", sudo = true, merge = true).also { protonStatus = null } } }.let { a -> Item("pc", "Connect to fastest server", actions = listOf(a, Act("Disconnect") { guarded { sh("protonvpn-cli d", sudo = true, merge = true).also { protonStatus = null } } })) })
        }
        return Page(blocks, forms = forms)
    }

    // ---- libvirt VMs ----
    private suspend fun vmsPage(): Page {
        val out = sh(Scripts.get("linux_VMS_SCRIPT"), sudo = true, merge = true)
        val vms = out.lines().filter { it.startsWith("VM|") }.mapNotNull { l ->
            val p = l.split('|'); if (p.size < 6) null else listOf(p[1].trim(), p[2].trim().lowercase(), p[3].trim().lowercase(), p[4].trim(), p[5].trim())
        }.sortedBy { it[0].lowercase() }
        return Page(vms.map { v ->
            val running = v[1] == "running"; val mem = v[4].filter { it.isDigit() }.toLongOrNull()?.let { it / 1024 }
            fun va(label: String, verb: String, confirm: String? = null, danger: Boolean = false) = Act(label, confirm, danger) {
                guarded { sh("virsh -c qemu:///system $verb ${shQuote(v[0])}", sudo = true, merge = true); delay(2000); null } }
            Item(v[0], v[0], "${v[3]} vCPU" + (mem?.let { " · $it MiB" } ?: "") + if (v[2] in listOf("enable", "yes")) " · autostart" else "",
                badge = v[1], badgeLevel = if (running) Level.Info else Level.Warning,
                actions = if (running) listOf(va("Shut down", "shutdown", "Shut down ${v[0]}?"), va("Restart", "reboot", "Restart ${v[0]}?"), va("Power off", "destroy", "Force power off ${v[0]}?", true)) else listOf(va("Start", "start")))
        }, error = if (vms.isEmpty()) "No virtual machines." else null)
    }
}
