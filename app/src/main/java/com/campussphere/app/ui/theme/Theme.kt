package com.campussphere.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val LightColorScheme = lightColorScheme(
    primary = LightPrimary, onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer, onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary, onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer, onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightTertiary, onTertiary = Color.White,
    tertiaryContainer = LightTertiaryContainer, onTertiaryContainer = LightOnTertiaryContainer,
    background = LightBackground, onBackground = LightOnSurface,
    surface = LightSurface, onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant, onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline, outlineVariant = LightOutlineVariant,
    surfaceContainerLowest = LightContainerLowest, surfaceContainerLow = LightContainerLow,
    surfaceContainer = LightContainer, surfaceContainerHigh = LightContainerHigh,
    surfaceContainerHighest = LightContainerHighest,
    error = Color(0xFFB42318), errorContainer = Color(0xFFFEE4E2), onErrorContainer = Color(0xFF7A271A),
)

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary, onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer, onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary, onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer, onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkTertiary, onTertiary = Color(0xFF003731),
    tertiaryContainer = DarkTertiaryContainer, onTertiaryContainer = DarkOnTertiaryContainer,
    background = DarkBackground, onBackground = DarkOnSurface,
    surface = DarkSurface, onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant, onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline, outlineVariant = DarkOutlineVariant,
    surfaceContainerLowest = DarkContainerLowest, surfaceContainerLow = DarkContainerLow,
    surfaceContainer = DarkContainer, surfaceContainerHigh = DarkContainerHigh,
    surfaceContainerHighest = DarkContainerHighest,
    error = Color(0xFFFDA29B), errorContainer = Color(0xFF55160C), onErrorContainer = Color(0xFFFEE4E2),
)

/** Soft, friendly corners: chips and fields 12dp, cards 18dp, sheets and dialogs 28dp. */
val CampusShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * The CampusSphere brand theme. Colours come from the logo, so the app looks the same on every phone; the
 * wallpaper-based "dynamic colour" of Android 12+ is off unless a caller asks for it.
 */
@Composable
fun CampusSphereTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
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

    MaterialTheme(colorScheme = colorScheme, typography = Typography, shapes = CampusShapes) {
        // Paint the theme background behind every screen, so text and cards match in both light and dark mode.
        Surface(modifier = Modifier.fillMaxSize(), color = colorScheme.background, content = content)
    }
}
