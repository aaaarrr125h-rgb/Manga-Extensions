package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import rx.Observable

/**
 * ABI 1.6 catalogue source.
 *
 * Deprecated at this level, kept because published 1.6 extensions still implement it, and
 * because `HttpSource` extends it.
 */
@Suppress("Unused")
interface CatalogueSource : Source {

    /**
     * An ISO 639-1 compliant language code (two letters in lower case).
     */
    val lang: String

    override suspend fun getPopularManga(page: Int): MangasPage = throw Exception("Stub!")

    override suspend fun getLatestUpdates(page: Int): MangasPage = throw Exception("Stub!")

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage = throw Exception("Stub!")

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = throw Exception("Stub!")

    override suspend fun getPageList(chapter: SChapter): List<Page> = throw Exception("Stub!")

    @Deprecated("Use the suspend API instead", ReplaceWith("getPopularManga"))
    fun fetchPopularManga(page: Int): Observable<MangasPage> = throw Exception("Stub!")

    @Deprecated("Use the suspend API instead", ReplaceWith("getSearchManga"))
    fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> = throw Exception("Stub!")

    @Deprecated("Use the suspend API instead", ReplaceWith("getLatestUpdates"))
    fun fetchLatestUpdates(page: Int): Observable<MangasPage> = throw Exception("Stub!")
}
