package haven.wear.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.ResolvingDataSource
import haven.mobile.core.domain.error.HavenError
import haven.wear.library.LibraryRepository
import kotlinx.coroutines.runBlocking
import java.io.IOException
import javax.inject.Inject

/**
 * `haven://track/<id>` → the decrypted file on the watch.
 *
 * Media3's `ResolvingDataSource` swaps the URI for a `file://` one right before opening, so ExoPlayer
 * reads a plain local file. The first play of a track blocks here (on ExoPlayer's loader thread,
 * where that's allowed) while [TrackFiles] fetches and decrypts it; later plays resolve instantly.
 */
@OptIn(UnstableApi::class)
object HavenDataSource {

    const val SCHEME = "haven"

    fun uriFor(id: String): Uri = Uri.Builder().scheme(SCHEME).authority("track").appendPath(id).build()

    fun trackId(uri: Uri): String? =
        uri.takeIf { it.scheme == SCHEME && it.authority == "track" }?.lastPathSegment

    class Factory @Inject constructor(
        private val library: LibraryRepository,
        private val files: TrackFiles,
    ) : DataSource.Factory {

        private val delegate = ResolvingDataSource.Factory(FileDataSource.Factory()) { spec -> resolve(spec) }

        override fun createDataSource(): DataSource = delegate.createDataSource()

        private fun resolve(spec: DataSpec): DataSpec {
            val id = trackId(spec.uri) ?: return spec
            val track = library.track(id)
                ?: throw TrackUnavailableException(HavenError.CacheMiss("This track is no longer in the library."))
            val file = try {
                runBlocking { files.file(track.item) }
            } catch (e: HavenError) {
                throw TrackUnavailableException(e)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw java.io.InterruptedIOException("cancelled")
            }
            return spec.withUri(Uri.fromFile(file))
        }
    }
}

/** Carries the unlock failure through ExoPlayer so the service can explain it. */
class TrackUnavailableException(val reason: HavenError) : IOException(reason.message, reason)
