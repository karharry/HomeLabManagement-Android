package com.techloungegr.homelabmanagement.session

import com.techloungegr.homelabmanagement.model.*
import kotlinx.coroutines.CoroutineScope

/** Bazzite / CachyOS: the Linux pages plus full CPU, GPU (NVIDIA/AMD/Intel) and hardware sensors. */
class GamingLinuxSession(machine: Machine, scope: CoroutineScope) : LinuxSession(machine, scope) {
    private var hw: Map<String, List<String>> = emptyMap()
    private var osId = ""; private var ostree = false

    override fun baseNav(): List<NavItem> = sortNav(listOf(NavItem.Dashboard, NavItem.Storage, NavItem.Cpu, NavItem.Gpu, NavItem.Sensors, NavItem.Processes,
        NavItem.Services, NavItem.ScheduledTasks, NavItem.Updates, NavItem.Logs, NavItem.Users, NavItem.Console, NavItem.Alerts))

    override fun extraNav(n: MutableSet<NavItem>) { n.add(NavItem.Cpu); n.add(NavItem.Gpu); n.add(NavItem.Sensors) }

    private suspend fun collect(): Map<String, List<String>> {
        hw = sections(sh(Scripts.get("gaming_HW_SCRIPT"), sudo = true, merge = false))
        val osrel = hw["OSREL"].orEmpty()
        osId = (osrel.firstOrNull { it.startsWith("ID=") }?.removePrefix("ID=")?.trim('"')?.lowercase() ?: "") + " " +
            (osrel.firstOrNull { it.startsWith("ID_LIKE=") }?.removePrefix("ID_LIKE=")?.trim('"')?.lowercase() ?: "")
        ostree = osrel.any { it.startsWith("OSTREE=1") }
        return hw
    }

    private fun kvLines(lines: List<String>, sep: String): Map<String, String> = lines.mapNotNull { l ->
        val i = l.indexOf(sep); if (i > 0) l.substring(0, i).trim() to l.substring(i + sep.length).trim() else null }.toMap()

    override suspend fun cpuPage(): Page {
        val s = collect()
        val l = kvLines(s["CPUCAP"].orEmpty(), ":"); val pol = kvLines(s["CPUPOL"].orEmpty(), "=")
        val freqs = s["CPUFREQ"].orEmpty().mapNotNull { line -> val f = line.trim().split(Regex("\\s+")); if (f.size == 2 && f[1].all { it.isDigit() }) f[0] to f[1].toDouble() / 1000 else null }
        val b = ArrayList<Block>()
        val boost = pol["boost"]?.let { it == "1" } ?: pol["no_turbo"]?.let { it == "0" }
        b.add(Stats(listOf("Model" to (l["Model name"] ?: "–"), "Vendor" to (l["Vendor ID"] ?: "–"), "Cores / threads" to "${l["Core(s) per socket"] ?: "?"} / ${l["CPU(s)"] ?: "?"}",
            "Sockets" to (l["Socket(s)"] ?: "1"), "Max clock" to Fmt.mhz(l["CPU max MHz"]?.toDoubleOrNull() ?: pol["max"]?.toDoubleOrNull()?.div(1000)),
            "Min clock" to Fmt.mhz(l["CPU min MHz"]?.toDoubleOrNull() ?: pol["min"]?.toDoubleOrNull()?.div(1000)),
            "Caches" to "L1d ${l["L1d cache"] ?: "–"} · L2 ${l["L2 cache"] ?: "–"} · L3 ${l["L3 cache"] ?: "–"}",
            "Governor" to (pol["governor"] ?: "–"), "Driver" to (pol["driver"] ?: "–"), "EPP" to (pol["epp"] ?: "–"), "Boost" to when (boost) { true -> "On"; false -> "Off"; else -> "–" },
            "SMT" to (pol["smt"] ?: "–"), "Microcode" to (s["CPUCAP"].orEmpty().firstOrNull { it.startsWith("microcode") }?.substringAfter(':')?.trim() ?: "–"))))
        val temp = cpuTemp(s)
        temp?.let { b.add(Stats(listOf("Package temperature" to Fmt.temp(it)))) }
        b.add(Chart("Total load", cpuHistory.toList(), "%", 100f))
        b.add(Heading("Per-core load and frequency"))
        cores.forEach { (n, u) -> b.add(Gauge(n.uppercase(), (u / 100).toFloat(), "${u.toInt()}%" + (freqs.firstOrNull { it.first == n }?.let { " · ${Fmt.mhz(it.second)}" } ?: ""))) }
        return Page(b, refreshMs = 3000)
    }

    private fun cpuTemp(s: Map<String, List<String>>): Double? {
        var best: Double? = null
        for (line in s["HWMON"].orEmpty()) {
            val f = line.split('|'); if (f.size < 5 || !f[2].startsWith("temp")) continue
            val v = f[4].toDoubleOrNull()?.div(1000) ?: continue
            if (f[0] == "k10temp" && f[3] in listOf("Tctl", "Tdie")) return v
            if (f[0] == "coretemp" && f[3].startsWith("Package")) return v
            if (f[0] in listOf("k10temp", "coretemp", "zenpower", "cpu_thermal", "acpitz")) best = maxOf(best ?: v, v)
        }
        return best
    }

    override suspend fun gpuPage(): Page {
        val s = collect()
        val b = ArrayList<Block>()
        var cur: MutableMap<String, String>? = null; var name = ""; var curName = ""; var title = ""; var vendor = ""
        val hwl = ArrayList<Triple<String, String, String>>()
        fun flush() {
            val a = cur ?: return
            val vend = when (a["_vendor"]) { "0x10de" -> "NVIDIA"; "0x1002" -> "AMD"; "0x8086" -> "Intel"; else -> a["_vendor"] ?: "GPU" }
            b.add(Heading("${a["_name"]?.ifBlank { null } ?: vend} (${a["_card"]})"))
            val util = a["gpu_busy_percent"]?.toDoubleOrNull() ?: a["mem_busy_percent"]?.toDoubleOrNull()
            val vu = a["mem_info_vram_used"]?.toDoubleOrNull(); val vt = a["mem_info_vram_total"]?.toDoubleOrNull()
            val gu = a["mem_info_gtt_used"]?.toDoubleOrNull(); val gt = a["mem_info_gtt_total"]?.toDoubleOrNull()
            util?.let { b.add(Gauge("Utilisation", (it / 100).toFloat().coerceIn(0f, 1f), "${it.toInt()}%")) }
            if (vu != null && vt != null && vt > 0) b.add(Gauge("VRAM", (vu / vt).toFloat().coerceIn(0f, 1f), "${Fmt.bytes(vu)} / ${Fmt.bytes(vt)}"))
            if (gu != null && gt != null && gt > 0) b.add(Gauge("GTT", (gu / gt).toFloat().coerceIn(0f, 1f), "${Fmt.bytes(gu)} / ${Fmt.bytes(gt)}"))
            val rows = ArrayList<Pair<String, String>>()
            rows.add("Driver" to (a["_driver"] ?: "–")); rows.add("Slot" to (a["_slot"] ?: "–"))
            a["gt_cur_freq_mhz"]?.let { rows.add("GPU clock" to "$it MHz${a["gt_max_freq_mhz"]?.let { m -> " / $m MHz" } ?: ""}") }
            a["pp_dpm_sclk"]?.takeIf { it.isNotBlank() }?.let { rows.add("Core clock" to it.replace("*", "").trim()) }
            a["pp_dpm_mclk"]?.takeIf { it.isNotBlank() }?.let { rows.add("Memory clock" to it.replace("*", "").trim()) }
            a["current_link_speed"]?.let { rows.add("PCIe link" to "$it × ${a["current_link_width"] ?: "?"}") }
            for ((k, label, v) in hwl) {
                val x = v.toDoubleOrNull() ?: continue
                when {
                    k.startsWith("temp") -> rows.add("Temp ${label.ifBlank { k.substringBefore('_') }}" to Fmt.temp(x / 1000))
                    k.startsWith("power") && !k.contains("cap") -> rows.add("Power" to Fmt.watts(x / 1_000_000))
                    k.contains("cap") && k.startsWith("power") -> rows.add("Power cap" to Fmt.watts(x / 1_000_000))
                    k.startsWith("fan") && k.contains("input") -> rows.add("Fan" to "${x.toInt()} RPM")
                }
            }
            b.add(Stats(rows)); hwl.clear()
        }
        for (line in s["GPU"].orEmpty()) {
            val f = line.split('|')
            when (f[0]) {
                "CARD" -> { flush(); cur = mutableMapOf("_card" to f.getOrElse(1) { "" }, "_slot" to f.getOrElse(2) { "" }, "_vendor" to f.getOrElse(3) { "" }, "_driver" to f.getOrElse(5) { "" }) }
                "ATTR" -> cur?.put(f.getOrElse(1) { "" }, f.drop(2).joinToString("|").trim())
                "HW" -> hwl.add(Triple(f.getOrElse(1) { "" }, f.getOrElse(2) { "" }, f.getOrElse(3) { "" }))
                "NAME" -> cur?.put("_name", f.getOrElse(1) { "" })
            }
        }
        flush()
        val nv = s["NVIDIA"].orEmpty().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split(", ") }.filter { it.size >= 16 }
        nv.forEach { f ->
            b.add(Heading("NVIDIA ${f[1]}"))
            b.add(Gauge("Utilisation", (f[2].toFloatOrNull() ?: 0f) / 100, "${f[2]}%"))
            b.add(Gauge("VRAM", ((f[4].toFloatOrNull() ?: 0f) / (f[5].toFloatOrNull() ?: 1f)).coerceIn(0f, 1f), "${f[4]} / ${f[5]} MiB"))
            b.add(Stats(listOf("Temperature" to "${f[6]}°C", "Power" to "${f[7]} / ${f[8]} W", "Fan" to "${f[9]}%", "Core clock" to "${f[10]} / ${f[11]} MHz", "Memory clock" to "${f[12]} / ${f[13]} MHz", "Driver" to f[14], "P-state" to f[15])))
        }
        if (b.isEmpty()) return Page(error = "No GPU found.")
        val proc = try { sh(Scripts.get("gaming_GPU_PROC_SCRIPT"), sudo = true) } catch (_: Exception) { "" }
        if (proc.isNotBlank()) { b.add(Heading("GPU processes")); b.add(Mono(proc.trim().take(2000))) }
        return Page(b, refreshMs = 3000)
    }

    override suspend fun loadExtraPage(nav: NavItem, query: String): Page = when (nav) {
        NavItem.Sensors -> sensorsPage()
        else -> super.loadExtraPage(nav, query)
    }

    private suspend fun sensorsPage(): Page {
        val s = collect(); val b = ArrayList<Block>()
        data class R(val chip: String, val dev: String, val file: String, val label: String, val v: Double)
        val readings = s["HWMON"].orEmpty().mapNotNull { l -> val f = l.split('|'); if (f.size < 5) null else f[4].toDoubleOrNull()?.let { R(f[0], f[1], f[2], f[3], it) } }
        if (readings.isEmpty()) return Page(error = "No hardware sensors found (is lm_sensors installed?).")
        readings.groupBy { it.chip.ifBlank { "hwmon" } }.toSortedMap().forEach { (chip, list) ->
            b.add(Heading(chip))
            list.forEach { r ->
                val kind = r.file.takeWhile { it.isLetter() }
                val (type, value) = when (kind) {
                    "temp" -> if (r.v <= -50000 || r.v > 250000) return@forEach else "Temperature" to Fmt.temp(r.v / 1000)
                    "fan" -> "Fan" to "${r.v.toInt()} RPM"; "power" -> "Power" to Fmt.watts(r.v / 1_000_000); "in" -> "Voltage" to "%.3f V".format(r.v / 1000)
                    "freq" -> "Clock" to Fmt.mhz(r.v / 1_000_000); else -> return@forEach
                }
                b.add(Item("$chip-${r.dev}-${r.file}", r.label.ifBlank { r.file.substringBefore('_') }, type, value))
            }
        }
        return Page(b, filterable = true, refreshMs = 4000)
    }

    override suspend fun updatesPage(): Page {
        val items = ArrayList<Item>()
        try {
            if (ostree || "bazzite" in osId) {
                val t = sh("rpm-ostree upgrade --check 2>&1", sudo = true, merge = true)
                if ("AvailableUpdate" in t) items.add(Item("rpm-ostree", "Bazzite OS image", "rpm-ostree", Regex("Version:\\s*(\\S+)").find(t)?.groupValues?.get(1) ?: "new image"))
            } else sh("pacman -Qu 2>/dev/null").lines().forEach { l -> val f = l.trim().split(Regex("\\s+")); if (f.size >= 4) items.add(Item(f[0], f[0], "pacman", f[3])) }
            sh("flatpak remote-ls --updates --columns=application,version 2>/dev/null").lines().forEach { l ->
                val f = l.split('\t'); if (f.isNotEmpty() && f[0].isNotBlank()) items.add(Item("flatpak-${f[0]}", f[0].trim(), "flatpak", f.getOrElse(1) { "" }.trim()))
            }
        } catch (e: Exception) { return Page(error = e.message) }
        val up = Act("Upgrade all", confirm = "Upgrade everything now?${if (ostree) " A reboot is needed to apply the new OS image." else ""}") {
            guarded {
                sh(if (ostree || "bazzite" in osId) "rpm-ostree upgrade 2>&1; flatpak update -y --noninteractive 2>&1; echo; echo 'Reboot to apply the new OS image.'"
                else "pacman -Syu --noconfirm 2>&1; flatpak update -y --noninteractive 2>&1", sudo = true, merge = true).takeLast(3000)
            }
        }
        return Page(if (items.isEmpty()) listOf(Note("Everything is up to date.")) else items, topActions = if (items.isEmpty()) emptyList() else listOf(up), filterable = true)
    }
}
