package android.net

/**
 * Compile time stand in for the platform class of the same name.
 *
 * Only the members the extension ABI surface actually touches are present. On a device the
 * real `android.net.Uri` is used; this class is `compileOnly` and never packaged.
 */
class Uri private constructor(private val raw: String) {

    val scheme: String?
        get() = raw.substringBefore("://", missingDelimiterValue = "").ifEmpty { null }?.lowercase()

    val host: String?
        get() = raw.substringAfter("://", missingDelimiterValue = "").substringBefore('/').ifEmpty { null }

    val path: String
        get() = raw.substringAfter('/', missingDelimiterValue = "")

    override fun toString(): String = raw

    override fun equals(other: Any?): Boolean = other is Uri && other.raw == raw

    override fun hashCode(): Int = raw.hashCode()

    companion object {
        @JvmStatic
        fun parse(value: String): Uri = Uri(value)
    }
}
