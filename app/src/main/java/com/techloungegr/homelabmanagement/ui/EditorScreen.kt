package com.techloungegr.homelabmanagement.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.techloungegr.homelabmanagement.data.Store
import com.techloungegr.homelabmanagement.model.*
import com.techloungegr.homelabmanagement.net.forgetSshHostKey
import com.techloungegr.homelabmanagement.net.forgetTlsCert
import com.techloungegr.homelabmanagement.session.SessionManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(machineId: String?, onDone: () -> Unit) {
    val existing = machineId?.let { id -> SessionManager.machines.value.firstOrNull { it.id == id } }
    var m by remember { mutableStateOf(existing ?: Machine(kind = MachineKind.debian, port = 22)) }
    val id = m.id
    var secret by remember { mutableStateOf(if (existing != null) Store.secretOf(id) else "") }
    var sshPw by remember { mutableStateOf(if (existing != null) Store.sshPasswordOf(id) else "") }
    var fanPw by remember { mutableStateOf(if (existing != null) Store.fanPasswordOf(id) else "") }
    var pihole by remember { mutableStateOf(if (existing != null) Store.piholePasswordOf(id) else "") }
    var confirmDelete by remember { mutableStateOf(false) }
    var kindMenu by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val k = m.kind

    Scaffold(topBar = {
        TopAppBar(title = { Text(if (existing == null) "Add machine" else "Edit machine") },
            navigationIcon = { IconButton(onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                TextButton(enabled = m.isValid, onClick = {
                    SessionManager.save(m)
                    Store.setSecret("secret.$id", secret); Store.setSecret("ssh.$id", sshPw); Store.setSecret("fan.$id", fanPw); Store.setSecret("pihole.$id", pihole)
                    onDone()
                }) { Text("Save") }
            })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ExposedDropdownMenuBox(kindMenu, { kindMenu = it }) {
                OutlinedTextField(k.label, {}, readOnly = true, label = { Text("Type") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(kindMenu) },
                    modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth())
                ExposedDropdownMenu(kindMenu, { kindMenu = false }) {
                    MachineKind.entries.forEach { opt -> DropdownMenuItem({ Text(opt.label) }, { kindMenu = false; m = m.copy(kind = opt, port = opt.defaultPort) }) }
                }
            }
            Field("Name (optional)", m.name) { m = m.copy(name = it) }
            Field("Address (host or IP)", m.host, KeyboardType.Uri) { m = m.copy(host = it.trim()) }
            Field(if (k == MachineKind.truenas || k == MachineKind.opnsense) "Web / API port" else "SSH port", (m.port.let { if (it == 0) "" else it.toString() }), KeyboardType.Number) { m = m.copy(port = it.toIntOrNull() ?: 0) }

            when (k) {
                MachineKind.truenas -> {
                    Text("TrueNAS API", style = MaterialTheme.typography.titleSmall)
                    SwitchRow("Sign in with user name and password (instead of an API key)", m.usePassword) { m = m.copy(usePassword = it) }
                    if (m.usePassword) Field("User name", m.user) { m = m.copy(user = it.trim()) }
                    Field(if (m.usePassword) "Password" else "API key", secret, password = true) { secret = it }
                    SwitchRow("Use HTTPS", m.useTLS) { m = m.copy(useTLS = it) }
                    if (m.useTLS) SwitchRow("Allow self-signed certificate (pinned on first connect)", m.allowSelfSigned) { m = m.copy(allowSelfSigned = it) }
                    Text("Optional SSH (containers, VM stats, console, VPN)", style = MaterialTheme.typography.titleSmall)
                    SwitchRow("Enable SSH", m.sshEnabled) { m = m.copy(sshEnabled = it) }
                    if (m.sshEnabled) {
                        Field("SSH port", (m.sshPort.let { if (it == 0) "" else it.toString() }), KeyboardType.Number) { m = m.copy(sshPort = it.toIntOrNull() ?: 22) }
                        Field("SSH user", m.sshUser) { m = m.copy(sshUser = it.trim()) }
                        Field("SSH password", sshPw, password = true) { sshPw = it }
                        SwitchRow("Use sudo", m.sshSudo) { m = m.copy(sshSudo = it) }
                    }
                    Text("Fan controller (optional)", style = MaterialTheme.typography.titleSmall)
                    Field("Fan controller port", (m.fanPort.let { if (it == 0) "" else it.toString() }), KeyboardType.Number) { m = m.copy(fanPort = it.toIntOrNull() ?: 9595) }
                    Field("Fan controller password (blank if none)", fanPw, password = true) { fanPw = it }
                }
                MachineKind.opnsense -> {
                    Field("API key", m.user) { m = m.copy(user = it.trim()) }
                    Field("API secret", secret, password = true) { secret = it }
                    SwitchRow("Use HTTPS", m.useTLS) { m = m.copy(useTLS = it) }
                    if (m.useTLS) SwitchRow("Allow self-signed certificate (pinned on first connect)", m.allowSelfSigned) { m = m.copy(allowSelfSigned = it) }
                }
                else -> {
                    Field("User name", m.user) { m = m.copy(user = it.trim()) }
                    Field("Password", secret, password = true) { secret = it }
                    if (k != MachineKind.windows) SwitchRow("Use sudo for actions that need root", m.useSudo) { m = m.copy(useSudo = it) }
                    if (k == MachineKind.windows) Field("LibreHardwareMonitor web server port (0 = use WMI)", (m.lhmPort.let { if (it == 0) "" else it.toString() }), KeyboardType.Number) { m = m.copy(lhmPort = it.toIntOrNull() ?: 0) }
                    if (k.isLinux && k != MachineKind.bazzite) Field("Pi-hole v6 web password (optional)", pihole, password = true) { pihole = it }
                }
            }
            Field("Web interface URL (optional)", m.webUrl, KeyboardType.Uri) { m = m.copy(webUrl = it.trim()) }
            Field("MAC address for Wake-on-LAN (optional)", m.mac) { m = m.copy(mac = it.trim()) }

            if (existing != null) {
                OutlinedButton({ forgetSshHostKey(m.host, if (k == MachineKind.truenas) m.sshPort else m.port); forgetTlsCert(m.host, m.port); note = "Pinned keys forgotten. They will be pinned again on the next connect." }) { Text("Forget pinned host key / certificate") }
                note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                OutlinedButton({ confirmDelete = true }, colors = ButtonDefaults.outlinedButtonColors(contentColor = CritRed)) { Text("Delete machine") }
            }
            Text("Passwords and keys are stored encrypted with the Android Keystore and only ever sent to the machine you enter them for.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Delete ${m.displayName}?") }, text = { Text("The machine and its saved credentials are removed from this device. The machine itself is not touched.") },
        confirmButton = { TextButton({ confirmDelete = false; SessionManager.delete(m.id); onDone() }) { Text("Delete", color = CritRed) } }, dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } })
}

@Composable
private fun Field(label: String, value: String, type: KeyboardType = KeyboardType.Text, password: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else type, autoCorrectEnabled = false, capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None))
}

@Composable
private fun SwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium); Spacer(Modifier.width(8.dp)); Switch(value, onChange)
    }
}
