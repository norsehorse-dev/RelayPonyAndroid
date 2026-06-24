package com.relaypony.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = RelayLightPrimary,
    onPrimary = RelayLightOnPrimary,
    primaryContainer = RelayLightPrimaryContainer,
    onPrimaryContainer = RelayLightOnPrimaryContainer,
    secondary = RelayLightSecondary,
    onSecondary = RelayLightOnSecondary,
    secondaryContainer = RelayLightSecondaryContainer,
    onSecondaryContainer = RelayLightOnSecondaryContainer,
    background = RelayLightBackground,
    onBackground = RelayLightOnBackground,
    surface = RelayLightSurface,
    onSurface = RelayLightOnSurface,
    surfaceVariant = RelayLightSurfaceVariant,
    onSurfaceVariant = RelayLightOnSurfaceVariant,
    outline = RelayLightOutline,
    error = RelayLightError,
    onError = RelayLightOnError,
)

private val DarkColors = darkColorScheme(
    primary = RelayDarkPrimary,
    onPrimary = RelayDarkOnPrimary,
    primaryContainer = RelayDarkPrimaryContainer,
    onPrimaryContainer = RelayDarkOnPrimaryContainer,
    secondary = RelayDarkSecondary,
    onSecondary = RelayDarkOnSecondary,
    secondaryContainer = RelayDarkSecondaryContainer,
    onSecondaryContainer = RelayDarkOnSecondaryContainer,
    background = RelayDarkBackground,
    onBackground = RelayDarkOnBackground,
    surface = RelayDarkSurface,
    onSurface = RelayDarkOnSurface,
    surfaceVariant = RelayDarkSurfaceVariant,
    onSurfaceVariant = RelayDarkOnSurfaceVariant,
    outline = RelayDarkOutline,
    error = RelayDarkError,
    onError = RelayDarkOnError,
)

/** Brand theme. Dynamic (Material You) color is intentionally off so RelayPony keeps a consistent
 *  identity across devices and matches the rest of the Pony family. */
@Composable
fun RelayPonyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = RelayPonyTypography,
        content = content,
    )
}
