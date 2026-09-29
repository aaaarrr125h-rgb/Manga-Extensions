package app.shura.abi.v16

import app.shura.source.api.Chapter
import app.shura.source.api.ChapterRef
import app.shura.source.api.ExtensionAbi
import app.shura.source.api.ExtensionMetadata
import app.shura.source.api.Manga
import app.shura.source.api.MangaListPage
import app.shura.source.api.MangaRef
import app.shura.source.api.PageRef
import app.shura.source.api.SourceCapability
import app.shura.source.api.SourceCapabilities
import app.shura.source.api.SourceDescriptor
import app.shura.source.api.SourceFilters
import app.shura.source.api.SourceKey
import app.shura.source.api.SourceLanguage
import app.shura.source.api.SourceProvider
import app.shura.source.api.UnsupportedSourceOperationException
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

/**
 * Presents an ABI 1.6 `CatalogueSource` as a SHURA [SourceProvider].
 *
 * The whole 1.6 surface is `suspend`, so this bridge is a straight translation with no
 * blocking calls and no dispatcher to hand over. It still lives inside the extension's
 * classloader, because `eu.kanade.tachiyomi.source.*` exists twice with two different shapes
 * and the host must never see either of them.
 *
 * Details and chapters are both reachable through `getMangaUpdate`, which is why this level can
 * update one without the other; the host keeps that as
 * [SourceCapability.INCREMENTAL_UPDATE].
 *
 * [memoCache] is what makes this level usable at all; see [MemoCache].
 */
class ShuraAbi16Bridge(
    private val source: CatalogueSource,
    extension: ExtensionMetadata,
) : SourceProvider {

    private val memoCache = MemoCache()

    override val descriptor: SourceDescriptor = SourceDescriptor(
        key = SourceKey.of(extension.packageName, source.id),
        name = source.name,
        language = SourceLanguage.normalize(source.lang),
        abi = ExtensionAbi.V1_6,
        capabilities = capabilitiesOf(source),
        versionName = extension.versionName,
        versionCode = extension.versionCode,
        contentWarning = extension.contentWarning,
    )

    override suspend fun popularManga(page: Int): MangaListPage {
        requireCapability(SourceCapability.BROWSE_POPULAR)
        return Abi16Models.toMangaListPage(source.getPopularManga(page))
    }

    override suspend fun latestUpdates(page: Int): MangaListPage {
        requireCapability(SourceCapability.BROWSE_LATEST)
        return Abi16Models.toMangaListPage(source.getLatestUpdates(page))
    }

    override suspend fun searchManga(page: Int, query: String, filters: SourceFilters): MangaListPage {
        requireCapability(SourceCapability.SEARCH)
        return Abi16Models.toMangaListPage(
            source.getSearchManga(page, query, filters.applyTo(source.getFilterList())),
        )
    }

    override suspend fun mangaDetails(manga: MangaRef): Manga {
        requireCapability(SourceCapability.FETCH_DETAILS)
        val stub = ShuraSManga(
            url = manga.value,
            memo = Abi16Models.toExtensionMemo(memoCache.mangaMemo(manga.value)),
        )
        val update = source.getMangaUpdate(
            manga = stub,
            chapters = emptyList(),
            fetchDetails = true,
            fetchChapters = false,
        )
        return remember(stub, update.manga)
    }

    override suspend fun chapterList(manga: MangaRef): List<Chapter> {
        requireCapability(SourceCapability.FETCH_CHAPTERS)
        val stub = ShuraSManga(
            url = manga.value,
            memo = Abi16Models.toExtensionMemo(memoCache.mangaMemo(manga.value)),
        )
        val update = source.getMangaUpdate(
            manga = stub,
            chapters = emptyList(),
            fetchDetails = false,
            fetchChapters = true,
        )
        update.chapters.forEach { memoCache.rememberChapter(it.url, it.memo.toString()) }
        return update.chapters.map(Abi16Models::toChapter)
    }

    override suspend fun pageList(chapter: ChapterRef): List<PageRef> {
        requireCapability(SourceCapability.FETCH_PAGES)
        val pages = source.getPageList(
            ShuraSChapter(
                url = chapter.value,
                memo = Abi16Models.toExtensionMemo(memoCache.chapterMemo(chapter.value)),
            ),
        )
        return Abi16Models.toPageRefs(pages)
    }

    /**
     * Records whatever the source put in `memo` and hands back the entry.
     *
     * The value is stored after the call, never before, so a source that clears its own memo is
     * respected instead of being handed the previous value again.
     */
    private fun remember(stub: ShuraSManga, updated: eu.kanade.tachiyomi.source.model.SManga): Manga {
        memoCache.rememberManga(updated.url, updated.memo.takeIf { it.isNotEmpty() }?.toString())
        return Abi16Models.toManga(updated)
    }

    private fun requireCapability(capability: SourceCapability) {
        if (capability !in descriptor.capabilities) {
            throw UnsupportedSourceOperationException(descriptor.key, capability)
        }
    }

    private companion object {

        fun capabilitiesOf(source: CatalogueSource): SourceCapabilities {
            var capabilities = SourceCapabilities.of(
                SourceCapability.BROWSE_POPULAR,
                SourceCapability.SEARCH,
                SourceCapability.FETCH_DETAILS,
                SourceCapability.FETCH_CHAPTERS,
                SourceCapability.FETCH_PAGES,
                SourceCapability.INCREMENTAL_UPDATE,
            )
            if (source.supportsLatest) {
                capabilities += SourceCapability.BROWSE_LATEST
                capabilities += SourceCapability.LATEST_SUPPORTED
            }
            if (runCatching(source::getFilterList).getOrNull()?.isNotEmpty() == true) {
                capabilities += SourceCapability.SEARCH_FILTERS
            }
            if (source is ConfigurableSource) {
                capabilities += SourceCapability.CONFIGURABLE
            }
            if (source is UnmeteredSource) {
                capabilities += SourceCapability.UNMETERED
            }
            return capabilities
        }

        /**
         * Applies SHURA's flat name to value filters onto the source's own filter objects. The
         * source owns the shape of its filters; a name it does not recognise is dropped.
         */
        fun SourceFilters.applyTo(filters: FilterList): FilterList {
            if (values.isEmpty() || filters.isEmpty()) return filters
            for (filter in filters) {
                val raw = values[filter.name] ?: continue
                when (filter) {
                    is Filter.Text -> filter.state = raw
                    is Filter.CheckBox -> raw.toBooleanStrictOrNull()?.let { filter.state = it }
                    is Filter.Select<*> -> raw.toIntOrNull()?.let { filter.state = it }
                    is Filter.TriState -> raw.toIntOrNull()?.let { filter.state = it }
                    is Filter.Sort -> {
                        val parts = raw.split(':', limit = 2)
                        val index = parts.getOrNull(0)?.toIntOrNull() ?: continue
                        val ascending = parts.getOrNull(1)?.toBooleanStrictOrNull() ?: false
                        filter.state = Filter.Sort.Selection(index, ascending)
                    }

                    is Filter.Header, is Filter.Separator, is Filter.Group<*> -> Unit
                }
            }
            return filters
        }
    }
}
