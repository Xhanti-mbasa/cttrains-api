package za.co.cttrains.app.ui.lab

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import za.co.cttrains.app.data.Preferences

/**
 * "Platform Ink": the Lab edition's fixed palette. Deliberately no Material You recolouring,
 * so the Signal colour keeps meaning "act now" and mode colours keep meaning a mode.
 */
@Immutable
data class LabColors(
    val ground: Color, val surface: Color, val ink: Color, val muted: Color, val line: Color,
    val signal: Color, val onSignal: Color, val train: Color, val myCiti: Color, val goldenArrow: Color, val onMode: Color,
    val staleBg: Color, val staleInk: Color, val chip: Color, val dark: Boolean,
)

val LabLight = LabColors(
    ground = Color(0xFFF6F4EF), surface = Color(0xFFFFFFFF), ink = Color(0xFF15171A), muted = Color(0xFF4A4F57), line = Color(0xFFE2DED5),
    signal = Color(0xFFC2410C), onSignal = Color.White, train = Color(0xFF1D4ED8), myCiti = Color(0xFF0F766E), goldenArrow = Color(0xFFA16207), onMode = Color.White,
    staleBg = Color(0xFFFEF3C7), staleInk = Color(0xFF78350F), chip = Color(0xFFEEECE6), dark = false,
)
val LabDark = LabColors(
    ground = Color(0xFF0F1114), surface = Color(0xFF171A1F), ink = Color(0xFFECEDEE), muted = Color(0xFFA6ACB4), line = Color(0xFF2A2F36),
    signal = Color(0xFFFB923C), onSignal = Color(0xFF0F1114), train = Color(0xFF93B4FF), myCiti = Color(0xFF5EEAD4), goldenArrow = Color(0xFFFCD34D), onMode = Color(0xFF0F1114),
    staleBg = Color(0xFF3A2A0A), staleInk = Color(0xFFFDE68A), chip = Color(0xFF22262C), dark = true,
)

val LocalLab = staticCompositionLocalOf { LabLight }

object LabType {
    /** Departure times: heavy, tabular, never truncated. */
    val time = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Black, fontSize = 44.sp, lineHeight = 46.sp, fontFeatureSettings = "tnum", letterSpacing = (-0.5).sp)
    val timeMedium = time.copy(fontSize = 32.sp, lineHeight = 34.sp)
    val timeSmall = time.copy(fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.ExtraBold)
    val title = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Black, fontSize = 32.sp, lineHeight = 36.sp)
    val route = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 25.sp)
    val body = TextStyle(fontSize = 16.sp, lineHeight = 23.sp)
    val bodyStrong = body.copy(fontWeight = FontWeight.SemiBold)
    val small = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
    val label = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)
}

@Composable
fun LabTheme(preferences: Preferences, content: @Composable () -> Unit) {
    val dark = when (preferences.theme) { "Light" -> false; "Dark" -> true; else -> isSystemInDarkTheme() }
    val c = if (dark) LabDark else LabLight
    val context = LocalContext.current
    val view = LocalView.current
    SideEffect {
        (context as? Activity)?.let { activity ->
            WindowCompat.getInsetsController(activity.window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    // Reused screens from the regular edition read MaterialTheme; map the Lab palette onto it.
    val scheme = if (dark) darkColorScheme(
        primary = c.ink, onPrimary = c.ground, primaryContainer = c.chip, onPrimaryContainer = c.ink,
        secondary = c.muted, secondaryContainer = c.chip, onSecondaryContainer = c.ink,
        tertiary = c.signal, onTertiary = c.onSignal, tertiaryContainer = c.staleBg, onTertiaryContainer = c.staleInk,
        background = c.ground, onBackground = c.ink, surface = c.ground, onSurface = c.ink, onSurfaceVariant = c.muted,
        surfaceContainer = c.surface, surfaceContainerLow = c.surface, surfaceContainerHigh = c.chip, surfaceContainerHighest = c.chip, outline = c.line,
    ) else lightColorScheme(
        primary = c.ink, onPrimary = c.surface, primaryContainer = c.chip, onPrimaryContainer = c.ink,
        secondary = c.muted, secondaryContainer = c.chip, onSecondaryContainer = c.ink,
        tertiary = c.signal, onTertiary = c.onSignal, tertiaryContainer = c.staleBg, onTertiaryContainer = c.staleInk,
        background = c.ground, onBackground = c.ink, surface = c.ground, onSurface = c.ink, onSurfaceVariant = c.muted,
        surfaceContainer = c.surface, surfaceContainerLow = c.surface, surfaceContainerHigh = c.chip, surfaceContainerHighest = c.chip, outline = c.line,
    )
    CompositionLocalProvider(LocalLab provides c) {
        MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
    }
}
