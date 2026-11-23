package com.techloungegr.homelabmanagement.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.work.*
import com.techloungegr.homelabmanagement.MainActivity
import com.techloungegr.homelabmanagement.data.Store
import com.techloungegr.homelabmanagement.model.ConnState
import com.techloungegr.homelabmanagement.model.Fmt
import com.techloungegr.homelabmanagement.session.SessionManager
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

@Serializable
data class WidgetRow(val name: String, val kind: String, val online: Boolean, val cpu: Int?, val mem: Int?, val alerts: Int, val at: Long)

object WidgetData {
    private val json = Json { ignoreUnknownKeys = true }
    fun load(): List<WidgetRow> = try { json.decodeFromString(ListSerializer(WidgetRow.serializer()), Store.pref("widget.rows", "[]")) } catch (_: Exception) { emptyList() }
    fun save(rows: List<WidgetRow>) = Store.setPref("widget.rows", json.encodeToString(ListSerializer(WidgetRow.serializer()), rows))

    /** Captures the live sessions' current state for the widget. */
    fun fromSessions(): List<WidgetRow> = SessionManager.all().map { s ->
        val snap = s.snap.value
        WidgetRow(s.machine.displayName, s.machine.kind.label, s.state.value is ConnState.Connected, snap.cpu?.toInt(), if (snap.memTotal > 0) (snap.memFraction * 100).toInt() else null, snap.alerts.size, snap.updatedAt)
    }
}

class StatusWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        Store.init(context)
        val rows = WidgetData.load()
        provideContent { Content(rows) }
    }

    @Composable
    private fun Content(rows: List<WidgetRow>) {
        val bg = ColorProvider(Color(0xFF111318)); val fg = ColorProvider(Color(0xFFE6E8EE)); val dim = ColorProvider(Color(0xFF9AA0AA))
        Column(GlanceModifier.fillMaxSize().background(bg).cornerRadius(20.dp).padding(12.dp).clickable(actionStartActivity<MainActivity>())) {
            Text("HomeLab", style = TextStyle(color = ColorProvider(Color(0xFFA8C7FA)), fontSize = 13.sp, fontWeight = FontWeight.Bold))
            Spacer(GlanceModifier.height(6.dp))
            if (rows.isEmpty()) Text("Open the app to add machines.", style = TextStyle(color = dim, fontSize = 12.sp))
            else LazyColumn {
                items(rows) { r ->
                    Row(GlanceModifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (r.online) "●" else "○", style = TextStyle(color = ColorProvider(if (r.online) Color(0xFF34A853) else Color(0xFFD93025)), fontSize = 12.sp))
                        Spacer(GlanceModifier.width(6.dp))
                        Text(r.name, modifier = GlanceModifier.defaultWeight(), maxLines = 1, style = TextStyle(color = fg, fontSize = 13.sp))
                        Text(if (r.online) "CPU ${r.cpu?.let { "$it%" } ?: "–"}  RAM ${r.mem?.let { "$it%" } ?: "–"}" + (if (r.alerts > 0) "  ⚠${r.alerts}" else "") else "offline",
                            style = TextStyle(color = dim, fontSize = 11.sp))
                    }
                }
            }
        }
    }
}

class StatusWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StatusWidget()
    override fun onEnabled(context: Context) { WidgetWorker.schedule(context); super.onEnabled(context) }
    override fun onDisabled(context: Context) { WorkManager.getInstance(context).cancelUniqueWork(WidgetWorker.NAME); super.onDisabled(context) }
}

/** Refreshes the widget about every 15 minutes: polls each machine once, then redraws. */
class WidgetWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Store.init(applicationContext); com.techloungegr.homelabmanagement.session.Scripts.init(applicationContext)
        val machines = Store.loadMachines()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val rows = try {
            withTimeout(90_000) {
                machines.map { m -> async {
                    val s = SessionManager.create(m, scope)
                    try { s.refreshOnce() } catch (_: Exception) {}
                    val snap = s.snap.value
                    val r = WidgetRow(m.displayName, m.kind.label, s.state.value is ConnState.Connected, snap.cpu?.toInt(), if (snap.memTotal > 0) (snap.memFraction * 100).toInt() else null, snap.alerts.size, System.currentTimeMillis())
                    s.stop(); r
                } }.awaitAll()
            }
        } catch (_: Exception) { return Result.retry() } finally { scope.cancel() }
        WidgetData.save(rows)
        StatusWidget().updateAll(applicationContext)
        return Result.success()
    }

    companion object {
        const val NAME = "widget-refresh"
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<WidgetWorker>(15, TimeUnit.MINUTES).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}

private suspend fun GlanceAppWidget.updateAll(ctx: Context) {
    val mgr = androidx.glance.appwidget.GlanceAppWidgetManager(ctx)
    mgr.getGlanceIds(this::class.java).forEach { update(ctx, it) }
}
