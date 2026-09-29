package app.shura.source.api

/**
 * One thing a source is able to do.
 *
 * The host advertises these to the UI and refuses calls that are not advertised, so an
 * extension that silently does not implement a method is a load-time failure rather than a
 * surprise at read time.
 */
enum class SourceCapability(internal val mask: Int) {
    /** `Source.getPopularManga(page)` / `CatalogueSource.fetchPopularManga(page)`. */
    BROWSE_POPULAR(1 shl 0),

    /** `Source.getLatestUpdates(page)` / `CatalogueSource.fetchLatestUpdates(page)`. */
    BROWSE_LATEST(1 shl 1),

    /** `Source.getSearchManga(page, query, filters)` / `fetchSearchManga(...)`. */
    SEARCH(1 shl 2),

    /** The source returns a non-empty `getFilterList()`. */
    SEARCH_FILTERS(1 shl 3),

    /** `Source.getMangaUpdate(..., fetchDetails = true)` / `getMangaDetails(manga)`. */
    FETCH_DETAILS(1 shl 4),

    /** `Source.getMangaUpdate(..., fetchChapters = true)` / `getChapterList(manga)`. */
    FETCH_CHAPTERS(1 shl 5),

    /** `Source.getPageList(chapter)` / `fetchPageList(chapter)`. */
    FETCH_PAGES(1 shl 6),

    /** The source implements `ConfigurableSource` and exposes a preference screen. */
    CONFIGURABLE(1 shl 7),

    /** `Source.supportsLatest` is true. */
    LATEST_SUPPORTED(1 shl 8),

    /** ABI 1.6 only: `getMangaUpdate` honours the two fetch flags independently. */
    INCREMENTAL_UPDATE(1 shl 9),

    /** The source has to drive a WebView to answer, e.g. for a Cloudflare interstitial. */
    REQUIRES_WEBVIEW(1 shl 10),

    /** The source implements `UnmeteredSource`, e.g. a self hosted server. */
    UNMETERED(1 shl 11),
    ;

    companion object {
        val ALL: Set<SourceCapability> = entries.toSet()
    }
}

/**
 * An immutable set of [SourceCapability], packed into one `Int` so it is cheap to carry around
 * and cheap to compare.
 */
@JvmInline
value class SourceCapabilities(val bits: Int) {

    operator fun contains(capability: SourceCapability): Boolean = bits and capability.mask != 0

    operator fun plus(capability: SourceCapability): SourceCapabilities =
        SourceCapabilities(bits or capability.mask)

    operator fun minus(capability: SourceCapability): SourceCapabilities =
        SourceCapabilities(bits and capability.mask.inv())

    fun hasAll(other: SourceCapabilities): Boolean = bits and other.bits == other.bits

    fun hasAny(other: SourceCapabilities): Boolean = bits and other.bits != 0

    fun without(other: SourceCapabilities): SourceCapabilities = SourceCapabilities(bits and other.bits.inv())

    /** The capabilities in enum order, so the result is stable and printable. */
    fun asList(): List<SourceCapability> =
        SourceCapability.entries.filter { it in this }

    fun asNames(): List<String> = asList().map { it.name }

    override fun toString(): String =
        asNames().joinToString(prefix = "[", postfix = "]").ifEmpty { "[]" }

    companion object {
        val NONE: SourceCapabilities = SourceCapabilities(0)

        val DEFAULT: SourceCapabilities = SourceCapabilities(
            SourceCapability.BROWSE_POPULAR.mask or
                SourceCapability.SEARCH.mask or
                SourceCapability.FETCH_DETAILS.mask or
                SourceCapability.FETCH_CHAPTERS.mask or
                SourceCapability.FETCH_PAGES.mask,
        )

        fun of(vararg capabilities: SourceCapability): SourceCapabilities =
            capabilities.fold(NONE) { acc, capability -> acc + capability }

        fun from(bits: Int): SourceCapabilities = SourceCapabilities(bits)

        fun parse(names: Iterable<String>): SourceCapabilities {
            val unknown = names.map { it.trim() }.filter { it.isNotEmpty() } - SourceCapability.entries.map { it.name }
            require(unknown.isEmpty()) { "Unknown source capabilities: $unknown" }
            return of(*names.map { it.trim() }.filter { it.isNotEmpty() }.map(SourceCapability::valueOf).toTypedArray())
        }

        fun parseOrNull(raw: String): SourceCapabilities? =
            runCatching { parse(raw.split(',', ';', ' ')) }.getOrNull()
    }
}
