package haven.wear.library

import haven.mobile.core.collections.Holding
import haven.mobile.core.collections.decodeAbiString
import haven.mobile.core.collections.requiredUnits
import haven.mobile.core.domain.ArkivStatus
import haven.mobile.core.domain.ContentCacheStatus
import haven.mobile.core.domain.GateMetadata
import haven.mobile.core.domain.MediaItem
import haven.mobile.core.domain.MediaKind
import haven.mobile.core.domain.TokenGate
import haven.mobile.core.domain.TokenStandard
import cloud.filecoin.foc.cache.PieceRef
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.BigInteger

/** Library model, snapshot codec, and the balance/symbol decoding the library is built from. */
class LibraryTest {

    private fun item(id: String, title: String, gate: TokenGate? = null, sealed: GateMetadata? = null, creator: String? = null) = MediaItem(
        id = id, kind = MediaKind.AUDIO, owner = "0x" + "ab".repeat(20), title = title, description = null,
        mimeType = "audio/mpeg", fileExtension = "mp3", filenameHint = null, sizeBytes = 4_000_000,
        createdAt = Instant.parse("2026-01-02T03:04:05Z"), createdAtBlock = 10, expiresAtBlock = null,
        pieceRef = PieceRef(pieceCid = "baga$id", size = 4_000_100, providerServiceUrls = listOf("https://sp.example")),
        filecoinCid = null, encryptedCid = null, cidHash = null, gate = gate, isEncrypted = gate != null,
        encryptionMetadata = sealed, cidEncryptionMetadata = null, attestation = null,
        arkivStatus = ArkivStatus.FRESH, contentCacheStatus = ContentCacheStatus.UNCACHED, lastAccessedAt = null,
        durationSeconds = 212, creatorHandle = creator,
    )

    private val gate = TokenGate("eip155:8453", "0xAa70bC79fD1cB4a6FBA717018351F0C3c64B79Df", 5.0, TokenStandard.ERC20)
    private val drip = GateMetadata.Sealed(
        version = 4, encryptedAesKey = "k", cid = "bafy", chain = "BaseMainnet", tokenAddress = gate.tokenAddress,
        threshold = "0", epoch = 670, marketCapTarget = 5, oracleAddress = "0x" + "cc".repeat(20), attributeGateType = 4,
    )

    private val library = Library(
        tracks = listOf(
            Track(item("b", "Blue", gate, creator = "moth.eth"), Access.Open, "BaseMainnet:${gate.tokenAddress.lowercase()}"),
            Track(item("a", "Amber", gate), Access.NeedsMore(BigDecimal("5"), BigDecimal("2.5"), "HVN"), "BaseMainnet:${gate.tokenAddress.lowercase()}"),
            Track(item("c", "Cinder", gate, drip), Access.Drip(BigInteger.valueOf(5), BigInteger.valueOf(2)), "BaseMainnet:${gate.tokenAddress.lowercase()}"),
            Track(item("d", "Dusk"), Access.Open, null),
        ),
        collectionTitles = mapOf("BaseMainnet:${gate.tokenAddress.lowercase()}" to "HVN"),
        updatedAtMillis = 1234,
    )

    @Test
    fun `collections group by gate, playable first, titled by symbol`() {
        val c = library.collections.single()
        assertEquals("HVN", c.title)
        assertEquals(listOf("Blue", "Amber", "Cinder"), c.tracks.map { it.title })
        assertEquals(1, c.playableCount)
    }

    @Test
    fun `artists use the creator handle, else a short address`() {
        val names = library.artists.map { it.name }.toSet()
        assertTrue("moth.eth" in names)
        assertTrue("0xabab…abab" in names)
    }

    @Test
    fun `drip progress is actual over required, clamped`() {
        assertEquals(0.4f, Access.Drip(BigInteger.valueOf(5), BigInteger.valueOf(2)).progress, 1e-6f)
        assertEquals(1f, Access.Drip(BigInteger.valueOf(5), BigInteger.valueOf(9)).progress, 1e-6f)
        assertEquals(0f, Access.Drip(BigInteger.valueOf(5), null).progress, 1e-6f)
    }

    @Test
    fun `snapshot round-trips tracks, access, gates and v4 seals`() {
        val decoded = LibraryCodec.decode(LibraryCodec.encode(library))!!
        assertEquals(library.tracks.map { it.id }, decoded.tracks.map { it.id })
        assertEquals(library.tracks.map { it.access }, decoded.tracks.map { it.access })
        assertEquals(library.collectionTitles, decoded.collectionTitles)
        val cinder = decoded.track("c")!!.item
        assertEquals(drip, cinder.encryptionMetadata)
        assertEquals(gate, cinder.gate)
        assertEquals("bagac", cinder.pieceRef?.pieceCid)
        assertTrue(cinder.isDrip)
        assertNull(decoded.track("d")!!.item.gate)
    }

    @Test
    fun `unknown snapshot versions are ignored`() {
        assertNull(LibraryCodec.decode("""{"v":99,"tracks":[]}"""))
        assertNull(LibraryCodec.decode("not json"))
    }

    @Test
    fun `threshold scales by decimals, collections count items`() {
        assertEquals(BigInteger("5000000000000000000"), requiredUnits(5.0, 18))
        assertEquals(BigInteger.valueOf(2), requiredUnits(2.0, null))
        assertTrue(Holding(BigInteger.valueOf(2), null, "NOUN").meets(1.0))
        assertFalse(Holding(BigInteger("4999999999999999999"), 18, "HVN").meets(5.0))
        assertEquals(BigDecimal("2.5"), Holding(BigInteger("2500000000000000000"), 18, "HVN").wholeUnits.stripTrailingZeros())
    }

    @Test
    fun `symbol decodes ABI string and legacy bytes32`() {
        val abi = "0x" +
            "0000000000000000000000000000000000000000000000000000000000000020" +
            "0000000000000000000000000000000000000000000000000000000000000003" +
            "48564e0000000000000000000000000000000000000000000000000000000000"
        assertEquals("HVN", decodeAbiString(abi))
        assertEquals("MKR", decodeAbiString("0x4d4b520000000000000000000000000000000000000000000000000000000000"))
        assertNull(decodeAbiString("0x"))
    }
}
