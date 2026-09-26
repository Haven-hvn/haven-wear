package haven.wear.ui.library

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import haven.wear.library.Artist
import haven.wear.library.Track
import haven.wear.library.TrackCollection
import haven.wear.ui.components.Cover
import haven.wear.ui.components.Glyphs
import haven.wear.ui.components.TrackRow
import haven.wear.ui.theme.HavenColors

@Composable
fun CollectionsScreen(collections: List<TrackCollection>, art: Map<String, Bitmap>, onOpen: (TrackCollection) -> Unit) {
    GroupList(
        title = "Collections",
        entries = collections.map { c ->
            GroupEntry(c.key, c.title, subtitle(c.tracks.size, c.chain?.label), c.tracks.firstOrNull { art[it.id] != null }?.let { art[it.id] })
        },
        onOpen = { key -> collections.firstOrNull { it.key == key }?.let(onOpen) },
    )
}

@Composable
fun ArtistsScreen(artists: List<Artist>, art: Map<String, Bitmap>, onOpen: (Artist) -> Unit) {
    GroupList(
        title = "Artists",
        entries = artists.map { a ->
            GroupEntry(a.key, a.name, subtitle(a.tracks.size, null), a.tracks.firstOrNull { art[it.id] != null }?.let { art[it.id] })
        },
        onOpen = { key -> artists.firstOrNull { it.key == key }?.let(onOpen) },
    )
}

private data class GroupEntry(val key: String, val title: String, val subtitle: String, val art: Bitmap?)

@Composable
private fun GroupList(title: String, entries: List<GroupEntry>, onOpen: (String) -> Unit) {
    val state = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    ScreenScaffold(scrollState = state) { padding ->
        TransformingLazyColumn(state = state, contentPadding = padding) {
            item { ListHeader(modifier = Modifier.transformedHeight(this, spec), transformation = SurfaceTransformation(spec)) { Text(title) } }
            items(entries, key = { it.key }) { entry ->
                Button(
                    onClick = { onOpen(entry.key) },
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    icon = { Cover(seed = entry.key, title = entry.title, art = entry.art, size = 32.dp) },
                    secondaryLabel = { Text(entry.subtitle, color = HavenColors.Muted, maxLines = 1) },
                ) { Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

/**
 * A collection, an artist, or all songs. Big cover and title, then the two actions everyone wants —
 * play, shuffle — as a pair of round buttons, then the tracks.
 */
@Composable
fun TrackListScreen(
    title: String,
    subtitle: String?,
    seed: String,
    tracks: List<Track>,
    art: Map<String, Bitmap>,
    currentId: String?,
    onPlay: (Track?) -> Unit,
    onShuffle: () -> Unit,
    onLocked: (Track) -> Unit,
) {
    val state = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    val anyPlayable = tracks.any { it.isPlayable }
    ScreenScaffold(scrollState = state) { padding ->
        TransformingLazyColumn(state = state, contentPadding = padding) {
            item {
                Column(
                    Modifier.fillMaxWidth().transformedHeight(this, spec).padding(bottom = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Cover(seed = seed, title = title, art = tracks.firstNotNullOfOrNull { art[it.id] }, size = 52.dp)
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp, start = 16.dp, end = 16.dp),
                    )
                    if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = HavenColors.Muted)
                }
            }
            if (anyPlayable) {
                item {
                    Row(
                        Modifier.fillMaxWidth().transformedHeight(this, spec).padding(bottom = 4.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilledIconButton(onClick = { onPlay(null) }) {
                            Icon(Glyphs.Play, contentDescription = "Play", modifier = Modifier.size(24.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        FilledTonalIconButton(onClick = onShuffle) {
                            Icon(Glyphs.Shuffle, contentDescription = "Shuffle", modifier = Modifier.size(22.dp))
                        }
                    }
                }
            }
            items(tracks, key = { it.id }) { track ->
                TrackRow(
                    track = track,
                    art = art[track.id],
                    spec = spec,
                    isCurrent = track.id == currentId,
                    onClick = { if (track.isPlayable) onPlay(track) else onLocked(track) },
                )
            }
        }
    }
}

private fun subtitle(count: Int, chain: String?): String =
    listOfNotNull(if (count == 1) "1 song" else "$count songs", chain).joinToString(" · ")
