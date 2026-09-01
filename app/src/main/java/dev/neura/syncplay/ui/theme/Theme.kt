package dev.neura.syncplay.ui.theme

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** Stable accents used by the player screen for warnings and sync state. */
val Amber = Color(0xFFF4B860)
val Cyan = Color(0xFF63D8D2)

/** Expressive connection accent: lavender reads as the primary action color. */
val Lavender = Color(0xFFD9C2FF)

private val Noir = Color(0xFF090E18)
private val Ink = Color(0xFF101725)
private val Slate = Color(0xFF1A2435)
val Paper = Color(0xFFE8ECF2)
val Muted = Color(0xFFA6B0BF)

private val DarkColors = darkColorScheme(
    primary = Lavender,
    onPrimary = Color(0xFF34204D),
    primaryContainer = Color(0xFF513A72),
    onPrimaryContainer = Color(0xFFF1DEFF),
    secondary = Cyan,
    onSecondary = Color(0xFF003735),
    secondaryContainer = Color(0xFF0D4F4D),
    onSecondaryContainer = Color(0xFF9DF2EE),
    tertiary = Color(0xFF9FE3B1),
    onTertiary = Color(0xFF08391D),
    tertiaryContainer = Color(0xFF1F5C37),
    onTertiaryContainer = Color(0xFFB9F5C8),
    background = Noir,
    onBackground = Paper,
    surface = Ink,
    onSurface = Paper,
    surfaceVariant = Slate,
    onSurfaceVariant = Muted,
    outlineVariant = Color(0xFF313D50),
    outline = Color(0xFF465363),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF6E4C9A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEBD7FF),
    onPrimaryContainer = Color(0xFF281344),
    secondary = Color(0xFF006A66),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF9DF2EE),
    onSecondaryContainer = Color(0xFF00201E),
    tertiary = Color(0xFF2D7041),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFB8F2C4),
    onTertiaryContainer = Color(0xFF00210B),
    background = Color(0xFFF7F8FC),
    onBackground = Color(0xFF1A1C20),
    surface = Color(0xFFF7F8FC),
    onSurface = Color(0xFF1A1C20),
    surfaceVariant = Color(0xFFE4E7ED),
    onSurfaceVariant = Color(0xFF45484F),
    outline = Color(0xFF757982),
    outlineVariant = Color(0xFFC5C7D0),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
    largeIncreased = RoundedCornerShape(28.dp),
    extraLargeIncreased = RoundedCornerShape(36.dp),
    extraExtraLarge = RoundedCornerShape(44.dp),
)

private val AppTypography = Typography(
    displaySmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.25).sp,
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
)

@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun SyncplayTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialExpressiveTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        motionScheme = MotionScheme.expressive(),
        shapes = ExpressiveShapes,
        typography = AppTypography,
        content = content,
    )
}
