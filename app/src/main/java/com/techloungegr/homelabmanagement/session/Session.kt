package com.techloungegr.homelabmanagement.session

import android.content.Context
import com.techloungegr.homelabmanagement.data.Store
import com.techloungegr.homelabmanagement.model.*
import com.techloungegr.homelabmanagement.net.SshRunner
import com.techloungegr.homelabmanagement.net.SshSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object Scripts {
    private lateinit var ctx: Context
    private val cache = HashMap<String, String>()
    fun init(c: Context) { ctx = c.applicationContext }
    fun get(name: String): String = synchronized(cache) {
        cache.getOrPut(name) { ctx.assets.open("scripts/$name.txt").bufferedReader().use { it.readText() } }
    }
}

const val HISTORY_LEN = 90

/** Marks work done for a page/action so it uses its own SSH connection and never disturbs the poll loop. */
class PageElement : kotlin.coroutines.AbstractCoroutineContextElement(PageElement) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<PageElement>
}
val PageCtx = PageElement()


/** Live state + polling for one managed machine (mirrors MachineSession in the Linux app). */
abstract class Session(val machine: Machine, protected val scope: CoroutineScope) {
    val id get() = machine.id
    private val _state = MutableStateFlow<ConnState>(ConnState.Disconnected)
    val state: StateFlow<ConnState> = _state.asStateFlow()
    private val _snap = MutableStateFlow(Snapshot())
    val snap: StateFlow<Snapshot> = _snap.asStateFlow()
    private val _nav = MutableStateFlow(baseNav())
    val nav: StateFlow<List<NavItem>> = _nav.asStateFlow()
    /** Non-fatal problem from the last action (shown as a snackbar). */
    val messages = MutableStateFlow<String?>(null)

    @Volatile var foreground = false
    @Volatile var selected = false
    private var job: Job? = null

    protected var cpuHistory = ArrayList<Float>()
    protected var memHistory = ArrayList<Float>()

    abstract fun baseNav(): List<NavItem>
    /** One refresh of the dashboard data. Throw to mark the machine as failed. */
    protected abstract suspend fun poll(): Snapshot
    abstract suspend fun loadPage(nav: NavItem, query: String): Page
    suspend fun page(nav: NavItem, query: String): Page = withContext(PageCtx) { loadPage(nav, query) }
    suspend fun <T> inPage(block: suspend () -> T): T = withContext(PageCtx) { block() }
    protected open fun closeBackend() {}
    open suspend fun reboot() {}
    open suspend fun shutdown() {}
    open val consoleAvailable: Boolean get() = true
    open val consoleUnavailableReason: String get() = ""
    open suspend fun runConsole(command: String): String = ""

    protected fun setNav(items: Collection<NavItem>) { _nav.value = sortNav(items + NavItem.History) }
    protected fun push(list: ArrayList<Float>, v: Double?) {
        if (v == null) return
        list.add(v.toFloat()); while (list.size > HISTORY_LEN) list.removeAt(0)
    }
    protected fun updateSnap(f: (Snapshot) -> Snapshot) = _snap.update(f)
    fun setState(s: ConnState) { _state.value = s }

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                if (!foreground) { delay(1000); continue }
                refreshOnce()
                delay(if (_state.value is ConnState.Connected) (if (selected) 5_000 else 15_000) else 12_000)
            }
        }
    }

    suspend fun refreshOnce() {
        try {
            if (_state.value !is ConnState.Connected) _state.value = ConnState.Connecting
            val s = poll()
            _snap.value = s.copy(updatedAt = System.currentTimeMillis())
            _state.value = ConnState.Connected
            val net = if (s.rxRate != null || s.txRate != null) (s.rxRate ?: 0.0) + (s.txRate ?: 0.0) else null
            Store.recordSample(id, s.cpu, if (s.memTotal > 0) s.memUsed / s.memTotal else null, net)
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            _state.value = ConnState.Failed(e.message ?: e.javaClass.simpleName)
            try { closeBackend() } catch (_: Exception) {}
        }
    }

    fun stop() { job?.cancel(); job = null; try { closeBackend() } catch (_: Exception) {}; _state.value = ConnState.Disconnected }

    protected fun alertsFailedServices(names: List<String>) =
        names.map { AlertItem("svc-$it", Level.Critical, "Service failed: $it") }

    /** Runs a user action and reports failures; used by every page action. */
    protected suspend fun guarded(block: suspend () -> String?): String? =
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { e.message ?: e.javaClass.simpleName }

    // ---- shared pages ----
    protected fun storagePage(items: List<StorageItem>, actions: (StorageItem) -> List<Act> = { emptyList() }): Page =
        Page(items.map {
            Item(it.id, it.title, listOf(it.subtitle, it.statusText).filter { s -> s.isNotBlank() }.joinToString(" · "),
                "${Fmt.bytes(it.used)} of ${Fmt.bytes(it.size)}", progress = it.fraction,
                badge = if (it.healthy) "" else "Unhealthy", badgeLevel = Level.Critical, actions = actions(it))
        }, error = if (items.isEmpty()) "No storage information yet." else null)

    protected fun alertsPage(alerts: List<AlertItem>, dismiss: ((AlertItem) -> Act)? = null): Page =
        if (alerts.isEmpty()) Page(listOf(Note("No active alerts.")))
        else Page(alerts.map { Item(it.id, it.title, it.detail, badge = when (it.level) { Level.Critical -> "Critical"; Level.Warning -> "Warning"; else -> "Info" },
            badgeLevel = it.level, actions = listOfNotNull(dismiss?.invoke(it))) })

    protected fun historyPage(range: String): Page {
        val seconds = when (range) { "1h" -> 3600L; "6h" -> 6 * 3600L; "7d" -> 7 * 86400L; else -> 86400L }
        val (cpu, mem, net) = Store.historySeries(id, seconds)
        if (cpu.isEmpty() && mem.isEmpty()) return Page(listOf(Note("No history recorded yet. Samples are stored once a minute while the app is open.")), queryHint = "Range: 1h, 6h, 24h or 7d")
        return Page(listOf(Heading("Last $range"), Chart("CPU", cpu, "%", 100f), Chart("Memory", mem, "%", 100f),
            Chart("Network", net.map { it }, "B/s")), queryHint = "Range: 1h, 6h, 24h or 7d")
    }

    protected fun sshSettings(): SshSettings = SshSettings(machine.host, machine.port, machine.user, Store.secretOf(id), machine.useSudo)
}

/** Base for the sessions that talk SSH (Linux, macOS, Windows). */
abstract class SshSession(machine: Machine, scope: CoroutineScope) : Session(machine, scope) {
    protected val runner = SshRunner()
    protected val pageRunner = SshRunner()
    protected val aux = SshRunner()
    protected var prevNet: Triple<Double, Double, Long>? = null

    override fun closeBackend() { runner.close(); pageRunner.close(); aux.close(); prevNet = null }

    private suspend fun pick(r: SshRunner?): SshRunner = r ?: if (kotlin.coroutines.coroutineContext[PageElement] != null) pageRunner else runner

    protected suspend fun sh(script: String, sudo: Boolean = false, merge: Boolean = false, r: SshRunner? = null): String {
        val use = pick(r)
        return withContext(Dispatchers.IO) { use.sh(script, sshSettings(), sudo, merge) }
    }

    protected suspend fun ps(script: String, r: SshRunner? = null): String {
        val use = pick(r)
        return withContext(Dispatchers.IO) { use.ps(script, sshSettings()) }
    }

    protected fun netRates(rx: Double, tx: Double): Pair<Double?, Double?> {
        val now = System.currentTimeMillis(); val prev = prevNet; prevNet = Triple(rx, tx, now)
        if (prev == null) return null to null
        val dt = (now - prev.third) / 1000.0
        if (dt <= 0.5) return null to null
        return (maxOf(0.0, rx - prev.first) / dt) to (maxOf(0.0, tx - prev.second) / dt)
    }

    override suspend fun runConsole(command: String): String = withContext(Dispatchers.IO) {
        try { pageRunner.sh(command, sshSettings(), sudo = true, merge = true) } catch (e: Exception) { e.message ?: "error" }
    }
}

object SessionManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessions = LinkedHashMap<String, Session>()
    val machines = MutableStateFlow<List<Machine>>(emptyList())
    private var fg = false

    fun load() { machines.value = Store.loadMachines(); sync() }

    private fun sync() {
        val ids = machines.value.map { it.id }.toSet()
        synchronized(sessions) {
            sessions.keys.filter { it !in ids }.forEach { sessions.remove(it)?.stop() }
            for (m in machines.value) {
                val existing = sessions[m.id]
                if (existing == null || existing.machine != m) {
                    existing?.stop()
                    val s = create(m); s.foreground = fg; sessions[m.id] = s; s.start()
                }
            }
        }
    }

    fun create(m: Machine, sc: CoroutineScope = scope): Session = when (m.kind) {
        MachineKind.truenas -> TrueNasSession(m, sc)
        MachineKind.windows -> WindowsSession(m, sc)
        MachineKind.macos -> MacSession(m, sc)
        MachineKind.opnsense -> OpnSession(m, sc)
        MachineKind.bazzite -> GamingLinuxSession(m, sc)
        else -> LinuxSession(m, sc)
    }

    fun session(id: String): Session? = synchronized(sessions) { sessions[id] }
    fun all(): List<Session> = synchronized(sessions) { sessions.values.toList() }

    fun save(m: Machine) {
        val list = machines.value.toMutableList()
        val i = list.indexOfFirst { it.id == m.id }
        if (i >= 0) list[i] = m else list.add(m)
        Store.saveMachines(list); machines.value = list; sync()
    }

    fun delete(id: String) {
        val list = machines.value.filter { it.id != id }
        Store.saveMachines(list); Store.forgetSecrets(id); machines.value = list; sync()
    }

    fun select(id: String?) { synchronized(sessions) { sessions.values.forEach { it.selected = it.id == id } } }

    fun setForeground(f: Boolean) { fg = f; synchronized(sessions) { sessions.values.forEach { it.foreground = f } } }
}
