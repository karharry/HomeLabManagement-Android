package com.techloungegr.homelabmanagement.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.techloungegr.homelabmanagement.model.Fmt

@Composable
fun LineChart(series: List<Float>, modifier: Modifier = Modifier, max: Float? = null, color: Color = MaterialTheme.colorScheme.primary) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier.fillMaxWidth().height(64.dp)) {
        drawRect(track.copy(alpha = 0.4f), size = size)
        if (series.size < 2) return@Canvas
        val top = max ?: (series.max() * 1.15f).coerceAtLeast(1f)
        val dx = size.width / (series.size - 1)
        val path = Path(); val fill = Path()
        series.forEachIndexed { i, v ->
            val x = i * dx; val y = size.height - (v / top).coerceIn(0f, 1f) * size.height
            if (i == 0) { path.moveTo(x, y); fill.moveTo(x, size.height); fill.lineTo(x, y) } else { path.lineTo(x, y); fill.lineTo(x, y) }
        }
        fill.lineTo(size.width, size.height); fill.close()
        drawPath(fill, color.copy(alpha = 0.15f))
        drawPath(path, color, style = Stroke(width = 2.5f, cap = StrokeCap.Round))
    }
}

@Composable
fun ChartCard(title: String, series: List<Float>, unit: String, max: Float?) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                val last = series.lastOrNull()
                if (last != null) Text(if (unit == "%") "${last.toInt()}%" else Fmt.rate(last.toDouble()), style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(6.dp))
            LineChart(series, max = max)
        }
    }
}

@Composable
fun BarGauge(label: String, fraction: Float, text: String, modifier: Modifier = Modifier) {
    val color = when { fraction >= 0.9f -> CritRed; fraction >= 0.75f -> WarnAmber; else -> MaterialTheme.colorScheme.primary }
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(8.dp), color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant, strokeCap = StrokeCap.Round, gapSize = 0.dp, drawStopIndicator = {})
    }
}
