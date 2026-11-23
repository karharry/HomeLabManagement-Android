package com.techloungegr.homelabmanagement.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techloungegr.homelabmanagement.model.*
import com.techloungegr.homelabmanagement.session.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun PageContent(session: Session, nav: NavItem, snackbar: SnackbarHostState) {
    var query by rememberSaveable(session.id, nav) { mutableStateOf("") }
    var submitted by rememberSaveable(session.id, nav) { mutableStateOf("") }
    var filter by rememberSaveable(session.id, nav) { mutableStateOf("") }
    var tick by remember(session.id, nav) { mutableIntStateOf(0) }
    var page by remember(session.id, nav) { mutableStateOf<Page?>(null) }
    var failure by remember(session.id, nav) { mutableStateOf<String?>(null) }
    var loading by remember(session.id, nav) { mutableStateOf(true) }
    val state by session.state.collectAsState()

    LaunchedEffect(session.id, nav, submitted, tick, state is ConnState.Connected) {
        if (state !is ConnState.Connected && nav != NavItem.History) { loading = false; return@LaunchedEffect }
        while (true) {
            try {
                val p = session.loadPage(nav, submitted)
                page = p; failure = null
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { failure = e.message ?: e.javaClass.simpleName }
            loading = false
            val ms = page?.refreshMs ?: 0
            if (ms <= 0) break
            delay(ms)
        }
    }

    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<String?>(null) }
    var confirmAct by remember { mutableStateOf<Act?>(null) }
    var activeForm by remember { mutableStateOf<FormAct?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun runAct(a: Act) {
        scope.launch {
            busy = true
            val r = try { a.run() } catch (e: CancellationException) { throw e } catch (e: Exception) { e.message ?: "error" }
            busy = false
            if (r == null) { snackbar.showSnackbar("${a.label}: done"); tick++ } else { result = r; tick++ }
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        val p = page
        if (p?.queryHint != null) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), singleLine = true,
                label = { Text(p.queryHint, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingIcon = { IconButton({ submitted = query; tick++ }) { Icon(Icons.Default.Search, "Apply") } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { submitted = query; tick++ }))
        }
        if (p?.filterable == true) {
            OutlinedTextField(filter, { filter = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), singleLine = true, label = { Text("Filter") },
                leadingIcon = { Icon(Icons.Default.FilterList, null) }, trailingIcon = { if (filter.isNotEmpty()) IconButton({ filter = "" }) { Icon(Icons.Default.Close, "Clear") } })
        }
        if (p != null && (p.forms.isNotEmpty() || p.topActions.isNotEmpty())) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                p.forms.forEach { f -> FilledTonalButton({ activeForm = f }) { Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(f.label) } }
                p.topActions.forEach { a -> FilledTonalButton({ if (a.confirm != null) confirmAct = a else runAct(a) }) { Text(a.label) } }
            }
        }
        when {
            state !is ConnState.Connected && nav != NavItem.History -> CenterMessage(
                when (val s = state) { is ConnState.Failed -> "Not connected: ${s.message}"; ConnState.Connecting -> "Connecting…"; else -> "Not connected." })
            loading && p == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            p == null -> CenterMessage(failure ?: "Nothing to show.")
            else -> {
                val blocks = if (p.filterable && filter.isNotBlank()) p.blocks.filter { b -> b !is Item || b.title.contains(filter, true) || b.subtitle.contains(filter, true) || b.detail.contains(filter, true) } else p.blocks
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    failure?.let { item { NoteCard(Note("Refresh failed: $it", warn = true)) } }
                    p.error?.let { e -> if (blocks.isEmpty()) item { NoteCard(Note(e, warn = true)) } }
                    items(blocks.size) { i -> BlockView(blocks[i], onAct = { a -> if (a.confirm != null) confirmAct = a else runAct(a) }) }
                }
            }
        }
    }

    confirmAct?.let { a ->
        AlertDialog(onDismissRequest = { confirmAct = null }, title = { Text(a.label) }, text = { Text(a.confirm ?: "") },
            confirmButton = { TextButton({ confirmAct = null; runAct(a) }) { Text(a.label, color = if (a.danger) CritRed else MaterialTheme.colorScheme.primary) } },
            dismissButton = { TextButton({ confirmAct = null }) { Text("Cancel") } })
    }
    result?.let { r ->
        AlertDialog(onDismissRequest = { result = null }, title = { Text("Result") },
            text = { SelectionContainer { Text(r, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.verticalScroll(rememberScrollState())) } },
            confirmButton = { TextButton({ result = null }) { Text("OK") } })
    }
    activeForm?.let { f ->
        FormDialog(f, onDismiss = { activeForm = null }, onSubmit = { values ->
            activeForm = null
            scope.launch { busy = true; val r = try { f.submit(values) } catch (e: CancellationException) { throw e } catch (e: Exception) { e.message ?: "error" }; busy = false
                if (r == null) { snackbar.showSnackbar("${f.label}: done"); tick++ } else { result = r; tick++ } }
        })
    }
}


@Composable
fun CenterMessage(text: String) = Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun NoteCard(n: Note) {
    val c = if (n.warn) WarnAmber else MaterialTheme.colorScheme.primary
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = c.copy(alpha = 0.12f))) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(if (n.warn) Icons.Default.Warning else Icons.Default.Info, null, tint = c, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp)); Text(n.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun BlockView(b: Block, onAct: (Act) -> Unit) {
    when (b) {
        is Heading -> Text(b.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
        is Stats -> Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                b.items.forEach { (k, v) -> Row(Modifier.fillMaxWidth()) {
                    Text(k, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.42f))
                    SelectionContainer(Modifier.weight(0.58f)) { Text(v, style = MaterialTheme.typography.bodyMedium) }
                } }
            }
        }
        is Gauge -> BarGauge(b.label, b.fraction, b.text)
        is Chart -> ChartCard(b.title, b.series, b.unit, b.max)
        is Note -> NoteCard(b)
        is Mono -> Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
            SelectionContainer { Text(b.text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(10.dp)) }
        }
        is Item -> ItemRow(b, onAct)
    }
}

@Composable
private fun ItemRow(i: Item, onAct: (Act) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    Card(onClick = { if (i.detail.isNotBlank()) expanded = !expanded }, modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(i.title, style = MaterialTheme.typography.bodyLarge, maxLines = if (expanded) 6 else 2, overflow = TextOverflow.Ellipsis)
                    if (i.subtitle.isNotBlank()) Text(i.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = if (expanded) 8 else 2, overflow = TextOverflow.Ellipsis)
                }
                if (i.trailing.isNotBlank()) Text(i.trailing, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp).widthIn(max = 150.dp), maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                if (i.badge.isNotBlank()) Badge(i.badge, i.badgeLevel, Modifier.padding(start = 8.dp))
                if (i.actions.isNotEmpty()) Box {
                    IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "Actions") }
                    DropdownMenu(menu, { menu = false }) {
                        i.actions.forEach { a -> DropdownMenuItem(text = { Text(a.label, color = if (a.danger) CritRed else MaterialTheme.colorScheme.onSurface) }, onClick = { menu = false; onAct(a) }) }
                    }
                }
            }
            i.progress?.let { Spacer(Modifier.height(6.dp)); BarGaugeThin(it) }
            if (expanded && i.detail.isNotBlank()) { Spacer(Modifier.height(6.dp)); SelectionContainer { Text(i.detail, style = MaterialTheme.typography.bodySmall) } }
        }
    }
}

@Composable
private fun BarGaugeThin(f: Float) {
    val color = when { f >= 0.9f -> CritRed; f >= 0.75f -> WarnAmber; else -> MaterialTheme.colorScheme.primary }
    LinearProgressIndicator(progress = { f.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(6.dp), color = color, trackColor = MaterialTheme.colorScheme.surfaceVariant, gapSize = 0.dp, drawStopIndicator = {})
}

@Composable
fun Badge(text: String, level: Level, modifier: Modifier = Modifier) {
    val c = when (level) { Level.Info -> OkGreen; Level.Warning -> WarnAmber; Level.Critical -> CritRed }
    Surface(modifier, color = c.copy(alpha = 0.16f), shape = RoundedCornerShape(50)) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 3.dp), color = c, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@Composable
fun FormDialog(f: FormAct, onDismiss: () -> Unit, onSubmit: (Map<String, String>) -> Unit) {
    val values = remember { mutableStateMapOf<String, String>() }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(f.label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                f.fields.forEach { fld ->
                    OutlinedTextField(values[fld.key].orEmpty(), { values[fld.key] = it }, Modifier.fillMaxWidth(), label = { Text(fld.label) },
                        placeholder = if (fld.hint.isNotBlank()) ({ Text(fld.hint, maxLines = 1) }) else null,
                        singleLine = !fld.multiline, minLines = if (fld.multiline) 4 else 1, maxLines = if (fld.multiline) 10 else 1,
                        visualTransformation = if (fld.secret) PasswordVisualTransformation() else VisualTransformation.None,
                        textStyle = if (fld.multiline) androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp) else androidx.compose.ui.text.TextStyle.Default)
                }
            }
        },
        confirmButton = { TextButton({ onSubmit(values.toMap()) }) { Text("OK") } }, dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}
