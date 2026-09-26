package haven.wear.library

import cloud.filecoin.foc.cache.FocChain
import cloud.filecoin.foc.cache.PieceRef
import haven.mobile.core.domain.ArkivStatus
import haven.mobile.core.domain.ContentCacheStatus
import haven.mobile.core.domain.GateMetadata
import haven.mobile.core.domain.MediaItem
import haven.mobile.core.domain.MediaKind
import haven.mobile.core.domain.TokenGate
import haven.mobile.core.domain.TokenStandard
import kotlinx.datetime.Instant
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger

/**
 * The library as it was last seen, as JSON — so the watch opens straight into its music.
 *
 * Public data only: Arkiv metadata, gate conditions, balances already read. No key, no plaintext.
 * Field mapping follows haven-mobile's `MediaRepositoryImpl` mirror entity, and the gate codec is its
 * `jsonFromGateMetadata` / `parseGateMetadata` byte-for-byte. Attestations are not kept: nothing on
 * the watch reads them and the next refresh brings them back.
 */
internal object LibraryCodec {

    private const val VERSION = 1

    fun encode(library: Library): String = JSONObject()
        .put("v", VERSION)
        .put("updatedAt", library.updatedAtMillis)
        .put("titles", JSONObject(library.collectionTitles as Map<*, *>))
        .put(
            "tracks",
            JSONArray().apply {
                library.tracks.forEach { track ->
                    put(
                        JSONObject()
                            .put("item", encodeItem(track.item))
                            .put("access", encodeAccess(track.access))
                            .putOpt("collection", track.collectionKey),
                    )
                }
            },
        )
        .toString()

    /** Null when unreadable or from another version; the caller then waits for a refresh. */
    fun decode(json: String): Library? = runCatching {
        val root = JSONObject(json)
        if (root.optInt("v") != VERSION) return null
        val titlesObj = root.optJSONObject("titles") ?: JSONObject()
        val titles = titlesObj.keys().asSequence().associateWith { titlesObj.getString(it) }
        val array = root.getJSONArray("tracks")
        val tracks = (0 until array.length()).map { i ->
            val t = array.getJSONObject(i)
            Track(
                item = decodeItem(t.getJSONObject("item")),
                access = decodeAccess(t.getJSONObject("access")),
                collectionKey = t.optString("collection").takeIf { it.isNotEmpty() },
            )
        }
        Library(tracks, titles, root.optLong("updatedAt"))
    }.getOrNull()

    private fun encodeAccess(access: Access): JSONObject = when (access) {
        Access.Open -> JSONObject().put("t", "open")
        is Access.NeedsMore -> JSONObject().put("t", "more")
            .put("required", access.required.toPlainString())
            .put("held", access.held.toPlainString())
            .putOpt("symbol", access.symbol)
        is Access.Drip -> JSONObject().put("t", "drip")
            .put("required", access.required.toString())
            .putOpt("actual", access.actual?.toString())
    }

    private fun decodeAccess(obj: JSONObject): Access = when (obj.optString("t")) {
        "more" -> Access.NeedsMore(
            BigDecimal(obj.getString("required")),
            BigDecimal(obj.getString("held")),
            obj.optString("symbol").takeIf { it.isNotEmpty() },
        )
        "drip" -> Access.Drip(
            BigInteger(obj.getString("required")),
            obj.optString("actual").takeIf { it.isNotEmpty() }?.let(::BigInteger),
        )
        else -> Access.Open
    }

    private fun encodeItem(item: MediaItem): JSONObject = JSONObject().apply {
        put("id", item.id)
        put("kind", item.kind.name)
        put("owner", item.owner)
        put("title", item.title)
        putOpt("description", item.description)
        putOpt("mimeType", item.mimeType)
        putOpt("fileExtension", item.fileExtension)
        putOpt("filenameHint", item.filenameHint)
        putOpt("sizeBytes", item.sizeBytes)
        put("createdAt", item.createdAt.toString())
        putOpt("createdAtBlock", item.createdAtBlock)
        putOpt("expiresAtBlock", item.expiresAtBlock)
        item.pieceRef?.let { ref ->
            put(
                "piece",
                JSONObject()
                    .put("cid", ref.pieceCid)
                    .put("size", ref.size)
                    .put("providers", JSONArray(ref.providerServiceUrls))
                    .putOpt("wallet", ref.walletAddress)
                    .put("cdn", ref.cdnEnabled)
                    .put("chain", ref.chain.name)
                    .put("ipfs", ref.ipfsIndexed)
                    .putOpt("unixFsRoot", ref.unixFsRoot)
                    .put("gateways", JSONArray(ref.trustlessGateways)),
            )
        }
        putOpt("filecoinCid", item.filecoinCid)
        putOpt("encryptedCid", item.encryptedCid)
        putOpt("cidHash", item.cidHash)
        item.gate?.let { gate ->
            put(
                "gate",
                JSONObject()
                    .put("chain", gate.chain)
                    .put("token", gate.tokenAddress)
                    .put("threshold", gate.threshold)
                    .put("standard", gate.tokenStandard.name),
            )
        }
        put("isEncrypted", item.isEncrypted)
        item.encryptionMetadata?.let { put("encryption", jsonFromGateMetadata(it)) }
        item.cidEncryptionMetadata?.let { put("cidEncryption", jsonFromGateMetadata(it)) }
        put("arkivStatus", item.arkivStatus.name)
        putOpt("durationSeconds", item.durationSeconds)
        putOpt("creatorHandle", item.creatorHandle)
        putOpt("creatorAddress", item.creatorAddress)
    }

    private fun decodeItem(o: JSONObject): MediaItem {
        val piece = o.optJSONObject("piece")?.let { p ->
            PieceRef(
                pieceCid = p.getString("cid"),
                size = p.optLong("size"),
                providerServiceUrls = p.optJSONArray("providers").strings(),
                walletAddress = p.optStringOrNull("wallet"),
                cdnEnabled = p.optBoolean("cdn"),
                chain = runCatching { FocChain.valueOf(p.getString("chain")) }.getOrDefault(FocChain.MAINNET),
                ipfsIndexed = p.optBoolean("ipfs"),
                unixFsRoot = p.optStringOrNull("unixFsRoot"),
                trustlessGateways = p.optJSONArray("gateways").strings(),
            )
        }
        val gate = o.optJSONObject("gate")?.let { g ->
            TokenGate(
                chain = g.getString("chain"),
                tokenAddress = g.optString("token"),
                threshold = g.optDouble("threshold", 0.0),
                tokenStandard = runCatching { TokenStandard.valueOf(g.getString("standard")) }.getOrDefault(TokenStandard.ERC20),
            )
        }
        return MediaItem(
            id = o.getString("id"),
            kind = runCatching { MediaKind.valueOf(o.getString("kind")) }.getOrDefault(MediaKind.AUDIO),
            owner = o.getString("owner"),
            title = o.optString("title"),
            description = o.optStringOrNull("description"),
            mimeType = o.optStringOrNull("mimeType"),
            fileExtension = o.optStringOrNull("fileExtension"),
            filenameHint = o.optStringOrNull("filenameHint"),
            sizeBytes = o.optLongOrNull("sizeBytes"),
            createdAt = Instant.parse(o.getString("createdAt")),
            createdAtBlock = o.optLongOrNull("createdAtBlock"),
            expiresAtBlock = o.optLongOrNull("expiresAtBlock"),
            pieceRef = piece,
            filecoinCid = o.optStringOrNull("filecoinCid"),
            encryptedCid = o.optStringOrNull("encryptedCid"),
            cidHash = o.optStringOrNull("cidHash"),
            gate = gate,
            isEncrypted = o.optBoolean("isEncrypted"),
            encryptionMetadata = o.optStringOrNull("encryption")?.let(::parseGateMetadata),
            cidEncryptionMetadata = o.optStringOrNull("cidEncryption")?.let(::parseGateMetadata),
            attestation = null,
            arkivStatus = runCatching { ArkivStatus.valueOf(o.getString("arkivStatus")) }.getOrDefault(ArkivStatus.FRESH),
            contentCacheStatus = ContentCacheStatus.UNCACHED,
            lastAccessedAt = null,
            durationSeconds = o.optLongOrNull("durationSeconds"),
            creatorHandle = o.optStringOrNull("creatorHandle"),
            creatorAddress = o.optStringOrNull("creatorAddress"),
        )
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) getString(key) else null

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (has(key) && !isNull(key)) getLong(key) else null

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).map { getString(it) }
}

// ── Gate metadata codec: verbatim from haven-mobile core-cache-mirror/MediaRepositoryImpl.kt ──

internal fun jsonFromGateMetadata(metadata: GateMetadata): String {
    val obj = JSONObject()
    when (metadata) {
        is GateMetadata.V1 -> {
            obj.put("type", "V1")
            obj.put("wrappedKey", metadata.wrappedKey.toString(Charsets.UTF_8))
            obj.put("nonce", metadata.nonce)
        }
        is GateMetadata.V3 -> {
            obj.put("type", "V3")
            obj.put("epochId", metadata.epochId)
            obj.put("wrappedKey", metadata.wrappedKey.toString(Charsets.UTF_8))
            obj.put("gateReference", metadata.gateReference)
        }
        is GateMetadata.V4 -> {
            obj.put("type", "V4")
            obj.put("epochId", metadata.epochId)
            obj.put("marketCapTargetUsd", metadata.marketCapTargetUsd)
            obj.put("wrappedKey", metadata.wrappedKey.toString(Charsets.UTF_8))
            obj.put("gateReference", metadata.gateReference)
            obj.put("tokenAddress", metadata.tokenAddress)
            obj.put("chain", metadata.chain)
        }
        is GateMetadata.Sealed -> {
            obj.put("type", "Sealed")
            obj.put("version", metadata.version)
            obj.put("encryptedAesKey", metadata.encryptedAesKey)
            obj.put("cid", metadata.cid)
            obj.put("chain", metadata.chain)
            obj.put("tokenAddress", metadata.tokenAddress)
            obj.put("threshold", metadata.threshold)
            metadata.epoch?.let { obj.put("epoch", it) }
            metadata.marketCapTarget?.let { obj.put("marketCapTarget", it) }
            if (metadata.oracleAddress.isNotEmpty()) obj.put("oracleAddress", metadata.oracleAddress)
            metadata.attributeGateType?.let { obj.put("attributeGateType", it) }
        }
    }
    return obj.toString()
}

/** Absent, null or negative -> null; 0 is a value (threshold-zero epochs, zero targets). */
private fun JSONObject.optNatOrNull(key: String): Long? =
    if (has(key) && !isNull(key)) optLong(key, -1).takeIf { it >= 0 } else null

internal fun parseGateMetadata(json: String): GateMetadata {
    val obj = JSONObject(json)
    return when (obj.optString("type", "V1")) {
        "V3" -> GateMetadata.V3(
            epochId = obj.getLong("epochId"),
            wrappedKey = obj.getString("wrappedKey").toByteArray(Charsets.UTF_8),
            gateReference = obj.getString("gateReference"),
        )
        "V4" -> GateMetadata.V4(
            epochId = obj.optLong("epochId", 0),
            marketCapTargetUsd = obj.optLong("marketCapTargetUsd", 0),
            wrappedKey = obj.optString("wrappedKey", "").toByteArray(Charsets.UTF_8),
            gateReference = obj.optString("gateReference", ""),
            tokenAddress = obj.optString("tokenAddress", ""),
            chain = obj.optString("chain", ""),
        )
        "Sealed" -> GateMetadata.Sealed(
            version = obj.optLong("version", 0),
            encryptedAesKey = obj.optString("encryptedAesKey", ""),
            cid = obj.optString("cid", ""),
            chain = obj.optString("chain", ""),
            tokenAddress = obj.optString("tokenAddress", ""),
            threshold = obj.optString("threshold", ""),
            epoch = obj.optNatOrNull("epoch"),
            marketCapTarget = obj.optNatOrNull("marketCapTarget"),
            oracleAddress = obj.optString("oracleAddress", ""),
            attributeGateType = obj.optNatOrNull("attributeGateType"),
        )
        else -> GateMetadata.V1(
            wrappedKey = obj.getString("wrappedKey").toByteArray(Charsets.UTF_8),
            nonce = obj.getString("nonce"),
        )
    }
}
