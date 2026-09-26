package haven.mobile.core.arkiv

import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import javax.net.ssl.SSLHandshakeException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Transport failures render verbatim on Feed, Library, and Launches — so the host, the
 * exception class, and the root cause travel IN the message, not just the cause. These pin
 * that shape without touching the network (no tree is planted here either, which also proves
 * the log call is a safe no-op off-device).
 */
class NetworkErrorDiagnosticsTest {

    private val client = ArkivClientImpl(ArkivConfig(endpointUrl = "https://rpc.example.test:443"))

    private fun messageFor(e: Exception) = with(client) { networkError("test", e).message }

    @Test
    fun `dns failure names host class and detail`() {
        val message = messageFor(UnknownHostException("Unable to resolve host \"rpc.example.test\""))

        assertTrue(message.contains("rpc.example.test"), "host missing: $message")
        assertTrue(message.contains("UnknownHostException"), "class missing: $message")
        assertTrue(message.contains("Unable to resolve host"), "detail missing: $message")
        assertTrue(message.startsWith("Couldn't reach "), "prefix changed: $message")
    }

    @Test
    fun `tls failure surfaces its root cause`() {
        val root = CertPathValidatorException("Trust anchor for certification path not found.")
        val tls = SSLHandshakeException("Handshake failed").apply { initCause(root) }

        val message = messageFor(tls)

        assertTrue(message.contains("SSLHandshakeException"), "outer class missing: $message")
        assertTrue(
            message.contains("caused by CertPathValidatorException: Trust anchor"),
            "root cause missing: $message",
        )
    }

    @Test
    fun `identical root message is not repeated`() {
        val message = messageFor(RuntimeException("boom", RuntimeException("boom")))

        assertTrue(!message.contains("caused by"), "redundant root repeated: $message")
    }

    @Test
    fun `long detail is truncated but host survives`() {
        val message = messageFor(SocketTimeoutException("t".repeat(500)))

        assertTrue(message.contains("rpc.example.test"), "host missing: $message")
        assertTrue(message.length < 500, "message unbounded (${message.length}): $message")
    }

    @Test
    fun `exception without detail shows its class`() {
        val message = messageFor(SocketTimeoutException())

        assertTrue(message.contains("SocketTimeoutException"), "class missing: $message")
        assertTrue(!message.contains("null"), "null leaked into message: $message")
    }

    @Test
    fun `blank endpoint degrades to Arkiv`() {
        val blank = ArkivClientImpl(ArkivConfig(endpointUrl = ""))
        val message = with(blank) { networkError("test", UnknownHostException("x")).message }

        assertTrue(message.contains("Couldn't reach Arkiv "), "fallback missing: $message")
    }

    @Test
    fun `unparseable endpoint falls back to the raw value`() {
        val raw = "not a url"
        val weird = ArkivClientImpl(ArkivConfig(endpointUrl = raw))
        val message = with(weird) { networkError("test", UnknownHostException("x")).message }

        assertTrue(message.contains(raw), "raw endpoint missing: $message")
    }

    @Test
    fun `failing call is named in the message`() {
        val message = with(client) { networkError("listLaunches", UnknownHostException("x")).message }

        assertTrue(message.contains("[listLaunches]"), "source missing: $message")
    }

    @Test
    fun `original exception is preserved as the cause`() {
        val failure = UnknownHostException("nope")
        val error = with(client) { networkError("test", failure) }

        assertEquals(failure, error.cause)
    }
}
