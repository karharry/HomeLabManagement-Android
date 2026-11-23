package com.techloungegr.homelabmanagement.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.techloungegr.homelabmanagement.model.*
import com.techloungegr.homelabmanagement.session.Session
import com.techloungegr.homelabmanagement.session.SessionManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpen: (String) -> Unit, onAdd: () -> Unit, onEdit: (String) -> Unit, onAbout: () -> Unit) {
    val machines by SessionManager.machines.collectAsState()
    Scaffold(
        topBar = { TopAppBar(title = { Text("HomeLab Management") }, actions = { IconButton(onAbout) { Icon(Icons.Default.Info, "About") } }) },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = onAdd) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Add machine") } },
    ) { pad ->
        if (machines.isEmpty()) {
            Box(Modifier.padding(pad).fillMaxSize().padding(32.dp), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.Dns, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("No machines yet", style = MaterialTheme.typography.titleLarge)
                    Text("Add a TrueNAS, Linux, Windows, macOS or OPNsense machine to monitor and manage it from here.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onAdd) { Text("Add your first machine") }
                }
            }
        } else {
            val sessions = machines.mapNotNull { SessionManager.session(it.id) }
            LazyVerticalGrid(GridCells.Adaptive(300.dp), Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(sessions, key = { it.id }) { s -> MachineCard(s, { onOpen(s.id) }, { onEdit(s.id) }) }
            }
        }
    }
}

@Composable
private fun MachineCard(s: Session, onOpen: () -> Unit, onEdit: () -> Unit) {
    val state by s.state.collectAsState()
    val snap by s.snap.collectAsState()
    Card(onClick = onOpen, Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(kindIcon(s.machine.kind), null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.machine.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${s.machine.kind.label} · ${s.machine.host}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onEdit) { Icon(Icons.Default.Edit, "Edit") }
            }
            StateDot(state)
            when (state) {
                is ConnState.Connected -> {
                    BarGauge("CPU", ((snap.cpu ?: 0.0) / 100).toFloat(), Fmt.percent(snap.cpu))
                    BarGauge("Memory", snap.memFraction, Fmt.percent(snap.memFraction * 100.0))
                    Row(Modifier.fillMaxWidth()) {
                        Text("Up ${Fmt.uptime(snap.uptime)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        if (snap.alerts.isNotEmpty()) Badge("${snap.alerts.size} alert${if (snap.alerts.size > 1) "s" else ""}", snap.alerts.maxOf { it.level })
                    }
                }
                is ConnState.Failed -> Text((state as ConnState.Failed).message, style = MaterialTheme.typography.bodySmall, color = CritRed, maxLines = 3, overflow = TextOverflow.Ellipsis)
                else -> {}
            }
        }
    }
}
