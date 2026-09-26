package haven.wear.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme

/**
 * Haven on the wrist: true black for the OLED, warm white type, and one colour — ember — reserved
 * for the thing you're most likely to do next (play, continue, unlock progress). Everything else is
 * greyscale so the music, and its artwork, carry the colour.
 */
object HavenColors {
    val Ember = Color(0xFFFF7A59)
    val EmberDim = Color(0xFFD9573A)
    val EmberContainer = Color(0xFF3A1D14)
    val OnEmber = Color(0xFF1A0E0A)
    val Ink = Color(0xFFF5F1EC)
    val Muted = Color(0xFFA7A3A0)
    val Faint = Color(0xFF5E5A57)
    val Surface = Color(0xFF1C1A19)
    val SurfaceHigh = Color(0xFF2A2725)
    val Black = Color(0xFF000000)
}

private val scheme = ColorScheme(
    primary = HavenColors.Ember,
    primaryDim = HavenColors.EmberDim,
    primaryContainer = HavenColors.EmberContainer,
    onPrimary = HavenColors.OnEmber,
    onPrimaryContainer = HavenColors.Ember,
    surfaceContainerLow = HavenColors.Black,
    surfaceContainer = HavenColors.Surface,
    surfaceContainerHigh = HavenColors.SurfaceHigh,
    onSurface = HavenColors.Ink,
    onSurfaceVariant = HavenColors.Muted,
    outline = HavenColors.Faint,
    outlineVariant = HavenColors.SurfaceHigh,
    background = HavenColors.Black,
    onBackground = HavenColors.Ink,
)

@Composable
fun HavenTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
