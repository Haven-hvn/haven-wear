package haven.mobile.core.arkiv

import haven.mobile.core.domain.ArkivStatus
import haven.mobile.core.domain.ContentCacheStatus
import haven.mobile.core.domain.MediaItem
import haven.mobile.core.domain.MediaKind
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * 2.0 entities carry no timestamp attribute — only the creation block — so wall-clock dates
 * resolve from the block header. Anything unresolvable reads null and the item keeps its
 * honest "unknown"; items that already parsed a real date never trigger a lookup.
 */
class BlockTimestampTest {

    private val client = ArkivClientImpl(ArkivConfig(endpointUrl = "https://example.com"))

    @Test
    fun `hex quantity parses to wall-clock time`() {
        val parsed = with(client) { parseBlockTimestamp(JSONObject().put("timestamp", "0x6aade9bc")) }

        assertEquals(Instant.fromEpochSeconds(1789782460), parsed)
    }

    @Test
    fun `decimal timestamp parses`() {
        val parsed = with(client) { parseBlockTimestamp(JSONObject().put("timestamp", "1789782460")) }

        assertEquals(Instant.fromEpochSeconds(1789782460), parsed)
    }

    @Test
    fun `missing block reads null`() {
        with(client) {
            assertNull(parseBlockTimestamp(null))
            assertNull(parseBlockTimestamp(JSONObject()))
            assertNull(parseBlockTimestamp(JSONObject().put("timestamp", "")))
        }
    }

    @Test
    fun `malformed and non-positive timestamps read null`() {
        with(client) {
            assertNull(parseBlockTimestamp(JSONObject().put("timestamp", "xyz")))
            assertNull(parseBlockTimestamp(JSONObject().put("timestamp", "0xzz")))
            assertNull(parseBlockTimestamp(JSONObject().put("timestamp", "0x0")))
            assertNull(parseBlockTimestamp(JSONObject().put("timestamp", "0")))
            assertNull(parseBlockTimestamp(JSONObject().put("timestamp", "-5")))
        }
    }

    @Test
    fun `items with real dates pass through without lookups`() {
        runBlocking {
            val items = listOf(sample("a", "2024-05-01T00:00:00Z"), sample("b", "2024-06-01T00:00:00Z"))

            val resolved = with(client) { resolveCreatedAt(items) }

            assertEquals(items, resolved)
        }
    }

    private fun sample(id: String, createdAt: String) = MediaItem(
        id = id, kind = MediaKind.VIDEO, owner = "0xabc", title = "t$id", description = null,
        mimeType = "video/mp4", fileExtension = ".mp4", filenameHint = null, sizeBytes = null,
        createdAt = Instant.parse(createdAt), createdAtBlock = 100, expiresAtBlock = null,
        pieceRef = null, filecoinCid = null, encryptedCid = null, cidHash = null,
        gate = null, isEncrypted = false, encryptionMetadata = null, cidEncryptionMetadata = null,
        attestation = null, arkivStatus = ArkivStatus.FRESH, contentCacheStatus = ContentCacheStatus.UNCACHED,
        lastAccessedAt = null,
    )
}
