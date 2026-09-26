package haven.wear.playback

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Album art, from the picture embedded in each audio file (ID3 APIC, FLAC PICTURE, MP4 `covr`).
 *
 * Arkiv entities carry no cover field, so art appears once a track is on the watch: [TrackFiles]
 * pulls the picture out the first time it has the file and [save] writes a small JPEG to
 * `filesDir/art` (pruned when foc evicts the track). [load] brings covers back into memory after a
 * restart. Tracks with no embedded picture get a generated cover in the UI.
 */
@Singleton
class ArtworkStore @Inject constructor() {

    private val _art = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
    /** media id → cover. */
    val art: StateFlow<Map<String, Bitmap>> = _art.asStateFlow()

    fun save(mediaId: String, picture: ByteArray, file: File) {
        val bitmap = decodeScaled(picture) ?: return
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            tmp.renameTo(file)
        }
        _art.update { it + (mediaId to bitmap) }
    }

    /** Loads covers for tracks on the watch that aren't in memory yet. Call off the main thread. */
    fun load(entries: List<Pair<String, File>>) {
        val missing = entries.filter { (id, file) -> id !in _art.value && file.isFile }
        if (missing.isEmpty()) return
        val loaded = missing.mapNotNull { (id, file) -> BitmapFactory.decodeFile(file.path)?.let { id to it } }
        _art.update { it + loaded }
    }

    private fun decodeScaled(data: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= TARGET_PX && bounds.outHeight / (sample * 2) >= TARGET_PX) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val side = minOf(decoded.width, decoded.height)
        val square = Bitmap.createBitmap(decoded, (decoded.width - side) / 2, (decoded.height - side) / 2, side, side)
        return if (side > TARGET_PX) Bitmap.createScaledBitmap(square, TARGET_PX, TARGET_PX, true) else square
    }

    private companion object {
        /** Enough for the Now Playing glow and 52 dp headers on a 480 px screen. */
        const val TARGET_PX = 192
    }
}
