package haven.mobile.core.arkiv

import haven.mobile.core.domain.MediaKind

/**
 * Chooses the viewer for an entity.
 *
 * Extracted from `ArkivClientImpl` so it can be tested without HTTP: this mapping decides which
 * renderer a user gets, and getting it wrong is a blank screen rather than an error. MIME type
 * wins when present because it comes from the publisher's own metadata; the extension is the
 * fallback for entities written before a MIME was recorded.
 *
 * Extension sets mirror `MOBILE_V1_REQUIREMENTS.md` §4.
 */
object MediaKindResolver {

    private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm", "mov", "m4v", "avi")
    private val AUDIO_EXTENSIONS = setOf("mp3", "flac", "ogg", "oga", "wav", "m4a", "aac", "opus")
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp")
    private val DOCUMENT_EXTENSIONS = setOf("pdf")

    fun resolve(mimeType: String?, fileExtension: String?): MediaKind {
        fromMime(mimeType)?.let { return it }
        return fromExtension(fileExtension)
    }

    private fun fromMime(mimeType: String?): MediaKind? {
        val mime = mimeType?.trim()?.lowercase()?.substringBefore(';') ?: return null
        if (mime.isEmpty()) return null
        return when {
            mime == "application/pdf" -> MediaKind.DOCUMENT
            mime.startsWith("video/") -> MediaKind.VIDEO
            mime.startsWith("audio/") -> MediaKind.AUDIO
            mime.startsWith("image/") -> MediaKind.IMAGE
            // A generic octet-stream tells us nothing; fall through to the extension.
            mime == "application/octet-stream" -> null
            else -> null
        }
    }

    private fun fromExtension(fileExtension: String?): MediaKind {
        // Accepts ".mp4", "mp4", "MP4", and a whole filename ("clip.final.mp4").
        val ext = fileExtension?.trim()?.lowercase()?.substringAfterLast('.')?.takeIf { it.isNotEmpty() }
            ?: return MediaKind.FILE
        return when (ext) {
            in VIDEO_EXTENSIONS -> MediaKind.VIDEO
            in AUDIO_EXTENSIONS -> MediaKind.AUDIO
            in IMAGE_EXTENSIONS -> MediaKind.IMAGE
            in DOCUMENT_EXTENSIONS -> MediaKind.DOCUMENT
            else -> MediaKind.FILE
        }
    }
}
