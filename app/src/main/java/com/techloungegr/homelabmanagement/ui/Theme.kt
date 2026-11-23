package com.techloungegr.homelabmanagement.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Blue = Color(0xFF1A73E8)

private val Light = lightColorScheme(primary = Blue, onPrimary = Color.White, primaryContainer = Color(0xFFD3E3FD), onPrimaryContainer = Color(0xFF041E49),
    secondary = Color(0xFF5F6368), background = Color(0xFFF8FAFD), surface = Color(0xFFF8FAFD), surfaceVariant = Color(0xFFE1E5EC))
private val Dark = darkColorScheme(primary = Color(0xFFA8C7FA), onPrimary = Color(0xFF062E6F), primaryContainer = Color(0xFF0842A0), onPrimaryContainer = Color(0xFFD3E3FD),
    secondary = Color(0xFFC4C7C5), background = Color(0xFF111318), surface = Color(0xFF111318), surfaceVariant = Color(0xFF2B2F36))

val OkGreen = Color(0xFF34A853)
val WarnAmber = Color(0xFFF9AB00)
val CritRed = Color(0xFFD93025)

@Composable
fun HomeLabTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = if (Build.VERSION.SDK_INT >= 31 && false) (if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)) else if (dark) Dark else Light
    MaterialTheme(colorScheme = scheme, content = content)
}
