package app.shura.source.host

/**
 * The one thing that went wrong, named.
 *
 * The UI has to say different things depending on what failed: a repository that cannot be
 * reached is a connectivity problem and retrying later is the right advice, a repository that
 * answered with something this host cannot read is a repository problem, and an extension that
 * fails its signature check is a security problem that must never be retried into acceptance.
 * Collapsing all three into one exception is what makes a broken install look like a broken
 * network, so each failure mode gets its own type here, and the whole set shares one parent so a
 * caller can still catch "the repository layer" as a whole.
 *
 * Failures *inside* an extension are not in this hierarchy: they surface as [ExtensionLoadException],
 * [UnsupportedAbiException] or an `app.shura.source.api` [app.shura.source.api.SourceException], so
 * "the extension is broken" is never confused with "the repository is broken".
 */
sealed class RepositoryException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * The repository could not be reached at all: no DNS, no route, a refused connection or a timeout.
 *
 * Never raised for a response this host received and then disliked -- that is
 * [RepositoryResponseException]. The distinction is what tells a caller whether retrying can help.
 */
class RepositoryUnreachableException(
    val url: String,
    message: String,
    cause: Throwable? = null,
) : RepositoryException("$url: $message", cause)

/**
 * The repository answered, and the answer cannot be used.
 *
 * A wrong status, a body over the size cap, bytes that are not the JSON this host reads, an index
 * with no extensions in it: all of these mean the response itself is the problem.
 */
class RepositoryResponseException(
    val url: String,
    message: String,
    cause: Throwable? = null,
) : RepositoryException("$url: $message", cause)

/**
 * The repository answered with something internally inconsistent, or with metadata that fails its
 * own contract.
 *
 * Raised when `signingKey` is not a 64 character SHA-256 digest, because a client compares that
 * value against the certificate it reads out of an APK and any other shape matches nothing -- so
 * every install would fail closed. An index that cannot describe its own signature verification is
 * refused rather than half believed.
 */
class RepositoryIntegrityException(
    val url: String,
    message: String,
) : RepositoryException("$url: $message")

/** An extension the index lists cannot be downloaded from the URL the index publishes. */
class ExtensionArtifactUnavailableException(
    val packageName: String,
    val url: String?,
    message: String,
    cause: Throwable? = null,
) : RepositoryException("$packageName: $message", cause)

/**
 * A downloaded extension failed verification and must not be installed.
 *
 * This is the refusal path: nothing reaches the store, nothing is registered, and the previously
 * installed version is left exactly as it was. The message says which check failed and what was
 * expected, because "verification failed" on its own is not actionable.
 */
class ExtensionVerificationException(
    val packageName: String,
    message: String,
) : RepositoryException("$packageName: $message")

/** The extension store could not read or write its own directory. */
class ExtensionStorageException(
    val path: String,
    message: String,
    cause: Throwable? = null,
) : RepositoryException("$path: $message", cause)

/**
 * Turns a transport level failure into the right repository level exception.
 *
 * The cause chain is walked rather than only the top type, because `HttpURLConnection` does not
 * surface a refused connection as a `ConnectException`: it arrives wrapped, and matching only the
 * outermost type would report "cannot be reached" as a malformed response. Walking the chain is also
 * what makes this survive a transport that wraps everything in one exception type.
 *
 * The mapping is the decision the UI depends on: a socket timeout, an unknown host, a refused
 * connection and a failed handshake all mean "retry later", while a connection that was established
 * and then broke means the response itself is unusable.
 */
internal fun transportFailure(url: String, failure: Throwable): RepositoryException {
    val chain = generateSequence(failure) { current ->
        current.cause?.takeIf { it !== current }
    }.toList()
    fun described(vararg types: Class<*>): Throwable? = chain.firstOrNull { candidate ->
        types.any { type -> type.isInstance(candidate) }
    }

    val unreachable = described(
        java.net.UnknownHostException::class.java,
        java.net.SocketTimeoutException::class.java,
        java.net.ConnectException::class.java,
        java.net.NoRouteToHostException::class.java,
        javax.net.ssl.SSLException::class.java,
    )
    if (unreachable != null) {
        val reason = when (unreachable) {
            is java.net.UnknownHostException -> "no such host"
            is java.net.SocketTimeoutException -> "timed out"
            is java.net.ConnectException -> "connection refused"
            is java.net.NoRouteToHostException -> "no route to host"
            else -> "TLS failed: ${unreachable.message}"
        }
        return RepositoryUnreachableException(url, reason, failure)
    }
    val message = chain.firstNotNullOfOrNull { it.message?.takeIf(String::isNotBlank) }
        ?: failure.javaClass.simpleName
    return RepositoryResponseException(url, message, failure)
}
