package com.wineverdict.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * The brand is a cellar ledger: warm paper, one claret label, a brass rule.
 *
 * Claret is not a background colour. It is what the app stamps on the things it is sure about:
 * the cellar's numbers, the primary action, the verdict. Everything else is paper and ink, with
 * hairlines doing the dividing that shadows used to do.
 */
private val Paper = Color(0xFFF7F2E9)
private val PaperRaised = Color(0xFFFFFDF9)
private val Ink = Color(0xFF17110F)
private val InkSoft = Color(0xFF5C5149)
private val Claret = Color(0xFF6E1A2A)
private val ClaretDeep = Color(0xFF47101A)
private val ClaretPale = Color(0xFFF2DDE0)
private val Brass = Color(0xFF8A6A34)
private val BrassPale = Color(0xFFF2E6CC)
private val Rule = Color(0xFFE0D4C6)

private val LightColors = lightColorScheme(
    primary = Claret,
    onPrimary = Color(0xFFFFFBF5),
    primaryContainer = ClaretPale,
    onPrimaryContainer = ClaretDeep,
    secondary = Brass,
    onSecondary = Color(0xFFFFFBF5),
    secondaryContainer = BrassPale,
    onSecondaryContainer = Color(0xFF2E2410),
    // No third hue. Brass is the only second accent, and quiet text is ink, not a colour.
    tertiary = Brass,
    onTertiary = Color(0xFFFFFBF5),
    tertiaryContainer = BrassPale,
    onTertiaryContainer = Color(0xFF2E2410),
    background = Paper,
    onBackground = Ink,
    surface = PaperRaised,
    onSurface = Ink,
    surfaceVariant = Color(0xFFEFE7DB),
    onSurfaceVariant = InkSoft,
    // Dark enough to read as text on paper (4.6-5.1:1), still a step lighter than onSurfaceVariant.
    outline = Color(0xFF75655A),
    outlineVariant = Rule,
    error = Color(0xFF96242A),
    onError = Color(0xFFFFFBF5),
    errorContainer = Color(0xFFF6DCDB),
    onErrorContainer = Color(0xFF450A0D)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFEBB2BC),
    onPrimary = Color(0xFF3D0C16),
    primaryContainer = Color(0xFF5A1524),
    onPrimaryContainer = Color(0xFFF8DDE1),
    secondary = Color(0xFFDCBE87),
    onSecondary = Color(0xFF33280F),
    secondaryContainer = Color(0xFF3E3215),
    onSecondaryContainer = Color(0xFFF2E6CC),
    tertiary = Color(0xFFDCBE87),
    onTertiary = Color(0xFF33280F),
    tertiaryContainer = Color(0xFF3E3215),
    onTertiaryContainer = Color(0xFFF2E6CC),
    background = Color(0xFF12100F),
    onBackground = Color(0xFFF1E7E0),
    surface = Color(0xFF1C1715),
    onSurface = Color(0xFFF1E7E0),
    surfaceVariant = Color(0xFF2C2523),
    onSurfaceVariant = Color(0xFFD5C8BE),
    outline = Color(0xFF9E8F85),
    outlineVariant = Color(0xFF463D38),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF4A0F12),
    onErrorContainer = Color(0xFFFFDAD6)
)

private val base = Typography()
private val Serif = FontFamily.Serif

// Serif at book weight, not bold: the sizes carry the hierarchy, the way a wine label does.
private val WineVerdictTypography = Typography(
    displaySmall = base.displaySmall.copy(
        fontFamily = Serif,
        fontWeight = FontWeight.Normal,
        fontSize = 38.sp,
        lineHeight = 44.sp,
        letterSpacing = (-0.6).sp
    ),
    headlineMedium = base.headlineMedium.copy(
        fontFamily = Serif,
        fontWeight = FontWeight.Normal,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.4).sp
    ),
    headlineSmall = base.headlineSmall.copy(
        fontFamily = Serif,
        fontWeight = FontWeight.Normal,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.3).sp
    ),
    titleLarge = base.titleLarge.copy(
        fontFamily = Serif,
        fontWeight = FontWeight.Normal,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.2).sp
    ),
    titleMedium = base.titleMedium.copy(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.1.sp
    ),
    labelSmall = base.labelSmall.copy(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.5.sp
    )
)

// Near-square shapes read as a printed label; the old 12-20dp pills read as a generic app.
private val WineVerdictShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun WineVerdictTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = WineVerdictTypography,
        shapes = WineVerdictShapes,
        content = content
    )
}
