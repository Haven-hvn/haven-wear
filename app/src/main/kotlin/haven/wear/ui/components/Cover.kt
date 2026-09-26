package haven.wear.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Text
import haven.wear.ui.theme.HavenColors

/**
 * Album art, or — for anything not played yet this session — a cover generated from the title:
 * a two-tone gradient whose hue is stable per track, with the initial set in it. Never a grey
 * placeholder; a library of generated covers still reads as a record shelf.
 */
@Composable
fun Cover(
    seed: String,
    title: String,
    art: Bitmap?,
    size: Dp,
    modifier: Modifier = Modifier,
    corner: Dp = size * 0.22f,
) {
    val shape = RoundedCornerShape(corner)
    if (art != null) {
        val image = remember(art) { art.asImageBitmap() }
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(shape),
        )
        return
    }
    val (a, b) = remember(seed) { coverColors(seed) }
    Box(
        modifier = modifier.size(size).clip(shape).background(Brush.linearGradient(listOf(a, b))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "♪",
            color = Color.White.copy(alpha = 0.92f),
            fontSize = (size.value * 0.42f).sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** Stable, pleasant pair: same hue family, one lighter, never neon. */
fun coverColors(seed: String): Pair<Color, Color> {
    val hue = ((seed.hashCode().toLong() and 0xFFFFFFFFL) % 360L).toFloat()
    return Color.hsl(hue, 0.55f, 0.52f) to Color.hsl((hue + 28f) % 360f, 0.60f, 0.30f)
}

/** The average colour of a cover, for the Now Playing glow. */
fun Bitmap.averageColor(): Color {
    val px = Bitmap.createScaledBitmap(this, 1, 1, true).getPixel(0, 0)
    return Color(px)
}

/**
 * A thin ring that fills clockwise from twelve o'clock. Used around the play button and for drip
 * progress, so "how far along" always looks the same.
 */
@Composable
fun ProgressRing(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = HavenColors.Ember,
    track: Color = HavenColors.SurfaceHigh,
    stroke: Dp = 3.dp,
) {
    Canvas(modifier.fillMaxSize()) {
        val width = stroke.toPx()
        val inset = width / 2
        val arcSize = androidx.compose.ui.geometry.Size(size.width - width, size.height - width)
        drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(width))
        val sweep = 360f * progress().coerceIn(0f, 1f)
        if (sweep > 0f) {
            drawArc(color, -90f, sweep, false, Offset(inset, inset), arcSize, style = Stroke(width, cap = StrokeCap.Round))
        }
    }
}
