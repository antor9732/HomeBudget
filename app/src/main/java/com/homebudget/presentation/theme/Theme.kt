package com.homebudget.presentation.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.homebudget.core.common.ThemeMode

val Brand = Color(0xFF1B5E52)
val Accent = Color(0xFFE0A84F)
val IncomeGreen = Color(0xFF2E7D32)
val ExpenseRed = Color(0xFFC62828)
val IncomeGreenDark = Color(0xFF81C784)
val ExpenseRedDark = Color(0xFFEF9A9A)

private val Light = lightColorScheme(
    primary = Brand, onPrimary = Color.White,
    primaryContainer = Color(0xFFCDE8E0), onPrimaryContainer = Color(0xFF00201A),
    secondary = Color(0xFF8A5A12), onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE2B8), onSecondaryContainer = Color(0xFF2B1700),
    tertiary = Color(0xFF3D5F8A),
    background = Color(0xFFF8F6F1), onBackground = Color(0xFF1B1C1A),
    surface = Color(0xFFF8F6F1), onSurface = Color(0xFF1B1C1A),
    surfaceVariant = Color(0xFFE6E3DA), onSurfaceVariant = Color(0xFF47473F),
    surfaceContainer = Color(0xFFEFECE4), surfaceContainerHigh = Color(0xFFE9E6DE),
    surfaceContainerLow = Color(0xFFF3F1EA), surfaceContainerLowest = Color.White,
    error = ExpenseRed,
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8FD3C3), onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF0E4A40), onPrimaryContainer = Color(0xFFCDE8E0),
    secondary = Color(0xFFF2C073), onSecondary = Color(0xFF462A00),
    secondaryContainer = Color(0xFF643F00), onSecondaryContainer = Color(0xFFFFE2B8),
    background = Color(0xFF121614), onBackground = Color(0xFFE2E3DF),
    surface = Color(0xFF121614), onSurface = Color(0xFFE2E3DF),
    surfaceVariant = Color(0xFF3F4945), onSurfaceVariant = Color(0xFFBFC9C4),
    surfaceContainer = Color(0xFF1C211F), surfaceContainerHigh = Color(0xFF262B29),
    surfaceContainerLow = Color(0xFF181D1B), surfaceContainerLowest = Color(0xFF0D110F),
    error = ExpenseRedDark,
)

private val Type = Typography().let { t ->
    t.copy(
        displaySmall = t.displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

val Numeric = TextStyle(fontFamily = FontFamily.Default, fontFeatureSettings = "tnum")

@Composable
fun HomeBudgetTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = Type, content = content)
}

@Composable
fun incomeColor() = if (MaterialTheme.colorScheme.background.luminanceIsDark()) IncomeGreenDark else IncomeGreen

@Composable
fun expenseColor() = if (MaterialTheme.colorScheme.background.luminanceIsDark()) ExpenseRedDark else ExpenseRed

private fun Color.luminanceIsDark() = (0.299 * red + 0.587 * green + 0.114 * blue) < 0.5
