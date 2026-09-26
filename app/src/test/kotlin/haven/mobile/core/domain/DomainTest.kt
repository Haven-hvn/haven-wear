package haven.mobile.core.domain

import kotlinx.datetime.Instant
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import cloud.filecoin.foc.cache.PieceRef

class GateMetadataTest {
    @Test
    fun `V1 holds wrappedKey and nonce`() {
        val m = GateMetadata.V1(wrappedKey = byteArrayOf(1,2,3), nonce = "abc")
        assertEquals("abc", (m as GateMetadata.V1).nonce)
        assertArrayEquals(byteArrayOf(1,2,3), m.wrappedKey)
    }
    @Test
    fun `V3 holds epochId and gateReference`() {
        val m = GateMetadata.V3(epochId = 42, wrappedKey = byteArrayOf(9), gateReference = "ref")
        assertEquals(42, (m as GateMetadata.V3).epochId)
    }
}

class MediaKindTest {
    @Test
    fun `MediaItem kind covers all viewers`() {
        val kinds = MediaKind.values()
        assertTrue(kinds.contains(MediaKind.VIDEO))
        assertTrue(kinds.contains(MediaKind.FILE))
        assertEquals(5, kinds.size)
    }
}

class PieceRefTest {
    @Test
    fun `PieceRef requires providerServiceUrls`() {
        val ref = PieceRef(pieceCid = "bafkzcib123", size = 1024, providerServiceUrls = emptyList())
        assertEquals("bafkzcib123", ref.pieceCid)
    }
}

class ContentCacheStatusTest {
    @Test
    fun `all cache statuses present per tokens`() {
        val vals = ContentCacheStatus.values().map { it.name }
        assertTrue(vals.contains("CACHED"))
        assertTrue(vals.contains("UNCACHED"))
    }
}
