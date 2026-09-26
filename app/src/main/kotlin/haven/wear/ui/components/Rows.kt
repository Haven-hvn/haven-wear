package haven.wear.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import haven.wear.library.Access
import haven.wear.library.Track
import haven.wear.ui.theme.HavenColors
import java.math.BigDecimal

/**
 * One song. Cover, title, artist — and, only when it matters, why it won't play yet, in words
 * rather than a code: "Hold 25 FWB" or "Unlocks at 5 ETH".
 */
@Composable
fun TransformingLazyColumnItemScope.TrackRow(
    track: Track,
    art: Bitmap?,
    spec: TransformationSpec,
    onClick: () -> Unit,
    isCurrent: Boolean = false,
) {
    val locked = !track.isPlayable
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
        transformation = SurfaceTransformation(spec),
        colors = ButtonDefaults.filledTonalButtonColors(),
        icon = { Cover(seed = track.id, title = track.title, art = art, size = 32.dp) },
        secondaryLabel = {
            Text(
                text = accessLine(track) ?: track.artistName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (locked) HavenColors.Ember else HavenColors.Muted,
            )
        },
    ) {
        Text(
            text = track.title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = when {
                isCurrent -> HavenColors.Ember
                locked -> HavenColors.Muted
                else -> HavenColors.Ink
            },
        )
    }
}

/** A row that opens a list: glyph, label, count. */
@Composable
fun TransformingLazyColumnItemScope.NavRow(
    label: String,
    glyph: androidx.compose.ui.graphics.vector.ImageVector,
    spec: TransformationSpec,
    onClick: () -> Unit,
    detail: String? = null,
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
        transformation = SurfaceTransformation(spec),
        colors = ButtonDefaults.filledTonalButtonColors(),
        icon = { Icon(glyph, contentDescription = null, modifier = Modifier.size(22.dp), tint = HavenColors.Ember) },
        secondaryLabel = detail?.let { { Text(it, color = HavenColors.Muted) } },
    ) {
        Text(label)
    }
}

fun accessLine(track: Track): String? = when (val a = track.access) {
    Access.Open -> null
    is Access.NeedsMore -> "Hold ${a.required.pretty()} ${a.symbol ?: "tokens"}"
    is Access.Drip -> "Unlocks at ${a.required} ETH"
}

fun BigDecimal.pretty(): String {
    val plain = stripTrailingZeros()
    return if (plain.scale() <= 0) "%,d".format(plain.toBigInteger()) else plain.setScale(minOf(plain.scale(), 4), java.math.RoundingMode.DOWN).toPlainString()
}
