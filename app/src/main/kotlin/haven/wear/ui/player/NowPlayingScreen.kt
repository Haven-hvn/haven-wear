package haven.wear.ui.player

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.google.android.horologist.media.ui.material3.screens.player.PlayerScreen
import haven.wear.playback.AudioChapter
import haven.wear.playback.NowPlaying
import haven.wear.playback.chapterIndexAt
import haven.wear.ui.components.Glyphs
import haven.wear.ui.components.ProgressRing
import haven.wear.ui.components.averageColor
import haven.wear.ui.components.coverColors
import haven.wear.ui.theme.HavenColors
import kotlinx.coroutines.delay

/**
 * Now Playing. Horologist's round-screen player layout; everything inside it is Haven's:
 *
 * - Background: a soft glow in the colour of the album art (or the generated cover), so each song
 *   tints the watch a little differently. Cross-fades between tracks.
 * - Centre: title (marquee when long) and artist.
 * - Transport: previous · play/pause · next, with the song's progress as a thin ring around play —
 *   one glance, no scrubber to fiddle with on a 1.4" screen.
 * - Bottom: shuffle · queue · repeat. Volume is the crown.
 * - Chapters: a merged album names its playing track under the artist; tapping the text
 *   opens the chapter list. Ordinary tracks show nothing extra.
 */
@Composable
fun NowPlayingScreen(
    state: NowPlaying,
    art: Bitmap?,
    positionMs: () -> Long,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onQueue: () -> Unit,
    chapters: List<AudioChapter> = emptyList(),
    onChapters: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var progress by remember { mutableFloatStateOf(0f) }
    var chapterIndex by remember { mutableIntStateOf(-1) }
    LaunchedEffect(state.mediaId, state.isPlaying, state.durationMs, chapters) {
        while (true) {
            val position = positionMs()
            progress = if (state.durationMs > 0) position.toFloat() / state.durationMs else 0f
            chapterIndex = chapterIndexAt(chapters, position)
            if (!state.isPlaying) break
            delay(500)
        }
    }

    val glowTarget = when {
        art != null -> remember(art) { art.averageColor() }
        state.mediaId != null -> coverColors(state.mediaId).first
        else -> HavenColors.Black
    }
    val glow by animateColorAsState(glowTarget, animationSpec = tween(700), label = "glow")

    PlayerScreen(
        modifier = modifier,
        background = {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.radialGradient(listOf(glow.copy(alpha = 0.45f), Color.Black), radius = 520f),
                ),
            )
        },
        mediaDisplay = { MediaText(state, chapters.getOrNull(chapterIndex)?.title, onChapters?.takeIf { chapters.isNotEmpty() }) },
        controlButtons = {
            Transport(
                state = state,
                progress = { progress },
                onTogglePlay = onTogglePlay,
                onNext = onNext,
                onPrevious = onPrevious,
            )
        },
        buttons = {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SmallToggle(Glyphs.Shuffle, "Shuffle", state.shuffle, enabled = !state.isEmpty, onClick = onShuffle)
                SmallToggle(Glyphs.Queue, "Up next", active = false, enabled = state.queue.size > 1, onClick = onQueue)
                SmallToggle(
                    glyph = if (state.repeatMode == Player.REPEAT_MODE_ONE) Glyphs.RepeatOne else Glyphs.Repeat,
                    label = "Repeat",
                    active = state.repeatMode != Player.REPEAT_MODE_OFF,
                    enabled = !state.isEmpty,
                    onClick = onRepeat,
                )
            }
        },
    )
}

@Composable
private fun MediaText(state: NowPlaying, chapterTitle: String?, onChapters: (() -> Unit)?) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 28.dp)
            .then(if (onChapters != null) Modifier.clickable(onClick = onChapters) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when {
            state.isEmpty -> {
                Text("Not playing", style = MaterialTheme.typography.titleMedium, color = HavenColors.Ink)
                Text("Swipe for your library", style = MaterialTheme.typography.bodySmall, color = HavenColors.Muted)
            }
            state.waitingForOutput -> {
                Icon(Glyphs.Headphones, contentDescription = null, tint = HavenColors.Ember, modifier = Modifier.size(18.dp))
                Text("Connect headphones", style = MaterialTheme.typography.titleSmall, color = HavenColors.Ink, textAlign = TextAlign.Center)
            }
            else -> {
                Text(
                    state.title.orEmpty().ifBlank { "Untitled" },
                    style = MaterialTheme.typography.titleMedium,
                    color = HavenColors.Ink,
                    maxLines = 1,
                    modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1500),
                )
                Text(
                    state.artist.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = HavenColors.Muted,
                    maxLines = 1,
                )
                if (chapterTitle != null) {
                    Text(
                        chapterTitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = HavenColors.Ember,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1500),
                    )
                }
            }
        }
    }
}

@Composable
private fun Transport(
    state: NowPlaying,
    progress: () -> Float,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrevious, enabled = state.hasPrevious) {
            Icon(Glyphs.Previous, contentDescription = "Previous", modifier = Modifier.size(26.dp))
        }
        Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
            ProgressRing(progress = progress, stroke = 3.dp)
            FilledIconButton(
                onClick = onTogglePlay,
                enabled = !state.isEmpty,
                modifier = Modifier.size(52.dp),
            ) {
                Icon(
                    if (state.isPlaying) Glyphs.Pause else Glyphs.Play,
                    contentDescription = if (state.isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        IconButton(onClick = onNext, enabled = state.hasNext) {
            Icon(Glyphs.Next, contentDescription = "Next", modifier = Modifier.size(26.dp))
        }
    }
}

@Composable
private fun SmallToggle(
    glyph: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(36.dp),
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = if (active) HavenColors.Ember else HavenColors.Muted,
        ),
    ) {
        Icon(glyph, contentDescription = label, modifier = Modifier.size(20.dp))
    }
}
