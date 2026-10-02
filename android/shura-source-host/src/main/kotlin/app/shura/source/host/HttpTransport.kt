package app.shura.source.host

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

/**
 * A response body this host has agreed to hold in memory, with a size cap already applied.
 */
class HttpResponse(
    val url: String,
    val status: Int,
    val contentType: String?,
    val body: ByteArray,
) {
    /** The body as UTF-8, which is what every JSON index in this repository is. */
    fun bodyAsText(): String = String(body, Charsets.UTF_8)

    override fun toString(): String =
        "HttpResponse(url=$url, status=$status, contentType=$contentType, bytes=${body.size})"
}

/**
 * The only way this host reaches the network.
 *
 * The repository and installation logic depends on this interface rather than on
 * [HttpURLConnection] directly, so tests can serve a real loopback HTTP server and still drive the
 * exact production code path, and so nothing above this line can quietly open its own connection
 * with different timeouts or no size cap.
 *
 * Implementations are blocking. Callers on a dispatcher move them off the main thread.
 */
interface HttpTransport {

    /**
     * Fetches [url], following redirects.
     *
     * @throws RepositoryUnreachableException if the request never produced a response.
     * @throws RepositoryResponseException if a response arrived but cannot be used.
     */
    fun get(url: String): HttpResponse
}

/**
 * The production [HttpTransport], built on `HttpURLConnection`.
 *
 * No new dependency is introduced for this: `HttpURLConnection` is part of the platform, so this
 * works unchanged on a device and inside a plain JVM test.
 *
 * Every download is bounded. [maxBodyBytes] caps the body as it streams, so a server that announces
 * an enormous index is refused while it is still arriving rather than after it has been buffered.
 * Redirects are followed because extension artifacts live behind GitHub's redirect to its CDN, and
 * the hop is counted so a redirect loop cannot spin forever.
 */
class UrlHttpTransport(
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 30_000,
    private val maxBodyBytes: Long = 32L * 1024 * 1024,
    private val maxRedirects: Int = 5,
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val proxy: Proxy = Proxy.NO_PROXY,
) : HttpTransport {

    override fun get(url: String): HttpResponse {
        var current = url
        repeat(maxRedirects + 1) { hop ->
            val connection = open(current)
            try {
                // responseCode is what actually connects, so it is where a refused connection,
                // an unknown host or a handshake failure surfaces.
                val status = try {
                    connection.responseCode
                } catch (e: IOException) {
                    throw transportFailure(url, e)
                }
                when (status) {
                    in 200..299 -> return readBody(current, connection)

                    in REDIRECT_STATUSES -> {
                        val location = connection.getHeaderField("Location")
                        if (location.isNullOrBlank()) {
                            throw RepositoryResponseException(current, "status $status with no Location header")
                        }
                        val next = URL(URL(current), location).toString()
                        RedirectPolicy.requireAllowed(original = url, next = next)
                        current = next
                    }

                    else -> throw RepositoryResponseException(
                        current,
                        "unexpected status $status ${connection.responseMessage.orEmpty()}".trim(),
                    )
                }
            } finally {
                connection.disconnect()
            }
        }
        throw RepositoryResponseException(url, "more than $maxRedirects redirects")
    }

    private fun open(url: String): HttpURLConnection {
        val connection = try {
            (URL(url).openConnection(proxy) as HttpURLConnection)
        } catch (e: IOException) {
            throw transportFailure(url, e)
        }
        return connection.apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMillis
            readTimeout = readTimeoutMillis
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept-Encoding", "identity")
        }
    }

    private fun readBody(url: String, connection: HttpURLConnection): HttpResponse {
        val announced = connection.contentLengthLong
        if (announced > maxBodyBytes) {
            throw RepositoryResponseException(
                url,
                "announced body of $announced bytes exceeds the ${maxBodyBytes} byte limit",
            )
        }
        val body = try {
            readBounded(url, connection.inputStream)
        } catch (e: IOException) {
            throw transportFailure(url, e)
        }
        return HttpResponse(
            url = url,
            status = connection.responseCode,
            contentType = connection.contentType,
            body = body,
        )
    }

    private fun readBounded(url: String, stream: InputStream): ByteArray {
        val buffer = ByteArray(STREAM_BUFFER)
        val collected = ByteArrayOutputStream()
        var total = 0L
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBodyBytes) {
                throw RepositoryResponseException(url, "body exceeds the ${maxBodyBytes} byte limit")
            }
            collected.write(buffer, 0, read)
        }
        return collected.toByteArray()
    }

        // RedirectPolicy allows or refuses this hop.
    private companion object {
        const val DEFAULT_USER_AGENT = "Shura/1.0 (+https://github.com/aaaarrr125h-rgb/Manga-Extensions)"
        val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
        const val STREAM_BUFFER = 16 * 1024
    }
}

/**
 * Which redirects this host is willing to follow.
 *
 * The repository is published over HTTPS and an APK that arrives unencrypted cannot be trusted to be
 * the APK the index named, so a hop from `https` to `http` is refused rather than followed. A hop
 * from `http` up to `https` is fine, and a chain that starts in plaintext stays allowed, which is
 * what makes the loop testable against a loopback server.
 *
 * This is a pure function of two URLs on purpose: the rule deserves a test that asserts the refusal
 * itself, not one that asserts a DNS failure happened to happen first.
 */
internal object RedirectPolicy {

    fun requireAllowed(original: String, next: String) {
        val originalIsSecure = original.startsWith(HTTPS_PREFIX)
        val nextIsPlaintext = next.startsWith(HTTP_PREFIX)
        if (originalIsSecure && nextIsPlaintext) {
            throw RepositoryResponseException(
                original,
                "redirect downgrades to plaintext: $next",
            )
        }
    }

    private const val HTTPS_PREFIX = "https://"
    private const val HTTP_PREFIX = "http://"
}
