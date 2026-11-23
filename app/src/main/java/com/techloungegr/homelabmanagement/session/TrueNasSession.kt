package com.techloungegr.homelabmanagement.session

import com.techloungegr.homelabmanagement.data.Store
import com.techloungegr.homelabmanagement.model.*
import com.techloungegr.homelabmanagement.net.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

private val levels = listOf("INFO", "NOTICE", "WARNING", "ERROR", "CRITICAL", "ALERT", "EMERGENCY")
private val safeName = Regex("^[A-Za-z0-9][A-Za-z0-9_.-]*$")
private const val VPN_PREFIX = "ix-homelab-vpn-"

/** TrueNAS SCALE over its JSON-RPC WebSocket API, with optional SSH for containers, VM stats and the console. */
class TrueNasSession(machine: Machine, scope: CoroutineScope) : SshSession(machine, scope) {
    private var client: TrueNasClient? = null
    private var cpuRt: Double? = null
    private var memRt: Double? = null
    private var prevRapl: Pair<Double, Long>? = null
    private var powerWatts: Double? = null
    private var lastPower = 0L
    private var raw = Snapshot()
    private val sshOn get() = machine.sshEnabled && machine.sshUser.isNotBlank()

    override fun baseNav() = sortNav(listOf(NavItem.Dashboard, NavItem.Storage, NavItem.Datasets, NavItem.Shares, NavItem.Network,
        NavItem.Apps, NavItem.Vms, NavItem.Containers, NavItem.Services, NavItem.DataProtection, NavItem.Fan, NavItem.Updates,
        NavItem.Users, NavItem.Console, NavItem.Alerts, NavItem.Vpn, NavItem.History))

    override fun closeBackend() {
        client?.disconnect(); client = null; prevRapl = null
        super.closeBackend()
    }

    private fun tnSsh() = SshSettings(machine.host, machine.sshPort, machine.sshUser, Store.sshPasswordOf(id), machine.sshSudo)
    private suspend fun tsh(script: String, sudo: Boolean = true, r: SshRunner = aux) =
        withContext(Dispatchers.IO) { r.sh(script, tnSsh(), sudo, merge = true) }

    override val consoleAvailable get() = sshOn
    override val consoleUnavailableReason get() = "The console needs SSH. Turn on “SSH” and enter its login in this machine's settings."

    override suspend fun runConsole(command: String): String =
        if (!sshOn) consoleUnavailableReason else try { tsh(command, machine.sshSudo, runner) } catch (e: Exception) { e.message ?: "error" }

    private suspend fun ensureClient(): TrueNasClient {
        client?.let { return it }
        val c = TrueNasClient()
        c.onRealtime = { cpu, mem ->
            cpu?.let { cpuRt = it; push(cpuHistory, it) }
            if (mem != null) { memRt = mem; push(memHistory, mem * 100) }
            if (raw.memTotal > 0) updateSnap { s ->
                s.copy(cpu = cpuRt ?: s.cpu, memUsed = raw.memTotal * (memRt ?: 0.0), cpuHistory = cpuHistory.toList(), memHistory = memHistory.toList())
            }
        }
        c.onClose = { setState(ConnState.Failed("Connection lost")); client = null }
        val secret = Store.secretOf(id)
        if (machine.usePassword) c.connect(machine.host, machine.port, machine.useTLS, machine.allowSelfSigned, null, machine.user, secret)
        else c.connect(machine.host, machine.port, machine.useTLS, machine.allowSelfSigned, secret, null, null)
        client = c
        return c
    }

    private suspend fun rpc(method: String, vararg params: JsonElement): JsonElement? =
        (client ?: throw NetError("Not connected to TrueNAS.")).call(method, JsonArray(params.toList()))

    private suspend fun rpcOrNull(method: String, vararg params: JsonElement): JsonElement? =
        try { rpc(method, *params) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }

    /** Like rpcOrNull, but records why a call failed so the page can say so instead of looking empty. */
    private suspend fun rpcE(errs: MutableList<String>, method: String, vararg params: JsonElement, timeoutMs: Long = 120_000): JsonElement? =
        try { (client ?: throw NetError("Not connected to TrueNAS.")).call(method, JsonArray(params.toList()), timeoutMs) }
        catch (e: CancellationException) { throw e } catch (e: Exception) { errs.add("$method: ${e.message}"); null }

    private fun errNotes(errs: List<String>): List<Block> = errs.map { Note(it, warn = true) }

    private var apps: List<JsonObject> = emptyList()
    private var vms: List<JsonObject> = emptyList()

    override suspend fun poll(): Snapshot {
        val c = ensureClient()
        val info = c.call("system.info")
        val pools = rpcOrNull("pool.query"); val services = rpcOrNull("service.query"); val alerts = rpcOrNull("alert.list")
        apps = rpcOrNull("app.query").rows(); vms = rpcOrNull("vm.query").rows()
        val cores = info.num("cores") ?: 0.0
        val load = info.list("loadavg").firstOrNull()?.let { (it as? JsonPrimitive)?.doubleOrNull }
        if (cpuRt == null && load != null && cores > 0) { push(cpuHistory, minOf(100.0, load / cores * 100)) }
        val physmem = info.num("physmem") ?: 0.0
        val storage = pools.rows().map { p ->
            val scan = p["scan"]
            val fn = scan.str("function"); val st = scan.str("state")
            var summary = ""
            if (fn != null && st != null) {
                summary = if (st == "SCANNING") "${fn.lowercase().replaceFirstChar { it.uppercase() }} ${(scan.num("percentage") ?: 0.0).toInt()}%"
                else {
                    val errs = (scan.num("errors") ?: 0.0).toInt()
                    "${fn.lowercase().replaceFirstChar { it.uppercase() }} ${st.lowercase()}" + if (errs > 0) " · $errs errors" else ""
                }
            }
            StorageItem(p.str("name") ?: "?", p.str("name") ?: "?", (p.num("size") ?: 0.0).toLong(), (p.num("allocated") ?: 0.0).toLong(),
                p.str("status") ?: "", p.bool("healthy") == true, summary)
        }
        svc = services.rows().sortedBy { it.str("service") }.map {
            ServiceRow(it.str("id") ?: "", it.str("service") ?: "", it.str("service") ?: "", it.str("state") ?: "", it.str("state") == "RUNNING")
        }
        rawAlerts = alerts.rows().filter { it.bool("dismissed") != true }
        val alertItems = rawAlerts.map { a ->
            val sev = levels.indexOf(a.str("level") ?: "INFO").coerceAtLeast(0)
            AlertItem(a.str("uuid") ?: "", if (sev >= 4) Level.Critical else if (sev >= 2) Level.Warning else Level.Info, a.str("formatted") ?: "Alert", a.str("level") ?: "")
        }
        if (sshOn && System.currentTimeMillis() - lastPower > 30_000) { lastPower = System.currentTimeMillis(); samplePower() }
        val memUsed = if (physmem > 0) physmem * (memRt ?: 0.0) else 0.0
        raw = Snapshot(
            hostName = info.str("hostname") ?: "", os = "TrueNAS " + (info.str("version") ?: ""),
            uptime = info.num("uptime_seconds") ?: 0.0, cpu = cpuRt ?: (if (load != null && cores > 0) minOf(100.0, load / cores * 100) else null),
            memUsed = memUsed, memTotal = physmem, powerWatts = powerWatts, storage = storage, alerts = alertItems,
            cpuHistory = cpuHistory.toList(), memHistory = memHistory.toList(),
        )
        return raw
    }

    private var svc: List<ServiceRow> = emptyList()
    private var rawAlerts: List<JsonObject> = emptyList()

    private suspend fun samplePower() {
        try {
            val out = tsh("cat /sys/class/powercap/intel-rapl:0/energy_uj 2>/dev/null").trim()
            val e = out.toDoubleOrNull() ?: run { powerWatts = null; return }
            val now = System.currentTimeMillis(); val p = prevRapl
            if (p != null) { val dt = (now - p.second) / 1000.0; if (dt > 0.5 && e >= p.first) powerWatts = (e - p.first) / 1_000_000 / dt }
            prevRapl = e to now
        } catch (_: Exception) {}
    }

    override suspend fun reboot() { rpcOrNull("system.reboot", JsonPrimitive("Rebooted from HomeLab Management"), buildJsonObject { }) }
    override suspend fun shutdown() { rpcOrNull("system.shutdown", JsonPrimitive("Shut down from HomeLab Management"), buildJsonObject { }) }

    private suspend fun act(method: String, vararg p: JsonElement, settle: Long = 1500): String? = guarded {
        rpc(method, *p); delay(settle); poll().also { raw = it }; null
    }

    override suspend fun loadPage(nav: NavItem, query: String): Page = when (nav) {
        NavItem.Storage -> storagePage(raw.storage) { s ->
            listOf(Act("Start scrub") { act("pool.scrub.scrub", JsonPrimitive(s.id), JsonPrimitive("START")) ?.let { m -> act("pool.scrub", JsonPrimitive(s.id), JsonPrimitive("START")) } },
                Act("Stop scrub", confirm = "Stop the scrub on ${s.id}?") { act("pool.scrub.scrub", JsonPrimitive(s.id), JsonPrimitive("STOP")) })
        }
        NavItem.Services -> servicesPage()
        NavItem.Apps -> appsPage()
        NavItem.Vms -> vmsPage()
        NavItem.Datasets -> datasetsPage()
        NavItem.Shares -> sharesPage()
        NavItem.Network -> networkPage()
        NavItem.DataProtection -> protectionPage()
        NavItem.Updates -> updatesPage()
        NavItem.Users -> usersPage()
        NavItem.Containers -> containersPage()
        NavItem.Fan -> fanPage()
        NavItem.Vpn -> vpnPage()
        NavItem.Alerts -> alertsPage(raw.alerts) { a -> Act("Dismiss") { guarded { rpc("alert.dismiss", JsonPrimitive(a.id)); null } } }
        NavItem.History -> historyPage(query.ifBlank { "24h" })
        else -> Page(listOf(Note("Not available.")))
    }

    private fun servicesPage() = Page(svc.map { s ->
        Item(s.id, s.displayName, trailing = "", badge = s.state.lowercase().replaceFirstChar { it.uppercase() },
            badgeLevel = if (s.isRunning) Level.Info else Level.Warning, actions = listOf("start", "stop", "restart").map { v ->
                Act(v.replaceFirstChar { it.uppercase() }, confirm = if (v != "start") "${v.replaceFirstChar { it.uppercase() }} ${s.name}?" else null, danger = v == "stop") {
                    act("service.control", JsonPrimitive(v.uppercase()), JsonPrimitive(s.name), buildJsonObject { })
                }
            })
    }, filterable = true)

    private fun appsPage(): Page {
        val items = apps.sortedBy { it.str("name") }.map { a ->
            val name = a.str("name") ?: "?"; val state = a.str("state") ?: ""
            val running = state == "RUNNING"
            Item(name, name, listOfNotNull(a.str("human_version"), if (a.bool("upgrade_available") == true) "update available" else null).joinToString(" · "),
                badge = state.lowercase().replaceFirstChar { it.uppercase() }, badgeLevel = if (running) Level.Info else Level.Warning,
                actions = listOfNotNull(
                    Act("Redeploy", confirm = "Redeploy $name?") { act("app.redeploy", JsonPrimitive(name), settle = 3000) },
                    if (running) Act("Stop", confirm = "Stop $name?", danger = true) { act("app.stop", JsonPrimitive(name), settle = 3000) }
                    else Act("Start") { act("app.start", JsonPrimitive(name), settle = 3000) }))
        }
        val install = FormAct("Install custom app", listOf(Field("name", "App name", hint = "lowercase letters, numbers, dashes"),
            Field("yaml", "Docker Compose YAML", multiline = true))) { f ->
            guarded {
                val name = f["name"].orEmpty().trim(); val yaml = f["yaml"].orEmpty()
                if (name.isEmpty() || yaml.isBlank()) return@guarded "Enter a name and the compose YAML."
                val job = rpc("app.create", buildJsonObject { put("custom_app", true); put("app_name", name); put("custom_compose_config_string", yaml) })
                val jobId = (job as? JsonPrimitive)?.intOrNull ?: return@guarded "TrueNAS accepted the request but returned no job."
                repeat(400) {
                    delay(1500)
                    val j = rpc("core.get_jobs", JsonArray(listOf(JsonArray(listOf(JsonArray(listOf(JsonPrimitive("id"), JsonPrimitive("="), JsonPrimitive(jobId)))))))).rows().firstOrNull()
                    when (j.str("state")) {
                        "SUCCESS" -> { poll().also { raw = it }; return@guarded null }
                        "FAILED", "ABORTED" -> return@guarded j.str("error") ?: "Installation failed."
                    }
                }
                "Timed out waiting for TrueNAS. Check the Apps page."
            }
        }
        return Page(items, forms = listOf(install), filterable = true)
    }

    private fun vmsPage(): Page {
        val items = vms.sortedBy { (it.str("name") ?: "").lowercase() }.map { v ->
            val vid = v.num("id")?.toInt() ?: 0
            val name = v.str("name") ?: "?"; val st = v["status"]; val state = st.str("state") ?: "UNKNOWN"; val running = state == "RUNNING"
            val mem = v.num("memory")?.let { Fmt.bytes(it * 1024 * 1024) } ?: ""
            val cpus = ((v.num("vcpus") ?: 1.0) * (v.num("cores") ?: 1.0) * (v.num("threads") ?: 1.0)).toInt()
            Item(vid.toString(), name, "$cpus vCPU · $mem" + if (v.bool("autostart") == true) " · autostart" else "",
                badge = state.lowercase().replaceFirstChar { it.uppercase() }, badgeLevel = if (running) Level.Info else Level.Warning,
                actions = if (running) listOf(
                    Act("Shut down", confirm = "Send a shutdown signal to $name?") { act("vm.stop", JsonPrimitive(vid), buildJsonObject { put("force", false) }, settle = 3000) },
                    Act("Restart", confirm = "Restart $name?") { act("vm.restart", JsonPrimitive(vid), settle = 3000) },
                    Act("Power off", confirm = "Force power off $name? Unsaved data may be lost.", danger = true) { act("vm.poweroff", JsonPrimitive(vid), settle = 2000) })
                else listOf(Act("Start") { act("vm.start", JsonPrimitive(vid), settle = 3000) }))
        }
        return Page(items, filterable = true, error = if (items.isEmpty()) "No virtual machines." else null)
    }

    private suspend fun datasetsPage(): Page {
        val errs = ArrayList<String>()
        val opts = { sel: List<String>, limit: Int? -> buildJsonObject { put("select", JsonArray(sel.map { JsonPrimitive(it) })); limit?.let { put("limit", it); put("order_by", JsonArray(listOf(JsonPrimitive("-name")))) } } }
        val ds = rpcE(errs, "pool.dataset.query", JsonArray(emptyList()), opts(listOf("name", "type", "used", "available", "mountpoint"), null)).rows()
            .mapNotNull { r -> (r.str("name") ?: r.str("id"))?.let { it to r } }.sortedBy { it.first.lowercase() }
        val snaps = rpcE(errs, "zfs.snapshot.query", JsonArray(emptyList()), opts(listOf("name", "dataset", "properties"), 300)).rows()
            .mapNotNull { r -> (r.str("name") ?: r.str("id"))?.let { it to r } }.sortedByDescending { it.first }
        val blocks = ArrayList<Block>(errNotes(errs))
        blocks.add(Heading("Datasets (${ds.size})"))
        ds.forEach { (n, r) -> blocks.add(Item(n, n, listOf(r.str("type"), r.str("mountpoint")).filterNotNull().filter { it.isNotBlank() }.joinToString(" · "), "${Fmt.bytes(r.num("used"))} used")) }
        blocks.add(Heading("Latest snapshots (${snaps.size}${if (snaps.size >= 300) "+" else ""})"))
        snaps.forEach { (n, r) -> blocks.add(Item(n, n.substringAfter('@'), r.str("dataset") ?: n.substringBefore('@'), Fmt.bytes(r["properties"].num("used") ?: r.num("used")))) }
        return Page(blocks, filterable = true)
    }

    private suspend fun sharesPage(): Page {
        val errs = ArrayList<String>(); val items = ArrayList<Block>()
        rpcE(errs, "sharing.smb.query").rows().forEachIndexed { i, r -> items.add(Item("smb-$i", r.str("name") ?: "", r.str("path") ?: "", badge = "SMB", badgeLevel = if (r.bool("enabled") == false) Level.Warning else Level.Info, detail = r.str("comment") ?: "")) }
        rpcE(errs, "sharing.nfs.query").rows().forEachIndexed { i, r ->
            val paths = r.strList("path").ifEmpty { r.strList("paths") }.ifEmpty { listOfNotNull(r.str("path")) }
            items.add(Item("nfs-$i", paths.firstOrNull() ?: "", paths.joinToString(", "), badge = "NFS", badgeLevel = if (r.bool("enabled") == false) Level.Warning else Level.Info, detail = r.str("comment") ?: ""))
        }
        return Page(errNotes(errs) + items, filterable = true, error = if (items.isEmpty() && errs.isEmpty()) "No shares." else null)
    }

    private suspend fun networkPage(): Page {
        val errs = ArrayList<String>()
        val items = rpcE(errs, "interface.query").rows().mapNotNull { r ->
            val name = r.str("name") ?: r.str("id") ?: return@mapNotNull null
            val state = r["state"]
            val addrs = state.list("aliases").mapNotNull { it.str("address") }
            Item(name, name, addrs.joinToString(", "), badge = state.str("link_state") ?: "", badgeLevel = if (state.str("link_state") == "LINK_STATE_UP") Level.Info else Level.Warning)
        }
        return Page(errNotes(errs) + items, error = if (items.isEmpty() && errs.isEmpty()) "No interfaces." else null)
    }

    private suspend fun protectionPage(): Page {
        val items = ArrayList<Block>()
        suspend fun add(method: String, prefix: String, kind: String, field: String, fallback: String? = null) {
            rpcOrNull(method).rows().forEachIndexed { i, r ->
                items.add(Item("$prefix-$i", r.str(field) ?: fallback?.let { r.str(it) } ?: "–", kind, badge = if (r.bool("enabled") == false) "Disabled" else "Enabled",
                    badgeLevel = if (r.bool("enabled") == false) Level.Warning else Level.Info))
            }
        }
        add("pool.snapshottask.query", "snap", "Snapshot Task", "dataset"); add("replication.query", "repl", "Replication", "name")
        add("rsynctask.query", "rsync", "Rsync", "remotehost"); add("cloudsync.query", "cloud", "Cloud Sync", "description")
        add("cronjob.query", "cron", "Cron Job", "description", "command")
        return Page(items, filterable = true, error = if (items.isEmpty()) "No data protection tasks." else null)
    }

    private suspend fun updatesPage(): Page {
        val errs = ArrayList<String>()
        val obj = rpcE(errs, "update.status") ?: return Page(errNotes(errs), error = "Couldn't read the update status.")
        val labels = mapOf("NORMAL" to "Up to date", "REBOOT_REQUIRED" to "Reboot required to finish an update", "HAS_UPDATE" to "Update available",
            "UPDATE_AVAILABLE" to "Update available", "ERROR" to "Error checking for updates")
        val code = obj.str("code"); val status = obj["status"]
        val nv = status["new_version"]
        val ver = (nv as? JsonObject)?.let { it.str("version") ?: it.str("train") } ?: (nv as? JsonPrimitive)?.content
        val err = obj.str("error")
        return Page(listOf(Stats(listOfNotNull("Status" to (labels[code] ?: code ?: "Unknown"), ver?.let { "New version" to it }, err?.let { "Error" to it })),
            Note("Install updates from the TrueNAS web interface; the Linux app is read-only here too.")))
    }

    private suspend fun usersPage(): Page {
        val errs = ArrayList<String>()
        val items = rpcE(errs, "user.query").rows().mapNotNull { r ->
            val uid = r.num("uid")?.toInt() ?: return@mapNotNull null; val name = r.str("username") ?: return@mapNotNull null
            Item(uid.toString(), name, r.str("full_name") ?: "", "uid $uid", badge = if (r.bool("locked") == true) "Locked" else "", badgeLevel = Level.Warning)
        }.sortedBy { it.title.lowercase() }
        return Page(errNotes(errs) + items, filterable = true)
    }

    // ---- containers (Docker over SSH) ----
    private suspend fun containersPage(): Page {
        if (!sshOn) return Page(error = "Containers need SSH. Turn on SSH in this machine's settings.")
        return dockerPage(this::tsh)
    }

    private suspend fun dockerPage(run: suspend (String, Boolean, SshRunner) -> String): Page {
        val text = try { run(Scripts.get("docker_SAMPLE_SCRIPT"), true, aux) } catch (e: Exception) { return Page(error = e.message) }
        val list = Docker.parse(text)
        if (list.isEmpty() && !text.contains("{")) return Page(error = "On the NAS: " + text.replace("---STATS---", "").trim().take(300))
        return Page(list.map { c -> dockerItem(c, run) }, filterable = true, refreshMs = 10_000)
    }

    private fun dockerItem(c: Docker.Container, run: suspend (String, Boolean, SshRunner) -> String): Item {
        val verbs = if (c.running) listOf("stop", "restart") else listOf("start")
        return Item(c.name, c.name, c.image + (if (c.ports.isNotBlank()) " · ${c.ports}" else ""), listOfNotNull(c.cpu, c.memory).joinToString(" · "),
            badge = c.state, badgeLevel = if (c.running) Level.Info else Level.Warning, detail = c.status,
            actions = verbs.map { v -> Act(v.replaceFirstChar { it.uppercase() }, confirm = if (v != "start") "${v.replaceFirstChar { it.uppercase() }} ${c.name}?" else null) {
                guarded { if (!safeName.matches(c.name)) "Invalid container name." else { run("docker $v ${c.name}", true, aux); null } }
            } } + Act("Logs") { guarded { if (!safeName.matches(c.name)) "Invalid container name." else run("docker logs --tail 300 ${c.name}", true, aux).ifBlank { "(no output)" } } })
    }

    // ---- fan controller ----
    private fun fanClient() = FanClient("http://${machine.host}:${machine.fanPort}", Store.fanPasswordOf(id))
    private suspend fun fanPage(): Page {
        val fc = fanClient()
        return try {
            val s = fc.status(); val cfg = fc.config()
            val blocks = ArrayList<Block>()
            blocks.add(Stats(listOf("Fan" to (s.rpm?.let { "$it RPM" } ?: "–"), "Duty" to (s.pwmPct?.let { "${it.toInt()}%" } ?: "–"),
                "Control temp" to Fmt.temp(s.ctlTemp), "Source" to (s.src ?: "–"))))
            s.error?.let { blocks.add(Note(it, warn = true)) }
            blocks.add(Heading("Temperatures"))
            s.temps.forEach { (k, v) -> blocks.add(Item(k, k, v.first, Fmt.temp(v.second))) }
            blocks.add(Heading("Configuration"))
            blocks.add(Stats(listOf("Mode" to cfg.mode, "Manual" to "${cfg.manualPct.toInt()}%", "Minimum" to "${cfg.minPct.toInt()}%", "CPU offset" to "${cfg.cpuOffset}°", "Hysteresis" to "${cfg.hysteresis}°")))
            blocks.add(Mono("Curve (°C → %)\n" + cfg.curve.joinToString("\n") { "${it[0].toInt()}°C → ${it[1].toInt()}%" }))
            val setMode = { m: String -> Act("Mode: $m") { guarded { fc.save(cfg.copy(mode = m)); null } } }
            val form = FormAct("Edit settings", listOf(
                Field("mode", "Mode (auto or manual)", hint = cfg.mode), Field("manual", "Manual duty %", hint = cfg.manualPct.toInt().toString()),
                Field("min", "Minimum duty %", hint = cfg.minPct.toInt().toString()), Field("off", "CPU offset °C", hint = cfg.cpuOffset.toString()),
                Field("hyst", "Hysteresis °C", hint = cfg.hysteresis.toString()),
                Field("curve", "Curve as temp:pct pairs", multiline = true, hint = cfg.curve.joinToString(", ") { "${it[0].toInt()}:${it[1].toInt()}" }))) { f ->
                guarded {
                    val curve = f["curve"].orEmpty().ifBlank { cfg.curve.joinToString(",") { "${it[0]}:${it[1]}" } }.split(',', '\n').mapNotNull { p ->
                        val a = p.trim().split(':'); if (a.size == 2) listOf(a[0].trim().toDouble(), a[1].trim().toDouble()) else null }
                    fc.save(cfg.copy(mode = f["mode"]?.ifBlank { null } ?: cfg.mode, manualPct = f["manual"]?.toDoubleOrNull() ?: cfg.manualPct,
                        minPct = f["min"]?.toDoubleOrNull() ?: cfg.minPct, cpuOffset = f["off"]?.toDoubleOrNull() ?: cfg.cpuOffset,
                        hysteresis = f["hyst"]?.toDoubleOrNull() ?: cfg.hysteresis, curve = curve)); null
                }
            }
            Page(blocks, forms = listOf(form), topActions = listOf(setMode("auto"), setMode("manual")), refreshMs = 3000)
        } catch (e: Exception) { Page(error = "Fan controller (${machine.host}:${machine.fanPort}): ${e.message}") }
    }

    // ---- VPN (containerised WireGuard) ----
    private suspend fun vpnPage(): Page {
        if (!sshOn) return Page(error = "Enable SSH on this machine first (machine settings).")
        val text = try { tsh(Scripts.get("docker_SAMPLE_SCRIPT"), true) } catch (e: Exception) { return Page(error = e.message) }
        val list = Docker.parse(text).filter { it.name.startsWith(VPN_PREFIX) }
        val items = list.map { c ->
            val name = c.name.removePrefix(VPN_PREFIX)
            var detail = ""
            if (c.running) try { detail = tsh("docker exec ${c.name} wg show all dump").lines().filter { it.isNotBlank() }.let { l ->
                val f = l.getOrNull(1)?.split('\t'); if (f != null && f.size >= 9) "endpoint ${f[3]} · rx ${Fmt.bytes(f[6].toDoubleOrNull())} · tx ${Fmt.bytes(f[7].toDoubleOrNull())}" else "" } } catch (_: Exception) {}
            Item(c.name, name, detail, badge = if (c.running) "Connected" else "Stopped", badgeLevel = if (c.running) Level.Info else Level.Warning,
                actions = listOf(if (c.running) Act("Disconnect") { guarded { tsh("docker stop ${c.name}"); null } } else Act("Connect") { guarded { tsh("docker start ${c.name}"); null } },
                    Act("Remove", confirm = "Remove tunnel $name?", danger = true) { guarded { tsh("docker rm -f ${c.name}; rm -rf /tmp/homelab-vpn-$name"); null } }))
        }
        val add = FormAct("Add tunnel", listOf(Field("name", "Name"), Field("conf", "WireGuard config", multiline = true))) { f ->
            guarded {
                val name = f["name"].orEmpty().trim()
                if (!WireGuard.validName(name)) return@guarded "Use letters, numbers, dashes and underscores only."
                val b64 = java.util.Base64.getEncoder().encodeToString(f["conf"].orEmpty().toByteArray())
                val dir = "/tmp/homelab-vpn-$name"
                val out = tsh("""
mkdir -p $dir/wg_confs
echo $b64 | base64 -d > $dir/wg_confs/wg0.conf
chmod 600 $dir/wg_confs/wg0.conf
docker rm -f $VPN_PREFIX$name >/dev/null 2>&1
docker run -d --name $VPN_PREFIX$name --cap-add NET_ADMIN --cap-add SYS_MODULE \
  --sysctl net.ipv4.conf.all.src_valid_mark=1 -v $dir:/config --restart unless-stopped \
  lscr.io/linuxserver/wireguard:latest
""")
                if (out.lowercase().contains("error") || out.lowercase().contains("cannot")) out else null
            }
        }
        return Page(items, forms = listOf(add), error = if (items.isEmpty()) "No tunnels yet." else null)
    }
}
