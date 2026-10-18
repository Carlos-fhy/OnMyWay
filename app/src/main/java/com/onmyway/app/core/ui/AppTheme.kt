package com.onmyway.app.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * OnMyWay visual language
 * -----------------------
 * 一条安静的绿色路线：纸张般的暖底色、深松绿主色、少量琥珀色路标。
 * 颜色只承担语义，不用大面积渐变抢内容；圆角和留白负责“精致”。
 */
private val LightPalette = lightColorScheme(
    primary = Color(0xFF1B5C43),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD8EBDD),
    onPrimaryContainer = Color(0xFF0A2E1E),
    inversePrimary = Color(0xFF8FD3B0),
    secondary = Color(0xFF52655A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6EEE7),
    onSecondaryContainer = Color(0xFF1F2E25),
    tertiary = Color(0xFFB2661B),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE3C7),
    onTertiaryContainer = Color(0xFF3C1C00),
    background = Color(0xFFF7F8F3),
    onBackground = Color(0xFF18231D),
    surface = Color(0xFFF7F8F3),
    onSurface = Color(0xFF18231D),
    surfaceVariant = Color(0xFFE4E9E0),
    onSurfaceVariant = Color(0xFF5B6860),
    surfaceTint = Color(0xFF1B5C43),
    inverseSurface = Color(0xFF2C352F),
    inverseOnSurface = Color(0xFFEFF3EC),
    surfaceDim = Color(0xFFDCE2D9),
    surfaceBright = Color(0xFFFCFDF8),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFBFCF7),
    surfaceContainer = Color(0xFFF1F4EC),
    surfaceContainerHigh = Color(0xFFE9EEE4),
    surfaceContainerHighest = Color(0xFFE2E8DE),
    outline = Color(0xFF718077),
    outlineVariant = Color(0xFFDFE5DB),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val DarkPalette = darkColorScheme(
    primary = Color(0xFF6AD9A4),
    onPrimary = Color(0xFF003823),
    primaryContainer = Color(0xFF1D4D38),
    onPrimaryContainer = Color(0xFFC2F0D9),
    inversePrimary = Color(0xFF1B5C43),
    secondary = Color(0xFFB5C7BA),
    onSecondary = Color(0xFF223027),
    secondaryContainer = Color(0xFF24332A),
    onSecondaryContainer = Color(0xFFD4E2D8),
    tertiary = Color(0xFFF6B26B),
    onTertiary = Color(0xFF4A2400),
    tertiaryContainer = Color(0xFF6B3705),
    onTertiaryContainer = Color(0xFFFFDCC4),
    background = Color(0xFF0F1613),
    onBackground = Color(0xFFE0E6E1),
    surface = Color(0xFF0F1613),
    onSurface = Color(0xFFE0E6E1),
    surfaceVariant = Color(0xFF2B382F),
    onSurfaceVariant = Color(0xFF9FACA3),
    surfaceTint = Color(0xFF6AD9A4),
    inverseSurface = Color(0xFFE0E6E1),
    inverseOnSurface = Color(0xFF18231D),
    surfaceDim = Color(0xFF0F1613),
    surfaceBright = Color(0xFF303D35),
    surfaceContainerLowest = Color(0xFF131B17),
    surfaceContainerLow = Color(0xFF18211C),
    surfaceContainer = Color(0xFF1E2822),
    surfaceContainerHigh = Color(0xFF25312A),
    surfaceContainerHighest = Color(0xFF2C3931),
    outline = Color(0xFF859388),
    outlineVariant = Color(0xFF2C3931),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val AppTypography = Typography(
    displaySmall = TextStyle(
        fontSize = 36.sp,
        lineHeight = 42.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.6).sp,
    ),
    headlineLarge = TextStyle(fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
    headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

@Composable
internal fun OnMyWayTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkPalette else LightPalette,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
