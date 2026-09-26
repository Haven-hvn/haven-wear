package haven.mobile.core.haven.aol

import dev.ic.kotlin.agent.AgentException
import dev.ic.kotlin.agent.AnonymousIdentity
import dev.ic.kotlin.agent.HttpTransport
import dev.ic.kotlin.agent.Identity
import dev.ic.kotlin.agent.PollTimeoutException
import dev.ic.kotlin.agent.Reply
import dev.ic.kotlin.agent.TransportCallResponse
import dev.ic.kotlin.agent.buildCallEnvelope
import dev.ic.kotlin.agent.buildReadStateEnvelope
import dev.ic.kotlin.agent.computeContentRequestId
import dev.ic.kotlin.candid.Principal
import dev.ic.kotlin.cbor.CborDecoder
import dev.ic.kotlin.cbor.CborValue
import dev.ic.kotlin.crypto.certification.Certificate
import dev.ic.kotlin.crypto.certification.LookupResult
import kotlinx.coroutines.delay

/**
 * Update-call driver that survives slow executions.
 *
 * `requestDecryptionKey` legitimately takes 10s+ (EVM-RPC balance check, then VetKD
 * derivation). The `/api/v3` sync response then often arrives before execution finishes,
 * carrying a certificate whose status is `processing` — sometimes after seconds of waiting,
 * sometimes immediately. ic-kotlin 0.1.0's `IcAgent.call` throws
 * `AgentException("Unexpected request status in certificate: processing")` on that shape
 * instead of polling, which surfaced as "couldn't reach the service that unlocks this
 * item" right after signing.
 *
 * This driver treats any non-final sync status (`received`, `processing`, absent paths,
 * or anything unrecognized) as "keep polling `read_state`" — the same fallback the web
 * agent applies. Only `replied` / `rejected` return; `done` (pruned) fails like upstream.
 *
 * Built purely on ic-kotlin's public API so no Haven logic leaks into the transport
 * package; when ic-kotlin ships the upstream fix this driver can delegate again.
 */
class IcCallWithPolling(
    private val transport: HttpTransport,
    private val identity: Identity = AnonymousIdentity,
    private val pollTimeoutMs: Long = DEFAULT_POLL_TIMEOUT_MS,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    private val maxPollIntervalMs: Long = DEFAULT_MAX_POLL_INTERVAL_MS,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    suspend fun call(canisterId: Principal, method: String, arg: ByteArray): Reply {
        val sender = identity.senderPrincipal
        val ingressExpiry = clockMs() * 1_000_000L + INGRESS_EXPIRY_OFFSET_NANOS
        val envelope = buildCallEnvelope(sender, canisterId, method, arg, ingressExpiry, null, identity)
        val requestId = computeContentRequestId("call", sender, canisterId, method, arg, ingressExpiry, null, null)
        when (val response = transport.call(canisterId, envelope)) {
            is TransportCallResponse.Replied -> {
                when (val status = statusFromResponse(response.arg, requestId)) {
                    is SyncStatus.Final -> return status.reply
                    SyncStatus.Pending -> { /* execution still running — poll below */ }
                }
            }
            is TransportCallResponse.Accepted -> { /* poll below */ }
        }
        return poll(canisterId, requestId, ingressExpiry)
    }

    private suspend fun poll(canisterId: Principal, requestId: ByteArray, ingressExpiry: Long): Reply {
        val startMs = clockMs()
        var intervalMs = pollIntervalMs
        while (true) {
            if (clockMs() - startMs > pollTimeoutMs) {
                throw PollTimeoutException("Polling for request status timed out after ${pollTimeoutMs}ms")
            }
            delay(intervalMs)
            val paths = listOf(listOf(REQUEST_STATUS_LABEL, requestId))
            val envelope = buildReadStateEnvelope(identity.senderPrincipal, canisterId, ingressExpiry, paths, identity)
            when (val status = statusFromResponse(transport.readState(canisterId, envelope), requestId)) {
                is SyncStatus.Final -> return status.reply
                SyncStatus.Pending -> intervalMs = (intervalMs * 2).coerceAtMost(maxPollIntervalMs)
            }
        }
    }

    private fun statusFromResponse(responseBytes: ByteArray, requestId: ByteArray): SyncStatus {
        val decoded = CborDecoder.decode(responseBytes)
        val map = (unwrapSelfDescribe(decoded) as? CborValue.CborMap)?.entries
            ?: throw AgentException("Call response is not a CBOR map")
        val certBytes = when (val certValue = map[CborValue.CborTextString("certificate")]) {
            is CborValue.CborByteString -> certValue.bytes
            null -> throw AgentException("Call response missing 'certificate' field")
            else -> throw AgentException("Call response 'certificate' is not a byte string")
        }
        return statusFromCertificate(certBytes, requestId)
    }

    private fun statusFromCertificate(certBytes: ByteArray, requestId: ByteArray): SyncStatus {
        val tree = Certificate.fromCbor(certBytes).tree
        val statusBytes = when (val found = tree.lookup(listOf(REQUEST_STATUS_LABEL, requestId, STATUS_LABEL))) {
            is LookupResult.Found -> found.value
            else -> return SyncStatus.Pending
        }
        return when (String(statusBytes, Charsets.UTF_8)) {
            "replied" -> {
                val reply = when (val found = tree.lookup(listOf(REQUEST_STATUS_LABEL, requestId, REPLY_LABEL))) {
                    is LookupResult.Found -> found.value
                    else -> throw AgentException("Certificate has replied status but missing reply data")
                }
                SyncStatus.Final(Reply.Replied(reply))
            }
            "rejected" -> {
                val code = when (val found = tree.lookup(listOf(REQUEST_STATUS_LABEL, requestId, REJECT_CODE_LABEL))) {
                    is LookupResult.Found -> decodeLeb128(found.value)
                    else -> 0L
                }
                val message = when (val found = tree.lookup(listOf(REQUEST_STATUS_LABEL, requestId, REJECT_MESSAGE_LABEL))) {
                    is LookupResult.Found -> String(found.value, Charsets.UTF_8)
                    else -> "Unknown rejection"
                }
                val errorCode = when (val found = tree.lookup(listOf(REQUEST_STATUS_LABEL, requestId, ERROR_CODE_LABEL))) {
                    is LookupResult.Found -> String(found.value, Charsets.UTF_8)
                    else -> null
                }
                SyncStatus.Final(Reply.Rejected(code, message, errorCode))
            }
            "done" -> throw AgentException("Request completed but response was already pruned (status: done)")
            else -> SyncStatus.Pending
        }
    }

    private sealed interface SyncStatus {
        class Final(val reply: Reply) : SyncStatus
        data object Pending : SyncStatus
    }

    companion object {
        const val DEFAULT_POLL_TIMEOUT_MS = 5 * 60_000L
        const val DEFAULT_POLL_INTERVAL_MS = 1_000L
        const val DEFAULT_MAX_POLL_INTERVAL_MS = 30_000L
        /** Ingress expiry horizon: 5 minutes past now, in nanoseconds. */
        const val INGRESS_EXPIRY_OFFSET_NANOS = 300_000_000_000L

        private val REQUEST_STATUS_LABEL = "request_status".toByteArray(Charsets.UTF_8)
        private val STATUS_LABEL = "status".toByteArray(Charsets.UTF_8)
        private val REPLY_LABEL = "reply".toByteArray(Charsets.UTF_8)
        private val REJECT_CODE_LABEL = "reject_code".toByteArray(Charsets.UTF_8)
        private val REJECT_MESSAGE_LABEL = "reject_message".toByteArray(Charsets.UTF_8)
        private val ERROR_CODE_LABEL = "error_code".toByteArray(Charsets.UTF_8)

        internal fun unwrapSelfDescribe(value: CborValue): CborValue =
            if (value is CborValue.CborTag && value.tag == CborValue.SELF_DESCRIBE_TAG) value.value else value

        /** Unsigned LEB128 (`reject_code` wire form) to Long. */
        internal fun decodeLeb128(bytes: ByteArray): Long {
            var result = 0L
            var shift = 0
            for (b in bytes) {
                result = result or ((b.toLong() and 0x7F) shl shift)
                if (b.toInt() and 0x80 == 0) break
                shift += 7
            }
            return result
        }
    }
}
