package app.shura.source.api

/**
 * Stable identity of a single source inside the whole catalogue.
 *
 * The [packageName] is the extension APK's package; the [sourceId] is the id the extension
 * itself reports. Together they are unique: one extension can ship many sources, and the same
 * site can be shipped by more than one extension.
 *
 * The wire form is `"<packageName>#<sourceId>"`, which is what gets written to the database and
 * what travels across the Shura Sync layer, so [parse] is deliberately strict.
 */
@JvmInline
value class SourceKey(val value: String) {

    init {
        val separator = value.indexOf(SEPARATOR)
        require(separator > 0) { "SourceKey must be '<packageName>#<sourceId>', got '$value'" }
        val id = value.substring(separator + 1)
        require(id.toLongOrNull() != null) { "SourceKey source id is not a Long, got '$id'" }
        require(isValidPackageName(value.substring(0, separator))) {
            "SourceKey package name is not a valid package name: '${value.substring(0, separator)}'"
        }
    }

    val packageName: String get() = value.substring(0, value.indexOf(SEPARATOR))

    val sourceId: Long get() = value.substring(value.indexOf(SEPARATOR) + 1).toLong()

    override fun toString(): String = value

    companion object {
        const val SEPARATOR: Char = '#'

        fun of(packageName: String, sourceId: Long): SourceKey =
            SourceKey("$packageName$SEPARATOR$sourceId")

        fun parse(raw: String): SourceKey = SourceKey(raw)

        fun parseOrNull(raw: String): SourceKey? = runCatching { SourceKey(raw) }.getOrNull()

        /**
         * A package name is one or more dot separated identifiers. Each identifier starts with
         * a letter and continues with letters, digits or underscores.
         */
        fun isValidPackageName(candidate: String): Boolean {
            if (candidate.isEmpty()) return false
            return candidate.split('.').all { segment ->
                segment.isNotEmpty() &&
                    segment[0].isLetter() &&
                    segment.all { it.isLetterOrDigit() || it == '_' }
            }
        }
    }
}
