package io.github.cyrus_sch.speakerlink.ui.theme

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

private val LightColors = lightColorScheme(
    primary = Color(0xFF00696E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF9CF1F6),
    onPrimaryContainer = Color(0xFF002022),
    secondary = Color(0xFF4A6365),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCE8E9),
    onSecondaryContainer = Color(0xFF051F21),
    tertiary = Color(0xFF4D5F7D),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD5E3FF),
    onTertiaryContainer = Color(0xFF071C36),
    background = Color(0xFFFAFDFC),
    onBackground = Color(0xFF191C1C),
    surface = Color(0xFFFAFDFC),
    onSurface = Color(0xFF191C1C),
    surfaceVariant = Color(0xFFDAE4E5),
    onSurfaceVariant = Color(0xFF3F4949),
    outline = Color(0xFF6F7979),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF80D4DA),
    onPrimary = Color(0xFF00373A),
    primaryContainer = Color(0xFF004F53),
    onPrimaryContainer = Color(0xFF9CF1F6),
    secondary = Color(0xFFB0CCCD),
    onSecondary = Color(0xFF1B3436),
    secondaryContainer = Color(0xFF324B4D),
    onSecondaryContainer = Color(0xFFCCE8E9),
    tertiary = Color(0xFFB5C7EA),
    onTertiary = Color(0xFF1E314C),
    tertiaryContainer = Color(0xFF354764),
    onTertiaryContainer = Color(0xFFD5E3FF),
    background = Color(0xFF191C1C),
    onBackground = Color(0xFFE0E3E3),
    surface = Color(0xFF191C1C),
    onSurface = Color(0xFFE0E3E3),
    surfaceVariant = Color(0xFF3F4949),
    onSurfaceVariant = Color(0xFFBEC8C9),
    outline = Color(0xFF899393),
)

@Composable
fun SpeakerLinkTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
