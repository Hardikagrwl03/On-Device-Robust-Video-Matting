package dev.hamster.rvm.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Material You dynamic colour is deliberately OFF. The app's palette is a fixed brand scheme
 * because (a) "one common theme across the app" is not achievable from a per-device wallpaper,
 * (b) the app's own content is achromatic - an alpha matte is black and white - so the chrome
 * palette is the only colour there is and must be chosen, not inherited, and (c) every contrast
 * ratio this design depends on was measured against these exact values.
 * Flip this to true only to A/B the wallpaper-derived palette on a device; nothing in the app's
 * visual language depends on it and there is no user-facing toggle.
 */
private const val USE_DYNAMIC_COLOR = false

private val RvmLightColors = lightColorScheme(
    primary = RvmPrimary40,
    onPrimary = RvmPrimary100,
    primaryContainer = RvmPrimary90,
    onPrimaryContainer = RvmPrimary10,
    inversePrimary = RvmPrimary80,
    secondary = RvmSecondary40,
    onSecondary = RvmSecondary100,
    secondaryContainer = RvmSecondary90,
    onSecondaryContainer = RvmSecondary10,
    tertiary = RvmTertiary40,
    onTertiary = RvmTertiary100,
    tertiaryContainer = RvmTertiary90,
    onTertiaryContainer = RvmTertiary10,
    background = RvmNeutral98,
    onBackground = RvmNeutral10,
    surface = RvmNeutral98,
    onSurface = RvmNeutral10,
    surfaceVariant = RvmNeutralVariant90,
    onSurfaceVariant = RvmNeutralVariant30,
    inverseSurface = RvmNeutral20,
    inverseOnSurface = RvmNeutral95,
    outline = RvmNeutralVariant50,
    outlineVariant = RvmNeutralVariant80,
    scrim = RvmNeutral0,
    surfaceBright = RvmNeutral98,
    surfaceDim = RvmNeutral87,
    surfaceContainerLowest = RvmNeutral100,
    surfaceContainerLow = RvmNeutral96,
    surfaceContainer = RvmNeutral94,
    surfaceContainerHigh = RvmNeutral92,
    surfaceContainerHighest = RvmNeutral90,
)

private val RvmDarkColors = darkColorScheme(
    primary = RvmPrimary80,
    onPrimary = RvmPrimary20,
    primaryContainer = RvmPrimary30,
    onPrimaryContainer = RvmPrimary90,
    inversePrimary = RvmPrimary40,
    secondary = RvmSecondary80,
    onSecondary = RvmSecondary20,
    secondaryContainer = RvmSecondary30,
    onSecondaryContainer = RvmSecondary90,
    tertiary = RvmTertiary80,
    onTertiary = RvmTertiary20,
    tertiaryContainer = RvmTertiary30,
    onTertiaryContainer = RvmTertiary90,
    background = RvmNeutral6,
    onBackground = RvmNeutral90,
    surface = RvmNeutral6,
    onSurface = RvmNeutral90,
    surfaceVariant = RvmNeutralVariant30,
    onSurfaceVariant = RvmNeutralVariant80,
    inverseSurface = RvmNeutral90,
    inverseOnSurface = RvmNeutral20,
    outline = RvmNeutralVariant60,
    outlineVariant = RvmNeutralVariant30,
    scrim = RvmNeutral0,
    surfaceBright = RvmNeutral24,
    surfaceDim = RvmNeutral6,
    surfaceContainerLowest = RvmNeutral4,
    surfaceContainerLow = RvmNeutral10,
    surfaceContainer = RvmNeutral12,
    surfaceContainerHigh = RvmNeutral17,
    surfaceContainerHighest = RvmNeutral22,
)

@Composable
fun RvmTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colorScheme = when {
        USE_DYNAMIC_COLOR && dark -> dynamicDarkColorScheme(context)
        USE_DYNAMIC_COLOR -> dynamicLightColorScheme(context)
        dark -> RvmDarkColors
        else -> RvmLightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = RvmTypography,
        shapes = RvmShapes,
        content = content
    )
}
