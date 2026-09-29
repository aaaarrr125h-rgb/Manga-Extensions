package app.shura.abi.v16

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
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.json.JsonObject
import java.util.Locale
import kotlin.math.floor

/** Translation between the published ABI 1.6 surface and the SHURA-native models. */
internal object Abi16Models {

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
        memo = source.memo.toShuraMemo(),
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
        memo = source.memo.toShuraMemo(),
    )

    fun toPageRefs(pages: List<Page>): List<PageRef> =
        pages.map { PageRef(index = it.index, url = it.url, imageUrl = it.imageUrl) }

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

    private fun Float.toChapterNumber(): String? = when {
        isNaN() || this < 0f -> null
        this == floor(this) -> String.format(Locale.ROOT, "%.0f", this)
        // Float.toString is locale independent, and unlike "%.1f" it does not round 12.25 to 12.3.
        else -> toString()
    }

    /** An empty memo means "this source keeps no state", which is null on the host side. */
    private fun JsonObject.toShuraMemo(): String? =
        takeIf { it.isNotEmpty() }?.toString()

    /**
     * The host's memo text back into the object a source writes into.
     *
     * Text that is not a JSON object is dropped rather than thrown over: the memo is opaque state
     * the host only stores and hands back, and a corrupt one must not stop a source from being
     * read at all.
     */
    fun toExtensionMemo(raw: String?): JsonObject {
        if (raw.isNullOrBlank()) return JsonObject(LinkedHashMap())
        return runCatching { json.parseToJsonElement(raw) as? JsonObject }
            .getOrNull()
            ?: JsonObject(LinkedHashMap())
    }

    private val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
}
