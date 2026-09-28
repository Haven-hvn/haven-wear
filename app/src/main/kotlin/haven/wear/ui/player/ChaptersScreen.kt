package haven.wear.ui.player

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
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
import haven.wear.playback.AudioChapter
import haven.wear.playback.chapterIndexAt
import haven.wear.ui.theme.HavenColors
import kotlinx.coroutines.delay

/**
 * Tracks inside a merged single-file album: one row per ID3 chapter, the playing one
 * lit up. Tap to jump — the same list the phone viewer shows, sized for a 1.4" screen.
 */
@Composable
fun ChaptersScreen(
    chapters: List<AudioChapter>,
    positionMs: () -> Long,
    isPlaying: Boolean,
    onSeekTo: (Long) -> Unit,
) {
    var current by remember { mutableIntStateOf(-1) }
    LaunchedEffect(chapters, isPlaying) {
        while (true) {
            current = chapterIndexAt(chapters, positionMs())
            if (!isPlaying) break
            delay(500)
        }
    }
    val listState = rememberTransformingLazyColumnState()
    // Open on the playing track, not the top of a long album.
    LaunchedEffect(Unit) {
        val at = chapterIndexAt(chapters, positionMs())
        if (at > 0) listState.scrollToItem(at + 1)
    }
    val spec = rememberTransformationSpec()
    ScreenScaffold(scrollState = listState) { padding ->
        TransformingLazyColumn(state = listState, contentPadding = padding) {
            item { ListHeader(modifier = Modifier.transformedHeight(this, spec), transformation = SurfaceTransformation(spec)) { Text("Chapters") } }
            itemsIndexed(chapters, key = { index, chapter -> "$index:${chapter.startMs}" }) { index, chapter ->
                val playing = index == current
                Button(
                    onClick = { onSeekTo(chapter.startMs) },
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                    colors = if (playing) ButtonDefaults.filledTonalButtonColors(contentColor = HavenColors.Ember) else ButtonDefaults.filledTonalButtonColors(),
                    secondaryLabel = { Text(if (playing) "Now playing" else chapter.startMs.toElapsed(), maxLines = 1, color = HavenColors.Muted) },
                ) {
                    Text(chapter.title.ifBlank { "Untitled" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** 90000ms → "1:30", 3723000ms → "1:02:03". Hours only when the album earns them. */
private fun Long.toElapsed(): String {
    val totalSeconds = (this / 1000).coerceAtLeast(0)
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    else "$minutes:${seconds.toString().padStart(2, '0')}"
}
