/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 SiRan-Dev
 */
package io.github.vanessajeann.sherry.album

import android.app.Activity
import androidx.core.view.WindowCompat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// The translucent surfaces are composited over the app's own aurora background.
internal val GlassPanel = Color(0xB3233850)
internal val GlassEdge = Color(0x6673D7ED)
private val GlassColors = darkColorScheme(
    primary = Color(0xFF9CEBFF),
    onPrimary = Color(0xFF071F30),
    primaryContainer = Color(0x99355C78),
    onPrimaryContainer = Color(0xFFE7FAFF),
    secondary = Color(0xFFF7ACEB),
    onSecondary = Color(0xFF32172E),
    secondaryContainer = Color(0x995C3F6D),
    onSecondaryContainer = Color(0xFFFFE8FB),
    tertiary = Color(0xFFA6F7E6),
    surface = Color(0xFF10192C),
    onSurface = Color(0xFFF2F8FF),
    surfaceVariant = Color(0xA63B536C),
    onSurfaceVariant = Color(0xFFC5D6E9),
    surfaceContainerLowest = Color(0xFF11192B),
    surfaceContainerLow = Color(0xB31C2B41),
    surfaceContainer = GlassPanel,
    surfaceContainerHigh = Color(0xFF25364E),
    surfaceContainerHighest = Color(0xFF30455E),
    outline = Color(0xFF88A9C2),
    outlineVariant = Color(0x6688B2CA),
    error = Color(0xFFFFB4AB),
)

@Composable
internal fun glassButtonColors() = ButtonDefaults.buttonColors(
    containerColor = Color(0xB52A7188),
    contentColor = Color(0xFFF4FCFF),
    disabledContainerColor = Color(0x66415768),
    disabledContentColor = Color(0xFF9CACBC),
)

@Composable
internal fun glassTonalButtonColors() = ButtonDefaults.filledTonalButtonColors(
    containerColor = Color(0x99435F7A),
    contentColor = Color(0xFFF4FCFF),
    disabledContainerColor = Color(0x55415768),
    disabledContentColor = Color(0xFF9CACBC),
)

@Composable
internal fun glassChipColors() = FilterChipDefaults.filterChipColors(
    containerColor = Color(0x66364C66),
    labelColor = Color(0xFFDFECF6),
    selectedContainerColor = Color(0xAA2D7890),
    selectedLabelColor = Color(0xFFFFFFFF),
    disabledContainerColor = Color(0x33415768),
    disabledLabelColor = Color(0xFF9CACBC),
)

@Composable
fun PhotoTimeFixerTheme(content: @Composable () -> Unit) {
    val activity = LocalContext.current as? Activity
    SideEffect {
        activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }
    MaterialTheme(colorScheme = GlassColors) {
        Box(Modifier.fillMaxSize().background(Color(0xFF09111F))) {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(brush = Brush.verticalGradient(listOf(Color(0xFF101C35), Color(0xFF0D1428), Color(0xFF121A2C))))
                val scale = size.maxDimension
                drawCircle(brush = Brush.radialGradient(listOf(Color(0x994C318F), Color.Transparent),
                    center = Offset(size.width * 0.84f, size.height * 0.17f), radius = scale * 0.5f),
                    radius = scale * 0.5f, center = Offset(size.width * 0.84f, size.height * 0.17f))
                drawCircle(brush = Brush.radialGradient(listOf(Color(0x88316986), Color.Transparent),
                    center = Offset(size.width * 0.02f, size.height * 0.56f), radius = scale * 0.48f),
                    radius = scale * 0.48f, center = Offset(size.width * 0.02f, size.height * 0.56f))
                drawCircle(brush = Brush.radialGradient(listOf(Color(0x66572F72), Color.Transparent),
                    center = Offset(size.width * 0.9f, size.height * 0.91f), radius = scale * 0.42f),
                    radius = scale * 0.42f, center = Offset(size.width * 0.9f, size.height * 0.91f))
            }
            content()
        }
    }
}
