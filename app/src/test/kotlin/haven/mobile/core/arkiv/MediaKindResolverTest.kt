package haven.mobile.core.arkiv

import haven.mobile.core.domain.MediaKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * This mapping picks the renderer, so a wrong answer is a user staring at the wrong viewer — or at
 * a "save this file" screen for a video. Cases below are the ones real publishers produce.
 */
class MediaKindResolverTest {

    @Test
    fun `mime type wins over extension`() {
        // Publishers sometimes ship a .bin/.dat blob with a correct MIME. Trust the MIME.
        assertEquals(
            MediaKind.VIDEO,
            MediaKindResolver.resolve(mimeType = "video/mp4", fileExtension = ".bin"),
        )
    }

    @Test
    fun `mime parameters are ignored`() {
        assertEquals(
            MediaKind.VIDEO,
            MediaKindResolver.resolve(mimeType = "video/mp4; codecs=\"avc1.42E01E\"", fileExtension = null),
        )
    }

    @Test
    fun `pdf maps to document`() {
        assertEquals(
            MediaKind.DOCUMENT,
            MediaKindResolver.resolve(mimeType = "application/pdf", fileExtension = null),
        )
        assertEquals(
            MediaKind.DOCUMENT,
            MediaKindResolver.resolve(mimeType = null, fileExtension = ".pdf"),
        )
    }

    @Test
    fun `octet-stream falls through to the extension`() {
        // This is the common case for content published by the CLI: a generic MIME with a real
        // extension. Trusting the MIME here would send every item to the FILE viewer.
        assertEquals(
            MediaKind.AUDIO,
            MediaKindResolver.resolve(mimeType = "application/octet-stream", fileExtension = "flac"),
        )
    }

    @Test
    fun `extensions are accepted with or without a dot and in any case`() {
        assertEquals(MediaKind.IMAGE, MediaKindResolver.resolve(null, ".PNG"))
        assertEquals(MediaKind.IMAGE, MediaKindResolver.resolve(null, "png"))
        assertEquals(MediaKind.IMAGE, MediaKindResolver.resolve(null, "Png"))
    }

    @Test
    fun `a whole filename resolves by its final extension`() {
        assertEquals(MediaKind.VIDEO, MediaKindResolver.resolve(null, "interview.final.cut.mkv"))
    }

    @Test
    fun `unknown types are generic files`() {
        assertEquals(MediaKind.FILE, MediaKindResolver.resolve(null, ".zip"))
        assertEquals(MediaKind.FILE, MediaKindResolver.resolve("application/zip", ".zip"))
        assertEquals(MediaKind.FILE, MediaKindResolver.resolve(null, null))
    }

    @Test
    fun `blank inputs do not throw`() {
        assertEquals(MediaKind.FILE, MediaKindResolver.resolve("", ""))
        assertEquals(MediaKind.FILE, MediaKindResolver.resolve("   ", "   "))
    }

    @Test
    fun `every media kind is reachable`() {
        // Guards against a future edit that quietly orphans a viewer.
        val reached = setOf(
            MediaKindResolver.resolve("video/webm", null),
            MediaKindResolver.resolve("audio/mpeg", null),
            MediaKindResolver.resolve("image/webp", null),
            MediaKindResolver.resolve("application/pdf", null),
            MediaKindResolver.resolve("application/x-tar", null),
        )
        assertEquals(MediaKind.entries.toSet(), reached)
    }
}
