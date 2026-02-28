package avinash.app.headlinr.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = HeadlinrColors.accent,
    primaryContainer = HeadlinrColors.accentVariant,
    onPrimary = HeadlinrColors.white,
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
    outline = HeadlinrColors.outlineDark,
)

private val LightColorScheme = lightColorScheme(
    primary = HeadlinrColors.accent,
    primaryContainer = HeadlinrColors.accentLight,
    onPrimary = HeadlinrColors.white,
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
    outline = HeadlinrColors.warmGray,
)

@Composable
fun HeadlinrTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = HeadlinrTypography,
        content = content
    )
}
