package haven.wear.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import haven.wear.BuildConfig
import haven.wear.library.shortAddress
import haven.wear.ui.components.Glyphs
import haven.wear.ui.theme.HavenColors

/**
 * Three things, and only three: this watch's address, its recovery phrase, and a way to re-check
 * the library. No storage controls — the watch manages its own space.
 */
@Composable
fun SettingsScreen(
    address: String?,
    refreshing: Boolean,
    onAddress: () -> Unit,
    onRecovery: () -> Unit,
    onRefresh: () -> Unit,
) {
    val state = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    ScreenScaffold(scrollState = state) { padding ->
        TransformingLazyColumn(state = state, contentPadding = padding) {
            item { ListHeader(modifier = Modifier.transformedHeight(this, spec), transformation = SurfaceTransformation(spec)) { Text("Settings") } }
            item {
                Button(
                    onClick = onAddress,
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    secondaryLabel = { Text(address?.let(::shortAddress) ?: "…", color = HavenColors.Muted) },
                ) { Text("Watch address") }
            }
            item {
                Button(
                    onClick = onRecovery,
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    icon = { Icon(Glyphs.Lock, contentDescription = null, modifier = Modifier.size(18.dp), tint = HavenColors.Ember) },
                ) { Text("Recovery phrase") }
            }
            item {
                Button(
                    onClick = onRefresh,
                    enabled = !refreshing,
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                    colors = ButtonDefaults.filledTonalButtonColors(),
                ) { Text(if (refreshing) "Checking…" else "Check for new music") }
            }
            item {
                Text(
                    "Haven ${BuildConfig.VERSION_NAME}\nMusic is kept on this watch as you listen and cleared automatically when space is needed.",
                    style = MaterialTheme.typography.bodyExtraSmall,
                    color = HavenColors.Faint,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }
    }
}
