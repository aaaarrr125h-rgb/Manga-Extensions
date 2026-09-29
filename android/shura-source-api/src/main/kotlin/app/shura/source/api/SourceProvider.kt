package app.shura.source.api

/**
 * A source, as Shura sees it.
 *
 * Every implementation is loaded from an extension in its own classloader, so an extension can
 * only reach the catalogue through this interface and the SHURA-native models. Nothing here
 * mentions the extension API level it was written against: an ABI 1.4 extension and an ABI 1.6
 * extension are the same type to the rest of the app.
 *
 * A method whose capability is not in [SourceDescriptor.capabilities] must throw
 * [UnsupportedSourceOperationException] rather than return something invented.
 */
interface SourceProvider {

    val descriptor: SourceDescriptor

    /** One page of the popular list, 1-based, as the site pages it. */
    suspend fun popularManga(page: Int): MangaListPage

    /** One page of the most recently updated list, 1-based. */
    suspend fun latestUpdates(page: Int): MangaListPage

    /** One page of search results, 1-based. */
    suspend fun searchManga(page: Int, query: String, filters: SourceFilters = SourceFilters.EMPTY): MangaListPage

    /** Re-read one entry. The returned object is the updated one, not the argument. */
    suspend fun mangaDetails(manga: MangaRef): Manga

    /** Every chapter the source currently lists for one entry. */
    suspend fun chapterList(manga: MangaRef): List<Chapter>

    /** The pages of one chapter, in reading order. */
    suspend fun pageList(chapter: ChapterRef): List<PageRef>
}

/**
 * Filters for [SourceProvider.searchManga], kept as a plain name to value map.
 *
 * The host does not model the extension's own `Filter` hierarchy: that hierarchy differs
 * between ABI 1.4 and 1.6, and every host implementation would end up branching on it. The
 * bridge inside the extension classloader owns that translation; what crosses the classloader
 * boundary is only a map, so the host stays ABI agnostic.
 */
@JvmInline
value class SourceFilters(val values: Map<String, String>) {

    val names: Set<String> get() = values.keys

    operator fun get(name: String): String? = values[name]

    fun with(name: String, value: String): SourceFilters =
        SourceFilters(values + (name to value))

    override fun toString(): String = values.toString()

    companion object {
        val EMPTY: SourceFilters = SourceFilters(emptyMap())
    }
}
