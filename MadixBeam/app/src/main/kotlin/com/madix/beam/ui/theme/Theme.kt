package com.madix.beam.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.madix.beam.R

val MadixFontFamily = FontFamily(
    Font(R.font.ibm_plex_arabic_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_arabic_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_arabic_semibold, FontWeight.SemiBold),
    Font(R.font.ibm_plex_arabic_bold, FontWeight.Bold),
)

private fun TextStyle.branded(weight: FontWeight? = null): TextStyle =
    copy(fontFamily = MadixFontFamily, fontWeight = weight ?: fontWeight)

private val Base = Typography()

val MadixTypography = Typography(
    displayLarge = Base.displayLarge.branded(FontWeight.Bold),
    displayMedium = Base.displayMedium.branded(FontWeight.Bold),
    displaySmall = Base.displaySmall.branded(FontWeight.Bold),
    headlineLarge = Base.headlineLarge.branded(FontWeight.Bold),
    headlineMedium = Base.headlineMedium.branded(FontWeight.Bold),
    headlineSmall = Base.headlineSmall.branded(FontWeight.SemiBold),
    titleLarge = Base.titleLarge.branded(FontWeight.SemiBold),
    titleMedium = Base.titleMedium.branded(FontWeight.SemiBold),
    titleSmall = Base.titleSmall.branded(FontWeight.Medium),
    bodyLarge = Base.bodyLarge.branded().copy(lineHeight = 26.sp),
    bodyMedium = Base.bodyMedium.branded().copy(lineHeight = 22.sp),
    bodySmall = Base.bodySmall.branded().copy(lineHeight = 18.sp),
    labelLarge = Base.labelLarge.branded(FontWeight.SemiBold),
    labelMedium = Base.labelMedium.branded(FontWeight.Medium),
    labelSmall = Base.labelSmall.branded(FontWeight.Medium),
)

val MadixShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun MadixTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val extended = if (darkTheme) DarkMadixColors else LightMadixColors
    CompositionLocalProvider(LocalMadixColors provides extended) {
        MaterialTheme(
            colorScheme = scheme,
            typography = MadixTypography,
            shapes = MadixShapes,
            content = content,
        )
    }
}

object MadixTheme {
    val colors: MadixColors
        @Composable
        @ReadOnlyComposable
        get() = LocalMadixColors.current
}
