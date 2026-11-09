package za.co.cttrains.app.ui

import android.os.Build
import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import za.co.cttrains.app.data.Preferences

private val Light = lightColorScheme(
    primary = Color(0xFF006B5F), onPrimary = Color.White,
    primaryContainer = Color(0xFF9FF2DF), onPrimaryContainer = Color(0xFF00201A),
    secondary = Color(0xFF4A635D), secondaryContainer = Color(0xFFCDE8DF),
    tertiary = Color(0xFF8C5000), tertiaryContainer = Color(0xFFFFDDB9),
    background = Color(0xFFF7FAF7), surface = Color(0xFFF7FAF7),
    surfaceContainer = Color(0xFFEBEFEB), surfaceContainerLow = Color(0xFFF1F5F0),
    onSurface = Color(0xFF171D1A), onSurfaceVariant = Color(0xFF3F4944),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF83D6C3), primaryContainer = Color(0xFF005046),
    secondary = Color(0xFFB1CCC3), secondaryContainer = Color(0xFF334B43),
    tertiary = Color(0xFFFFB86C), tertiaryContainer = Color(0xFF693C00),
    background = Color(0xFF0F1512), surface = Color(0xFF0F1512),
    surfaceContainer = Color(0xFF1B211E), surfaceContainerLow = Color(0xFF171D1A),
)

@Composable
fun TrainTheme(preferences: Preferences, content: @Composable () -> Unit) {
    val dark = when (preferences.theme) { "Light" -> false; "Dark" -> true; else -> isSystemInDarkTheme() }
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
    val colors = if (preferences.dynamicColor && Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) Dark else Light
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}
