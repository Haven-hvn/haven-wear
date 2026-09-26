package haven.mobile.core.haven.aol

import dev.ic.kotlin.agent.AgentException
import dev.ic.kotlin.agent.AnonymousIdentity
import dev.ic.kotlin.agent.HttpTransport
import dev.ic.kotlin.agent.PollTimeoutException
import dev.ic.kotlin.agent.Reply
import dev.ic.kotlin.agent.TransportCallResponse
import dev.ic.kotlin.agent.computeContentRequestId
import dev.ic.kotlin.candid.Principal
import dev.ic.kotlin.cbor.CborEncoder
import dev.ic.kotlin.cbor.CborValue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

/**
 * `IcCallWithPolling` against a scripted transport. Certificates are hand-encoded per the
 * IC hash-tree spec ([0]=empty, [1]=fork, [2]=labeled, [3]=leaf) so the driver parses real
 * cert bytes — including the v3 sync-`processing` shape ic-kotlin 0.1.0 throws on.
 */
class IcCallWithPollingTest {

    private val canister = Principal.fromText("aaaaa-aa")
    private val method = "requestDecryptionKey"
    private val arg = "candid-arg".toByteArray(Charsets.UTF_8)
    private val fixedNowMs = 1_786_000_000_000L
    private val ingressExpiry = fixedNowMs * 1_000_000L + IcCallWithPolling.INGRESS_EXPIRY_OFFSET_NANOS
    private val requestId = computeContentRequestId(
        "call", AnonymousIdentity.senderPrincipal, canister, method, arg, ingressExpiry, null, null
    )

    @org.junit.Test
    fun `v3 sync processing falls back to polling and returns the reply`() = runBlocking {
        val replyArg = "gate-key-bytes".toByteArray(Charsets.UTF_8)
        val transport = FakeTransport(
            callResponse = TransportCallResponse.Replied(certResponse(statusTree("processing", emptyMap()))),
            readStates = listOf(certResponse(statusTree("replied", mapOf("reply" to replyArg)))),
        )

        val reply = driver(transport).call(canister, method, arg)

        assertTrue(reply is Reply.Replied)
        assertArrayEquals(replyArg, (reply as Reply.Replied).arg)
        assertEquals(1, transport.readStateCalls)
    }

    @org.junit.Test
    fun `unknown future sync status keeps polling instead of throwing`() = runBlocking {
        val replyArg = "gate-key-bytes".toByteArray(Charsets.UTF_8)
        val transport = FakeTransport(
            callResponse = TransportCallResponse.Replied(certResponse(statusTree("running", emptyMap()))),
            readStates = listOf(certResponse(statusTree("replied", mapOf("reply" to replyArg)))),
        )

        val reply = driver(transport).call(canister, method, arg)

        assertTrue(reply is Reply.Replied)
        assertArrayEquals(replyArg, (reply as Reply.Replied).arg)
    }

    @org.junit.Test
    fun `accepted polls until replied`() = runBlocking {
        val replyArg = "gate-key-bytes".toByteArray(Charsets.UTF_8)
        val transport = FakeTransport(
            callResponse = TransportCallResponse.Accepted,
            readStates = listOf(
                certResponse(statusTree("processing", emptyMap())),
                certResponse(statusTree("replied", mapOf("reply" to replyArg))),
            ),
        )

        val reply = driver(transport).call(canister, method, arg)

        assertTrue(reply is Reply.Replied)
        assertArrayEquals(replyArg, (reply as Reply.Replied).arg)
        assertEquals(2, transport.readStateCalls)
    }

    @org.junit.Test
    fun `sync replied returns without polling`() = runBlocking {
        val replyArg = "gate-key-bytes".toByteArray(Charsets.UTF_8)
        val transport = FakeTransport(
            callResponse = TransportCallResponse.Replied(certResponse(statusTree("replied", mapOf("reply" to replyArg)))),
            readStates = emptyList(),
        )

        val reply = driver(transport).call(canister, method, arg)

        assertTrue(reply is Reply.Replied)
        assertArrayEquals(replyArg, (reply as Reply.Replied).arg)
        assertEquals(0, transport.readStateCalls)
    }

    @org.junit.Test
    fun `sync rejected maps code message and error code`() = runBlocking {
        val transport = FakeTransport(
            callResponse = TransportCallResponse.Replied(
                certResponse(
                    statusTree(
                        "rejected",
                        mapOf(
                            "reject_code" to byteArrayOf(5),
                            "reject_message" to "canister trapped".toByteArray(Charsets.UTF_8),
                            "error_code" to "IC0503".toByteArray(Charsets.UTF_8),
                        ),
                    )
                )
            ),
            readStates = emptyList(),
        )

        val reply = driver(transport).call(canister, method, arg)

        assertTrue(reply is Reply.Rejected)
        reply as Reply.Rejected
        assertEquals(5L, reply.code)
        assertEquals("canister trapped", reply.message)
        assertEquals("IC0503", reply.errorCode)
    }

    @org.junit.Test
    fun `done status fails fast like upstream`() = runBlocking {
        val transport = FakeTransport(
            callResponse = TransportCallResponse.Replied(certResponse(statusTree("done", emptyMap()))),
            readStates = emptyList(),
        )

        try {
            driver(transport).call(canister, method, arg)
            fail("expected AgentException for pruned (done) status")
        } catch (e: AgentException) {
            assertTrue(e.message.orEmpty().contains("pruned"))
        }
    }

    @org.junit.Test
    fun `missing certificate field fails with a clear error`() = runBlocking {
        val transport = FakeTransport(
            callResponse = TransportCallResponse.Replied(
                CborEncoder.encode(CborValue.CborMap(mapOf(CborValue.CborTextString("nope") to CborValue.CborUnsigned(1))))
            ),
            readStates = emptyList(),
        )

        try {
            driver(transport).call(canister, method, arg)
            fail("expected AgentException for missing certificate")
        } catch (e: AgentException) {
            assertTrue(e.message.orEmpty().contains("certificate"))
        }
    }

    @org.junit.Test
    fun `endless processing times out instead of hanging`() = runBlocking {
        val transport = FakeTransport(
            callResponse = TransportCallResponse.Accepted,
            readStates = listOf(certResponse(statusTree("processing", emptyMap()))),
        )
        // Real clock: the fixed test clock would never advance past the timeout.
        val impatient = IcCallWithPolling(
            transport, AnonymousIdentity, pollTimeoutMs = 25, pollIntervalMs = 1, maxPollIntervalMs = 2,
        )

        try {
            impatient.call(canister, method, arg)
            fail("expected PollTimeoutException")
        } catch (e: PollTimeoutException) {
            assertTrue(e.message.orEmpty().contains("timed out"))
        }
        assertTrue(transport.readStateCalls >= 1)
    }

    @org.junit.Test
    fun `leb128 decoding covers multi-byte reject codes`() {
        assertEquals(5L, IcCallWithPolling.decodeLeb128(byteArrayOf(5)))
        assertEquals(300L, IcCallWithPolling.decodeLeb128(byteArrayOf(0xAC.toByte(), 0x02)))
        assertEquals(0L, IcCallWithPolling.decodeLeb128(byteArrayOf()))
    }

    private fun driver(transport: FakeTransport) = IcCallWithPolling(
        transport, AnonymousIdentity, pollTimeoutMs = 5_000, pollIntervalMs = 1, maxPollIntervalMs = 2,
        clockMs = { fixedNowMs },
    )

    /** `request_status/<requestId>/<status + extra leaves>` as hash-tree CBOR. */
    private fun statusTree(status: String, extra: Map<String, ByteArray>): CborValue {
        var node: CborValue = labeled("status", leaf(status.toByteArray(Charsets.UTF_8)))
        for ((key, value) in extra) {
            node = fork(node, labeled(key, leaf(value)))
        }
        return labeled("request_status", labeledBytes(requestId, node))
    }

    private fun certResponse(tree: CborValue): ByteArray {
        val cert = CborValue.CborMap(
            mapOf(
                CborValue.CborTextString("tree") to tree,
                CborValue.CborTextString("signature") to CborValue.CborByteString(ByteArray(0)),
            )
        )
        val response = CborValue.CborMap(
            mapOf(CborValue.CborTextString("certificate") to CborValue.CborByteString(CborEncoder.encode(cert)))
        )
        return CborEncoder.encode(CborValue.CborTag(CborValue.SELF_DESCRIBE_TAG, response))
    }

    private fun labeled(label: String, subtree: CborValue) =
        labeledBytes(label.toByteArray(Charsets.UTF_8), subtree)

    private fun labeledBytes(label: ByteArray, subtree: CborValue) =
        CborValue.CborArray(listOf(CborValue.CborUnsigned(2), CborValue.CborByteString(label), subtree))

    private fun leaf(bytes: ByteArray) =
        CborValue.CborArray(listOf(CborValue.CborUnsigned(3), CborValue.CborByteString(bytes)))

    private fun fork(left: CborValue, right: CborValue) =
        CborValue.CborArray(listOf(CborValue.CborUnsigned(1), left, right))

    private class FakeTransport(
        private val callResponse: TransportCallResponse,
        private val readStates: List<ByteArray>,
    ) : HttpTransport {
        var readStateCalls = 0

        override suspend fun call(canisterId: Principal, envelope: ByteArray): TransportCallResponse = callResponse

        override suspend fun query(canisterId: Principal, envelope: ByteArray): ByteArray =
            throw UnsupportedOperationException("query unused")

        override suspend fun readState(canisterId: Principal, envelope: ByteArray): ByteArray {
            readStateCalls++
            return readStates[minOf(readStateCalls - 1, readStates.lastIndex)]
        }

        override suspend fun status(): ByteArray = throw UnsupportedOperationException("status unused")
    }
}
