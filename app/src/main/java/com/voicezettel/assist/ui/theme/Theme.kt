package com.voicezettel.assist.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = md_primary_light,
    onPrimary = md_on_primary_light,
    primaryContainer = md_primary_container_light,
    onPrimaryContainer = md_on_primary_container_light,
    secondary = md_secondary_light,
    onSecondary = md_on_secondary_light,
    secondaryContainer = md_secondary_container_light,
    onSecondaryContainer = md_on_secondary_container_light,
    background = md_background_light,
    onBackground = md_on_background_light,
    surface = md_surface_light,
    onSurface = md_on_surface_light,
    surfaceVariant = md_surface_variant_light,
    onSurfaceVariant = md_on_surface_variant_light,
    error = md_error_light,
    onError = md_on_error_light
)

private val DarkColors = darkColorScheme(
    primary = md_primary_dark,
    onPrimary = md_on_primary_dark,
    primaryContainer = md_primary_container_dark,
    onPrimaryContainer = md_on_primary_container_dark,
    secondary = md_secondary_dark,
    onSecondary = md_on_secondary_dark,
    secondaryContainer = md_secondary_container_dark,
    onSecondaryContainer = md_on_secondary_container_dark,
    background = md_background_dark,
    onBackground = md_on_background_dark,
    surface = md_surface_dark,
    onSurface = md_on_surface_dark,
    surfaceVariant = md_surface_variant_dark,
    onSurfaceVariant = md_on_surface_variant_dark,
    error = md_error_dark,
    onError = md_on_error_dark
)

@Composable
fun VoiceZettelTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content
    )
}
