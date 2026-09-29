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
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Entry point named in the extension manifest meta-data `tachiyomi.extension.class`.
 */
class Generated : SourceFactory {
    override fun createSources(): List<Source> = listOf(TachiyomiMix())
}

/**
 * ABI 1.6 fixture source.
 *
 * A contract fixture, not a scraper: the catalogue is fixed and nothing here was checked
 * against a live site. It shows the 1.6 shape — every call is `suspend`, details and chapters
 * come out of the one `getMangaUpdate` call and can be fetched independently, and `memo` exists.
 */
class TachiyomiMix : CatalogueSource {

    override val id: Long = 3_196_562_139_457_428_713L
    override val name: String = "TachiyomiMix"
    override val lang: String = "all"
    override val supportsLatest: Boolean = true

    // Filter.Text is abstract in the published surface, exactly as a real extension sees it.
    override fun getFilterList(): FilterList = FilterList(object : Filter.Text("Search", "") {})

    override suspend fun getPopularManga(page: Int): MangasPage = page(page, "Popular")

    override suspend fun getLatestUpdates(page: Int): MangasPage = page(page, "Latest")

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        val text = filters.filterIsInstance<Filter.Text>().firstOrNull { it.name == "Search" }?.state.orEmpty()
        val needle = text.ifEmpty { query }
        return page(page, "Search", needle)
    }

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        // A real 1.6 source uses `memo` to carry per entry state between calls, and expects to
        // read it back. The fixture does the same, so the host's memo round trip is exercised
        // rather than assumed: it counts how many times it has been asked, and echoes the value it
        // was given last time.
        val seenBefore = manga.memo["visits"]?.jsonPrimitive?.intOrNull ?: 0
        var details = manga
        var updatedChapters = chapters
        if (fetchDetails) {
            details = manga.apply {
                title = manga.title
                author = "fixture"
                artist = "fixture"
                status = SManga.ONGOING
                description = "Deterministic fixture entry for host testing."
                genre = "action,fantasy"
                thumbnail_url = "https://example.invalid/cover/${manga.url}.jpg"
                initialized = true
                memo = buildJsonObject {
                    put("visits", JsonPrimitive(seenBefore + 1))
                    put("siteId", JsonPrimitive("tmx-${manga.url.hashCode()}"))
                }
            }
        }
        if (fetchChapters) {
            updatedChapters = listOf(
                chapter("${manga.url}/chapter-1", "Chapter 1", 1f),
                chapter("${manga.url}/chapter-2", "Chapter 2", 2.5f),
            )
        }
        return SMangaUpdate(details, updatedChapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> =
        List(3) { index -> Page(index = index, url = "${chapter.url}/page-${index + 1}") }

    private fun page(page: Int, label: String, needle: String = ""): MangasPage {
        val mangas = listOf("$label entry ${page}${if (needle.isEmpty()) "" else " for '$needle'"}")
            .map { title ->
                object : SManga {
                    override var url: String = "/manga/${title.hashCode()}"
                    override var title: String = title
                    override var thumbnail_url: String? = null
                    override var artist: String? = null
                    override var author: String? = null
                    override var status: Int = SManga.UNKNOWN
                    override var description: String? = null
                    override var genre: String? = null
                    override var update_strategy: UpdateStrategy = UpdateStrategy.ALWAYS_UPDATE
                    override var memo: JsonObject = JsonObject(LinkedHashMap())
                    override var initialized: Boolean = false
                }
            }
        return MangasPage(mangas, hasNextPage = page < 2)
    }

    private fun chapter(url: String, name: String, number: Float): SChapter = object : SChapter {
        override var url: String = url
        override var name: String = name
        override var chapter_number: Float = number
        override var scanlator: String? = "fixture"
        override var date_upload: Long = 1_700_000_000_000L
        override var memo: JsonObject = buildJsonObject {
            put("chapterSiteId", JsonPrimitive("c-${url.hashCode()}"))
        }
    }
}
