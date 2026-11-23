package com.techloungegr.homelabmanagement.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.techloungegr.homelabmanagement.BuildConfig
import com.techloungegr.homelabmanagement.session.SessionManager

private sealed interface Route {
    data object Home : Route
    data class Machine(val id: String) : Route
    data class Edit(val id: String?) : Route
    data object About : Route
}

@Composable
fun HomeLabApp() {
    HomeLabTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            var routeKey by rememberSaveable { mutableStateOf("home") }
            val route: Route = when {
                routeKey == "about" -> Route.About
                routeKey.startsWith("machine:") -> Route.Machine(routeKey.removePrefix("machine:"))
                routeKey.startsWith("edit:") -> Route.Edit(routeKey.removePrefix("edit:").ifEmpty { null })
                else -> Route.Home
            }
            fun go(r: String) { routeKey = r }
            val wide = LocalConfiguration.current.screenWidthDp >= 840
            val machines by SessionManager.machines.collectAsState()

            LaunchedEffect(route) { SessionManager.select((route as? Route.Machine)?.id) }
            BackHandler(enabled = route != Route.Home) {
                routeKey = when (route) { is Route.Edit -> if (route.id != null) "machine:${route.id}" else "home"; else -> "home" }
            }
            when (route) {
                Route.Home -> HomeScreen(onOpen = { go("machine:$it") }, onAdd = { go("edit:") }, onEdit = { go("edit:$it") }, onAbout = { go("about") })
                is Route.Machine -> {
                    val s = SessionManager.session(route.id)
                    if (s == null || machines.none { it.id == route.id }) { LaunchedEffect(Unit) { go("home") } }
                    else MachineScreen(s, wide, onBack = { go("home") }, onEdit = { go("edit:${route.id}") })
                }
                is Route.Edit -> EditorScreen(route.id, onDone = { go(if (route.id != null && machines.any { it.id == route.id }) "machine:${route.id}" else "home") })
                Route.About -> AboutScreen { go("home") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AboutScreen(onBack: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("About") }, navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("HomeLab Management", style = MaterialTheme.typography.headlineSmall)
            Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
            Text("Monitor and manage a home lab from your phone or handheld: TrueNAS SCALE, Linux (Debian/Ubuntu, Raspberry Pi OS, Arch, Bazzite/CachyOS), Windows, macOS and OPNsense machines, all from one app. " +
                "This is the Android version of HomeLab Management for Linux, macOS and Windows.")
            Text("Your machines, credentials and history stay on this device. Nothing is sent anywhere except directly to the machines you add. Passwords and keys are encrypted with the Android Keystore.")
            Text("Open source under the MIT License.")
            Text("github.com/karharry/HomeLabManagement-Android", color = MaterialTheme.colorScheme.primary)
            Text("Privacy policy: karharry.github.io/privacy", color = MaterialTheme.colorScheme.primary)
        }
    }
}
