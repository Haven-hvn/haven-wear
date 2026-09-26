package haven.mobile.core.domain

import kotlinx.datetime.Instant
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LibraryLabelsTest {
    @Test
    fun `own upload by creator address reads My contribution`() {
        val item = item(creator = "0xABC", owner = "0xother")
        assertEquals("My contribution", item.provenanceLabel("0xabc"))
    }

    @Test
    fun `own upload falls back to owner address`() {
        val item = item(creator = null, owner = "0xabc")
        assertEquals("My contribution", item.provenanceLabel("0xABC"))
    }

    @Test
    fun `everything else is The pool`() {
        val item = item(creator = "0xother", owner = "0xother")
        assertEquals("The pool", item.provenanceLabel("0xabc"))
        assertEquals("The pool", item.provenanceLabel(null))
    }

    @Test
    fun `shade ladder answers who can open this`() {
        assertEquals("Everyone", item(encrypted = false).shadeLabel())
        assertEquals("Members", item(gate = TokenGate("base", "0xtoken", 1.0)).shadeLabel())
        assertEquals(
            "Collectors",
            item(gate = TokenGate("base", "0xnft", 1.0, TokenStandard.ERC721)).shadeLabel(),
        )
        assertEquals(
            "Collectors",
            item(gate = TokenGate("base", "0xnft", 1.0, TokenStandard.ERC1155)).shadeLabel(),
        )
        assertEquals("Only you", item(encrypted = true, gate = null).shadeLabel())
    }

    @Test
    fun `caption order is provenance first shade second`() {
        val item = item(creator = "0xabc", gate = TokenGate("base", "0xtoken", 1.0))
        assertEquals("My contribution · Members", item.captionLine("0xabc"))
        assertEquals("The pool · Members", item.captionLine("0xother"))
    }

    @Test
    fun `creator without address is not yet verified`() {
        assertFalse(item(creator = null).isCreatorVerifiable())
        assertTrue(item(creator = "0xabc").isCreatorVerifiable())
    }

    @Test
    fun `gate requirement names the token`() {
        assertEquals(
            "Requires the 0xtoken token",
            item(gate = TokenGate("base", "0xtoken", 1.0)).gateRequirementLine(),
        )
        assertEquals("Requires the gate token", item(encrypted = true).gateRequirementLine())
    }

    private fun item(
        creator: String? = "0xother",
        owner: String = "0xother",
        encrypted: Boolean = true,
        gate: TokenGate? = null,
    ) = MediaItem(
        id = "id",
        kind = MediaKind.VIDEO,
        owner = owner,
        title = "title",
        description = null,
        mimeType = null,
        fileExtension = null,
        filenameHint = null,
        sizeBytes = null,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        createdAtBlock = null,
        expiresAtBlock = null,
        pieceRef = null,
        filecoinCid = null,
        encryptedCid = null,
        cidHash = null,
        gate = gate,
        isEncrypted = encrypted,
        encryptionMetadata = null,
        cidEncryptionMetadata = null,
        attestation = null,
        arkivStatus = ArkivStatus.FRESH,
        contentCacheStatus = ContentCacheStatus.CACHED,
        lastAccessedAt = null,
        creatorAddress = creator,
    )
}
