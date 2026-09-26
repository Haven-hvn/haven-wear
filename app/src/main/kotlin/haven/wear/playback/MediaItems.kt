package haven.wear.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import haven.wear.library.Library
import haven.wear.library.Track

/** Library tracks ↔ Media3 items. The media id is the Arkiv entity id; the URI is resolved locally. */
object MediaItems {

    const val EXTRA_COLLECTION = "haven.collection"

    fun from(track: Track, library: Library?): MediaItem {
        val collectionTitle = track.collectionKey?.let { key -> library?.collections?.firstOrNull { it.key == key }?.title }
        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(HavenDataSource.uriFor(track.id))
            .apply { track.item.mimeType?.let(::setMimeType) }
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artistName)
                    .setAlbumTitle(collectionTitle)
                    .setDisplayTitle(track.title)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .setDurationMs(track.item.durationSeconds?.takeIf { it > 0 }?.times(1000))
                    .setExtras(Bundle().apply { putString(EXTRA_COLLECTION, track.collectionKey) })
                    .build(),
            )
            .build()
    }

    /**
     * Controllers (the phone-free system media controls, the Tile, a resumption request) may send
     * bare media ids. Rebuild full items from the library; ids it no longer has are dropped.
     */
    fun resolve(items: List<MediaItem>, library: Library?): List<MediaItem> =
        items.mapNotNull { item ->
            if (item.localConfiguration != null) item
            else library?.track(item.mediaId)?.takeIf { it.isPlayable }?.let { from(it, library) }
        }
}
