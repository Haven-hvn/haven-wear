package haven.wear.ui.locked

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import haven.wear.library.Access
import haven.wear.library.Track
import haven.wear.ui.components.Glyphs
import haven.wear.ui.components.ProgressRing
import haven.wear.ui.components.pretty
import haven.wear.ui.theme.HavenColors

/**
 * Why a song won't play yet, and the one thing that changes that. Never an error screen: the track
 * exists, the watch just doesn't hold enough yet — or the premiere hasn't been pumped to its target.
 *
 * - Below threshold: the lock, what it takes, what the watch holds, and the address to send to.
 * - Drip stage: a ring that fills as the market cap climbs toward the target. "Check again" asks the
 *   canister (no signature) and the ring moves, or the song opens.
 */
@Composable
fun LockedScreen(
    track: Track,
    refreshing: Boolean,
    onShowAddress: () -> Unit,
    onCheckAgain: () -> Unit,
    onPlay: () -> Unit,
) {
    val access = track.access
    val scroll = rememberScrollState()
    ScreenScaffold(
        scrollState = scroll,
        timeText = {},
        edgeButton = {
            when (access) {
                Access.Open -> EdgeButton(onClick = onPlay) { Text("Play") }
                is Access.NeedsMore -> EdgeButton(onClick = onShowAddress) { Text("Watch address") }
                is Access.Drip -> EdgeButton(onClick = onCheckAgain, enabled = !refreshing) {
                    Text(if (refreshing) "Checking…" else "Check again")
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(
            Modifier.verticalScroll(scroll).padding(padding).padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                when (access) {
                    is Access.Drip -> {
                        ProgressRing(progress = { access.progress }, stroke = 4.dp)
                        Text(
                            "${(access.progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = HavenColors.Ink,
                        )
                    }
                    else -> Icon(Glyphs.Lock, contentDescription = null, tint = HavenColors.Ember, modifier = Modifier.size(28.dp))
                }
            }
            Text(
                track.title,
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(track.artistName, style = MaterialTheme.typography.bodySmall, color = HavenColors.Muted, maxLines = 1)
            Text(
                text = explanation(access),
                style = MaterialTheme.typography.bodySmall,
                color = HavenColors.Ink,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        } }
    }
}

private fun explanation(access: Access): String = when (access) {
    Access.Open -> "Ready to play."
    is Access.NeedsMore -> {
        val symbol = access.symbol ?: "tokens"
        "Hold ${access.required.pretty()} $symbol to listen.\nThis watch has ${access.held.pretty()}."
    }
    is Access.Drip -> access.actual
        ?.let { "Premieres at ${access.required} ETH market cap.\nNow at $it ETH." }
        ?: "Premieres at ${access.required} ETH market cap."
}
