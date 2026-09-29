package app.shura.abi.v14

import app.shura.source.api.Chapter
import app.shura.source.api.ChapterRef
import app.shura.source.api.ContentRating
import app.shura.source.api.Manga
import app.shura.source.api.MangaListPage
import app.shura.source.api.MangaRef
import app.shura.source.api.MangaStatus
import app.shura.source.api.PageRef
import app.shura.source.api.UpdateStrategy
import eu.kanade.tachiyomi.source.model.MangasPage
import kotlin.math.floor
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import java.util.Locale

/**
 * Translation between the published ABI 1.4 surface and the SHURA-native models.
 *
 * The 1.4 level predates most of the structured fields: there are no alternate titles, no
 * banner, no score, no chapter lock state. Those stay null rather than being invented, and the
 * `SourceDescriptor` advertises only what this level can actually produce.
 */
internal object Abi14Models {

    fun toManga(source: SManga): Manga = Manga(
        ref = MangaRef(source.url),
        url = source.url,
        title = source.title,
        thumbnailUrl = source.thumbnail_url,
        author = source.author,
        artist = source.artist,
        status = MangaStatus.fromCode(source.status),
        genres = splitGenres(source.genre),
        description = source.description,
        contentRating = ContentRating.SAFE,
        updateStrategy = toShuraStrategy(source.update_strategy),
        initialized = source.initialized,
    )

    fun toMangaListPage(page: MangasPage): MangaListPage =
        MangaListPage(page.mangas.map(::toManga), page.hasNextPage)

    fun toChapter(source: SChapter): Chapter = Chapter(
        ref = ChapterRef(source.url),
        url = source.url,
        name = source.name,
        number = source.chapter_number.toChapterNumber(),
        scanlators = source.scanlator?.let { listOf(it) } ?: emptyList(),
        uploadDateMillis = source.date_upload,
    )

    fun toPageRefs(indexes: List<eu.kanade.tachiyomi.source.model.Page>): List<PageRef> =
        indexes.map { PageRef(index = it.index, url = it.url, imageUrl = it.imageUrl) }

    private fun splitGenres(raw: String?): List<String> =
        raw?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

    private fun toShuraStrategy(raw: eu.kanade.tachiyomi.source.model.UpdateStrategy): UpdateStrategy =
        when (raw) {
            eu.kanade.tachiyomi.source.model.UpdateStrategy.ALWAYS_UPDATE -> UpdateStrategy.ALWAYS_UPDATE
            eu.kanade.tachiyomi.source.model.UpdateStrategy.ONLY_FETCH_ONCE -> UpdateStrategy.ONLY_FETCH_ONCE
        }

    /**
     * ABI 1.4 carries the chapter number as a `Float`, where `NaN` and a negative value both
     * mean "the source did not say". Formatting is locale independent so a catalogue entry
     * written in one locale reads the same everywhere.
     */
    private fun Float.toChapterNumber(): String? = when {
        isNaN() || this < 0f -> null
        this == floor(this) -> String.format(Locale.ROOT, "%.0f", this)
        // Float.toString is locale independent, and unlike "%.1f" it does not round 12.25 to 12.3.
        else -> toString()
    }
}
