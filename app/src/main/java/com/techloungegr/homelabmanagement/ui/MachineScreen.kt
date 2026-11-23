package com.techloungegr.homelabmanagement.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techloungegr.homelabmanagement.model.*
import com.techloungegr.homelabmanagement.net.Wol
import com.techloungegr.homelabmanagement.session.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

fun navIcon(n: NavItem) = when (n) {
    NavItem.Dashboard -> Icons.Default.Speed; NavItem.Storage -> Icons.Default.Storage; NavItem.Processes -> Icons.Default.FormatListBulleted
    NavItem.Services -> Icons.Default.ToggleOn; NavItem.Alerts -> Icons.Default.Notifications; NavItem.Apps -> Icons.Default.Apps
    NavItem.Vms -> Icons.Default.DesktopWindows; NavItem.Containers -> Icons.Default.Inventory2; NavItem.Fan -> Icons.Default.Air
    NavItem.Pihole -> Icons.Default.Shield; NavItem.Pikvm -> Icons.Default.Keyboard; NavItem.Tailscale -> Icons.Default.Lan
    NavItem.Cpu -> Icons.Default.Memory; NavItem.Gpu -> Icons.Default.VideogameAsset; NavItem.Sensors -> Icons.Default.Thermostat
    NavItem.Power -> Icons.Default.Bolt; NavItem.Console -> Icons.Default.Terminal; NavItem.Logs -> Icons.Default.Description
    NavItem.Users -> Icons.Default.Person; NavItem.Updates -> Icons.Default.SystemUpdate; NavItem.Startup -> Icons.Default.PowerSettingsNew
    NavItem.ScheduledTasks -> Icons.Default.Schedule; NavItem.Software -> Icons.Default.Inventory; NavItem.Datasets -> Icons.Default.Folder
    NavItem.Shares -> Icons.Default.FolderShared; NavItem.Network -> Icons.Default.Lan; NavItem.DataProtection -> Icons.Default.Lock
    NavItem.RaspberryPi -> Icons.Default.Memory; NavItem.Vpn -> Icons.Default.VpnKey; NavItem.DhcpLeases -> Icons.Default.Lan
    NavItem.LocalDns -> Icons.Default.Dns; NavItem.DnsmasqHosts -> Icons.Default.Dns; NavItem.History -> Icons.Default.History
}

fun kindIcon(k: MachineKind) = when (k) {
    MachineKind.truenas -> Icons.Default.Dns; MachineKind.windows -> Icons.Default.DesktopWindows; MachineKind.macos -> Icons.Default.LaptopMac
    MachineKind.opnsense -> Icons.Default.Security; MachineKind.bazzite -> Icons.Default.VideogameAsset; MachineKind.raspios -> Icons.Default.Memory
    else -> Icons.Default.Terminal
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MachineScreen(session: Session, wide: Boolean, onBack: () -> Unit, onEdit: () -> Unit) {
    val nav by session.nav.collectAsState()
    var current by rememberSaveable(session.id) { mutableStateOf(NavItem.Dashboard) }
    if (current !in nav) current = NavItem.Dashboard
    val state by session.state.collectAsState()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val msg by session.messages.collectAsState()
    LaunchedEffect(msg) { msg?.let { snackbar.showSnackbar(it); session.messages.value = null } }

    val menu: @Composable (onPick: () -> Unit) -> Unit = { picked ->
        Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
            NAV_GROUPS.forEach { (group, items) ->
                val shown = items.filter { it in nav }
                if (shown.isEmpty()) return@forEach
                if (group != null) Text(group.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 28.dp, top = 12.dp, bottom = 4.dp))
                shown.forEach { n ->
                    NavigationDrawerItem(label = { Text(n.label) }, selected = n == current, icon = { Icon(navIcon(n), null) },
                        onClick = { current = n; picked() }, modifier = Modifier.padding(horizontal = 12.dp))
                }
            }
        }
    }

    val content: @Composable () -> Unit = {
        Scaffold(
            topBar = {
                TopAppBar(title = { Column { Text(session.machine.displayName, maxLines = 1); Text(current.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                    navigationIcon = {
                        if (wide) IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                        else IconButton({ scope.launch { drawer.open() } }) { Icon(Icons.Default.Menu, "Pages") }
                    },
                    actions = {
                        StateDot(state)
                        MachineMenu(session, onBack, onEdit, snackbar)
                    })
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when (current) {
                    NavItem.Dashboard -> Dashboard(session)
                    NavItem.Console -> ConsolePage(session)
                    else -> PageContent(session, current, snackbar)
                }
            }
        }
    }

    if (wide) {
        PermanentNavigationDrawer(drawerContent = { PermanentDrawerSheet(Modifier.width(280.dp)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(kindIcon(session.machine.kind), null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(10.dp))
                Column { Text(session.machine.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1); Text(session.machine.kind.label, style = MaterialTheme.typography.bodySmall) }
            }
            menu {}
        } }, content = content)
    } else {
        ModalNavigationDrawer(drawerState = drawer, drawerContent = { ModalDrawerSheet {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(kindIcon(session.machine.kind), null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(10.dp))
                Column { Text(session.machine.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1); Text(session.machine.kind.label, style = MaterialTheme.typography.bodySmall) }
            }
            menu { scope.launch { drawer.close() } }
        } }, content = content)
    }
}

@Composable
fun StateDot(state: ConnState) {
    val (c, t) = when (state) { ConnState.Connected -> OkGreen to "Connected"; ConnState.Connecting -> WarnAmber to "Connecting"; is ConnState.Failed -> CritRed to "Offline"; else -> MaterialTheme.colorScheme.outline to "Idle" }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 4.dp)) {
        Surface(color = c, shape = androidx.compose.foundation.shape.CircleShape, modifier = Modifier.size(10.dp)) {}
        Spacer(Modifier.width(6.dp)); Text(t, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun MachineMenu(session: Session, onBack: () -> Unit, onEdit: () -> Unit, snackbar: SnackbarHostState) {
    val ctx = LocalContext.current
    val m = session.machine
    var open by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    IconButton({ open = true }) { Icon(Icons.Default.MoreVert, "Machine menu") }
    DropdownMenu(open, { open = false }) {
        DropdownMenuItem({ Text("Refresh now") }, { open = false; scope.launch { session.refreshOnce() } })
        if (m.webAddress.isNotBlank()) DropdownMenuItem({ Text("Open web interface") }, { open = false; openUrl(ctx, m.webAddress) })
        if (m.kind.remoteDesktop == "rdp") DropdownMenuItem({ Text("Remote Desktop (Windows App)") }, { open = false; openRdp(ctx, m.host) })
        if (m.kind.remoteDesktop == "vnc") DropdownMenuItem({ Text("Screen sharing (VNC)") }, { open = false; openUrl(ctx, "vnc://${m.host}") })
        if (m.mac.isNotBlank()) DropdownMenuItem({ Text("Wake-on-LAN") }, { open = false; scope.launch {
            try { Wol.wake(m.mac); snackbar.showSnackbar("Magic packet sent to ${m.mac}") } catch (e: Exception) { snackbar.showSnackbar(e.message ?: "Wake-on-LAN failed") } } })
        DropdownMenuItem({ Text("Restart machine") }, { open = false; confirm = "reboot" })
        DropdownMenuItem({ Text("Shut down machine", color = CritRed) }, { open = false; confirm = "shutdown" })
        DropdownMenuItem({ Text("Edit machine") }, { open = false; onEdit() })
    }
    confirm?.let { what ->
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text(if (what == "reboot") "Restart ${m.displayName}?" else "Shut down ${m.displayName}?") },
            text = { Text(if (what == "reboot") "The machine will restart." else "The machine will power off and can only be started again with Wake-on-LAN or at the machine.") },
            confirmButton = { TextButton({ confirm = null; scope.launch {
                try { if (what == "reboot") session.reboot() else session.shutdown(); snackbar.showSnackbar("Command sent") } catch (e: CancellationException) { throw e } catch (e: Exception) { snackbar.showSnackbar(e.message ?: "Failed") } } }) { Text("Confirm", color = CritRed) } },
            dismissButton = { TextButton({ confirm = null }) { Text("Cancel") } })
    }
}

fun openUrl(ctx: Context, url: String) {
    try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    catch (e: ActivityNotFoundException) { android.widget.Toast.makeText(ctx, "No app can open $url", android.widget.Toast.LENGTH_LONG).show() }
}

/** Hands off to Microsoft's "Windows App" (package com.microsoft.rdc.androidx); opens its Play Store page if it isn't installed. */
fun openRdp(ctx: Context, host: String) {
    val pkg = "com.microsoft.rdc.androidx"
    val installed = try { ctx.packageManager.getPackageInfo(pkg, 0); true } catch (_: Exception) { false }
    if (installed) {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("rdp://full%20address=s:$host&audiomode=i:2&desktopwidth=i:1920&desktopheight=i:1080")).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(i); return } catch (_: Exception) {}
        ctx.packageManager.getLaunchIntentForPackage(pkg)?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return }
    }
    android.widget.Toast.makeText(ctx, "Install Microsoft's “Windows App” to connect to $host.", android.widget.Toast.LENGTH_LONG).show()
    openUrl(ctx, "market://details?id=$pkg")
}

@Composable
fun Dashboard(session: Session) {
    val s by session.snap.collectAsState()
    val state by session.state.collectAsState()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state is ConnState.Failed) item { BlockView(Note("Couldn't connect: ${(state as ConnState.Failed).message}", warn = true), {}) }
        item {
            BlockView(Stats(listOfNotNull("Host" to s.hostName.ifBlank { session.machine.host }, "System" to s.os.ifBlank { "–" }, "Uptime" to Fmt.uptime(s.uptime),
                if (s.powerWatts != null) "Power (est.)" to Fmt.watts(s.powerWatts) else null)), {})
        }
        item { BarGauge("CPU", ((s.cpu ?: 0.0) / 100).toFloat(), Fmt.percent(s.cpu)) }
        item { BarGauge("Memory", s.memFraction, if (s.memTotal > 100) "${Fmt.bytes(s.memUsed)} of ${Fmt.bytes(s.memTotal)}" else "${s.memUsed.toInt()}%") }
        if (s.rxRate != null || s.txRate != null) item { BlockView(Stats(listOf("Network ↓" to Fmt.rate(s.rxRate), "Network ↑" to Fmt.rate(s.txRate))), {}) }
        item { ChartCard("CPU history", s.cpuHistory, "%", 100f) }
        item { ChartCard("Memory history", s.memHistory, "%", 100f) }
        if (s.storage.isNotEmpty()) {
            item { Text("Storage", style = MaterialTheme.typography.titleMedium) }
            items(s.storage) { st -> BarGauge(st.title + if (st.statusText.isNotBlank()) " · ${st.statusText}" else "", st.fraction, "${Fmt.bytes(st.used)} / ${Fmt.bytes(st.size)}") }
        }
        if (s.alerts.isNotEmpty()) {
            item { Text("Alerts (${s.alerts.size})", style = MaterialTheme.typography.titleMedium) }
            items(s.alerts.take(5)) { a -> BlockView(Item(a.id, a.title, a.detail, badge = a.level.name, badgeLevel = a.level), {}) }
        }
    }
}

@Composable
fun ConsolePage(session: Session) {
    if (!session.consoleAvailable) { CenterMessage(session.consoleUnavailableReason); return }
    var cmd by remember { mutableStateOf("") }
    val log = remember { mutableStateListOf<Pair<String, String>>() }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val state by session.state.collectAsState()
    fun run() {
        val c = cmd.trim(); if (c.isEmpty() || busy) return
        cmd = ""; busy = true
        scope.launch { val out = session.runConsole(c); log.add(0, c to out.ifBlank { "(no output)" }); busy = false }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(cmd, { cmd = it }, Modifier.weight(1f), label = { Text("Command") }, singleLine = true, enabled = state is ConnState.Connected,
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send, autoCorrectEnabled = false, capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { run() }))
            Spacer(Modifier.width(8.dp))
            FilledIconButton({ run() }, enabled = !busy && cmd.isNotBlank()) { Icon(Icons.Default.PlayArrow, "Run") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text("Each command runs separately (with sudo where the machine allows it); there is no interactive shell.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp, horizontal = 0.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(log) { (c, out) ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
                    SelectionContainer { Column(Modifier.padding(10.dp)) {
                        Text("$ $c", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        Text(out, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)
                    } }
                }
            }
        }
    }
}
