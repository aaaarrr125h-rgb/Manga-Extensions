package app.shura.source.host

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [UrlHttpTransport] against a real loopback HTTP server.
 *
 * These are not stubbed responses: a real socket is opened and real bytes arrive. That is the point,
 * because the failures worth testing here are protocol level -- a redirect that never ends, a body
 * larger than the cap, a host that does not resolve -- and a fake transport would only ever prove the
 * fake behaves as written.
 */
class UrlHttpTransportTest {

    private var server: HttpServer? = null

    @AfterTest
    fun stopServer() {
        server?.stop(0)
        server = null
    }

    private fun serve(status: Int, body: ByteArray): String {
        val started = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        started.createContext("/") { exchange ->
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        started.executor = null
        started.start()
        server = started
        return "http://127.0.0.1:${started.address.port}"
    }

    /** A server whose only answer is a redirect to [location]. */
    private fun redirectingTo(location: String): String {
        val started = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        started.createContext("/") { exchange ->
            exchange.responseHeaders.add("Location", location)
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        started.executor = null
        started.start()
        server = started
        return "http://127.0.0.1:${started.address.port}"
    }

    @Test
    fun `reads a body and reports its status`() {
        val url = serve(200, "hello index".toByteArray())
        val response = UrlHttpTransport().get("$url/repo/index.json")
        assertEquals(200, response.status)
        assertEquals("hello index", response.bodyAsText())
        assertEquals(11, response.body.size)
    }

    @Test
    fun `a relative redirect is resolved and followed`() {
        val started = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        started.createContext("/start") { exchange ->
            exchange.responseHeaders.add("Location", "/end")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        started.createContext("/end") { exchange ->
            val body = "arrived".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        started.executor = null
        started.start()
        server = started

        val response = UrlHttpTransport().get("http://127.0.0.1:${started.address.port}/start")
        assertEquals("arrived", response.bodyAsText())
    }

    @Test
    fun `a redirect without a Location header is refused`() {
        val url = serve(302, ByteArray(0))
        val failure = assertFailsWith<RepositoryResponseException> { UrlHttpTransport().get("$url/x") }
        assertContains(failure.message.orEmpty(), "no Location header")
    }

    @Test
    fun `a self-referential redirect loop is bounded`() {
        val url = redirectingTo("/loop")
        val failure = assertFailsWith<RepositoryResponseException> {
            UrlHttpTransport(maxRedirects = 3).get("$url/loop")
        }
        assertContains(failure.message.orEmpty(), "redirects")
    }

    @Test
    fun `a status other than 2xx is a response failure`() {
        val url = serve(404, "missing".toByteArray())
        val failure = assertFailsWith<RepositoryResponseException> { UrlHttpTransport().get("$url/gone") }
        assertContains(failure.message.orEmpty(), "404")
    }

    @Test
    fun `a body over the cap is refused`() {
        val url = serve(200, ByteArray(64 * 1024) { 'a'.code.toByte() })
        val failure = assertFailsWith<RepositoryResponseException> {
            UrlHttpTransport(maxBodyBytes = 1024).get("$url/big")
        }
        assertContains(failure.message.orEmpty(), "limit")
    }

    @Test
    fun `a refused connection is a reachability failure`() {
        // Port 1 on loopback has nothing listening, so this fails without leaving the machine.
        val failure = assertFailsWith<RepositoryUnreachableException> {
            UrlHttpTransport(connectTimeoutMillis = 2_000).get("http://127.0.0.1:1/index.json")
        }
        assertContains(failure.message.orEmpty(), "connection refused")
    }

    @Test
    fun `an unresolvable host is a reachability failure`() {
        val failure = assertFailsWith<RepositoryUnreachableException> {
            UrlHttpTransport(connectTimeoutMillis = 2_000).get("http://shura.invalid/index.json")
        }
        assertContains(failure.message.orEmpty(), "no such host")
    }

    @Test
    fun `an empty body is returned as such rather than as a failure`() {
        // The repository client, not the transport, decides that an empty index is unusable.
        val url = serve(200, ByteArray(0))
        assertEquals(0, UrlHttpTransport().get("$url/empty").body.size)
    }
}

class RedirectPolicyTest {

    @Test
    fun `a hop from https to http is refused`() {
        val failure = assertFailsWith<RepositoryResponseException> {
            RedirectPolicy.requireAllowed("https://shura.example/index.json", "http://cdn.example/apk")
        }
        assertContains(failure.message.orEmpty(), "downgrades to plaintext")
    }

    @Test
    fun `a hop from https to https is allowed`() {
        RedirectPolicy.requireAllowed("https://shura.example/index.json", "https://cdn.example/apk")
    }

    @Test
    fun `a hop from http up to https is allowed`() {
        RedirectPolicy.requireAllowed("http://shura.example/index.json", "https://cdn.example/apk")
    }

    @Test
    fun `a plaintext chain is allowed so a loopback repository works`() {
        RedirectPolicy.requireAllowed("http://127.0.0.1:8080/index.json", "http://127.0.0.1:8081/apk")
        assertTrue(true)
    }

    @Test
    fun `a non http scheme is not treated as a downgrade`() {
        // Anything that is not plaintext-https is left to the URL layer, which will reject it.
        RedirectPolicy.requireAllowed("https://shura.example/index.json", "file:///etc/passwd")
    }
}