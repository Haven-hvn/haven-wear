package haven.wear.playback

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeBytes

/**
 * Chapter parsing for merged single-file albums: the Herald pipeline embeds one ID3 `CHAP`
 * per track plus a `CTOC` table, and the chapters screen turns them into a seekable track list.
 *
 * The headline fixture is not hand-built: it is the verbatim 171-byte tag ffmpeg 4.4.8
 * (`Lavf58.62.100`) wrote for a two-chapter MP3, so the happy path pins the real producer.
 * Everything else pins the parser's failure softness — chapters must never break playback.
 */
class ChaptersTest {

    @Test
    fun `real ffmpeg tag yields ordered titled chapters`() {
        val chapters = parseId3Chapters(REAL_FFMPEG_TAG)

        assertEquals(2, chapters.size)
        assertEquals(AudioChapter(startMs = 0L, title = "Track One"), chapters[0])
        assertEquals(AudioChapter(startMs = 2000L, title = "Track Two"), chapters[1])
    }

    @Test
    fun `ctoc order wins over storage order`() {
        val tag = tagV3(
            chapV3("ch0", 0, 5000, "First"),
            chapV3("ch1", 5000, 9000, "Second"),
            ctocV3("toc", listOf("ch1", "ch0")),
        )

        val chapters = parseId3Chapters(tag)

        assertEquals(listOf("Second", "First"), chapters.map { it.title })
        assertEquals(listOf(5000L, 0L), chapters.map { it.startMs })
    }

    @Test
    fun `chapters without a table sort by start time`() {
        val tag = tagV3(
            chapV3("late", 9000, 12000, "Late"),
            chapV3("early", 0, 3000, "Early"),
        )

        val chapters = parseId3Chapters(tag)

        assertEquals(listOf("Early", "Late"), chapters.map { it.title })
    }

    @Test
    fun `chapters missing from the table append by start time`() {
        val tag = tagV3(
            chapV3("ch0", 4000, 6000, "Unlisted"),
            chapV3("ch1", 0, 2000, "Listed"),
            ctocV3("toc", listOf("ch1")),
        )

        assertEquals(listOf("Listed", "Unlisted"), parseId3Chapters(tag).map { it.title })
    }

    @Test
    fun `v24 frame sizes read syncsafe`() {
        // A 200-char title pushes the TIT2 past 127 bytes, where syncsafe and plain u32
        // disagree — a plain-u32 read would overrun the CHAP and lose the title.
        val longTitle = "A".repeat(200)
        val tag = tagV4(chapV4("ch0", 0, 42000, longTitle))

        val chapters = parseId3Chapters(tag)

        assertEquals(1, chapters.size)
        assertEquals(longTitle, chapters[0].title)
    }

    @Test
    fun `utf16 chapter title decodes`() {
        val title = "Solitude".toByteArray(Charsets.UTF_16LE)
        val bom: ByteArray = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val tit2 = frameV3("TIT2", byteArrayOf(0x01) + bom + title + byteArrayOf(0, 0))
        val tag = tagV3(chapFrameV3("ch0", 0, 3000, tit2))

        assertEquals("Solitude", parseId3Chapters(tag).single().title)
    }

    @Test
    fun `unsynchronised tag still parses`() {
        // ÿ is 0xFF in Latin-1, so the unsync encoding stores it as FF 00.
        val tit2 = frameV3("TIT2", byteArrayOf(0x00) + "AÿB".toByteArray(Charsets.ISO_8859_1))
        val chap = chapFrameV3("ch0", 0, 3000, tit2)
        val encoded = chap.flatMap { if (it == 0xFF.toByte()) listOf(it, 0.toByte()) else listOf(it) }
        val tag = tagHeader(major = 3, flags = 0x80, bodySize = encoded.size) + encoded.toByteArray()

        assertEquals("AÿB", parseId3Chapters(tag).single().title)
    }

    @Test
    fun `chapter without a title falls back to its element id`() {
        val tag = tagV3(chapFrameV3("ch7", 1000, 2000, ByteArray(0)))

        assertEquals(AudioChapter(startMs = 1000L, title = "ch7"), parseId3Chapters(tag).single())
    }

    @Test
    fun `non-chapter files parse as no chapters, never a crash`() {
        assertTrue(parseId3Chapters(ByteArray(0)).isEmpty())
        assertTrue(parseId3Chapters("not a tag at all........".toByteArray()).isEmpty())
        // v2.2: three-letter frames, no CHAP — nothing to find.
        assertTrue(parseId3Chapters(tagHeader(major = 2, flags = 0, bodySize = 0)).isEmpty())
        // Truncated mid-frame: the header promises more than the bytes hold.
        val tag = tagV3(chapV3("ch0", 0, 3000, "Cut"))
        assertTrue(parseId3Chapters(tag.copyOf(30)).isEmpty())
    }

    @Test
    fun `file read parses the tag without loading the audio`(@TempDir dir: Path) {
        val file = dir.resolve("album.mp3").toFile()
        // A megabyte of fake audio after the tag: the read must stop at the tag end.
        file.writeBytes(REAL_FFMPEG_TAG + ByteArray(1024 * 1024))

        val chapters = readId3Chapters(file)

        assertEquals(listOf("Track One", "Track Two"), chapters.map { it.title })
    }

    @Test
    fun `file read fails soft on missing or tiny files`(@TempDir dir: Path) {
        assertTrue(readId3Chapters(dir.resolve("absent.mp3").toFile()).isEmpty())
        val tiny = dir.resolve("tiny.mp3").toFile().apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertTrue(readId3Chapters(tiny).isEmpty())
    }

    @Test
    fun `chapter index tracks position across boundaries`() {
        val chapters = listOf(
            AudioChapter(0L, "One"),
            AudioChapter(2000L, "Two"),
            AudioChapter(4000L, "Three"),
        )

        assertEquals(0, chapterIndexAt(chapters, 0L))
        assertEquals(0, chapterIndexAt(chapters, 1999L))
        assertEquals(1, chapterIndexAt(chapters, 2000L))
        assertEquals(2, chapterIndexAt(chapters, 99_999L))
        assertEquals(0, chapterIndexAt(chapters, -50L))
    }

    @Test
    fun `chapter index needs no ordering and empties to none`() {
        val shuffled = listOf(AudioChapter(5000L, "B"), AudioChapter(0L, "A"))

        assertEquals(1, chapterIndexAt(shuffled, 100L))
        assertEquals(0, chapterIndexAt(shuffled, 6000L))
        assertEquals(-1, chapterIndexAt(emptyList(), 1000L))
    }

    // ── Fixture builders ────────────────────────────────────────────────────────────

    private fun tagHeader(major: Int, flags: Int, bodySize: Int): ByteArray {
        val header = "ID3".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(major.toByte(), 0, flags.toByte())
        return header + syncsafe(bodySize)
    }

    private fun tagV3(vararg frames: ByteArray): ByteArray {
        val body = frames.reduce { acc, frame -> acc + frame }
        return tagHeader(major = 3, flags = 0, bodySize = body.size) + body
    }

    private fun tagV4(vararg frames: ByteArray): ByteArray {
        val body = frames.reduce { acc, frame -> acc + frame }
        return tagHeader(major = 4, flags = 0, bodySize = body.size) + body
    }

    private fun frameV3(id: String, data: ByteArray): ByteArray =
        id.toByteArray(Charsets.ISO_8859_1) + u32(data.size) + byteArrayOf(0, 0) + data

    private fun frameV4(id: String, data: ByteArray): ByteArray =
        id.toByteArray(Charsets.ISO_8859_1) + syncsafe(data.size) + byteArrayOf(0, 0) + data

    private fun tit2V3(title: String): ByteArray =
        frameV3("TIT2", byteArrayOf(0x03) + title.toByteArray(Charsets.UTF_8) + byteArrayOf(0))

    private fun tit2V4(title: String): ByteArray =
        frameV4("TIT2", byteArrayOf(0x03) + title.toByteArray(Charsets.UTF_8) + byteArrayOf(0))

    private fun chapFrameV3(elementId: String, startMs: Int, endMs: Int, subframes: ByteArray): ByteArray {
        val body = elementId.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) +
            u32(startMs) + u32(endMs) + u32(-1) + u32(-1) + subframes
        return frameV3("CHAP", body)
    }

    private fun chapV3(elementId: String, startMs: Int, endMs: Int, title: String): ByteArray =
        chapFrameV3(elementId, startMs, endMs, tit2V3(title))

    private fun chapV4(elementId: String, startMs: Int, endMs: Int, title: String): ByteArray {
        val body = elementId.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) +
            u32(startMs) + u32(endMs) + u32(-1) + u32(-1) + tit2V4(title)
        return frameV4("CHAP", body)
    }

    private fun ctocV3(elementId: String, entries: List<String>): ByteArray {
        var body = elementId.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0, 0x03, entries.size.toByte())
        for (entry in entries) body += entry.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0)
        return frameV3("CTOC", body)
    }

    private fun u32(value: Int): ByteArray = byteArrayOf(
        (value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte(),
    )

    private fun syncsafe(value: Int): ByteArray = byteArrayOf(
        (value shr 21).toByte(), (value shr 14).toByte(), (value shr 7).toByte(), value.toByte(),
    )

    companion object {
        private fun hex(text: String): ByteArray {
            val clean = text.filterNot { it.isWhitespace() }
            return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }

        /**
         * Verbatim tag ffmpeg 4.4.8 wrote for a two-chapter MP3 (2s + 2s sine tones,
         * "Track One" / "Track Two"): ID3v2.4, `TSSE` + `CTOC` + two `CHAP`, 10 bytes
         * of padding. Regenerate with any ffmpeg: concat two MP3s, attach an
         * FFMETADATA chapter file, take the first 171 bytes.
         */
        private val REAL_FFMPEG_TAG = hex(
            "49443304000000000121545353450000000f0000034c61766635382e37362e31" +
                "30300043544f430000000e0000746f6300030263683000636831004348415000" +
                "00002900006368300000000000000007d0ffffffffffffffff54495432000000" +
                "0b000003547261636b204f6e65004348415000000029000063683100000007d0" +
                "00000fa0ffffffffffffffff544954320000000b000003547261636b2054776f" +
                "0000000000000000000000",
        )
    }
}
