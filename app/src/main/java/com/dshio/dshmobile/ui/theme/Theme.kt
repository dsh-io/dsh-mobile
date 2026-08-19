package com.dshio.dshmobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// DeepCode — dark developer-tool theme. Cold near-black surfaces, ONE
// accent (teal) for the whole app, off-white text (never pure #000/#fff).
private val InkBackground = Color(0xFF0A0E13)
private val SurfacePanel = Color(0xFF121820)
private val SurfaceRaised = Color(0xFF1A222C)
private val AccentTeal = Color(0xFF2DD4BF)
private val AccentDim = Color(0xFF0E2B27)
private val TextPrimary = Color(0xFFD8E0E8)
private val TextSecondary = Color(0xFF8A97A6)
private val Hairline = Color(0xFF27313C)
private val ErrorRed = Color(0xFFF07178)
private val ErrorSurface = Color(0xFF2A1417)

private val DeepCodeColors = darkColorScheme(
    primary = AccentTeal,
    onPrimary = Color(0xFF06201C),
    primaryContainer = AccentDim,
    onPrimaryContainer = AccentTeal,
    secondary = TextSecondary,
    onSecondary = Color(0xFF0A0E13),
    background = InkBackground,
    onBackground = TextPrimary,
    surface = SurfacePanel,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceRaised,
    onSurfaceVariant = TextSecondary,
    outline = Hairline,
    outlineVariant = Color(0xFF1D252E),
    error = ErrorRed,
    onError = Color(0xFF2A0A0D),
    errorContainer = ErrorSurface,
    onErrorContainer = ErrorRed,
)

@Composable
fun DeepCodeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DeepCodeColors,
        content = content,
    )
}