package com.techloungegr.homelabmanagement.session

import com.techloungegr.homelabmanagement.model.*
import com.techloungegr.homelabmanagement.net.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.Request

private fun actionScript(body: String) = "\$ErrorActionPreference='Stop'\ntry{\n$body\n'OK'}catch{'ERROR: '+\$_.Exception.Message}"
private fun prelude() = Scripts.get("win_PRELUDE")

/** Windows over SSH + PowerShell, with LibreHardwareMonitor for sensors. */
class WindowsSession(machine: Machine, scope: CoroutineScope) : SshSession(machine, scope) {
    private var services: List<ServiceRow> = emptyList()
    private var storage: List<StorageItem> = emptyList()
    private var cpuName = ""; private var cpuInfo: JsonObject? = null
    private var cores: List<Triple<String, Double, Double>> = emptyList()
    private var gpuRows: List<JsonObject> = emptyList(); private var gpuEng: List<JsonObject> = emptyList(); private var gpuMem: List<JsonObject> = emptyList()
    private var sensors: List<Sensor> = emptyList(); private var lhmHttp: Boolean? = null
    private var hasWg = false
    private var alerts: List<AlertItem> = emptyList()
    data class Sensor(val name: String, val type: String, val value: Double, val id: String)

    override fun baseNav(): List<NavItem> = sortNav(listOf(NavItem.Dashboard, NavItem.Storage, NavItem.Cpu, NavItem.Gpu, NavItem.Sensors, NavItem.Processes,
        NavItem.Services, NavItem.Startup, NavItem.ScheduledTasks, NavItem.Software, NavItem.Updates, NavItem.Logs, NavItem.Users, NavItem.Console, NavItem.Alerts))

    private fun rows(sec: Map<String, List<String>>, key: String): List<JsonObject> = parseJson(sec[key]?.joinToString("\n")).rows()

    override suspend fun poll(): Snapshot {
        val text = ps(Scripts.get("win_SAMPLE_SCRIPT"))
        val sec = sections(text)
        val h = rows(sec, "HOST").firstOrNull()
        if (!sec.containsKey("END") || h == null) throw SshError(text.trim().take(160).ifBlank { "Unexpected (empty) reply. Is PowerShell available over SSH on this machine?" })
        val memTotal = h.num("memTotal") ?: 0.0
        val perf = rows(sec, "MEM").firstOrNull()
        val avail = perf.num("AvailableBytes") ?: h.num("memFree") ?: 0.0
        val used = maxOf(0.0, memTotal - avail)
        push(memHistory, if (memTotal > 0) used / memTotal * 100 else null)

        val coreRows = rows(sec, "CORES")
        var cpu: Double? = null
        coreRows.firstOrNull { it.str("Name") == "_Total" }?.let { t ->
            val tt = t.num("PercentProcessorTime") ?: 0.0; var u = t.num("PercentProcessorUtility")
            if (u == null || (u == 0.0 && tt > 0)) u = tt
            cpu = u.coerceIn(0.0, 100.0)
        }
        push(cpuHistory, cpu)
        cpuInfo = rows(sec, "CPUINFO").firstOrNull(); cpuName = cpuInfo.str("Name").orEmpty()
        val maxMhz = cpuInfo.num("MaxClockSpeed") ?: 0.0
        cores = coreRows.mapNotNull { r ->
            val name = r.str("Name") ?: return@mapNotNull null
            if (name == "_Total" || name.split(",").size != 2) return@mapNotNull null
            val util = (r.num("PercentProcessorUtility") ?: r.num("PercentProcessorTime") ?: 0.0).coerceIn(0.0, 100.0)
            val perfPct = r.num("PercentProcessorPerformance") ?: 0.0
            Triple(name, util, if (maxMhz > 0 && perfPct > 0) maxMhz * perfPct / 100 else 0.0)
        }.sortedBy { it.first }

        storage = rows(sec, "VOL").mapNotNull { r ->
            val id = r.str("DeviceID") ?: return@mapNotNull null; val size = r.num("Size") ?: return@mapNotNull null
            if (size <= 0) return@mapNotNull null
            StorageItem(id, id, size.toLong(), (size - (r.num("FreeSpace") ?: 0.0)).toLong(), r.str("VolumeName").orEmpty())
        }.sortedBy { it.id }
        services = rows(sec, "SVC").mapNotNull { r ->
            val n = r.str("Name") ?: return@mapNotNull null; val st = r.str("State").orEmpty()
            ServiceRow(n, n, r.str("DisplayName") ?: n, st, st == "Running", r.str("StartMode").orEmpty())
        }.sortedBy { it.displayName.lowercase() }

        var rx = 0.0; var tx = 0.0
        val skip = listOf("isatap", "teredo", "loopback", "6to4", "vethernet", "-wfp", "-qos", "-virtualbox", "-npcap", "filter", "pseudo", "bluetooth", "wan miniport", "tailscale")
        rows(sec, "NET").forEach { r -> val n = (r.str("Name") ?: "").lowercase(); if (skip.none { it in n }) { rx += r.num("BytesReceivedPersec") ?: 0.0; tx += r.num("BytesSentPersec") ?: 0.0 } }
        hasWg = sec["FEAT"].orEmpty().any { it.trim() == "WIREGUARD" }
        val wmi = rows(sec, "SENS").mapNotNull { r ->
            val n = r.str("Name"); val t = r.str("SensorType"); val v = r.num("Value"); val i = r.str("Identifier")
            if (n != null && t != null && v != null && i != null) Sensor(n, t, v, i) else null
        }
        val http = fetchLhm()
        sensors = http ?: wmi
        lhmHttp = if (machine.lhmPort > 0) http != null else null
        gpuRows = rows(sec, "GPUS"); gpuEng = rows(sec, "GPUENG"); gpuMem = rows(sec, "GPUMEM")
        val powerSensors = sensors.filter { it.type == "Power" }.map { it.value }
        alerts = emptyList()
        setNav(buildSet { addAll(baseNav()); if (hasWg) add(NavItem.Vpn) })
        val (rr, tr) = rx to tx
        return Snapshot(h.str("name").orEmpty(), h.str("os") ?: "Windows", h.num("up") ?: 0.0, cpu, used, memTotal, rr, tr,
            if (powerSensors.isEmpty()) null else powerSensors.sum(), storage, alerts, cpuHistory.toList(), memHistory.toList())
    }

    private suspend fun fetchLhm(): List<Sensor>? {
        if (machine.lhmPort <= 0) return null
        return try {
            val resp = Http.plain(5).newCall(Request.Builder().url("http://${machine.host}:${machine.lhmPort}/data.json").build()).await()
            resp.use { r ->
                if (r.code != 200) return null
                val root = parseJson(r.body?.string()) ?: return null
                val out = ArrayList<Sensor>()
                val names = mapOf("temperature" to "Temperature", "fan" to "Fan", "power" to "Power", "voltage" to "Voltage", "clock" to "Clock", "load" to "Load", "control" to "Control", "data" to "Data")
                fun walk(n: JsonElement) {
                    val sid = n.str("SensorId")
                    if (sid != null) {
                        val parts = sid.split("/")
                        if (parts.size >= 2) {
                            val type = names[parts[parts.size - 2].lowercase()]
                            val v = (n.str("Value") ?: "").split(" ").firstOrNull()?.replace(",", ".")?.toDoubleOrNull()
                            if (type != null && v != null && v.isFinite()) out.add(Sensor(n.str("Text") ?: sid, type, v, sid))
                        }
                    }
                    n.list("Children").forEach { walk(it) }
                }
                walk(root); out.ifEmpty { null }
            }
        } catch (_: Exception) { null }
    }

    override suspend fun reboot() { ps(actionScript("shutdown.exe /r /t 3 /f")) }
    override suspend fun shutdown() { ps(actionScript("shutdown.exe /s /t 3 /f")) }
    override suspend fun runConsole(command: String): String = try { ps(actionScript(command)) } catch (e: Exception) { e.message ?: "error" }

    private fun ok(out: String): String? = if (out.contains("OK") && !out.contains("ERROR:")) null else out.trim().ifBlank { "No reply." }
    private suspend fun runAction(body: String): String? = guarded { ok(ps(actionScript(body))) }

    override suspend fun loadPage(nav: NavItem, query: String): Page = when (nav) {
        NavItem.Storage -> storagePage(storage)
        NavItem.Cpu -> cpuPage()
        NavItem.Gpu -> gpuPage()
        NavItem.Sensors -> sensorsPage()
        NavItem.Processes -> processesPage()
        NavItem.Services -> {
            val text = ps(Scripts.get("win_SAMPLE_SCRIPT")); services = rows(sections(text), "SVC").mapNotNull { r ->
                val n = r.str("Name") ?: return@mapNotNull null; val st = r.str("State").orEmpty()
                ServiceRow(n, n, r.str("DisplayName") ?: n, st, st == "Running", r.str("StartMode").orEmpty()) }.sortedBy { it.displayName.lowercase() }
            Page(services.map { s ->
                Item(s.id, s.displayName, s.name, s.startMode, badge = s.state, badgeLevel = if (s.isRunning) Level.Info else Level.Warning,
                    actions = listOf("start", "stop", "restart").map { v ->
                        Act(v.replaceFirstChar { it.uppercase() }, confirm = if (v != "start") "${v.replaceFirstChar { it.uppercase() }} ${s.displayName}?" else null, danger = v == "stop") {
                            runAction(when (v) { "start" -> "Start-Service -Name ${psQuote(s.name)}"; "stop" -> "Stop-Service -Name ${psQuote(s.name)} -Force"; else -> "Restart-Service -Name ${psQuote(s.name)} -Force" })
                        } })
            }, filterable = true)
        }
        NavItem.Startup -> startupPage()
        NavItem.ScheduledTasks -> tasksPage()
        NavItem.Software -> softwarePage()
        NavItem.Updates -> updatesPage()
        NavItem.Logs -> logsPage(query)
        NavItem.Users -> usersPage()
        NavItem.Vpn -> vpnPage()
        NavItem.Alerts -> alertsPage(alerts)
        NavItem.History -> historyPage(query.ifBlank { "24h" })
        else -> Page(listOf(Note("Not available.")))
    }

    private fun cpuPage(): Page {
        val i = cpuInfo
        val b = ArrayList<Block>()
        b.add(Stats(listOf("Model" to cpuName.ifBlank { "–" }, "Cores" to "${i.num("NumberOfCores")?.toInt() ?: "–"} / ${i.num("NumberOfLogicalProcessors")?.toInt() ?: "–"} threads",
            "Max clock" to Fmt.mhz(i.num("MaxClockSpeed")), "L2 / L3" to "${i.num("L2CacheSize")?.toInt() ?: 0} KB / ${i.num("L3CacheSize")?.toInt() ?: 0} KB",
            "Virtualization" to when (i.bool("VirtualizationFirmwareEnabled")) { true -> "Enabled"; false -> "Disabled"; else -> "–" })))
        b.add(Chart("Total load", cpuHistory.toList(), "%", 100f))
        b.add(Heading("Per-core load"))
        cores.forEach { (n, u, f) -> b.add(Gauge("Core $n", (u / 100).toFloat(), "${u.toInt()}%" + if (f > 0) " · ${Fmt.mhz(f)}" else "")) }
        return Page(b, refreshMs = 3000)
    }

    private suspend fun gpuPage(): Page {
        val sec = sections(ps(Scripts.get("win_GPU_DETAIL_SCRIPT")))
        val nv = sec["NVSMI"].orEmpty().filter { it.isNotBlank() }.map { l -> l.split(", ") }.filter { it.size >= 16 }
        val procs = rows(sec, "GPUPROC")
        val b = ArrayList<Block>()
        val engByLuid = gpuEng.groupBy { it.str("luid") }
        gpuRows.forEach { g ->
            val name = g.str("name") ?: "GPU"
            val vram = g.num("vram") ?: g.num("ram")
            b.add(Item(name, name, "Driver ${g.str("driver") ?: "–"} · ${g.str("status") ?: ""}", vram?.let { Fmt.bytes(it) + " VRAM" } ?: "", detail = g.str("proc").orEmpty()))
        }
        if (gpuRows.isNotEmpty() && gpuEng.isNotEmpty()) {
            b.add(Heading("Engine utilisation"))
            engByLuid.forEach { (luid, e) -> e.sortedByDescending { it.num("util") ?: 0.0 }.take(4).forEach { r -> b.add(Gauge("${r.str("type")} (${luid?.takeLast(6)})", ((r.num("util") ?: 0.0) / 100).toFloat(), "${(r.num("util") ?: 0.0).toInt()}%")) } }
        }
        nv.forEach { f ->
            b.add(Heading("NVIDIA ${f[1]}"))
            b.add(Stats(listOf("Utilisation" to "${f[2]}%", "VRAM" to "${f[4]} / ${f[5]} MiB", "Temperature" to "${f[6]}°C", "Power" to "${f[7]} / ${f[8]} W", "Fan" to "${f[9]}%",
                "Core clock" to "${f[10]} / ${f[11]} MHz", "Memory clock" to "${f[12]} / ${f[13]} MHz", "Driver" to f[14], "P-state" to f[15])))
        }
        if (procs.isNotEmpty()) { b.add(Heading("GPU processes")); procs.forEach { p -> b.add(Item("gp-${p.num("pid")}", p.str("name") ?: "pid ${p.num("pid")?.toInt()}", "PID ${p.num("pid")?.toInt()}", "${(p.num("util") ?: 0.0).toInt()}% · ${Fmt.bytes(p.num("vram"))}")) } }
        if (b.isEmpty()) return Page(error = "No GPU data reported.")
        return Page(b, refreshMs = 3000)
    }

    private fun sensorsPage(): Page {
        if (sensors.isEmpty()) return Page(error = "No sensor data. Run LibreHardwareMonitor on the PC (Remote Web Server on, then enter its port in the machine settings, or leave it running for WMI).")
        val blocks = ArrayList<Block>()
        lhmHttp?.let { if (!it) blocks.add(Note("The LibreHardwareMonitor web server on port ${machine.lhmPort} didn't answer; showing WMI sensors.", warn = true)) }
        sensors.groupBy { it.type }.toSortedMap().forEach { (type, list) ->
            blocks.add(Heading(type))
            list.sortedBy { it.name }.forEach { s ->
                val v = when (type) { "Temperature" -> Fmt.temp(s.value); "Fan" -> "${s.value.toInt()} RPM"; "Power" -> Fmt.watts(s.value); "Voltage" -> "%.3f V".format(s.value)
                    "Clock" -> Fmt.mhz(s.value); "Load", "Control" -> "${s.value.toInt()}%"; else -> "%.1f".format(s.value) }
                blocks.add(Item(s.id, s.name, s.id.substringBeforeLast('/').substringAfterLast('/'), v))
            }
        }
        return Page(blocks, filterable = true, refreshMs = 4000)
    }

    private suspend fun processesPage(): Page {
        val sec = sections(ps(Scripts.get("win_PROCESSES_SCRIPT")))
        val n = maxOf(1, rows(sec, "CORES").size)
        val list = rows(sec, "PROCS").mapNotNull { r ->
            val pid = r.num("pid") ?: return@mapNotNull null; val name = r.str("name") ?: return@mapNotNull null
            ProcessRow(pid.toLong(), name, minOf(100.0, (r.num("cpu") ?: 0.0) / n), r.num("ws") ?: 0.0)
        }
        return Page(list.take(150).map { p ->
            Item(p.pid.toString(), p.name, "PID ${p.pid}", "%.1f%% CPU · %s".format(p.cpu, Fmt.bytes(p.mem)), progress = (p.cpu / 100).toFloat().coerceIn(0f, 1f),
                actions = listOf(Act("Kill", confirm = "Kill ${p.name} (PID ${p.pid})?", danger = true) { runAction("Stop-Process -Id ${p.pid} -Force") }))
        }, filterable = true, refreshMs = 5000)
    }

    private suspend fun startupPage(): Page {
        val items = rows(sections(ps(Scripts.get("win_STARTUP_SCRIPT"))), "STARTUP").sortedBy { (it.str("name") ?: "").lowercase() }
        val ap = "\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Explorer\\StartupApproved\\"
        return Page(items.map { r ->
            val name = r.str("name") ?: "?"; val kind = r.str("kind") ?: ""; val dis = r.bool("disabled") == true
            fun setScript(disabled: Boolean): String {
                if (kind == "task") return "${if (disabled) "Disable" else "Enable"}-ScheduledTask -TaskName ${psQuote(name)} -TaskPath ${psQuote(r.str("path") ?: "")} | Out-Null"
                val key = when (kind) { "hklm" -> "HKLM:${ap}Run"; "hklm32" -> "HKLM:${ap}Run32"; "hkcu" -> "HKCU:${ap}Run"; "folderUser" -> "HKCU:${ap}StartupFolder"; else -> "HKLM:${ap}StartupFolder" }
                val flag = if (disabled) "\$true" else "\$false"
                return "\$k=${psQuote(key)}\nif(!(Test-Path -LiteralPath \$k)){New-Item -Path \$k -Force|Out-Null}\n\$v=New-Object byte[] 12\n" +
                    "if($flag){\$v[0]=3;[BitConverter]::GetBytes([DateTime]::UtcNow.ToFileTime()).CopyTo(\$v,4)}else{\$v[0]=2}\nSet-ItemProperty -LiteralPath \$k -Name ${psQuote(name)} -Value \$v -Type Binary"
            }
            Item("$kind-$name", name, listOf(r.str("pub"), r.str("scope")).filter { !it.isNullOrBlank() }.joinToString(" · "), badge = if (dis) "Disabled" else "Enabled",
                badgeLevel = if (dis) Level.Warning else Level.Info, detail = r.str("cmd").orEmpty(),
                actions = listOf(Act(if (dis) "Enable" else "Disable", confirm = if (!dis) "Disable $name at startup?" else null) { runAction(setScript(!dis)) }))
        }, filterable = true)
    }

    private suspend fun tasksPage(): Page {
        val tasks = rows(sections(ps(Scripts.get("win_SCHEDULED_TASKS_SCRIPT"))), "TASKS").sortedBy { (it.str("name") ?: "").lowercase() }
        return Page(tasks.map { t ->
            val name = t.str("name") ?: "?"; val path = t.str("path") ?: ""; val state = t.str("state") ?: ""
            fun a(label: String, body: String, confirm: String? = null) = Act(label, confirm) { runAction(body) }
            Item("$path$name", name, path, badge = state, badgeLevel = if (state == "Disabled") Level.Warning else Level.Info,
                actions = listOf(a("Run now", "Start-ScheduledTask -TaskName ${psQuote(name)} -TaskPath ${psQuote(path)}"),
                    if (state == "Disabled") a("Enable", "Enable-ScheduledTask -TaskName ${psQuote(name)} -TaskPath ${psQuote(path)} | Out-Null")
                    else a("Disable", "Disable-ScheduledTask -TaskName ${psQuote(name)} -TaskPath ${psQuote(path)} | Out-Null", "Disable $name?")))
        }, filterable = true, error = if (tasks.isEmpty()) "No user-defined scheduled tasks." else null)
    }

    private suspend fun softwarePage(): Page {
        val list = rows(sections(ps(Scripts.get("win_SOFTWARE_SCRIPT"))), "SOFT").mapNotNull { r -> r.str("name")?.let { Triple(it, r.str("version").orEmpty(), r.str("publisher").orEmpty()) } }.sortedBy { it.first.lowercase() }
        return Page(list.map { Item("${it.first}-${it.second}", it.first, it.third, it.second) }, filterable = true)
    }

    private suspend fun updatesPage(): Page {
        val sec = sections(ps(Scripts.get("win_UPDATES_SCRIPT")))
        val hot = rows(sec, "HOTFIX"); val wu = rows(sec, "WU").firstOrNull()
        val dates = hot.mapNotNull { it.str("date") }
        return Page(listOf(Stats(listOf("Installed hotfixes" to hot.size.toString(), "Last installed" to (dates.maxOrNull() ?: "–"), "Windows Update service" to (wu.str("state") ?: "–"))),
            Note("Install updates from Windows Update on the PC.")) + hot.sortedByDescending { it.str("date") ?: "" }.map { Item(it.str("HotFixID") ?: "?", it.str("HotFixID") ?: "?", it.str("date") ?: "") })
    }

    private suspend fun logsPage(query: String): Page {
        val f = query.trim()
        val log = if (f.isEmpty()) "'Application','System'" else psQuote(f)
        val script = prelude() + "Sx EVT (Get-WinEvent -FilterHashtable @{LogName=$log;Level=1,2,3} -MaxEvents 200 -ErrorAction SilentlyContinue|select @{n='time';e={\$_.TimeCreated.ToString('MM-dd HH:mm:ss')}},@{n='level';e={\$_.LevelDisplayName}},@{n='log';e={\$_.LogName}},@{n='msg';e={(\$_.Message -split \"`n\")[0]}})\n'##END'\n"
        val ev = rows(sections(ps(script)), "EVT")
        return Page(ev.map { e -> Item("${e.str("time")}-${e.hashCode()}", e.str("msg") ?: "", "${e.str("time")} · ${e.str("log")}", badge = e.str("level") ?: "",
            badgeLevel = if (e.str("level") == "Information") Level.Info else if (e.str("level") == "Warning") Level.Warning else Level.Critical) }, filterable = true, queryHint = "Log name, e.g. Security (blank for Application + System)")
    }

    private suspend fun usersPage(): Page {
        val u = rows(sections(ps(Scripts.get("win_USERS_SCRIPT"))), "USR")
        return Page(if (u.isEmpty()) listOf(Note("Nobody is signed in.")) else u.map { Item("${it.str("name")}-${it.str("session")}", it.str("name") ?: "?", listOf(it.str("session"), it.str("state")).filter { s -> !s.isNullOrBlank() }.joinToString(" · "), it.str("logon") ?: "", detail = "Idle: ${it.str("idle") ?: "–"}") })
    }

    private suspend fun wgState(): List<WireGuard.Tunnel> {
        val sec = sections(ps(Scripts.get("win_WIREGUARD_STATUS_SCRIPT")))
        val names = rows(sec, "WGSVC").mapNotNull { r -> r.str("Name")?.takeIf { it.startsWith("WireGuardTunnel$") }?.removePrefix("WireGuardTunnel$") }
        return WireGuard.tunnels(names, sec["WGDUMP"].orEmpty().joinToString("\n"))
    }

    private suspend fun vpnPage(): Page {
        val tunnels = wgState()
        val wgExe = "\$wg=(Get-Command wireguard.exe -ErrorAction SilentlyContinue).Source\nif(!\$wg){\$wg='C:\\Program Files\\WireGuard\\wireguard.exe'}\n"
        return Page(tunnels.map { t ->
            Item(t.name, t.name, if (t.up) "${t.endpoint} · handshake ${t.handshake} · rx ${Fmt.bytes(t.rx)} · tx ${Fmt.bytes(t.tx)}" else "", badge = if (t.up) "Connected" else "Disconnected", badgeLevel = if (t.up) Level.Info else Level.Warning,
                actions = listOf(if (t.up) Act("Disconnect") { runAction("Stop-Service -Name ${psQuote("WireGuardTunnel$" + t.name)} -Force") } else Act("Connect") { runAction("Start-Service -Name ${psQuote("WireGuardTunnel$" + t.name)}") },
                    Act("Remove", confirm = "Remove tunnel ${t.name}?", danger = true) { runAction(wgExe + "& \$wg /uninstalltunnelservice ${psQuote(t.name)}") }))
        }, forms = listOf(FormAct("Add tunnel", listOf(Field("name", "Name"), Field("conf", "Config", multiline = true))) { f ->
            guarded {
                val name = f["name"].orEmpty().trim(); if (!WireGuard.validName(name)) return@guarded "Use letters, numbers, dashes and underscores only."
                val b64 = java.util.Base64.getEncoder().encodeToString(f["conf"].orEmpty().toByteArray())
                ok(ps(actionScript("\$bytes=[Convert]::FromBase64String(${psQuote(b64)})\n\$path=\"\$env:TEMP\\$name.conf\"\n[IO.File]::WriteAllBytes(\$path,\$bytes)\n$wgExe& \$wg /installtunnelservice \$path\nRemove-Item \$path -ErrorAction SilentlyContinue")))
            }
        }), error = if (tunnels.isEmpty()) "No tunnels yet." else null)
    }
}
