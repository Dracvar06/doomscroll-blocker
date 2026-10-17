package cat.doorman.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Compose's MaterialTheme defaults to a light colour scheme no matter what the
 * system is doing, while the platform window background follows DayNight. Left
 * alone that paints dark text onto a dark window, which is invisible. The theme
 * has to be told about dark mode explicitly.
 *
 * Doorman's own green rather than the wallpaper's colours. The block screen
 * cannot follow the wallpaper -- it is drawn by the service, outside any
 * theme -- so with dynamic colour the app and its block were two different
 * products, and the one place people meet Doorman most often looked least
 * like it. Every role below is the Material tonal palette of the launcher
 * icon's green (#1B5E4A), so the icon, the app and the block are one thing.
 */
@Composable
fun DoormanTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF1B6B52),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFA7F2D1),
    onPrimaryContainer = Color(0xFF002116),
    inversePrimary = Color(0xFF8BD5B6),
    secondary = Color(0xFF4C6359),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCFE9DC),
    onSecondaryContainer = Color(0xFF082018),
    tertiary = Color(0xFF3E6374),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFC2E8FD),
    onTertiaryContainer = Color(0xFF001F2A),
    background = Color(0xFFF5FBF7),
    onBackground = Color(0xFF171D1A),
    surface = Color(0xFFF5FBF7),
    onSurface = Color(0xFF171D1A),
    surfaceVariant = Color(0xFFDBE5DF),
    onSurfaceVariant = Color(0xFF3F4945),
    surfaceTint = Color(0xFF1B6B52),
    inverseSurface = Color(0xFF2C322F),
    inverseOnSurface = Color(0xFFECF2EE),
    outline = Color(0xFF6F7974),
    outlineVariant = Color(0xFFBFC9C3),
    surfaceBright = Color(0xFFF5FBF7),
    surfaceDim = Color(0xFFD5DBD8),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFEFF5F1),
    surfaceContainer = Color(0xFFE9EFEB),
    surfaceContainerHigh = Color(0xFFE4EAE6),
    surfaceContainerHighest = Color(0xFFDEE4E0),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8BD5B6),
    onPrimary = Color(0xFF003828),
    primaryContainer = Color(0xFF0F513D),
    onPrimaryContainer = Color(0xFFA7F2D1),
    inversePrimary = Color(0xFF1B6B52),
    secondary = Color(0xFFB3CCC0),
    onSecondary = Color(0xFF1F352C),
    secondaryContainer = Color(0xFF354B42),
    onSecondaryContainer = Color(0xFFCFE9DC),
    tertiary = Color(0xFFA6CCE0),
    onTertiary = Color(0xFF0A3544),
    tertiaryContainer = Color(0xFF244C5E),
    onTertiaryContainer = Color(0xFFC2E8FD),
    // The block screen's own background (overlay_block.xml), so leaving a
    // block for Doorman does not change the room you are standing in.
    background = Color(0xFF0F1A16),
    onBackground = Color(0xFFDEE4E0),
    surface = Color(0xFF0F1A16),
    onSurface = Color(0xFFDEE4E0),
    surfaceVariant = Color(0xFF3F4945),
    onSurfaceVariant = Color(0xFFBFC9C3),
    surfaceTint = Color(0xFF8BD5B6),
    inverseSurface = Color(0xFFDEE4E0),
    inverseOnSurface = Color(0xFF2C322F),
    outline = Color(0xFF89938E),
    outlineVariant = Color(0xFF3F4945),
    surfaceBright = Color(0xFF343B38),
    surfaceDim = Color(0xFF0F1A16),
    surfaceContainerLowest = Color(0xFF0A120F),
    surfaceContainerLow = Color(0xFF16201C),
    surfaceContainer = Color(0xFF1A2420),
    surfaceContainerHigh = Color(0xFF242E2A),
    surfaceContainerHighest = Color(0xFF2F3935),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)
