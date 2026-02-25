package avinash.app.headlinr.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = HeadlinrColors.accent,
    primaryContainer = HeadlinrColors.accentVariant,
    onPrimaryContainer = HeadlinrColors.white,
    secondary = HeadlinrColors.accentVariant,
    background = HeadlinrColors.surfaceDark,
    surface = HeadlinrColors.surfaceDark,
    surfaceVariant = HeadlinrColors.cardDark,
    onBackground = HeadlinrColors.textPrimaryDark,
    onSurface = HeadlinrColors.textPrimaryDark,
    onSurfaceVariant = HeadlinrColors.textSecondaryDark,
    error = HeadlinrColors.error,
    surfaceContainer = HeadlinrColors.cardDark,
    surfaceContainerHigh = HeadlinrColors.chipUnselectedDark,
)

private val LightColorScheme = lightColorScheme(
    primary = HeadlinrColors.accent,
    primaryContainer = HeadlinrColors.accentLight,
    onPrimaryContainer = HeadlinrColors.accentVariant,
    secondary = HeadlinrColors.accentVariant,
    background = HeadlinrColors.surfaceLight,
    surface = HeadlinrColors.surfaceLight,
    surfaceVariant = HeadlinrColors.cardLight,
    onBackground = HeadlinrColors.textPrimaryLight,
    onSurface = HeadlinrColors.textPrimaryLight,
    onSurfaceVariant = HeadlinrColors.textSecondaryLight,
    error = HeadlinrColors.error,
    surfaceContainer = HeadlinrColors.cardLight,
    surfaceContainerHigh = HeadlinrColors.chipUnselectedLight,
)

@Composable
fun HeadlinrTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
