package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import rx.Observable

/**
 * A basic interface for creating a source. It could be an online source, a local source, etc...
 *
 * ABI 1.4: browsing and pages are RxJava only, details and chapters are `suspend`.
 */
@Suppress("unused")
interface Source {

    /**
     * Id for the source. Must be unique.
     */
    val id: Long

    /**
     * Name of the source.
     */
    val name: String

    /**
     * Get the updated details for a manga.
     *
     * @since extensions-lib 1.4
     */
    suspend fun getMangaDetails(manga: SManga): SManga = throw Exception("Stub!")

    /**
     * Get all the available chapters for a manga.
     *
     * @since extensions-lib 1.4
     */
    suspend fun getChapterList(manga: SManga): List<SChapter> = throw Exception("Stub!")

    /**
     * Get the list of pages a chapter has. Pages should be returned
     * in the expected order; the index is ignored.
     */
    fun fetchPageList(chapter: SChapter): Observable<List<Page>>

    @Deprecated("Use the non-RxJava API instead", ReplaceWith("getMangaDetails"))
    fun fetchMangaDetails(manga: SManga): Observable<SManga> = throw Exception("Stub!")

    @Deprecated("Use the non-RxJava API instead", ReplaceWith("getChapterList"))
    fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = throw Exception("Stub!")
}
