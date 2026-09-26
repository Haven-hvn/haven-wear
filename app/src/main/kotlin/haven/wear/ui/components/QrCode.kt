package haven.wear.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * A QR code drawn as vector modules on a white card — crisp at any density, and with softly rounded
 * modules so it looks designed rather than printed. Contrast stays at pure black on white so every
 * wallet scanner reads it.
 */
@Composable
fun QrCode(text: String, size: Dp, modifier: Modifier = Modifier, description: String = "QR code") {
    val matrix = remember(text) { encode(text) }
    Box(
        modifier
            .size(size)
            .background(Color.White, RoundedCornerShape(size * 0.12f))
            .padding(size * 0.08f)
            .semantics { contentDescription = description },
    ) {
        Canvas(Modifier.size(size * 0.84f)) {
            val n = matrix.width
            val cell = this.size.minDimension / n
            val radius = CornerRadius(cell * 0.3f, cell * 0.3f)
            for (y in 0 until n) for (x in 0 until n) {
                if (matrix[x, y]) {
                    drawRoundRect(
                        color = Color.Black,
                        topLeft = Offset(x * cell, y * cell),
                        size = Size(cell * 1.02f, cell * 1.02f),
                        cornerRadius = radius,
                    )
                }
            }
        }
    }
}

private fun encode(text: String): BitMatrix = QRCodeWriter().encode(
    text,
    BarcodeFormat.QR_CODE,
    0,
    0,
    mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
)
