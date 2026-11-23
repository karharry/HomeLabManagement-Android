package com.techloungegr.homelabmanagement.model

import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

enum class MachineKind(val label: String, val defaultPort: Int) {
    truenas("TrueNAS", 443),
    debian("Debian / Ubuntu", 22),
    raspios("Raspberry Pi OS", 22),
    arch("Arch Linux", 22),
    bazzite("Bazzite / CachyOS", 22),
    windows("Windows", 22),
    macos("macOS", 22),
    opnsense("OPNsense", 443);

    val isLinux get() = this == debian || this == raspios || this == arch || this == bazzite
    val isPosix get() = this != truenas && this != windows && this != opnsense
    val remoteDesktop: String?
        get() = when (this) { windows -> "rdp"; macos -> "vnc"; else -> null }
}

enum class NavItem(val label: String) {
    Dashboard("Dashboard"), Storage("Storage"), Processes("Processes"), Services("Services"), Alerts("Alerts"),
    Apps("Apps"), Vms("VMs"), Containers("Containers"), Fan("Fan Control"), Pihole("Pi-hole"), Pikvm("PiKVM"),
    Tailscale("Tailscale"), Cpu("CPU"), Gpu("GPU"), Sensors("Sensors"), Power("Power & Thermal"),
    Console("Console"), Logs("Logs"), Users("Users"), Updates("Updates"), Startup("Startup Items"),
    ScheduledTasks("Scheduled Tasks"), Software("Installed Software"), Datasets("Datasets & Snapshots"),
    Shares("Shares"), Network("Network"), DataProtection("Data Protection"), RaspberryPi("Raspberry Pi"),
    Vpn("VPN"), DhcpLeases("DHCP Leases"), LocalDns("Local DNS"), DnsmasqHosts("Static Hosts"),
    History("History");
}

/** Canonical grouping/order, same as the Linux app's sidebar. */
val NAV_GROUPS: List<Pair<String?, List<NavItem>>> = listOf(
    null to listOf(NavItem.Dashboard),
    "Hardware" to listOf(NavItem.Storage, NavItem.Cpu, NavItem.Gpu, NavItem.Sensors, NavItem.Power, NavItem.Fan, NavItem.RaspberryPi),
    "Software" to listOf(NavItem.Apps, NavItem.Vms, NavItem.Containers, NavItem.Startup, NavItem.Software, NavItem.ScheduledTasks, NavItem.Updates),
    "Network" to listOf(NavItem.Network, NavItem.DhcpLeases, NavItem.DnsmasqHosts, NavItem.LocalDns, NavItem.Pihole, NavItem.Tailscale, NavItem.Vpn, NavItem.Pikvm),
    "Data" to listOf(NavItem.Datasets, NavItem.Shares, NavItem.DataProtection),
    "System" to listOf(NavItem.History, NavItem.Processes, NavItem.Services, NavItem.Logs, NavItem.Users, NavItem.Console),
    null to listOf(NavItem.Alerts),
)
val NAV_ORDER: List<NavItem> = NAV_GROUPS.flatMap { it.second }
fun sortNav(items: Collection<NavItem>): List<NavItem> = NAV_ORDER.filter { it in items }

@Serializable
data class Machine(
    val id: String = UUID.randomUUID().toString(),
    val kind: MachineKind = MachineKind.debian,
    val name: String = "",
    val host: String = "",
    val port: Int = 22,
    val user: String = "",
    val useTLS: Boolean = true,
    val allowSelfSigned: Boolean = true,
    val usePassword: Boolean = false,
    val sshEnabled: Boolean = false,
    val sshPort: Int = 22,
    val sshUser: String = "",
    val sshSudo: Boolean = true,
    val fanPort: Int = 9595,
    val useSudo: Boolean = true,
    val lhmPort: Int = 0,
    val webUrl: String = "",
    val mac: String = "",
) {
    val displayName: String get() = name.trim().ifEmpty { host.ifEmpty { kind.label } }
    val webAddress: String
        get() {
            if (webUrl.isNotBlank()) return webUrl.trim()
            if ((kind == MachineKind.truenas || kind == MachineKind.opnsense) && host.isNotBlank())
                return "${if (useTLS) "https" else "http"}://${host.trim()}:$port"
            return ""
        }
    val isValid: Boolean
        get() = host.isNotBlank() && (kind == MachineKind.truenas || user.isNotBlank())
}

// ---- shared row models ----

data class StorageItem(
    val id: String, val title: String, val size: Long, val used: Long,
    val subtitle: String = "", val healthy: Boolean = true, val statusText: String = "",
) { val fraction: Float get() = if (size > 0) (used.toFloat() / size).coerceIn(0f, 1f) else 0f }

data class ServiceRow(
    val id: String, val name: String, val displayName: String, val state: String,
    val isRunning: Boolean, val startMode: String = "", val canControl: Boolean = true,
)

data class ProcessRow(val pid: Long, val name: String, val cpu: Double, val mem: Double, val user: String = "")

enum class Level { Info, Warning, Critical }
data class AlertItem(val id: String, val level: Level, val title: String, val detail: String = "")

sealed interface ConnState {
    data object Disconnected : ConnState
    data object Connecting : ConnState
    data object Connected : ConnState
    data class Failed(val message: String) : ConnState
}

/** Everything the dashboard/overview/widget needs, refreshed on every poll. */
data class Snapshot(
    val hostName: String = "",
    val os: String = "",
    val uptime: Double = 0.0,
    val cpu: Double? = null,
    val memUsed: Double = 0.0,
    val memTotal: Double = 0.0,
    val rxRate: Double? = null,
    val txRate: Double? = null,
    val powerWatts: Double? = null,
    val storage: List<StorageItem> = emptyList(),
    val alerts: List<AlertItem> = emptyList(),
    val cpuHistory: List<Float> = emptyList(),
    val memHistory: List<Float> = emptyList(),
    val updatedAt: Long = 0,
) {
    val memFraction: Float get() = if (memTotal > 0) (memUsed / memTotal).toFloat().coerceIn(0f, 1f) else 0f
}

// ---- generic page model: every machine page is data, rendered by one UI ----

sealed interface Block
data class Heading(val title: String) : Block
data class Stats(val items: List<Pair<String, String>>) : Block
data class Gauge(val label: String, val fraction: Float, val text: String) : Block
data class Chart(val title: String, val series: List<Float>, val unit: String = "%", val max: Float? = null) : Block
data class Note(val text: String, val warn: Boolean = false) : Block
data class Mono(val text: String) : Block
data class Item(
    val id: String, val title: String, val subtitle: String = "", val trailing: String = "",
    val badge: String = "", val badgeLevel: Level = Level.Info, val progress: Float? = null,
    val actions: List<Act> = emptyList(), val detail: String = "",
) : Block

data class Act(
    val label: String, val confirm: String? = null, val danger: Boolean = false,
    /** Return null on success, or text to show the user (an error or a command's output). */
    val run: suspend () -> String?,
)

data class Field(val key: String, val label: String, val secret: Boolean = false, val multiline: Boolean = false, val hint: String = "")
data class FormAct(val label: String, val fields: List<Field>, val submit: suspend (Map<String, String>) -> String?)

data class Page(
    val blocks: List<Block> = emptyList(),
    val forms: List<FormAct> = emptyList(),
    val topActions: List<Act> = emptyList(),
    /** Client-side filter over Item titles. */
    val filterable: Boolean = false,
    /** If set, a text box that re-runs the load with the typed text (server-side filter, e.g. logs). */
    val queryHint: String? = null,
    val refreshMs: Long = 0,
    val error: String? = null,
)

// ---- formatting ----
object Fmt {
    fun bytes(n: Double?): String {
        if (n == null) return "–"
        var v = abs(if (n.isNaN() || n.isInfinite()) 0.0 else n)
        val neg = (n ?: 0.0) < 0
        val units = listOf("bytes", "KB", "MB", "GB", "TB", "PB")
        var i = 0
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        val s = if (i == 0) "${v.toLong()} bytes" else when {
            v < 10 -> "%.2f %s".format(v, units[i]); v < 100 -> "%.1f %s".format(v, units[i]); else -> "%.0f %s".format(v, units[i])
        }
        return if (neg) "-$s" else s
    }
    fun bytes(n: Long?) = bytes(n?.toDouble())
    fun rate(n: Double?) = if (n == null) "–" else bytes(n) + "/s"
    fun percent(f: Double?) = if (f == null) "–" else "${f.roundToInt()}%"
    fun uptime(seconds: Double?): String {
        if (seconds == null) return "–"
        val s = seconds.toLong(); val d = s / 86400; val h = (s % 86400) / 3600; val m = (s % 3600) / 60
        return if (d > 0) "${d}d ${h}h" else if (h > 0) "${h}h ${m}m" else "${m}m"
    }
    fun mhz(m: Double?): String = if (m == null || m <= 0) "–" else if (m >= 1000) "%.2f GHz".format(m / 1000) else "${m.toInt()} MHz"
    fun temp(c: Double?) = if (c == null) "–" else "${c.roundToInt()}°C"
    fun watts(w: Double?) = if (w == null) "–" else "%.1f W".format(w)
}
