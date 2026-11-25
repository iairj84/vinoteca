package com.vinoteca.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

private val Burgundy = Color(0xFF7B2233)
private val BurgundyDark = Color(0xFF5B1622)
private val BurgundyLight = Color(0xFFE9A8B4)
private val Gold = Color(0xFF8A6A2F)
private val Cream = Color(0xFFFBF8F3)
private val Ink = Color(0xFF1E1717)

private val LightColors = lightColorScheme(
    primary = Burgundy,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF6DEE2),
    onPrimaryContainer = Color(0xFF3A0B14),
    secondary = Gold,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF3E7CE),
    onSecondaryContainer = Color(0xFF2B2110),
    tertiary = Color(0xFF4B6151),
    onTertiary = Color.White,
    background = Cream,
    onBackground = Ink,
    surface = Color(0xFFFFFDF9),
    onSurface = Ink,
    surfaceVariant = Color(0xFFF0E7DC),
    onSurfaceVariant = Color(0xFF4E453E),
    outline = Color(0xFF8B7D72),
    outlineVariant = Color(0xFFDCCCBE),
    error = Color(0xFF9B2C2C),
    onError = Color.White
)

private val DarkColors = darkColorScheme(
    primary = BurgundyLight,
    onPrimary = Color(0xFF3A0B14),
    primaryContainer = Color(0xFF5B1622),
    onPrimaryContainer = Color(0xFFFFD9DE),
    secondary = Color(0xFFD9BC83),
    onSecondary = Color(0xFF352A10),
    secondaryContainer = Color(0xFF3D3115),
    onSecondaryContainer = Color(0xFFF3E7CE),
    tertiary = Color(0xFFA7C3B0),
    onTertiary = Color(0xFF142A1D),
    background = Color(0xFF14100F),
    onBackground = Color(0xFFF1E7E0),
    surface = Color(0xFF1D1917),
    onSurface = Color(0xFFF1E7E0),
    surfaceVariant = Color(0xFF2A2421),
    onSurfaceVariant = Color(0xFFD3C6BC),
    outline = Color(0xFF9C8E84),
    outlineVariant = Color(0xFF4A413C),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410)
)

private val base = Typography()

private val VinotecaTypography = Typography(
    displaySmall = base.displaySmall.copy(fontFamily = FontFamily.Serif),
    headlineMedium = base.headlineMedium.copy(fontFamily = FontFamily.Serif),
    headlineSmall = base.headlineSmall.copy(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold
    ),
    titleLarge = base.titleLarge.copy(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold
    ),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold)
)

@Composable
fun VinotecaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = VinotecaTypography,
        content = content
    )
}