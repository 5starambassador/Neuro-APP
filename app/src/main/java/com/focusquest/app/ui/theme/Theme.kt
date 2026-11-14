package com.focusquest.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object FqColors {
    val Background = Color(0xFF0F1226)
    val Surface = Color(0xFF1A1F3D)
    val SurfaceHigh = Color(0xFF252B52)
    val Primary = Color(0xFF7C5CFF)
    val Focus = Color(0xFF2EE6A6)
    val Calm = Color(0xFF5CC8FF)
    val Warm = Color(0xFFFFB547)
    val Danger = Color(0xFFFF5C7A)
    val TextDim = Color(0xFFA3A9CC)

    val avatars = listOf(
        Color(0xFF7C5CFF), Color(0xFF2EE6A6), Color(0xFFFFB547),
        Color(0xFFFF5C7A), Color(0xFF5CC8FF), Color(0xFFE36CFF),
    )
}

private val scheme = darkColorScheme(
    primary = FqColors.Primary,
    onPrimary = Color.White,
    secondary = FqColors.Focus,
    onSecondary = Color(0xFF00382A),
    tertiary = FqColors.Warm,
    background = FqColors.Background,
    onBackground = Color.White,
    surface = FqColors.Surface,
    onSurface = Color.White,
    surfaceVariant = FqColors.SurfaceHigh,
    onSurfaceVariant = FqColors.TextDim,
    surfaceContainer = FqColors.Surface,
    surfaceContainerHigh = FqColors.SurfaceHigh,
    error = FqColors.Danger,
)

private val typography = Typography(
    headlineLarge = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.ExtraBold),
    headlineMedium = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun FocusQuestTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
