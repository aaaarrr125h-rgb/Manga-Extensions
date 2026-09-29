package app.shura.source.api

/**
 * The extension API level an extension was compiled against, as published in the repository
 * index field `extensionLib` and in the extension manifest meta-data `tachiyomix.extensionLib`.
 *
 * The values are `MAJOR.MINOR` strings, exactly as they appear on the wire. Only the levels
 * that Shura can actually host are listed; an unknown level is an error, never a silent
 * downgrade to "the newest one we know".
 */
enum class ExtensionAbi(val version: String, val encoded: Int) {

    /**
     * `extensions-lib` 1.4. Browsing is RxJava only (`fetchPopularManga` returns
     * `Observable<MangasPage>`), details and chapters are `suspend`, pages are RxJava.
     */
    V1_4("1.4", 0x0104),

    /**
     * `tachiyomix` 1.6. Browsing, details, chapters and pages are all `suspend`, and
     * `getMangaUpdate(manga, chapters, fetchDetails, fetchChapters)` can update one half
     * without the other.
     */
    V1_6("1.6", 0x0106),
    ;

    /**
     * True for 1.6 and above, i.e. when the whole `Source` surface is `suspend`.
     *
     * Reads the entry after this one, so declaration order and version order have to agree.
     * The companion `init` block enforces that.
     */
    val isSuspendOnly: Boolean get() = ordinal >= V1_6.ordinal

    override fun toString(): String = version

    companion object {
        val OLDEST: ExtensionAbi = entries.first()
        val NEWEST: ExtensionAbi = entries.last()

        private val VERSION_PATTERN = Regex("""^\d+\.\d+$""")

        init {
            // A level added out of order would make isSuspendOnly and every comparison lie, and
            // the failure would only show up as a wrong capability at runtime.
            require(entries.zipWithNext().all { (a, b) -> a.encoded < b.encoded }) {
                "ExtensionAbi entries must be declared in ascending version order"
            }
        }

        fun parseOrNull(raw: String?): ExtensionAbi? {
            val trimmed = raw?.trim().orEmpty()
            if (!VERSION_PATTERN.matches(trimmed)) return null
            return entries.firstOrNull { it.version == trimmed }
        }

        fun parse(raw: String?): ExtensionAbi = parseOrNull(raw)
            ?: throw UnsupportedExtensionAbiException(
                version = raw.orEmpty(),
                supported = entries.map { it.version },
            )

        fun supports(raw: String?): Boolean = parseOrNull(raw) != null
    }
}

class UnsupportedExtensionAbiException(
    val version: String,
    val supported: List<String>,
) : IllegalArgumentException(
    "Extension API level '$version' is not supported by this host. Supported: ${supported.joinToString()}",
)
