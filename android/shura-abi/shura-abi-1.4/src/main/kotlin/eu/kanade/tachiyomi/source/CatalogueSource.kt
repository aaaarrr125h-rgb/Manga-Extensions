package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import rx.Observable

/**
 * ABI 1.4 catalogue source.
 *
 * Every browse entry point is RxJava. There is no `suspend` equivalent in this level, which is
 * exactly why the host needs a bridge rather than a cast.
 */
@Suppress("unused")
interface CatalogueSource : Source {

    /**
     * An ISO 639-1 compliant language code (two letters in lower case).
     */
    val lang: String

    /**
     * Whether the source has support for latest updates.
     */
    val supportsLatest: Boolean

    /**
     * Returns an observable containing a page with a list of manga.
     */
    fun fetchPopularManga(page: Int): Observable<MangasPage>

    /**
     * Returns an observable containing a page with a list of manga.
     */
    fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage>

    /**
     * Returns an observable containing a page with a list of latest manga updates.
     */
    fun fetchLatestUpdates(page: Int): Observable<MangasPage>

    /**
     * Returns the list of filters for the source.
     */
    fun getFilterList(): FilterList
}
