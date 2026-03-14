package space.u2re.cws.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = GreenAccent,
    onPrimary = Color(0xFF10210F),
    primaryContainer = GreenDark,
    onPrimaryContainer = NeutralText,
    secondary = GreenDark,
    onSecondary = NeutralText,
    secondaryContainer = GreenEarth,
    onSecondaryContainer = NeutralText,
    tertiary = GreenLight,
    onTertiary = Color(0xFF122111),
    tertiaryContainer = GreenMoss,
    onTertiaryContainer = NeutralText,
    background = SurfaceDark,
    onBackground = NeutralText,
    surface = SurfaceDark,
    onSurface = NeutralText,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = NeutralText,
    surfaceTint = GreenAccent,
    outline = NeutralGray,
    outlineVariant = GreenMossBorder,
    inversePrimary = GreenMain,
    error = Color(0xFFD92D20),
    errorContainer = Color(0xFF7A1A1A),
    onErrorContainer = NeutralText,
    onError = NeutralText
)

private val LightColorScheme = lightColorScheme(
    primary = GreenAccent,
    onPrimary = Color(0xFF10210F),
    primaryContainer = GreenFoliage,
    onPrimaryContainer = Color(0xFF1E3A1D),
    secondary = GreenMoss,
    onSecondary = SurfaceLight,
    secondaryContainer = Color(0xFFD9ECC9),
    onSecondaryContainer = Color(0xFF1E3A1D),
    tertiary = GreenDeep,
    onTertiary = SurfaceLight,
    tertiaryContainer = Color(0xFFE3F0D6),
    onTertiaryContainer = Color(0xFF234122),
    background = SurfaceLight,
    onBackground = Color(0xFF29402A),
    surface = SurfaceLight,
    onSurface = Color(0xFF29402A),
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = Color(0xFF516252),
    surfaceTint = GreenAccent,
    outline = NeutralGray,
    outlineVariant = Color(0xFFC4D5C1),
    inversePrimary = GreenLight,
    error = Color(0xFFD92D20),
    errorContainer = Color(0xFFFEE4E2),
    onErrorContainer = Color(0xFF5C1111),
    onError = NeutralText
)

@Composable
@Suppress("DEPRECATION")
fun LiveKitVoiceAssistantExampleTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}