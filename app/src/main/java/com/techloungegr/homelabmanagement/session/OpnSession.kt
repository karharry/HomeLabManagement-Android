package com.techloungegr.homelabmanagement.session

import com.techloungegr.homelabmanagement.data.Store
import com.techloungegr.homelabmanagement.model.*
import com.techloungegr.homelabmanagement.net.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*

/**
 * OPNsense over its native REST API (no SSH). Field names vary a little between OPNsense versions, so every
 * reader tries several plausible names and falls back to "no data" instead of failing.
 */
class OpnSession(machine: Machine, scope: CoroutineScope) : Session(machine, scope) {
    private var client: OpnClient? = null
    private var prevIf: Triple<Double, Double, Long>? = null
    private var services: List<ServiceRow> = emptyList()
    private var interfaces: List<Item> = emptyList()
    private var storage: List<StorageItem> = emptyList()
    private var diag: List<String> = emptyList()

    override fun baseNav() = sortNav(listOf(NavItem.Dashboard, NavItem.Storage, NavItem.Network, NavItem.Services, NavItem.DhcpLeases, NavItem.DnsmasqHosts, NavItem.Updates, NavItem.Alerts, NavItem.History, NavItem.Console))
    override val consoleAvailable get() = true
    override val consoleUnavailableReason get() = ""

    /** Read-only API explorer: type an API path (e.g. core/firmware/status) to see the raw JSON. */
    override suspend fun runConsole(command: String): String {
        val cmd = command.trim().removePrefix("/").removePrefix("api/")
        if (cmd.equals("diag", true)) {
            val sb = StringBuilder()
            for (p in listOf("diagnostics/system/systemTime", "diagnostics/activity/getActivity", "diagnostics/cpu_usage/getCPUType", "diagnostics/traffic/interface")) {
                sb.append("== ").append(p).append('\n')
                try {
                    val j = c().get(p)
                    sb.append((if (p.endsWith("getActivity")) j["headers"]?.toString() else j?.toString() ?: "(empty)").orEmpty().take(900))
                } catch (e: Exception) { sb.append(e.message) }
                sb.append("\n\n")
            }
            return sb.toString()
        }
        return try {
            val post = cmd.startsWith("post ")
            val path = cmd.removePrefix("post ").trim()
            if (post && !path.contains("search")) return "Only read-only search endpoints can be called with “post”."
            val json = if (post) c().post(path) else c().get(path)
            (json?.toString() ?: "(empty reply)").let { if (it.length > 8000) it.take(8000) + "…" else it }
        } catch (e: Exception) { e.message ?: "error" }
    }

    private fun c(): OpnClient = client ?: OpnClient(machine.host, machine.port, machine.user, Store.secretOf(id), machine.useTLS, machine.allowSelfSigned).also { client = it }
    override fun closeBackend() { client = null; prevIf = null }

    private fun JsonElement?.s(vararg keys: String): String = str(*keys) ?: ""
    private fun first(d: JsonElement?, vararg keys: String): JsonElement? { for (k in keys) d[k]?.let { return it }; return null }
    private fun parseSize(s: String?): Double? {
        s ?: return null
        val m = Regex("^\\s*([0-9.]+)\\s*([KMGTP]?)i?B?\\s*$", RegexOption.IGNORE_CASE).find(s) ?: return null
        val n = m.groupValues[1].toDoubleOrNull() ?: return null
        return n * when (m.groupValues[2].uppercase()) { "K" -> 1024.0; "M" -> 1048576.0; "G" -> 1073741824.0; "T" -> 1099511627776.0; "P" -> 1.125899906842624E15; else -> 1.0 }
    }
    private fun running(v: JsonElement?): Boolean = when (v) {
        is JsonPrimitive -> v.booleanOrNull ?: v.intOrNull?.let { it != 0 } ?: (v.content.lowercase() in listOf("running", "true", "1", "yes", "started"))
        else -> false
    }
    private fun uptimeFromString(s: String): Double {
        Regex("(?:(\\d+)\\s*days?,?\\s*|(\\d+)\\+)?(\\d+):(\\d+):(\\d+)").find(s)?.let { m ->
            val d = (m.groupValues[1].ifEmpty { m.groupValues[2] }).toDoubleOrNull() ?: 0.0
            return d * 86400 + m.groupValues[3].toDouble() * 3600 + m.groupValues[4].toDouble() * 60 + m.groupValues[5].toDouble()
        }
        var total = 0.0
        Regex("(\\d+)\\s*(day|hour|minute|second)").findAll(s.lowercase()).forEach { m ->
            val n = m.groupValues[1].toDouble(); total += n * when (m.groupValues[2]) { "day" -> 86400; "hour" -> 3600; "minute" -> 60; else -> 1 }
        }
        return total
    }

    override suspend fun poll(): Snapshot {
        val c = c(); val errs = ArrayList<String>()
        suspend fun tryGet(p: String): JsonElement? = try { c.get(p) } catch (e: NetError) { if (p.contains("systemInformation")) throw e; errs.add("$p: ${e.message}"); null } catch (e: Exception) { errs.add("$p: ${e.message}"); null }
        suspend fun tryPost(p: String): JsonElement? = try { c.post(p) } catch (e: Exception) { errs.add("$p: ${e.message}"); null }
        val info = c.get("diagnostics/system/systemInformation")
        val host = info.s("hostname", "name").ifBlank { machine.host }
        val version = info.s("versionstring", "product_version", "version").ifBlank { (info["versions"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.content }.orEmpty() }
        val os = if (version.startsWith("OPNsense", true)) version else "OPNsense $version".trim()
        var uptime = info.num("uptime", "uptime_seconds") ?: info.str("uptime")?.let { uptimeFromString(it) } ?: 0.0
        if (uptime == 0.0) tryGet("diagnostics/system/systemTime")?.str("uptime")?.let { uptime = uptimeFromString(it) }

        val res = tryGet("diagnostics/system/systemResources")
        var cpu = res.num("cpu", "cpu_usage", "cpu_total", "load") ?: res["cpu"].num("total", "used", "usage", "load")
        if (cpu == null || uptime == 0.0) {
            val act = tryGet("diagnostics/activity/getActivity")
            if (cpu == null) {
                cpu = act.num("cpu", "cpu_usage", "total") ?: act["cpu"].num("total", "used", "usage", "user")
                if (cpu == null) act["headers"]?.let { h ->
                    (h as? JsonArray)?.map { (it as? JsonPrimitive)?.content.orEmpty() }?.firstOrNull { it.startsWith("CPU:") }?.let { line ->
                        Regex("([0-9.]+)% idle").find(line)?.groupValues?.get(1)?.toDoubleOrNull()?.let { cpu = 100 - it }
                    }
                }
            }
            if (uptime == 0.0) (act["headers"] as? JsonArray)?.map { (it as? JsonPrimitive)?.content.orEmpty() }?.firstOrNull { "up" in it.lowercase() && "load" in it.lowercase() }?.let { uptime = uptimeFromString(it) }
        }
        cpu = cpu?.coerceIn(0.0, 100.0)
        push(cpuHistory, cpu)
        val mem = res["memory"] ?: res
        val memTotal = mem.num("total", "memory_total", "total_bytes"); val memUsed = mem.num("used", "memory_used", "used_bytes"); val memPct = mem.num("used_pct", "usage", "percent")
        var mt = 0.0; var mu = 0.0
        if (memTotal != null && memTotal > 0) { mt = memTotal; mu = memUsed ?: (memPct?.let { memTotal * it / 100 } ?: 0.0) }
        else if (memPct != null) { mt = 100.0; mu = memPct }
        push(memHistory, if (mt > 0) mu / mt * 100 else null)

        val disk = tryGet("diagnostics/system/systemDisk")
        val diskRows = (disk?.get("devices")).rows().ifEmpty { disk.rows() }
        storage = diskRows.mapNotNull { r ->
            val mount = r.str("mountpoint", "mounted_on", "mount", "target", "device", "fs", "filesystem", "_key") ?: "disk"
            var size = r.num("total_bytes") ?: r.num("blocks", "size", "total", "1k-blocks", "disk_size") ?: parseSize(r.str("size", "blocks", "total")) ?: 0.0
            var used = r.num("used_bytes") ?: r.num("used") ?: parseSize(r.str("used"))
            val avail = r.num("avail", "available", "free") ?: parseSize(r.str("avail", "available"))
            val pct = r.num("capacity", "used_pct", "capacity_pct", "pct")
            if (used == null && avail != null && size > 0) used = maxOf(0.0, size - avail)
            if (used == null && pct != null && size > 0) used = size * pct / 100
            if (size == 0.0 && used != null && avail != null) size = used + avail
            if (size > 0) StorageItem(mount, mount, size.toLong(), (used ?: 0.0).toLong()) else null
        }
        val svcRows = tryPost("core/service/search").rows()
        if (svcRows.isNotEmpty()) services = svcRows.map { s ->
            val run = running(first(s, "running", "status"))
            ServiceRow(s.s("id", "name"), s.s("name", "id"), s.str("description", "name") ?: s.s("name"), if (run) "running" else "stopped", run)
        }.sortedBy { it.displayName.lowercase() }
        val ifc = tryGet("diagnostics/interface/getInterfaceConfig")
        val ifEntries: List<Pair<String, JsonElement?>> = (ifc as? JsonObject)?.entries?.map { it.key to it.value as JsonElement? }?.filter { it.second is JsonObject } ?: ifc.rows().map { (it.str("device", "_key") ?: "") to it }
        interfaces = ifEntries.map { (key, r) ->
            val dev = r.str("device", "if") ?: key
            val name = r.str("description", "identifier") ?: dev
            val status = r.str("status") ?: if (r["flags"].let { f -> (f as? JsonArray)?.any { (it as? JsonPrimitive)?.content == "up" } } == true) "active" else "down"
            val v4 = r.list("ipv4").mapNotNull { a -> a.str("ipaddr")?.let { ip -> a.num("subnetbits")?.let { "$ip/${it.toInt()}" } ?: ip } }
            val v6 = r.list("ipv6").mapNotNull { a -> a.str("ipaddr") }.filter { !it.startsWith("fe80") }
            val flatAddr = listOfNotNull(r.str("ipaddr"), r.str("ipaddrv6")).filter { it.isNotBlank() && it.lowercase() != "none" }
            Item(dev, name, (v4 + v6 + flatAddr).joinToString(", ").ifBlank { r.str("macaddr").orEmpty() }, if (name != dev) dev else "",
                badge = status, badgeLevel = if (status.lowercase() in listOf("up", "active", "associated")) Level.Info else Level.Warning,
                detail = listOfNotNull(r.str("macaddr")?.let { "MAC $it" }, r.str("media")?.let { "Media $it" }, r.str("mtu")?.let { "MTU $it" }).joinToString(" · "))
        }
        var rr: Double? = null; var tr: Double? = null
        val stObj = (tryGet("diagnostics/interface/getInterfaceStatistics")?.get("statistics") as? JsonObject)?.values?.filter { it is JsonObject && it.str("network")?.startsWith("<Link") == true && it.str("name") != "lo0" }.orEmpty()
        if (stObj.isNotEmpty()) {
            val rx = stObj.sumOf { it.num("received-bytes", "bytes received", "inbytes") ?: 0.0 }; val tx = stObj.sumOf { it.num("sent-bytes", "bytes transmitted", "outbytes") ?: 0.0 }
            val now = System.currentTimeMillis(); prevIf?.let { p -> val dt = (now - p.third) / 1000.0; if (dt > 0.5) { rr = maxOf(0.0, rx - p.first) / dt; tr = maxOf(0.0, tx - p.second) / dt } }
            prevIf = Triple(rx, tx, now)
        }
        diag = errs
        val alerts = errs.mapIndexed { i, m -> AlertItem("diag-$i", Level.Info, "An OPNsense API call didn't return usable data", m) }
        return Snapshot(host, os, uptime, cpu, mu, mt, rr, tr, null, storage, alerts, cpuHistory.toList(), memHistory.toList())
    }

    override suspend fun reboot() { c().post("core/system/reboot") }
    override suspend fun shutdown() { c().post("core/system/halt") }

    override suspend fun loadPage(nav: NavItem, query: String): Page = when (nav) {
        NavItem.Storage -> storagePage(storage)
        NavItem.Network -> Page(interfaces, error = if (interfaces.isEmpty()) "No interface data." else null)
        NavItem.Services -> Page(services.map { s ->
            Item(s.id, s.displayName, s.name, badge = s.state, badgeLevel = if (s.isRunning) Level.Info else Level.Warning,
                actions = listOf("start", "stop", "restart").map { v ->
                    Act(v.replaceFirstChar { it.uppercase() }, confirm = if (v != "start") "${v.replaceFirstChar { it.uppercase() }} ${s.displayName}?" else null, danger = v == "stop") {
                        guarded { c().post("core/service/$v/${s.id}"); delay(1000); null } } })
        }, filterable = true)
        NavItem.DhcpLeases -> leasesPage()
        NavItem.DnsmasqHosts -> hostsPage()
        NavItem.Updates -> updatesPage()
        NavItem.Alerts -> alertsPage(snap.value.alerts)
        NavItem.History -> historyPage(query.ifBlank { "24h" })
        else -> Page(listOf(Note("Not available.")))
    }

    private suspend fun leasesPage(): Page {
        var rows: List<JsonObject> = emptyList(); val errs = ArrayList<String>(); var ok = false
        for (p in listOf("dnsmasq/leases/search", "dhcpv4/leases/search_lease", "kea/leases4/search")) {
            try { rows = c().get(p).rows(); ok = true; if (rows.isNotEmpty()) break } catch (e: Exception) { errs.add("$p: ${e.message}") }
        }
        if (!ok && errs.isNotEmpty()) return Page(error = errs.joinToString(" / "))
        return Page(rows.map { r ->
            val ip = r.s("address", "ip", "ip-address")
            Item(ip + r.s("mac", "hwaddr", "hw-address"), r.s("hostname", "client-hostname").ifBlank { "–" }, listOf(ip, r.s("mac", "hwaddr", "hw-address")).joinToString(" · "), r.s("if", "interface"), badge = r.s("state", "online"))
        }, filterable = true, error = if (rows.isEmpty()) "No DHCP leases." else null)
    }

    private suspend fun hostsPage(): Page {
        val rows = try { c().get("dnsmasq/settings/search_host").rows() } catch (e: Exception) { return Page(error = e.message) }
        suspend fun apply() { try { c().post("dnsmasq/service/reconfigure") } catch (_: Exception) {} }
        val add = FormAct("Add static host", listOf(Field("host", "Hostname"), Field("domain", "Domain"), Field("ip", "IP address"), Field("mac", "MAC (optional)"), Field("descr", "Description"))) { f ->
            guarded {
                val h = f["host"].orEmpty().trim(); val ip = f["ip"].orEmpty().trim()
                if (h.isEmpty() || ip.isEmpty()) return@guarded "Enter both a hostname and an IP address."
                val res = c().post("dnsmasq/settings/add_host", buildJsonObject { put("host", buildJsonObject {
                    put("host", h); put("domain", f["domain"].orEmpty().trim()); put("ip", ip); put("hwaddr", f["mac"].orEmpty().trim()); put("descr", f["descr"].orEmpty().trim()) }) })
                val r = res.str("result")
                if (r != null && r !in listOf("saved", "ok")) return@guarded "OPNsense rejected the host: ${res?.get("validations") ?: res}"
                apply(); null
            }
        }
        return Page(rows.sortedBy { it.s("host", "hostname").lowercase() }.map { r ->
            val uuid = r.s("uuid", "uuidSelector").ifBlank { r.s("_key") }
            Item(uuid, (r.s("host", "hostname") + r.s("domain").let { if (it.isNotBlank() && r.s("host", "hostname").isNotBlank()) ".$it" else "" }).ifBlank { r.s("ip", "address") }, listOf(r.s("hwaddr", "mac", "hw-address"), r.s("descr", "description")).filter { it.isNotBlank() }.joinToString(" · "),
                actions = listOf(Act("Remove", confirm = "Remove this static host?", danger = true) { guarded { c().post("dnsmasq/settings/del_host/$uuid"); apply(); null } }))
        }, forms = listOf(add), filterable = true, error = if (rows.isEmpty()) "No static hosts." else null)
    }

    private suspend fun updatesPage(): Page {
        val fw = try { c().get("core/firmware/status") } catch (e: Exception) { return Page(error = e.message) }
        val status = fw.str("status"); val nv = fw.str("new_version", "product_version_new")
        val pkgs = (first(fw, "new_packages", "upgrade_packages") as? JsonArray).orEmpty()
        val items = ArrayList<Block>()
        items.add(Stats(listOfNotNull("Status" to (if (status == null || status.lowercase() in listOf("none", "none_fetched", "up_to_date", "ok")) "Up to date" else status), fw.str("last_check")?.let { "Last checked" to it }, fw.str("product_version")?.let { "Installed" to it })))
        if (status != null && status.lowercase() !in listOf("", "none", "none_fetched", "up_to_date", "ok")) items.add(Item("firmware", "OPNsense firmware", "", nv ?: status))
        pkgs.forEach { p -> items.add(Item("pkg-${p.str("name") ?: p}", p.str("name") ?: (p as? JsonPrimitive)?.content ?: "?", "Package", p.str("new_version").orEmpty())) }
        items.add(Note("Applying firmware updates remotely isn't offered: a failed or interrupted firewall update can leave the box unreachable. Use System > Firmware in the OPNsense web UI."))
        return Page(items)
    }
}
