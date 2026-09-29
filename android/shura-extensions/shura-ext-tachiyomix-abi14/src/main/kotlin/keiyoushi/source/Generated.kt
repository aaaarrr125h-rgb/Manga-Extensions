package keiyoushi.source

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import rx.Observable

/**
 * Entry point named in the extension manifest meta-data `tachiyomi.extension.class`.
 *
 * This is the same fully qualified name the upstream build generates, so the loader logic is
 * the same one a real extension goes through.
 */
class Generated : SourceFactory {
    override fun createSources(): List<Source> = listOf(TachiyomiMix(), TachiyomiMixNoLatest())
}

/**
 * ABI 1.4 fixture source.
 *
 * It is a contract fixture, not a scraper: the catalogue it returns is fixed, and nothing here
 * has been checked against a live TachiyomiMix site. What it does prove is the shape the host
 * has to survive at this level — RxJava for browse and pages, `suspend` for details and
 * chapters, `Float` chapter numbers, no structured genres, no banner, no score.
 */
open class TachiyomiMix : CatalogueSource {

    override val id: Long = 3_196_562_139_457_428_713L
    override val name: String = "TachiyomiMix"
    override val lang: String = "all"
    override val supportsLatest: Boolean = true

    override fun fetchPopularManga(page: Int): Observable<MangasPage> =
        Observable.just(page(page, "Popular"))

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> =
        Observable.just(page(page, "Latest"))

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        val text = filters.filterIsInstance<Filter.Text>().firstOrNull { it.name == "Search" }?.state.orEmpty()
        val needle = text.ifEmpty { query }
        return Observable.just(page(page, "Search", needle))
    }

    // Filter.Text is abstract in the published surface, exactly as a real extension sees it.
    override fun getFilterList(): FilterList = FilterList(object : Filter.Text("Search", "") {})

    override suspend fun getMangaDetails(manga: SManga): SManga = manga.apply {
        title = manga.title
        author = "fixture"
        artist = "fixture"
        status = SManga.ONGOING
        description = "Deterministic fixture entry for host testing."
        genre = "action, fantasy"
        thumbnail_url = "https://example.invalid/cover/${manga.url}.jpg"
        initialized = true
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = listOf(
        chapter("${manga.url}/chapter-1", "Chapter 1", 1f),
        chapter("${manga.url}/chapter-2", "Chapter 2", 2f),
    )

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = Observable.just(
        List(3) { index -> Page(index = index, url = "${chapter.url}/page-${index + 1}") },
    )

    private fun page(page: Int, label: String, needle: String = ""): MangasPage {
        val mangas = listOf("$label entry ${page}${if (needle.isEmpty()) "" else " for '$needle'"}")
            .map { title ->
                object : SManga {
                    override var url: String = "/manga/${title.hashCode()}"
                    override var title: String = title
                    override var artist: String? = null
                    override var author: String? = null
                    override var description: String? = null
                    override var genre: String? = null
                    override var status: Int = SManga.UNKNOWN
                    override var thumbnail_url: String? = null
                    override var update_strategy: UpdateStrategy = UpdateStrategy.ALWAYS_UPDATE
                    override var initialized: Boolean = false
                }
            }
        return MangasPage(mangas, hasNextPage = page < 2)
    }

    private fun chapter(url: String, name: String, number: Float): SChapter = object : SChapter {
        override var url: String = url
        override var name: String = name
        override var date_upload: Long = 1_700_000_000_000L
        override var chapter_number: Float = number
        override var scanlator: String? = "fixture"
    }
}

/**
 * A second source in the same extension, so the loader is exercised on a `SourceFactory` that
 * really returns more than one source, and so the host's refusal path is reachable.
 *
 * `supportsLatest` is false, which means the bridge must not advertise BROWSE_LATEST and must
 * refuse `latestUpdates` instead of calling a method the source cannot answer.
 */
class TachiyomiMixNoLatest : TachiyomiMix() {

    override val id: Long = 4_507_511_004_372_246_913L
    override val name: String = "TachiyomiMix (no latest)"
    override val supportsLatest: Boolean = false
}
