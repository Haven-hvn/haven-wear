package haven.wear.ui.player

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import haven.wear.playback.NowPlaying
import haven.wear.ui.components.Cover
import haven.wear.ui.theme.HavenColors

/** Up next: what's playing, then what follows. Tap to jump. */
@Composable
fun QueueScreen(state: NowPlaying, art: Map<String, Bitmap>, onSkipTo: (Int) -> Unit) {
    val listState = rememberTransformingLazyColumnState()
    // Open on the playing song, not the top of a long queue.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (state.queueIndex > 0) listState.scrollToItem(state.queueIndex + 1)
    }
    val spec = rememberTransformationSpec()
    ScreenScaffold(scrollState = listState) { padding ->
        TransformingLazyColumn(state = listState, contentPadding = padding) {
            item { ListHeader(modifier = Modifier.transformedHeight(this, spec), transformation = SurfaceTransformation(spec)) { Text("Up next") } }
            itemsIndexed(state.queue, key = { index, entry -> "$index:${entry.mediaId}" }) { index, entry ->
                val current = index == state.queueIndex
                Button(
                    onClick = { onSkipTo(index) },
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                    colors = if (current) ButtonDefaults.filledTonalButtonColors(contentColor = HavenColors.Ember) else ButtonDefaults.filledTonalButtonColors(),
                    icon = { Cover(seed = entry.mediaId, title = entry.title, art = art[entry.mediaId], size = 30.dp) },
                    secondaryLabel = { Text(if (current) "Now playing" else entry.artist, maxLines = 1, color = HavenColors.Muted) },
                ) {
                    Text(entry.title.ifBlank { "Untitled" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
