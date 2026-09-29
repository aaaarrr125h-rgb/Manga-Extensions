package app.shura.source.api

/** Anything that goes wrong inside a source, wrapped so the host can show it as one thing. */
open class SourceException(
    val key: SourceKey,
    message: String,
    cause: Throwable? = null,
) : RuntimeException("[$key] $message", cause)

/** The source does not advertise the capability the caller asked for. */
class UnsupportedSourceOperationException(
    key: SourceKey,
    val capability: SourceCapability,
) : SourceException(key, "source does not support $capability")

/** The site answered, but with an error that looks like a login or a challenge. */
class SourceAuthenticationException(
    key: SourceKey,
    message: String = "source requires authentication",
    cause: Throwable? = null,
) : SourceException(key, message, cause)

/** The catalogue entry the caller passed in does not exist in the source any more. */
class SourceEntryNotFoundException(
    key: SourceKey,
    val ref: String,
) : SourceException(key, "no entry for ref '$ref'")
