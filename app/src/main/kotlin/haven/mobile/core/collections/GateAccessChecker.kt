package haven.mobile.core.collections

import haven.mobile.core.domain.HavenChain
import haven.mobile.core.domain.TokenGate
import haven.mobile.core.domain.havenChain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Does this address hold what a gate asks for?
 *
 * Ported from haven-mobile `core-collections`. One `eth_call` of `balanceOf(address)` per contract
 * (the same selector the canister's `checkBalance` uses, so ERC-20 and ERC-721 gates both work),
 * batched per chain, chains in parallel.
 *
 * haven-wear addition: [holdings]. The phone only needed a yes/no per gate; the watch shows *how
 * far* a reader is from a track ("Hold 25 FWB · you have 3"), so it keeps the balance, the decimals
 * and the token symbol. [satisfied] is now derived from it.
 *
 * Read-only and unsigned. The canister re-checks the balance of the signing address at unlock time,
 * so this only decides what the library shows, never what unlocks.
 */
interface GateAccessChecker {
    /** Gate keys this address satisfies. A gate that could not be read is absent, not false. */
    suspend fun satisfied(
        walletAddress: String,
        gates: List<TokenGate>,
        chains: Set<HavenChain> = HavenChain.mainnets.toSet(),
    ): Set<String>

    /** What the address holds per gate key. Unreadable contracts are absent. */
    suspend fun holdings(
        walletAddress: String,
        gates: List<TokenGate>,
        chains: Set<HavenChain> = HavenChain.mainnets.toSet(),
    ): Map<String, Holding>
}

/** A balance read, in base units, with what is needed to compare and display it. */
data class Holding(
    val balance: BigInteger,
    /** Null for collections (ERC-721 reverts on `decimals()`): the balance is an item count. */
    val decimals: Int?,
    val symbol: String?,
) {
    /** Threshold in base units; see [requiredUnits]. */
    fun meets(threshold: Double): Boolean = balance >= requiredUnits(threshold, decimals)

    /** Whole units held, for display. */
    val wholeUnits: BigDecimal
        get() = if (decimals == null || decimals == 0) BigDecimal(balance) else BigDecimal(balance).movePointLeft(decimals)
}

/** Stable identity for a gate: one chain, one contract. */
fun gateKey(chain: HavenChain, tokenAddress: String): String =
    "${chain.aolVariant}:${tokenAddress.lowercase()}"

fun TokenGate.gateKeyOrNull(): String? = havenChain()?.let { gateKey(it, tokenAddress) }

/**
 * Threshold in base units. A token quotes whole tokens while `balanceOf` answers in base units, so
 * the threshold is scaled by decimals; a collection has none and the threshold is an item count.
 */
internal fun requiredUnits(threshold: Double, decimals: Int?): BigInteger {
    val whole = BigInteger.valueOf(threshold.toLong().coerceAtLeast(0))
    return if (decimals == null || decimals == 0) whole else whole.multiply(BigInteger.TEN.pow(decimals))
}

@Singleton
internal class EvmGateAccessChecker @Inject constructor(
    private val endpoints: EvmEndpoints,
) : GateAccessChecker {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    override suspend fun satisfied(
        walletAddress: String,
        gates: List<TokenGate>,
        chains: Set<HavenChain>,
    ): Set<String> {
        val held = holdings(walletAddress, gates, chains)
        // Lowest threshold per contract: satisfying it is what makes any of that gate's items readable.
        return gates
            .groupBy { it.gateKeyOrNull() }
            .mapNotNull { (key, group) ->
                val holding = key?.let { held[it] } ?: return@mapNotNull null
                key.takeIf { holding.meets(group.minOf { it.threshold }) }
            }
            .toSet()
    }

    override suspend fun holdings(
        walletAddress: String,
        gates: List<TokenGate>,
        chains: Set<HavenChain>,
    ): Map<String, Holding> {
        if (gates.isEmpty()) return emptyMap()
        val padded = padAddress(walletAddress) ?: return emptyMap()

        val byChain = gates
            .mapNotNull { gate -> gate.havenChain()?.let { chain -> chain to gate.tokenAddress.lowercase() } }
            .filter { (chain, _) -> chain in chains }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, contracts) -> contracts.distinct() }
        if (byChain.isEmpty()) return emptyMap()

        return coroutineScope {
            byChain
                .map { (chain, contracts) ->
                    async(Dispatchers.IO) {
                        runCatching { readChain(chain, padded, contracts) }.getOrDefault(emptyMap())
                    }
                }
                .fold(HashMap<String, Holding>()) { acc, deferred -> acc.apply { putAll(deferred.await()) } }
        }
    }

    private fun readChain(chain: HavenChain, paddedAddress: String, contracts: List<String>): Map<String, Holding> {
        val calls = ArrayList<RpcCall>(contracts.size * 3)
        contracts.forEach { contract ->
            calls += RpcCall(contract, Field.BALANCE, SELECTOR_BALANCE_OF + paddedAddress)
            // Asked of everything: a collection reverts, the result is absent, and the threshold is
            // then read as a count — which is correct. Guessing the standard first could be wrong.
            calls += RpcCall(contract, Field.DECIMALS, SELECTOR_DECIMALS)
            calls += RpcCall(contract, Field.SYMBOL, SELECTOR_SYMBOL)
        }

        val batch = JSONArray()
        calls.forEachIndexed { index, call ->
            batch.put(
                JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("id", index)
                    .put("method", "eth_call")
                    .put("params", JSONArray().put(JSONObject().put("to", call.contract).put("data", call.data)).put("latest")),
            )
        }

        val request = Request.Builder()
            .url(endpoints.rpcUrl(chain))
            .post(batch.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyMap()
            response.body?.string() ?: return emptyMap()
        }

        val results = parseBatch(body)
        val balances = HashMap<String, BigInteger>()
        val decimals = HashMap<String, Int>()
        val symbols = HashMap<String, String>()
        calls.forEachIndexed { index, call ->
            val raw = results[index] ?: return@forEachIndexed
            when (call.field) {
                Field.BALANCE -> decodeWord(raw)?.let { balances[call.contract] = it }
                Field.DECIMALS -> decodeWord(raw)?.takeIf { it <= BigInteger.valueOf(77) }?.let { decimals[call.contract] = it.toInt() }
                Field.SYMBOL -> decodeAbiString(raw)?.let { symbols[call.contract] = it }
            }
        }

        return contracts.mapNotNull { contract ->
            val balance = balances[contract] ?: return@mapNotNull null
            gateKey(chain, contract) to Holding(balance, decimals[contract], symbols[contract])
        }.toMap()
    }

    /** JSON-RPC batches may come back out of order, so results are keyed by request id. */
    private fun parseBatch(body: String): Map<Int, String> {
        val array = runCatching { JSONArray(body) }.getOrNull()
            ?: runCatching { JSONArray().put(JSONObject(body)) }.getOrNull()
            ?: return emptyMap()
        val out = HashMap<Int, String>()
        for (index in 0 until array.length()) {
            val entry = array.optJSONObject(index) ?: continue
            if (entry.has("error")) continue
            val id = entry.optInt("id", -1)
            if (id < 0) continue
            out[id] = entry.optString("result", "")
        }
        return out
    }

    private fun padAddress(address: String): String? {
        val hex = address.trim().removePrefix("0x").lowercase()
        if (hex.length != ADDRESS_HEX_LENGTH || !hex.all { it.isDigit() || it in 'a'..'f' }) return null
        return hex.padStart(ABI_WORD_HEX_LENGTH, '0')
    }

    private data class RpcCall(val contract: String, val field: Field, val data: String)

    private enum class Field { BALANCE, DECIMALS, SYMBOL }

    private companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val SELECTOR_BALANCE_OF = "0x70a08231"
        const val SELECTOR_DECIMALS = "0x313ce567"
        const val SELECTOR_SYMBOL = "0x95d89b41"
        const val ADDRESS_HEX_LENGTH = 40
        const val ABI_WORD_HEX_LENGTH = 64
        const val TIMEOUT_SECONDS = 15L
    }
}

internal fun decodeWord(raw: String): BigInteger? {
    if (raw.isEmpty() || raw == "0x") return null
    val hex = raw.removePrefix("0x")
    if (hex.isEmpty() || !hex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return null
    return runCatching { BigInteger(hex, 16) }.getOrNull()
}

/**
 * `symbol()` as ABI `string`, or the legacy `bytes32` some early tokens return. Null for anything
 * that isn't short printable text — a symbol is shown to the reader, so garbage stays out.
 */
internal fun decodeAbiString(raw: String): String? {
    val hex = raw.removePrefix("0x")
    if (hex.length < 64 || hex.length % 2 != 0) return null
    val bytes = runCatching { ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }
        .getOrNull() ?: return null

    val text = if (bytes.size >= 64) {
        val offset = BigInteger(1, bytes.copyOfRange(0, 32)).toInt()
        if (offset < 0 || offset + 32 > bytes.size) return null
        val length = BigInteger(1, bytes.copyOfRange(offset, offset + 32)).toInt()
        if (length < 0 || offset + 32 + length > bytes.size) return null
        String(bytes, offset + 32, length, Charsets.UTF_8)
    } else {
        String(bytes.takeWhile { it != 0.toByte() }.toByteArray(), Charsets.UTF_8)
    }
    return text.trim().takeIf { it.isNotEmpty() && it.length <= 16 && it.all { c -> c.code in 0x20..0x7E } }
}
