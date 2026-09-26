package haven.mobile.core.arkiv

import cloud.filecoin.foc.cache.FocChain
import cloud.filecoin.foc.cache.PieceRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

import haven.mobile.core.domain.ArkivStatus
import haven.mobile.core.domain.Attestation
import haven.mobile.core.domain.Community
import haven.mobile.core.domain.ContentCacheStatus
import haven.mobile.core.domain.GateMetadata
import haven.mobile.core.domain.HavenChain
import haven.mobile.core.domain.LaunchStage
import haven.mobile.core.domain.MediaItem
import haven.mobile.core.domain.MerkleProofStep
import haven.mobile.core.domain.selectLiveStages
import haven.mobile.core.domain.TokenGate
import haven.mobile.core.domain.TokenStandard
import haven.mobile.core.domain.error.HavenError

@Singleton
class ArkivClientImpl @Inject constructor(
    private val config: ArkivConfig,
) : ArkivClient {

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(config.timeoutMillis, TimeUnit.MILLISECONDS)
        .readTimeout(config.timeoutMillis, TimeUnit.MILLISECONDS)
        .build()

    /**
     * Block height -> header timestamp. Block timestamps are immutable, so entries never
     * expire or revalidate; a refresh re-resolves only blocks it has never seen.
     */
    private val blockTimestampCache = ConcurrentHashMap<Long, Instant>()

    /**
     * Direct `arkiv_query` JSON-RPC against the chain, byte-compatible with `@arkiv-network/sdk`
     * 0.8 `runQuery` (CLI `ArkivEngineClient.query` sends the same shape).
     *
     * There is no REST gateway in front of Arkiv. Predicates serialize to a query string with
     * type-tagged literals (`gate_token = str('0x…') AND gate_chain = i32(8453)`,
     * `$owner = addr(0x…)`, `$key = key(0x…)`); options carry the full `select` map plus a hex
     * `limit` and an opaque `cursor`; the result is `{data, blockNumber, cursor}`. Iteration
     * ends when `cursor` is absent/empty or the page comes back short.
     *
     * The tagged literals are load-bearing, verified against the live node: bare hex is
     * rejected ("a hex literal must carry its type"), double-quoted strings are a parse error
     * (single quotes only), and the combinators are `AND`/`OR`, not `&&`/`||`.
     */
    private fun arkivQuery(query: String, limit: Int, cursor: String?): JSONObject {
        val options = JSONObject()
            .put("select", JSONObject()
                .put("key", true)
                .put("owner", true)
                .put("creator", true)
                .put("createdAt", true)
                .put("updatedAt", false)
                .put("expiresAt", true)
                .put("creationFlags", false)
                .put("contentType", true)
                .put("payload", true)
                .put("attributeSchema", false)
                .put("attributes", true))
            .put("limit", "0x" + limit.toString(16))
            .apply { cursor?.let { put("cursor", it) } }
        val body = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 1)
            .put("method", "arkiv_query")
            .put("params", JSONArray().put(query).put(options))
            .toString()
            .toRequestBody("application/json".toMediaType())
        httpClient.newCall(Request.Builder().url(config.endpointUrl).post(body).build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw HavenError.NetworkError("Arkiv query failed (${response.code}).")
            }
            val text = response.body?.string()
                ?: throw HavenError.NetworkError("Arkiv query came back empty.")
            val result = runCatching { JSONObject(text).getJSONObject("result") }.getOrNull()
                ?: throw HavenError.NetworkError("Arkiv query came back unreadable.")
            val error = JSONObject(text).optJSONObject("error")
            if (error != null) {
                throw HavenError.NetworkError("Arkiv rejected the query (${error.optString("message")}).")
            }
            return result
        }
    }

    /**
     * `key = str('value')` predicate, exactly like the SDK serializes `eq(name, string)`.
     * Single quotes only — a double-quoted string is a node parse error.
     */
    private fun eqStr(key: String, value: String): String =
        "$key = str('${value.replace("'", "")}')"

    /**
     * `key = i32(123)` predicate for numeric attributes (chain ids), like the SDK's
     * `eq(name, number)`. Bare ints parse, but writers stamp numbers as `i32` and comparison
     * is type-exact, so the tag stays.
     */
    private fun eqNum(key: String, value: Long): String = "$key = i32($value)"

    /**
     * Raw chain row -> the flat shape the item parser reads.
     *
     * The node returns SDK 0.8 `RpcEntity` rows: flat `key`/`owner`/`creator`/`createdAt`/
     * `expiresAt`/`contentType` fields plus `attributes` as `[{name, type, value}]` with
     * type-tagged values. Decoding mirrors CLI `decode_rpc_entity` / SDK
     * `entityFromRpcResult`: block heights land under the `*Block` keys the readers expect,
     * attributes merge in decoded, and anything unknown is skipped rather than guessed.
     *
     * The decoded payload then merges over the attributes — dapp parity
     * (`parseArkivEntityToVideo` spreads `{...attributes, ...payload}`): the piece CID,
     * gate blobs, and locator fields live in the payload, not the attributes, and without
     * the merge every item reads as having no stored content. System/identity keys stay
     * reserved — the row's key/owner/creator are facts about the row, never payload claims.
     */
    internal fun normalizeRpcEntity(raw: JSONObject): JSONObject {
        val out = JSONObject()
        raw.optString("key", null)?.let { out.put("id", it); out.put("key", it); out.put("entityKey", it) }
        raw.optString("owner", null)?.let { out.put("owner", it) }
        raw.optString("creator", null)?.let { out.put("creator", it) }
        raw.optString("contentType", null)?.let { out.put("contentType", it) }
        // Payload is entity metadata JSON (attestations live under `attn`, locators like
        // `piece` and gate blobs beside them); media bytes never touch the chain. Kept as
        // text for the attestation parser, and merged below for every other reader.
        raw.optString("payload", null)
            ?.let { hexToBytesOrNull(it) }
            ?.let { runCatching { it.toString(Charsets.UTF_8) }.getOrNull() }
            ?.takeIf { it.isNotBlank() }
            ?.let { out.put("payloadJson", it) }
        decodeRpcInt(raw.opt("createdAt"))?.let { out.put("createdAtBlock", it) }
        decodeRpcInt(raw.opt("expiresAt"))?.let { out.put("expiresAtBlock", it) }
        val attributes = raw.optJSONArray("attributes")
        if (attributes != null) {
            for (i in 0 until attributes.length()) {
                val entry = attributes.optJSONObject(i) ?: continue
                val name = entry.optString("name", null) ?: continue
                decodeRpcValue(entry.optString("type", null), entry.opt("value"))
                    ?.let { out.put(name, it) }
            }
        }
        out.optString("payloadJson", null)?.takeIf { it.isNotBlank() }?.let { text ->
            val payload = runCatching { JSONObject(text) }.getOrNull() ?: return@let
            payload.keys().forEach { key ->
                if (key !in RESERVED_MERGE_KEYS) out.put(key, payload.opt(key))
            }
        }
        // Row identity wins: a `creator` *attribute* (display name) must never overwrite the
        // creator address the binding check needs, so it is preserved under its own key.
        raw.optString("creator", null)?.let { out.put("creatorAddress", it) }
        return out
    }

    /** Strict hex decode for `0x`-prefixed wire blobs — malformed input fails closed to null. */
    private fun hexToBytesOrNull(hex: String): ByteArray? {
        val clean = hex.trim().removePrefix("0x").takeIf { it.isNotEmpty() } ?: return null
        if (clean.length % 2 != 0) return null
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            val hi = clean[i * 2].digitToIntOrNull(16) ?: return null
            val lo = clean[i * 2 + 1].digitToIntOrNull(16) ?: return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    /**
     * One `{name, type, value}` attribute -> the flat value readers expect.
     *
     * Tags mirror CLI `decode_rpc_value` (`bool`, `i32`, `u64`, `u256`, `dec`, `bytes32`,
     * `str`, `addr`, `key`, `bytes`). Writers stamp strings as `str` and numbers as `i32`
     * (see dapp `lib/arkiv-attrs`), so those two carry every Haven attribute in practice;
     * the rest decode for completeness. Unknown tags and malformed values decode to null
     * and the attribute is skipped — a corrupt row degrades to fewer fields, not a
     * failed page.
     */
    private fun decodeRpcValue(tag: String?, value: Any?): Any? {
        if (value == null || value === JSONObject.NULL) return null
        return when (tag) {
            "bool" -> when (value) {
                is Boolean -> value
                is String -> when (value) {
                    "true" -> true
                    "false" -> false
                    else -> null
                }
                else -> null
            }
            "i32" -> decodeRpcInt(value)
            // u64 can exceed Long.MAX_VALUE; u256 always might. Long when exact, Double
            // otherwise — the threshold reader is Double-based, so nothing downstream breaks.
            "u64", "u256" -> decodeRpcU256(value)
            "dec", "str", "addr", "key", "bytes32" -> value as? String
            // No reader takes raw bytes; keep the 0x spelling so a future one can hex-decode it.
            "bytes" -> (value as? String)?.takeIf { it.startsWith("0x") }
            else -> null
        }
    }

    /** Block heights and `i32` values: a JSON number, `0x` quantity, or decimal string. */
    private fun decodeRpcInt(value: Any?): Long? = when (value) {
        is Number -> value.toLong()
        is String -> {
            val clean = value.trim()
            runCatching {
                if (clean.startsWith("0x", ignoreCase = true)) clean.substring(2).toBigInteger(16).longValueExact()
                else clean.toLong()
            }.getOrNull()
        }
        else -> null
    }

    /** `u64`/`u256` values: exact Long when it fits, Double otherwise (never null on overflow). */
    private fun decodeRpcU256(value: Any?): Any? {
        val big: BigInteger = when (value) {
            is BigInteger -> value
            is Number -> BigInteger.valueOf(value.toLong())
            is String -> {
                val clean = value.trim()
                runCatching {
                    if (clean.startsWith("0x", ignoreCase = true)) clean.substring(2).toBigInteger(16)
                    else clean.toBigInteger()
                }.getOrNull()
            }
            else -> null
        } ?: return null
        return try {
            big.longValueExact()
        } catch (_: ArithmeticException) {
            big.toDouble()
        }
    }

    /** One page of normalized entities plus the next cursor (null when iteration ends). */
    private fun queryPage(
        query: String,
        pageSize: Int,
        cursor: String?,
    ): Triple<List<JSONObject>, String?, Long?> {
        val result = arkivQuery(query, pageSize, cursor)
        val data = result.optJSONArray("data") ?: JSONArray()
        val items = List(data.length()) { idx -> normalizeRpcEntity(data.getJSONObject(idx)) }
        return Triple(items, nextCursorOrNull(result, items.size, pageSize), headBlockOrNull(result))
    }

    /**
     * Chain head from an `arkiv_query` result, or null when the node omits it.
     *
     * Expiry is decided from `expiresAtBlock` against this head (dapp parity — no entity
     * carries its own "expired" flag), so a missing head fails open to "not expired".
     */
    internal fun headBlockOrNull(result: JSONObject): Long? =
        runCatching { decodeRpcInt(result.opt("blockNumber")) }.getOrNull()

    /**
     * Fills in wall-clock creation dates from the chain.
     *
     * 2.0 entities carry no timestamp attribute — only the creation block — so items parse
     * with [createdAt][MediaItem.createdAt] on epoch meaning "unknown". The block header holds
     * the real time, one `eth_getBlockByNumber` per distinct block: those resolve here, in
     * parallel, through the session cache. Anything unresolvable keeps epoch and still renders
     * "Unknown date" — a failed lookup degrades the label, never the listing. Internal so the
     * no-network pass-through pins.
     */
    internal suspend fun resolveCreatedAt(items: List<MediaItem>): List<MediaItem> {
        val missing = items
            .filter { it.createdAt == EPOCH }
            .mapNotNull { it.createdAtBlock?.takeIf { block -> block > 0 } }
            .distinct()
            .filter { !blockTimestampCache.containsKey(it) }
        if (missing.isNotEmpty()) {
            coroutineScope {
                missing.map { block ->
                    async { fetchBlockTimestamp(block)?.let { blockTimestampCache[block] = it } }
                }.awaitAll()
            }
        }
        if (blockTimestampCache.isEmpty()) return items
        return items.map { item ->
            if (item.createdAt == EPOCH) {
                item.createdAtBlock?.let { blockTimestampCache[it] }?.let { item.copy(createdAt = it) }
                    ?: item
            } else {
                item
            }
        }
    }

    /** One block header timestamp, or null when the node cannot serve it. Never throws. */
    private fun fetchBlockTimestamp(block: Long): Instant? {
        val timestamp = runCatching {
            val body = JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "eth_getBlockByNumber")
                .put("params", JSONArray().put("0x" + block.toString(16)).put(false))
                .toString()
                .toRequestBody("application/json".toMediaType())
            httpClient.newCall(Request.Builder().url(config.endpointUrl).post(body).build())
                .execute().use { response ->
                    if (!response.isSuccessful) return@runCatching null
                    val text = response.body?.string() ?: return@runCatching null
                    parseBlockTimestamp(JSONObject(text).optJSONObject("result"))
                }
        }.getOrNull()
        if (timestamp == null) Timber.d("Arkiv block timestamp lookup failed (block=%d)", block)
        return timestamp
    }

    /**
     * Block header -> wall-clock time. The node returns a `0x` quantity; decimal strings parse
     * too, and anything else (missing block, malformed value, non-positive time) reads null so
     * the item keeps its honest "unknown".
     */
    internal fun parseBlockTimestamp(result: JSONObject?): Instant? {
        val raw = result?.optString("timestamp", null)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val seconds = runCatching {
            if (raw.startsWith("0x", ignoreCase = true)) raw.substring(2).toLong(16)
            else raw.toLong()
        }.getOrNull()?.takeIf { it > 0 } ?: return null
        return Instant.fromEpochSeconds(seconds)
    }

    /**
     * Next-page cursor, or null when iteration ends.
     *
     * The node omits `cursor` (or sends it empty) on the last page, and a short page is
     * terminal even when a cursor rides along — either ends iteration. `optString` returns
     * null for a missing key through a platform type that compiles a direct call, so the
     * safe call here is load-bearing: a bare dereference NPEs on exactly the single-page
     * responses every small library returns. Internal so the terminal-page rule pins
     * without touching the network.
     */
    internal fun nextCursorOrNull(result: JSONObject, received: Int, pageSize: Int): String? =
        result.optString("cursor", null)?.takeIf { it.isNotEmpty() }
            ?.takeIf { received >= pageSize }

    override suspend fun listMediaForOwner(
        owner: String,
        pageSize: Int,
        cursor: String?,
    ): Result<ArkivPage<MediaItem>> {
        notConfigured<ArkivPage<MediaItem>>()?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                // Same `$owner` lookup the SDK emits for `ownedBy` (lowercase verifies OK on-chain).
                val (items, nextCursor, _) = queryPage("\$owner = addr(${owner.lowercase()})", pageSize, cursor)
                val mapped = resolveCreatedAt(items.map { it.toMediaItem() })
                Result.success(ArkivPage(items = mapped, nextCursor = nextCursor))
            } catch (e: HavenError) {
                Result.failure(e)
            } catch (e: Exception) {
                Result.failure(networkError("listMediaForOwner", e))
            }
        }
    }

    override suspend fun listMediaForCommunity(
        gate: TokenGate,
        pageSize: Int,
        cursor: String?,
    ): Result<ArkivPage<MediaItem>> {
        notConfigured<ArkivPage<MediaItem>>()?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                // Filters on the gating asset rather than the author, which is what makes this a feed
                // of a community's archive instead of a list of one wallet's uploads. Same predicates
                // as the dapp's `fetchCommunityFeedForToken`; the chain is stored as the EIP-155 id.
                val chainId = HavenChain.parse(gate.chain)?.chainId
                    ?: return@withContext Result.failure(
                        HavenError.Internal("Unknown gate chain: ${gate.chain}"),
                    )
                val query = "${eqStr("gate_token", gate.tokenAddress)} AND ${eqNum("gate_chain", chainId)}"
                val (items, nextCursor, _) = queryPage(query, pageSize, cursor)
                val mapped = resolveCreatedAt(items.map { it.toMediaItem() })
                Result.success(ArkivPage(items = mapped, nextCursor = nextCursor))
            } catch (e: HavenError) {
                Result.failure(e)
            } catch (e: Exception) {
                Result.failure(networkError("listMediaForCommunity", e))
            }
        }
    }

    override suspend fun discoverGates(chains: Set<HavenChain>): Result<List<TokenGate>> {
        notConfigured<List<TokenGate>>()?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                // Arkiv has no concept of gates — they are Haven's reading of entity attributes
                // (`gate_token`/`gate_chain`/`gate_threshold`, stamped at publish time). So discovery
                // mirrors the dapp: page Haven video entities and collect distinct gate attributes
                // client-side. Bounded: discovery pages the listing, it never crawls the archive.
                // Active means at least one non-expired entity: expiry is `expiresAtBlock`
                // against the query head, so expired rows contribute no gate.
                val gates = mutableListOf<TokenGate>()
                var cursor: String? = null
                var pages = 0
                do {
                    val (items, next, head) = queryPage(VIDEO_GROUPS_QUERY, SCAN_PAGE_SIZE, cursor)
                    for (item in items) {
                        val media = runCatching { item.toMediaItem() }.getOrNull() ?: continue
                        if (isExpired(media.expiresAtBlock, head)) continue
                        media.gate?.let { gates.add(it) }
                    }
                    cursor = next
                    pages++
                } while (cursor != null && pages < MAX_SCAN_PAGES)
                Result.success(dedupeGates(gates, chains))
            } catch (e: HavenError) {
                Result.failure(e)
            } catch (e: Exception) {
                Result.failure(networkError("discoverGates", e))
            }
        }
    }

    /**
     * Expired when the entity names an expiry at or below the chain head.
     *
     * Null expiry means "no expiry recorded" (active); null head means the node omitted
     * it, which fails open to active rather than hiding a live DAO. Pure for testing.
     */
    internal fun isExpired(expiresAtBlock: Long?, headBlock: Long?): Boolean =
        expiresAtBlock != null && headBlock != null && expiresAtBlock <= headBlock

    /**
     * One gate per (chain, contract): thresholds vary per entity, and the lowest is the one
     * that decides whether anything under it is readable. Shared by the attribute scan and
     * the legacy endpoint path.
     */
    private fun dedupeGates(gates: List<TokenGate>, chains: Set<HavenChain>): List<TokenGate> =
        gates
            .filter { HavenChain.parse(it.chain) in chains }
            .groupBy { "${HavenChain.parse(it.chain)?.aolVariant}:${it.tokenAddress.lowercase()}" }
            .mapNotNull { (_, group) -> group.minByOrNull { it.threshold } }

    private companion object {
        /** Free-text bound per cause in the on-screen message; the full stack goes to logcat. */
        const val MAX_CAUSE_CHARS = 160
        /** Bound on the cause-chain walk; deeper chains report the ancestor at the cap. */
        const val MAX_CAUSE_DEPTH = 5
        /** Epoch means "no wall-clock stamp" (see `parseCreatedAt`); rows render it as unknown. */
        val EPOCH = Instant.fromEpochMilliseconds(0)
        /**
         * Keys the payload merge must never overwrite: row identity and decoded system
         * fields. Everything else follows dapp spread order (payload wins over attributes).
         */
        val RESERVED_MERGE_KEYS = setOf(
            "id", "key", "entityKey", "owner", "creatorAddress", "contentType",
            "payloadJson", "createdAtBlock", "expiresAtBlock",
        )
        /** Entities per scan page; discovery pages the listing, it never loads the archive. */
        const val SCAN_PAGE_SIZE = 50
        /** Hard bound so a huge archive cannot turn discovery into an unbounded crawl. */
        const val MAX_SCAN_PAGES = 10
        /**
         * Drip PART rows: the `grp` marker plus `gate_type = i32(4)`, the numeric per-marketcap
         * spelling writers stamp. Same types the SDK emits, so comparison stays type-exact.
         */
        const val DRIP_PARTS_QUERY = "grp = str('haven.video.drip.part') AND gate_type = i32(4)"
        const val DRIP_PART_GROUP = "haven.video.drip.part"
        const val DRIP_SERIES_GROUP = "haven.video.drip.series"
        /** Series headers per `drip_id`: one lookup, near-unique, same `limit(5)` as the dapp. */
        const val SERIES_LOOKUP_LIMIT = 5
        /** Sanity bound for `drip_idx`; anything past it is corrupt, not a late stage. */
        const val MAX_DRIP_INDEX = 100_000L
        /**
         * Haven video groups that carry gate attributes (see dapp `arkiv-publish`).
         * Flat `OR` chain exactly like the SDK renders `or(...)` — the language has no
         * `||`, and parenthesised groups are untested against the node, so none are used.
         */
        const val VIDEO_GROUPS_QUERY =
            "grp = str('haven.video.full') OR grp = str('haven.video.drip.series') OR grp = str('haven.video.drip.part')"
    }

    override suspend fun discoverUserCommunities(address: String): Result<List<Community>> {
        notConfigured<List<Community>>()?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                // Dapp parity (`discoverUserCommunities`): the wallet's own entities, gate attributes
                // read locally. Answers "nothing" for a reader who never published — that is why
                // `discoverGates` exists alongside it.
                val (items, _, head) = queryPage("\$owner = addr(${address.lowercase()})", SCAN_PAGE_SIZE, null)
                val communities = items
                    .filter { item ->
                        val expires = runCatching { item.toMediaItem().expiresAtBlock }.getOrNull()
                        !isExpired(expires, head)
                    }
                    .mapNotNull { runCatching { it.toCommunity() }.getOrNull() }
                    // Local key, not the checker: core-collections owns gate keys and already
                    // depends on this module, so importing it here would be circular.
                    .distinctBy { "${it.gate.chain}:${it.gate.tokenAddress.lowercase()}" }
                Result.success(communities)
            } catch (e: HavenError) {
                Result.failure(e)
            } catch (e: Exception) {
                Result.failure(networkError("discoverUserCommunities", e))
            }
        }
    }

    override suspend fun listLaunches(): Result<List<LaunchStage>> {
        notConfigured<List<LaunchStage>>()?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                // Drip PARTs, walked whole: the gap rule needs every stage of a launch before it
                // can tell a hole from a cut-off page, so the parts query pages (bounded, like
                // `discoverGates`) instead of taking one capped list like the dapp's `limit(24)`.
                val parts = mutableListOf<JSONObject>()
                var cursor: String? = null
                var pages = 0
                do {
                    val (items, next, _) = queryPage(DRIP_PARTS_QUERY, SCAN_PAGE_SIZE, cursor)
                    parts += items.filter { it.optString("grp", null) == DRIP_PART_GROUP }
                    cursor = next
                    pages++
                } while (cursor != null && pages < MAX_SCAN_PAGES)
                // One series fetch per distinct drip_id — shared facts live on the header, never
                // on the part. A single `drip_id` equality plus a client-side `grp` check, exactly
                // like the dapp's find-or-create lookup.
                val dripIds = parts
                    .mapNotNull { it.optString("drip_id", null)?.takeIf { id -> id.isNotEmpty() } }
                    .distinct()
                val seriesById = coroutineScope {
                    dripIds.map { dripId -> async { dripId to findDripSeries(dripId) } }
                        .awaitAll()
                        .mapNotNull { (dripId, series) -> series?.let { dripId to it } }
                        .toMap()
                }
                val stages = parts.mapNotNull { it.toLaunchStage(seriesById) }
                Result.success(selectLiveStages(stages))
            } catch (e: HavenError) {
                Result.failure(e)
            } catch (e: Exception) {
                Result.failure(networkError("listLaunches", e))
            }
        }
    }

    /** The SERIES header for one `drip_id`, or null when it has expired or never existed. */
    private fun findDripSeries(dripId: String): JSONObject? {
        val (items, _, _) = queryPage(eqStr("drip_id", dripId), SERIES_LOOKUP_LIMIT, null)
        return items.firstOrNull { it.optString("grp", null) == DRIP_SERIES_GROUP }
    }

    override suspend fun getMedia(id: String): Result<MediaItem?> {
        notConfigured<MediaItem?>()?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                // Same `$key` lookup as the SDK's `getEntity`; keys are 32 bytes of hex.
                val clean = id.trim().removePrefix("0x")
                if (clean.length != 64 || !clean.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                    return@withContext Result.success(null)
                }
                val (items, _, _) = queryPage("\$key = key(0x${clean.lowercase()})", 1, null)
                val found = items.firstOrNull()?.let { runCatching { it.toMediaItem() }.getOrNull() }
                Result.success(found?.let { resolveCreatedAt(listOf(it)).first() })
            } catch (e: HavenError) {
                Result.failure(e)
            } catch (e: Exception) {
                Result.failure(networkError("getMedia", e))
            }
        }
    }

    /**
     * Transport failure -> an error the phone shows verbatim.
     *
     * Feed, Library, and Launches all render this message as the failure detail, so the
     * diagnostics travel IN the message, not just the cause: the endpoint host (proves which
     * URL the build carries), the failing call (which of the six queries threw), the exception
     * class (DNS vs TLS vs timeout vs refused), its detail, and the root cause when it adds
     * information (a TLS handshake failure's "Trust anchor … not found" lives one level down).
     * Each free-text part is truncated so the banner stays readable; the full stack goes to
     * logcat. Internal so the message shape pins without touching the network.
     */
    internal fun networkError(source: String, e: Exception): HavenError.NetworkError {
        Timber.w(e, "Arkiv %s failed (endpoint=%s)", source, config.endpointUrl)
        val host = endpointHost()
        val causeName = e.javaClass.simpleName.ifBlank { e.javaClass.name }
        val detail = e.message?.trim().orEmpty().take(MAX_CAUSE_CHARS)
        val at = if (detail.isNotEmpty()) "$causeName: $detail" else causeName
        val root = rootCause(e)
        val rootMessage = root?.message?.trim().orEmpty().take(MAX_CAUSE_CHARS)
        val rootPart = if (root != null && rootMessage.isNotEmpty() && rootMessage != detail) {
            "; caused by ${root.javaClass.simpleName}: $rootMessage"
        } else {
            ""
        }
        return HavenError.NetworkError(
            "Couldn't reach $host [$source] ($at$rootPart). Check your connection.",
            e,
        )
    }

    /** Host of the configured endpoint, or the raw value when it does not parse as a URI. */
    private fun endpointHost(): String =
        runCatching { java.net.URI(config.endpointUrl).host }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: config.endpointUrl.ifBlank { "Arkiv" }

    /** Deepest distinct cause, so TLS trust failures name their root. Bounded, cycle-safe. */
    private fun rootCause(e: Throwable): Throwable? {
        var current = e.cause ?: return null
        var depth = 0
        while (current.cause != null && current.cause !== current && depth < MAX_CAUSE_DEPTH) {
            current = current.cause!!
            depth++
        }
        return current
    }

    /**
     * Returns a typed failure when there is no endpoint to call, and null when there is.
     *
     * Generic so each caller keeps its own `Result<T>`. Without this, an unconfigured build threw
     * out of URL building for every query, which surfaced to the user as an unexplained failure
     * rather than "not set up yet".
     */
    private fun <T> notConfigured(): Result<T>? =
        if (config.isConfigured) {
            null
        } else {
            Result.failure(
                HavenError.NetworkError(
                    "This build of Haven can't look for new content.",
                ),
            )
        }

    /**
     * Entity -> `MediaItem`, against ARKIV_FORMAT 2.0.0 canonical keys.
     *
     * `lib/parse-arkiv-video.ts` (haven-dapp) is the spec: it merges `entity.attributes` with the
     * decoded payload and reads canonical `snake_case` keys off the result, and the record this
     * reads is merged the same way (see [normalizeRpcEntity]). Anything the dapp does not read
     * does not exist in practice — which ruled out two fields this parser previously invented,
     * and one it required:
     *
     *  - **`size_bytes`** — the payload carries a `size`, but foc's resolved piece is the size
     *    of record, so nothing is read here and [MediaItem.sizeBytes] stays null until then.
     *  - **`thumbnail_cid`** — absent from the dapp's read path, written by nothing. Removed rather
     *    than carried as a permanently null column.
     *  - **`arkivStatus` / `contentCacheStatus` / `createdAt` / `title` / `owner`** — all were read with
     *    `getString`, which throws. The dapp hard-codes status to "active", cache state is local, and
     *    title/owner have documented fallbacks. Nothing here is required now.
     *
     * 2.0 key map (old -> new): `content_mime_type` -> `mime` (enum int),
     * `filecoin_root_cid` -> `fcid`, `piece_cid` -> `piece`, `cid_hash` -> `sha256_ct`,
     * `duration` -> `dur_s`, `creator_handle` -> `creator`, `source_uri` -> `src`,
     * `encryption_metadata` -> `gate`, `cid_encryption_metadata` -> `cid_gate`,
     * `segment_metadata` -> `seg`, `attestation` -> `attn`. Deleted with no replacement:
     * `is_encrypted` (gate presence decides), `encrypted_cid` (never indexed),
     * `created_at`/`updated_at` (system fields), `project`/`type`/`category`/`tags`
     * (replaced by `grp`).
     *
     * The camelCase spellings are accepted alongside the canonical ones because the HTTP gateway in
     * front of Arkiv may already be reshaping them. Internal so the payload-merge result pins
     * without touching the network.
     */
    internal fun JSONObject.toMediaItem(): MediaItem {
        val mimeType = firstMime("mime", "mimeType", "contentMimeType")
        val sourceUri = firstString("src", "sourceUri")
        val extension = deriveExtension(mimeType, sourceUri)
        val title = firstString("title") ?: "Untitled"
        val pieceCid = firstString("piece", "pieceCid")

        // Gate presence decides encryption — 2.0 carries no is_encrypted flag.
        val gateMetadata = parseGateMetadata("gate")
        val cidGateMetadata = parseGateMetadata("cid_gate", "cidGate")
        val tokenGate = toTokenGate()

        // Retrieval scoping the record does not carry: the 2.0 payload holds only the
        // `piece` locator. foc-cache builds /piece candidates solely from this ref
        // (RemoteStore throws "No candidate endpoints" on an empty one), so supply
        // what the writer guarantees by convention: haven-cli uploads withCDN
        // (Filecoin Beam) on the gate's network, and the Beam per-account subdomain
        // is keyed by the uploader wallet (the record owner).
        val focChain = tokenGate?.chain
            ?.let { HavenChain.parse(it) }
            ?.let { if (it.isTestnet) FocChain.CALIBRATION else FocChain.MAINNET }
            // Ungated records carry no chain signal at all (no gate, no network).
            // Default to CALIBRATION: every record published to date is testnet
            // content, and Beam hosts are per-network — guessing MAINNET 404s on
            // calibration pieces. Revisit if mainnet-ungated records appear.
            ?: FocChain.CALIBRATION
        val beamWallet = (firstString("owner") ?: "").lowercase()
            .takeIf { it.matches(Regex("^0x[0-9a-f]{40}$")) }

        return MediaItem(
            // `key` is the entity id in Arkiv; `id` is what a gateway usually renames it to.
            id = firstString("id", "key", "entityKey") ?: "",
            kind = MediaKindResolver.resolve(mimeType, extension ?: sourceUri),
            owner = (firstString("owner") ?: "").lowercase(),
            title = title,
            description = firstString("description"),
            mimeType = mimeType,
            fileExtension = extension,
            // Derived, not stored: the FILE viewer needs something to pre-fill the save dialog with.
            filenameHint = extension?.let { "${title.take(64)}$it" } ?: title.take(64),
            // Not an entity field. foc reports the real size when it resolves the piece.
            sizeBytes = null,
            createdAt = parseCreatedAt(),
            createdAtBlock = firstLong("created_at_block", "createdAtBlock"),
            expiresAtBlock = firstLong("expires_at_block", "expiresAtBlock"),
            pieceRef = pieceCid?.let { cid ->
                PieceRef(
                    pieceCid = cid,
                    // Size/providers/gateways stay unknown: foc hedges the Beam URL
                    // (front of the race when cdnEnabled) with any SP endpoints.
                    // A stale provider list baked in from an index would send
                    // fetches at the wrong hosts.
                    size = 0L,
                    providerServiceUrls = emptyList(),
                    walletAddress = beamWallet,
                    cdnEnabled = beamWallet != null,
                    chain = focChain,
                    ipfsIndexed = false,
                    unixFsRoot = null,
                    trustlessGateways = emptyList(),
                )
            },
            filecoinCid = firstString("fcid", "filecoinCid"),
            // 2.0 never indexes the encrypted locator — always null (kept on the model for API stability).
            encryptedCid = null,
            cidHash = firstString("sha256_ct", "cidHash"),
            gate = tokenGate,
            isEncrypted = gateMetadata != null,
            encryptionMetadata = gateMetadata,
            cidEncryptionMetadata = cidGateMetadata,
            attestation = parseAttestationOrNull(),
            // The dapp hard-codes "active"; expiry is decided from `expires_at_block` against the head,
            // not from a status the entity carries.
            arkivStatus = ArkivStatus.FRESH,
            // Local state. The mirror resolves it against the content cache immediately after this.
            contentCacheStatus = ContentCacheStatus.UNCACHED,
            lastAccessedAt = null,
            durationSeconds = firstLong("dur_s"),
            creatorHandle = firstString("creator", "creatorHandle"),
            creatorAddress = firstString("creatorAddress"),
        )
    }

    /**
     * `created_at` is an ISO-8601 attribute written by the entity store, but not every entity has one.
     *
     * Falling back to "now" would make an old archive look newly published and sort to the top of every
     * screen, so an entity with no timestamp lands on epoch instead — and block-header resolution
     * upgrades it to the real creation time afterwards (see `resolveCreatedAt`).
     */
    private fun JSONObject.parseCreatedAt(): Instant {
        firstString("created_at", "createdAt")?.let { raw ->
            runCatching { Instant.parse(raw) }.getOrNull()?.let { return it }
        }
        return Instant.fromEpochMilliseconds(0)
    }

    /**
     * The payload's `attn` object, if it carries a complete attestation.
     *
     * haven-cli embeds the canister's receipt verbatim (see dapp `types/attestation`), so the
     * keys are camelCase and every binding field is required — anything missing fails closed
     * to null rather than producing a half-attestation that could render verified. Absence is
     * normal: most entities were never attested.
     */
    internal fun JSONObject.parseAttestationOrNull(): Attestation? {
        val payload = optString("payloadJson", null)?.takeIf { it.isNotBlank() } ?: return null
        val attn = runCatching { JSONObject(payload) }.getOrNull()?.opt("attn") ?: return null
        val obj = when (attn) {
            is JSONObject -> attn
            is String -> runCatching { JSONObject(attn) }.getOrNull() ?: return null
            else -> return null
        }
        return parseAttestationObject(obj)
    }

    /**
     * `attn` JSON -> model, discriminated like dapp `isMerkleAttestation`: a `merkleRoot`
     * string plus a `merkleProof` array routes to [Attestation.Merkle], anything else with a
     * `signature` to [Attestation.Single]. Partially-populated payloads fail closed — a lone
     * `merkleProof` without root lands in the Single branch, which then rejects it for the
     * missing `signature`.
     */
    internal fun parseAttestationObject(obj: JSONObject): Attestation? {
        val evmAddress = obj.requiredString("evmAddress") ?: return null
        val chain = obj.requiredString("chain") ?: return null
        val tokenAddress = obj.requiredString("tokenAddress") ?: return null
        val threshold = obj.opt("threshold").let { jsonDoubleOrNull(it) } ?: return null
        val balanceAtCheck = obj.opt("balanceAtCheck").let { jsonDoubleOrNull(it) } ?: return null
        val cidHash = obj.requiredString("cidHash") ?: return null
        val timestamp = jsonLongOrNull(obj.opt("timestamp")) ?: return null
        val merkleRoot = obj.optString("merkleRoot", null)
        val proofArray = obj.optJSONArray("merkleProof")
        if (merkleRoot != null && proofArray != null) {
            val cidCount = jsonLongOrNull(obj.opt("cidCount")) ?: return null
            val steps = (0 until proofArray.length()).map { idx ->
                val step = proofArray.optJSONObject(idx) ?: return null
                MerkleProofStep(
                    side = step.optString("side", null) ?: return null,
                    hash = step.optString("hash", null) ?: return null,
                )
            }
            return Attestation.Merkle(
                evmAddress = evmAddress,
                chain = chain,
                tokenAddress = tokenAddress,
                threshold = threshold,
                balanceAtCheck = balanceAtCheck,
                cidHash = cidHash,
                timestamp = timestamp,
                cidCount = cidCount,
                merkleProof = steps,
                merkleRoot = merkleRoot,
                rootSignature = obj.requiredString("rootSignature") ?: return null,
            )
        }
        return Attestation.Single(
            evmAddress = evmAddress,
            chain = chain,
            tokenAddress = tokenAddress,
            threshold = threshold,
            balanceAtCheck = balanceAtCheck,
            cidHash = cidHash,
            timestamp = timestamp,
            signature = obj.requiredString("signature") ?: return null,
        )
    }

    private fun JSONObject.requiredString(key: String): String? =
        optString(key, null)?.takeIf { it.isNotEmpty() }

    private fun jsonDoubleOrNull(value: Any?): Double? = when (value) {
        is Number -> value.toDouble().takeIf { it.isFinite() }
        is String -> value.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
        else -> null
    }

    private fun jsonLongOrNull(value: Any?): Long? = when (value) {
        is Number -> value.toLong()
        is String -> value.trim().toLongOrNull()
        else -> null
    }

    /**
     * Extension for viewer selection and the save dialog.
     *
     * Entities carry no filename, so it comes from the MIME type where that is unambiguous, and from the
     * source URI's tail otherwise.
     */
    private fun deriveExtension(mimeType: String?, sourceUri: String?): String? {
        val fromMime = when (mimeType?.substringBefore(';')?.trim()?.lowercase()) {
            "video/mp4" -> ".mp4"
            "video/webm" -> ".webm"
            "video/x-matroska" -> ".mkv"
            "video/quicktime" -> ".mov"
            "audio/mpeg" -> ".mp3"
            "audio/flac" -> ".flac"
            "audio/ogg" -> ".ogg"
            "audio/wav", "audio/x-wav" -> ".wav"
            "audio/mp4", "audio/m4a" -> ".m4a"
            "image/jpeg" -> ".jpg"
            "image/png" -> ".png"
            "image/webp" -> ".webp"
            "image/gif" -> ".gif"
            "image/heic" -> ".heic"
            "application/pdf" -> ".pdf"
            else -> null
        }
        if (fromMime != null) return fromMime

        val tail = sourceUri?.substringAfterLast('/')?.substringBefore('?') ?: return null
        if (!tail.contains('.')) return null
        return "." + tail.substringAfterLast('.').lowercase().take(8)
    }

    /** Null when the row carries no gate Haven-AOL can evaluate; the caller drops it. */
    private fun JSONObject.toCommunity(): Community? = toTokenGate()?.let { Community(gate = it) }

    /**
     * A gate condition as stored on Arkiv.
     *
     * Attribute names follow the entity spec (`gate_chain`, `gate_token`, `gate_threshold`); the camelCase
     * spellings are accepted too because the gateway's JSON shape has used both. `gate_chain` is the
     * EIP-155 id in 2.0 (a JSON number) — [HavenChain.parse] already accepts bare ids, so numbers are
     * read directly. A row missing either the chain or the contract is skipped rather than defaulted —
     * a gate with a guessed chain checks the wrong balance and answers confidently.
     */
    internal fun JSONObject.toTokenGate(): TokenGate? {
        val rawChain = firstChain("gate_chain", "gateChain", "chain") ?: return null
        val token = firstString("gateTokenAddress", "gate_token", "tokenAddress") ?: return null
        val chain = HavenChain.parse(rawChain) ?: return null
        val threshold = firstDouble("gateThreshold", "gate_threshold", "threshold") ?: 1.0
        val standard = firstString("gateTokenStandard", "gate_token_standard")
            ?.let { runCatching { TokenStandard.valueOf(it) }.getOrNull() }
            ?: TokenStandard.ERC20

        return TokenGate(
            // Stored canonically from here on, so downstream comparisons do not have to re-normalise.
            chain = chain.caip2,
            tokenAddress = token,
            threshold = threshold,
            tokenStandard = standard,
        )
    }

    /**
     * One PART row plus its SERIES header -> a launch stage.
     *
     * Mirrors dapp `parseDripInfo` + the `UpcomingDrops` row mapping: per-stage facts (`drip_id`,
     * `drip_idx`, `mcap_usd`) from the part, shared facts (title, total, token, chain) from the
     * series. A part without a `drip_id` or without a positive `mcap_usd` is dropped — the dapp
     * filters `marketCapTargetUsd > 0` the same way. A missing series degrades to "Untitled Drop"
     * with no token rather than dropping the row: the stage exists and its target is real.
     */
    internal fun JSONObject.toLaunchStage(seriesById: Map<String, JSONObject>): LaunchStage? {
        val dripId = firstString("drip_id")?.takeIf { it.isNotEmpty() } ?: return null
        val target = firstLong("mcap_usd") ?: return null
        val series = seriesById[dripId]
        return LaunchStage(
            id = firstString("id", "key", "entityKey") ?: "",
            title = series?.firstString("title")?.takeIf { it.isNotEmpty() } ?: "Untitled Drop",
            gateToken = series?.firstString("gate_token", "gateTokenAddress", "tokenAddress") ?: "",
            gateChain = series?.let { HavenChain.parse(it.firstChain("gate_chain", "gateChain", "chain")) },
            marketCapTargetUsd = target,
            dripIndex = firstIndex("drip_idx") ?: 0,
            dripTotal = series?.firstLong("drip_total")?.toInt() ?: 1,
            dripId = dripId,
            creatorHandle = series?.payloadField("creator"),
            createdAtBlock = firstLong("created_at_block", "createdAtBlock"),
        )
    }

    /** One string field out of the entity payload JSON (`payloadJson`); null when unreadable. */
    private fun JSONObject.payloadField(name: String): String? {
        val payload = optString("payloadJson", null)?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { JSONObject(payload) }.getOrNull()
            ?.optString(name, null)?.takeIf { it.isNotEmpty() }
    }

    /**
     * Stage index (`drip_idx`): 0 is the premiere, so unlike [firstLong] this accepts zero.
     * Garbage and absurd values fail closed to null (the caller then reads 0, the dapp's own
     * fallback for a non-finite index).
     */
    private fun JSONObject.firstIndex(vararg keys: String): Int? = keys
        .asSequence()
        .mapNotNull { key ->
            when (val value = opt(key)) {
                is Number -> value.toLong()
                is String -> value.trim().toLongOrNull()
                else -> null
            }?.takeIf { it in 0..MAX_DRIP_INDEX }
        }
        .firstOrNull()
        ?.toInt()

    private fun JSONObject.firstString(vararg keys: String): String? = keys
        .asSequence()
        .mapNotNull { key -> optString(key, null)?.takeIf { it.isNotEmpty() } }
        .firstOrNull()

    /** Chain as stored (`gate_chain` EIP id number in 2.0) or gateway-shaped string. */
    private fun JSONObject.firstChain(vararg keys: String): String? = keys
        .asSequence()
        .filter { has(it) && !isNull(it) }
        .mapNotNull { key ->
            when (val value = opt(key)) {
                is Number -> value.toLong().toString()
                is String -> value.takeIf { it.isNotEmpty() }
                else -> null
            }
        }
        .firstOrNull()

    /**
     * Shared MIME enum (ARKIV_FORMAT 2.0.0 §MIME enum — mirrors
     * `haven_cli.services.arkiv_sync.MIME_TO_ENUM` and dapp `lib/mime-enum`).
     * The `mime` attribute stores the enum int; gateway-reshaped responses
     * may already carry a MIME string, which passes through untouched.
     */
    private fun JSONObject.firstMime(vararg keys: String): String? {
        for (key in keys) {
            if (isNull(key)) continue
            when (val value = opt(key)) {
                is Number -> mimeEnumToMime(value.toInt())?.let { return it }
                is String -> value.takeIf { it.isNotEmpty() }?.let { return it }
            }
        }
        return null
    }

    private fun mimeEnumToMime(id: Int): String? = when (id) {
        1 -> "video/mp4"
        2 -> "video/webm"
        3 -> "video/quicktime"
        4 -> "audio/mpeg"
        5 -> "audio/wav"
        6 -> "audio/ogg"
        7 -> "image/png"
        8 -> "image/jpeg"
        9 -> "image/webp"
        10 -> "image/gif"
        11 -> "image/svg+xml"
        12 -> "text/plain"
        13 -> "text/markdown"
        14 -> "application/pdf"
        else -> null
    }

    private fun JSONObject.firstDouble(vararg keys: String): Double? = keys
        .asSequence()
        .filter { has(it) && !isNull(it) }
        .map { optDouble(it) }
        .firstOrNull { !it.isNaN() }

    /** Null rather than 0 for absent numbers: "no duration recorded" is not "zero seconds long". */
    private fun JSONObject.firstLong(vararg keys: String): Long? = keys
        .asSequence()
        .filter { has(it) && !isNull(it) }
        .map { optLong(it) }
        .firstOrNull { it > 0 }

    /**
     * The content gate (`gate`) and CID-layer gate (`cid_gate`), any version.
     *
     * The spec allows a JSON object or a string, and the version is decided by content rather than by
     * which key it arrived under: v4 records carry `marketCapTarget` (+ epoch), v3 records carry an
     * epoch (`epoch`/`epochId`), v1 records a nonce.
     * The Arkiv-level marker is `gate_type` (1|3|4 = per-file/per-epoch/per-marketcap).
     * Numeric only — no `gate_version` fallback.
     */
    /**
     * Non-negative integer field, or null when absent or malformed. Unlike [firstLong], 0 is a
     * value: v3/v4 threshold-zero gates pin epoch 0, and a v4 stage may target 0.
     */
    private fun JSONObject.optNat(key: String): Long? = when (val v = opt(key)) {
        is Number -> v.toLong().takeIf { it >= 0 && v.toDouble() == it.toDouble() }
        is String -> v.trim().toLongOrNull()?.takeIf { it >= 0 }
        else -> null
    }

    private fun JSONObject.parseGateMetadata(vararg keys: String): GateMetadata? {
        // Read here rather than threaded through: the attribute lives on the same merged
        // entity object as the gate blob.
        val gateTypeAttribute = firstLong("gate_type")
        val obj = keys.asSequence().mapNotNull { key ->
            // The gateway may pass the gate blob through as a JSON string
            // (how writers store it) or as a decoded object (reshaped).
            optJSONObject(key) ?: optString(key, null)?.takeIf { it.isNotEmpty() }?.let {
                runCatching { JSONObject(it) }.getOrNull()
            }
        }.firstOrNull() ?: return null

        // Sealed (VetKD) records as real writers emit them (`{version, encryptedAesKey, …}` —
        // see dapp `isGateMetadata`): recognized, never mistaken for open content, and failed
        // closed at decrypt time. Any non-empty sealed key counts, whatever the version claims —
        // an unparsable version must not read as unsealed (deliberately stricter than the dapp,
        // which nulls records whose version it does not route).
        obj.optString("encryptedAesKey", null)?.takeIf { it.isNotEmpty() }?.let { sealed ->
            return GateMetadata.Sealed(
                version = when (val v = obj.opt("version")) {
                    is Number -> v.toLong()
                    is String -> v.toLongOrNull() ?: 0L
                    else -> 0L
                },
                encryptedAesKey = sealed,
                cid = obj.optString("cid", null)?.takeIf { it.isNotEmpty() } ?: "",
                chain = obj.optString("chain", null)?.takeIf { it.isNotEmpty() } ?: "",
                tokenAddress = obj.optString("tokenAddress", null)?.takeIf { it.isNotEmpty() } ?: "",
                threshold = when (val t = obj.opt("threshold")) {
                    is Number -> t.toLong().toString()
                    is String -> t.trim()
                    else -> ""
                },
                // v3 seals key on (gate, epoch). Parsed explicitly, not via firstLong: epoch 0
                // is valid (threshold-zero gates) and must not read as "absent".
                epoch = obj.optNat("epoch"),
                // v4 drip seals (haven-aol build_gate_metadata_v4): reserve-unit target and
                // the Bond oracle, both bound by the v4 request and derivation.
                marketCapTarget = obj.optNat("marketCapTarget"),
                oracleAddress = obj.optString("oracleAddress", null)?.takeIf { it.isNotEmpty() } ?: "",
                // Entity attribute, merged alongside the payload: the version must agree.
                attributeGateType = gateTypeAttribute,
            )
        }

        val wrappedKey = obj.firstString("wrappedKey", "wrapped_key", "ciphertext")
            ?.toByteArray(Charsets.UTF_8)
            ?: return null

        val marketCapTarget = obj.firstLong("marketCapTarget", "market_cap_target", "marketCapTargetUsd", "market_cap_target_usd")
        val epoch = obj.firstLong("epoch", "epochId", "epoch_id")
        if (marketCapTarget != null && epoch != null) {
            return GateMetadata.V4(
                epochId = epoch,
                marketCapTargetUsd = marketCapTarget,
                wrappedKey = wrappedKey,
                gateReference = obj.firstString("gateReference", "gate_reference", "gate") ?: "",
                // v4 gate JSON carries the pump target token (`tokenAddress`) and its
                // chain (`chain`, Haven-AOL variant) — the buy-link inputs.
                tokenAddress = obj.firstString("tokenAddress", "token_address") ?: "",
                chain = obj.firstChain("chain", "gate_chain", "gateChain") ?: "",
            )
        }
        if (epoch != null) {
            return GateMetadata.V3(
                epochId = epoch,
                wrappedKey = wrappedKey,
                gateReference = obj.firstString("gateReference", "gate_reference", "gate") ?: "",
            )
        }
        return GateMetadata.V1(
            wrappedKey = wrappedKey,
            nonce = obj.firstString("nonce") ?: "",
        )
    }

    // No `parseStringList` any more: the only arrays it read were the PieceRef provider and gateway
    // lists, which are foc's to resolve rather than the index's to declare.
}