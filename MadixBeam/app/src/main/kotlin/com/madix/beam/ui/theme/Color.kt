package com.madix.beam.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Raw brand palette. */
object Brand {
    val Night = Color(0xFF0B0918)
    val Ink = Color(0xFF140A38)
    val Violet800 = Color(0xFF2A0E6B)
    val Violet600 = Color(0xFF5B2EEA)
    val Violet500 = Color(0xFF7C5CFF)
    val Violet300 = Color(0xFFB9A8FF)
    val Cyan500 = Color(0xFF0FB5D6)
    val Cyan300 = Color(0xFF6EF3FF)
    val Rose400 = Color(0xFFF472B6)
    val Emerald500 = Color(0xFF16C784)
    val Amber500 = Color(0xFFF5A524)
    val Red500 = Color(0xFFEF4444)
}

internal val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFA28BFF),
    onPrimary = Color(0xFF1C0A52),
    primaryContainer = Color(0xFF3B1FA3),
    onPrimaryContainer = Color(0xFFE7DEFF),
    secondary = Color(0xFF5FE3F7),
    onSecondary = Color(0xFF00363F),
    secondaryContainer = Color(0xFF0B4D5A),
    onSecondaryContainer = Color(0xFFB9F4FF),
    tertiary = Color(0xFFF7A3CF),
    onTertiary = Color(0xFF4F0F33),
    tertiaryContainer = Color(0xFF6B1F4A),
    onTertiaryContainer = Color(0xFFFFD8EA),
    background = Brand.Night,
    onBackground = Color(0xFFECE9F8),
    surface = Brand.Night,
    onSurface = Color(0xFFECE9F8),
    surfaceVariant = Color(0xFF1D1934),
    onSurfaceVariant = Color(0xFFBAB4D3),
    surfaceContainerLowest = Color(0xFF07060F),
    surfaceContainerLow = Color(0xFF12101F),
    surfaceContainer = Color(0xFF171427),
    surfaceContainerHigh = Color(0xFF1F1B33),
    surfaceContainerHighest = Color(0xFF29243F),
    inverseSurface = Color(0xFFECE9F8),
    inverseOnSurface = Color(0xFF1B1830),
    outline = Color(0xFF4C4668),
    outlineVariant = Color(0xFF2D2846),
    error = Color(0xFFFF7A7A),
    onError = Color(0xFF5A0000),
    errorContainer = Color(0xFF7A1C1C),
    onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color(0xFF000000),
)

internal val LightColorScheme = lightColorScheme(
    primary = Color(0xFF5B2EEA),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE7DEFF),
    onPrimaryContainer = Color(0xFF1C0A52),
    secondary = Color(0xFF00879F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFC9F6FF),
    onSecondaryContainer = Color(0xFF002A31),
    tertiary = Color(0xFFB0306F),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD8EA),
    onTertiaryContainer = Color(0xFF3D0024),
    background = Color(0xFFF6F5FC),
    onBackground = Color(0xFF17142A),
    surface = Color(0xFFF6F5FC),
    onSurface = Color(0xFF17142A),
    surfaceVariant = Color(0xFFE8E4F5),
    onSurfaceVariant = Color(0xFF4A4562),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFBFAFF),
    surfaceContainer = Color(0xFFF2F0FA),
    surfaceContainerHigh = Color(0xFFECE9F7),
    surfaceContainerHighest = Color(0xFFE5E1F3),
    inverseSurface = Color(0xFF2C2842),
    inverseOnSurface = Color(0xFFF3F0FF),
    outline = Color(0xFF7A7494),
    outlineVariant = Color(0xFFD3CEE6),
    error = Color(0xFFC62828),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    scrim = Color(0xFF000000),
)

/** Colours and brushes beyond the Material scheme. */
@Immutable
data class MadixColors(
    val isDark: Boolean,
    val brandGradient: Brush,
    val beamGradient: Brush,
    val sendGradient: Brush,
    val receiveGradient: Brush,
    val glass: Color,
    val glassBorder: Color,
    val auroraA: Color,
    val auroraB: Color,
    val auroraC: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val muted: Color,
)

internal val DarkMadixColors = MadixColors(
    isDark = true,
    brandGradient = Brush.linearGradient(listOf(Brand.Violet800, Brand.Violet600, Brand.Cyan500)),
    beamGradient = Brush.linearGradient(listOf(Brand.Violet500, Brand.Cyan500)),
    sendGradient = Brush.linearGradient(listOf(Color(0xFF7C5CFF), Color(0xFFB46CFF))),
    receiveGradient = Brush.linearGradient(listOf(Color(0xFF0FB5D6), Color(0xFF2F7BFF))),
    glass = Color(0x1AFFFFFF),
    glassBorder = Color(0x26FFFFFF),
    auroraA = Color(0x665B2EEA),
    auroraB = Color(0x4D0FB5D6),
    auroraC = Color(0x33F472B6),
    success = Color(0xFF34D399),
    warning = Color(0xFFFBBF24),
    danger = Color(0xFFFF7A7A),
    muted = Color(0xFF8E88A8),
)

internal val LightMadixColors = MadixColors(
    isDark = false,
    brandGradient = Brush.linearGradient(listOf(Brand.Violet800, Brand.Violet600, Brand.Cyan500)),
    beamGradient = Brush.linearGradient(listOf(Brand.Violet600, Brand.Cyan500)),
    sendGradient = Brush.linearGradient(listOf(Color(0xFF6A3CF0), Color(0xFFA855F7))),
    receiveGradient = Brush.linearGradient(listOf(Color(0xFF0BA5C4), Color(0xFF2563EB))),
    glass = Color(0xCCFFFFFF),
    glassBorder = Color(0x1F2A0E6B),
    auroraA = Color(0x335B2EEA),
    auroraB = Color(0x2B0FB5D6),
    auroraC = Color(0x1FF472B6),
    success = Color(0xFF0E9F6E),
    warning = Color(0xFFD97706),
    danger = Color(0xFFC62828),
    muted = Color(0xFF6E6886),
)

val LocalMadixColors = staticCompositionLocalOf { DarkMadixColors }
