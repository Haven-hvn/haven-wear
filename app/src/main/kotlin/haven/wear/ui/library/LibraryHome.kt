package haven.wear.ui.library

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import haven.wear.library.Library
import haven.wear.library.Track
import haven.wear.ui.components.Cover
import haven.wear.ui.components.Glyphs
import haven.wear.ui.components.NavRow
import haven.wear.ui.components.TrackRow
import haven.wear.ui.theme.HavenColors

/**
 * Library home — the page beside Now Playing. Shuffle first (the most common thing anyone does with
 * a watch full of music), then the three ways in, then what's new.
 */
@Composable
fun LibraryHome(
    library: Library?,
    art: Map<String, Bitmap>,
    currentId: String?,
    onShuffleAll: () -> Unit,
    onCollections: () -> Unit,
    onArtists: () -> Unit,
    onSongs: () -> Unit,
    onTrack: (Track) -> Unit,
    onSettings: () -> Unit,
    onShowAddress: () -> Unit,
) {
    val state = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    ScreenScaffold(scrollState = state) { padding ->
        TransformingLazyColumn(state = state, contentPadding = padding) {
            item { ListHeader(modifier = Modifier.transformedHeight(this, spec), transformation = SurfaceTransformation(spec)) { Text("Library") } }

            val lib = library ?: Library.EMPTY
            if (library != null && lib.tracks.isEmpty()) {
                item { EmptyLibrary(onShowAddress) }
            } else {
                if (lib.tracks.any { it.isPlayable }) {
                    item {
                        Button(
                            onClick = onShuffleAll,
                            modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                            transformation = SurfaceTransformation(spec),
                            icon = { Icon(Glyphs.Shuffle, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        ) { Text("Shuffle all") }
                    }
                }
                item { NavRow("Collections", Glyphs.Album, spec, onCollections, detail = count(lib.collections.size)) }
                item { NavRow("Artists", Glyphs.Person, spec, onArtists, detail = count(lib.artists.size)) }
                item { NavRow("Songs", Glyphs.Note, spec, onSongs, detail = count(lib.tracks.size)) }

                val recent = lib.tracks.sortedByDescending { it.item.createdAt }.take(RECENT_COUNT)
                if (recent.isNotEmpty()) {
                    item { ListHeader(modifier = Modifier.transformedHeight(this, spec), transformation = SurfaceTransformation(spec)) { Text("Recently added") } }
                    items(recent, key = { "recent:" + it.id }) { track ->
                        TrackRow(track, art[track.id], spec, onClick = { onTrack(track) }, isCurrent = track.id == currentId)
                    }
                }
            }

            item {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center) {
                    FilledTonalIconButton(onClick = onSettings) { Icon(Glyphs.Settings, contentDescription = "Settings", modifier = Modifier.size(20.dp)) }
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(onShowAddress: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Cover(seed = "haven", title = "♪", art = null, size = 44.dp)
        Text(
            "Nothing here yet",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 10.dp),
        )
        Text(
            "Music appears when this watch holds the tokens it's shared with.",
            style = MaterialTheme.typography.bodySmall,
            color = HavenColors.Muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
        )
        Button(onClick = onShowAddress, colors = ButtonDefaults.filledTonalButtonColors()) { Text("Watch address") }
    }
}

private fun count(n: Int): String? = if (n == 0) null else n.toString()

private const val RECENT_COUNT = 4
