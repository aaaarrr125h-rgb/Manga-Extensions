package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import rx.Observable

/**
 * A basic interface for creating a source. It could be an online source, a local source, etc...
 *
 * ABI 1.6 (`tachiyomix` 1.6): every entry point is `suspend`. The RxJava methods survive only
 * as deprecated shims and are never used by the Shura host.
 */
@Suppress("Unused")
interface Source {

    /**
     * ID for the source. Must be unique.
     */
    val id: Long

    /**
     * Name of the source.
     */
    val name: String

    /**
     * Whether the source has support for latest updates.
     */
    val supportsLatest: Boolean

    /**
     * Returns the list of filters for the source.
     */
    fun getFilterList(): FilterList = throw Exception("Stub!")

    /**
     * Get a page with a list of manga.
     *
     * @since tachiyomix 1.6
     */
    suspend fun getPopularManga(page: Int): MangasPage

    /**
     * Get a page with a list of latest manga updates.
     *
     * @since tachiyomix 1.6
     */
    suspend fun getLatestUpdates(page: Int): MangasPage

    /**
     * Get a page with a list of manga.
     *
     * @since tachiyomix 1.6
     */
    suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage

    /**
     * Fetches updated information for a manga.
     *
     * @since tachiyomix 1.6
     */
    suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate

    /**
     * Get the list of pages a chapter has. Pages should be returned
     * in the expected order; the index is ignored.
     *
     * @since tachiyomix 1.6
     */
    suspend fun getPageList(chapter: SChapter): List<Page>

    @Deprecated("Use the combined suspend API instead", ReplaceWith("getMangaUpdate"))
    fun fetchMangaDetails(manga: SManga): Observable<SManga> = throw Exception("Stub!")

    @Deprecated("Use the combined suspend API instead", ReplaceWith("getMangaUpdate"))
    fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = throw Exception("Stub!")

    @Deprecated("Use the suspend API instead", ReplaceWith("getPageList"))
    fun fetchPageList(chapter: SChapter): Observable<List<Page>> = throw Exception("Stub!")
}
