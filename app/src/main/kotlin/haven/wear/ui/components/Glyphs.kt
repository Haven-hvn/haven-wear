package haven.wear.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The handful of glyphs the app needs, drawn from Material path data (24dp grid). Kept local so the
 * app carries no icon library for a dozen shapes.
 */
object Glyphs {
    val Play = glyph("Play", "M8,5.14v13.72c0,0.79 0.87,1.27 1.54,0.84l10.29,-6.86c0.6,-0.4 0.6,-1.28 0,-1.68L9.54,4.3C8.87,3.87 8,4.35 8,5.14z")
    val Pause = glyph("Pause", "M7,19h3c0.55,0 1,-0.45 1,-1V6c0,-0.55 -0.45,-1 -1,-1H7c-0.55,0 -1,0.45 -1,1v12c0,0.55 0.45,1 1,1zM14,5c-0.55,0 -1,0.45 -1,1v12c0,0.55 0.45,1 1,1h3c0.55,0 1,-0.45 1,-1V6c0,-0.55 -0.45,-1 -1,-1h-3z")
    val Next = glyph("Next", "M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z")
    val Previous = glyph("Previous", "M6,6h2v12H6zM9.5,12l8.5,6V6z")
    val Shuffle = glyph("Shuffle", "M10.59,9.17L5.41,4L4,5.41l5.17,5.17l1.42,-1.41zM14.5,4l2.04,2.04L4,18.59L5.41,20L17.96,7.46L20,9.5V4h-5.5zM14.83,13.41l-1.41,1.41l3.13,3.13L14.5,20H20v-5.5l-2.04,2.04l-3.13,-3.13z")
    val Repeat = glyph("Repeat", "M7,7h10v3l4,-4l-4,-4v3H5v6h2V7zM17,17H7v-3l-4,4l4,4v-3h12v-6h-2v4z")
    val RepeatOne = glyph("Repeat one", "M7,7h10v3l4,-4l-4,-4v3H5v6h2V7zM17,17H7v-3l-4,4l4,4v-3h12v-6h-2v4zM13,15V9h-1l-2,1v1h1.5v4H13z")
    val Queue = glyph("Queue", "M15,6H3v2h12V6zM15,10H3v2h12v-2zM3,16h8v-2H3v2zM17,6v8.18c-0.31,-0.11 -0.65,-0.18 -1,-0.18c-1.66,0 -3,1.34 -3,3s1.34,3 3,3s3,-1.34 3,-3V8h3V6h-5z")
    val Lock = glyph("Locked", "M18,8h-1V6c0,-2.76 -2.24,-5 -5,-5S7,3.24 7,6v2H6c-1.1,0 -2,0.9 -2,2v10c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V10c0,-1.1 -0.9,-2 -2,-2zM12,17c-1.1,0 -2,-0.9 -2,-2s0.9,-2 2,-2s2,0.9 2,2s-0.9,2 -2,2zM15.1,8H8.9V6c0,-1.71 1.39,-3.1 3.1,-3.1s3.1,1.39 3.1,3.1v2z")
    val Note = glyph("Music", "M12,3v10.55c-0.59,-0.34 -1.27,-0.55 -2,-0.55c-2.21,0 -4,1.79 -4,4s1.79,4 4,4s4,-1.79 4,-4V7h4V3h-6z")
    val Album = glyph("Collections", "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10s10,-4.48 10,-10S17.52,2 12,2zM12,16.5c-2.49,0 -4.5,-2.01 -4.5,-4.5S9.51,7.5 12,7.5s4.5,2.01 4.5,4.5s-2.01,4.5 -4.5,4.5zM12,11c-0.55,0 -1,0.45 -1,1s0.45,1 1,1s1,-0.45 1,-1s-0.45,-1 -1,-1z")
    val Person = glyph("Artists", "M12,12c2.21,0 4,-1.79 4,-4s-1.79,-4 -4,-4s-4,1.79 -4,4s1.79,4 4,4zM12,14c-2.67,0 -8,1.34 -8,4v2h16v-2c0,-2.66 -5.33,-4 -8,-4z")
    val Settings = glyph("Settings", "M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94c0,-0.32 -0.02,-0.64 -0.07,-0.94l2.03,-1.58c0.18,-0.14 0.23,-0.41 0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22l-2.39,0.96c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94L14.4,2.81c-0.04,-0.24 -0.24,-0.41 -0.48,-0.41h-3.84c-0.24,0 -0.43,0.17 -0.47,0.41L9.25,5.35C8.66,5.59 8.12,5.92 7.63,6.29L5.24,5.33c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87C2.62,9.08 2.66,9.34 2.86,9.48l2.03,1.58C4.84,11.36 4.8,11.69 4.8,12s0.02,0.64 0.07,0.94l-2.03,1.58c-0.18,0.14 -0.23,0.41 -0.12,0.61l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54c0.05,0.24 0.24,0.41 0.48,0.41h3.84c0.24,0 0.44,-0.17 0.47,-0.41l0.36,-2.54c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32c0.12,-0.22 0.07,-0.47 -0.12,-0.61L19.14,12.94zM12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6s1.62,-3.6 3.6,-3.6s3.6,1.62 3.6,3.6S13.98,15.6 12,15.6z")
    val Headphones = glyph("Headphones", "M12,1c-4.97,0 -9,4.03 -9,9v7c0,1.66 1.34,3 3,3h3v-8H5v-2c0,-3.87 3.13,-7 7,-7s7,3.13 7,7v2h-4v8h3c1.66,0 3,-1.34 3,-3v-7c0,-4.97 -4.03,-9 -9,-9z")

    private fun glyph(name: String, path: String): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(pathData = addPathNodes(path), fill = SolidColor(Color.White))
            .build()
}
