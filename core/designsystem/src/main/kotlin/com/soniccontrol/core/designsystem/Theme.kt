package com.soniccontrol.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object SonicColors {
    val Teal = Color(0xFF2DD4BF)
    val TealDeep = Color(0xFF0E8C7C)
    val Graphite900 = Color(0xFF101215)
    val Graphite800 = Color(0xFF171A1E)
    val Graphite700 = Color(0xFF1F2328)
    val Graphite600 = Color(0xFF2A2F35)
    val Graphite500 = Color(0xFF3A4047)
    val Mist = Color(0xFFE7EAEC)
    val Fog = Color(0xFFA3ABB3)
    val Amber = Color(0xFFF5B342)
    val Coral = Color(0xFFFF6B6B)
    val Sky = Color(0xFF7CB8FF)
}

private val Dark: ColorScheme = darkColorScheme(
    primary = SonicColors.Teal,
    onPrimary = Color(0xFF002A24),
    primaryContainer = Color(0xFF0B3D37),
    onPrimaryContainer = Color(0xFFB4F5EA),
    secondary = Color(0xFF8FD8CC),
    onSecondary = Color(0xFF00201C),
    secondaryContainer = SonicColors.Graphite600,
    onSecondaryContainer = SonicColors.Mist,
    tertiary = SonicColors.Sky,
    background = SonicColors.Graphite900,
    onBackground = SonicColors.Mist,
    surface = SonicColors.Graphite800,
    onSurface = SonicColors.Mist,
    surfaceVariant = SonicColors.Graphite700,
    onSurfaceVariant = SonicColors.Fog,
    surfaceContainer = SonicColors.Graphite800,
    surfaceContainerHigh = SonicColors.Graphite700,
    surfaceContainerHighest = SonicColors.Graphite600,
    outline = SonicColors.Graphite500,
    outlineVariant = SonicColors.Graphite600,
    error = SonicColors.Coral,
)

private val Light: ColorScheme = lightColorScheme(
    primary = SonicColors.TealDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC8F3EC),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF3F6560),
    secondaryContainer = Color(0xFFDDE5E3),
    onSecondaryContainer = Color(0xFF1A1D21),
    tertiary = Color(0xFF2F6DB5),
    background = Color(0xFFF4F6F7),
    onBackground = Color(0xFF16191C),
    surface = Color.White,
    onSurface = Color(0xFF16191C),
    surfaceVariant = Color(0xFFE7EBED),
    onSurfaceVariant = Color(0xFF4F575E),
    surfaceContainer = Color(0xFFF0F3F4),
    surfaceContainerHigh = Color(0xFFE9EDEF),
    surfaceContainerHighest = Color(0xFFE1E6E8),
    outline = Color(0xFFB9C1C6),
    outlineVariant = Color(0xFFD5DADD),
    error = Color(0xFFC62828),
)

private val SonicTypography = Typography().let { t ->
    t.copy(
        displaySmall = t.displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
    )
}

val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)

private val SonicShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp),
)

/** [dark] null follows the system setting. */
@Composable
fun SonicTheme(dark: Boolean? = null, content: @Composable () -> Unit) {
    val useDark = dark ?: isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (useDark) Dark else Light, typography = SonicTypography, shapes = SonicShapes, content = content)
}
