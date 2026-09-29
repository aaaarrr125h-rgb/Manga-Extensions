package app.shura.abi.v14

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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import rx.Observable

/**
 * Presents an ABI 1.4 `CatalogueSource` as a SHURA [SourceProvider].
 *
 * This class is loaded inside the extension's own classloader, next to the `eu.kanade.*`
 * classes, because the 1.4 surface cannot be referenced from the host: the host has to support
 * 1.4 and 1.6 at the same time and those two surfaces share every package name.
 *
 * What crosses the classloader boundary is therefore only [SourceProvider] and the
 * SHURA-native models, all of which the parent classloader owns.
 *
 * The RxJava browse calls are the reason this class exists. At ABI 1.4 there is no `suspend`
 * equivalent of `fetchPopularManga`, so the observable is blocked on an I/O dispatcher rather
 * than on the caller's thread.
 */
class ShuraAbi14Bridge(
    private val source: CatalogueSource,
    extension: ExtensionMetadata,
    private val io: CoroutineDispatcher,
) : SourceProvider {

    override val descriptor: SourceDescriptor = SourceDescriptor(
        key = SourceKey.of(extension.packageName, source.id),
        name = source.name,
        language = SourceLanguage.normalize(source.lang),
        abi = ExtensionAbi.V1_4,
        capabilities = capabilitiesOf(source),
        versionName = extension.versionName,
        versionCode = extension.versionCode,
        contentWarning = extension.contentWarning,
    )

    override suspend fun popularManga(page: Int): MangaListPage {
        requireCapability(SourceCapability.BROWSE_POPULAR)
        return blocking { source.fetchPopularManga(page) }?.let(Abi14Models::toMangaListPage) ?: MangaListPage.EMPTY
    }

    override suspend fun latestUpdates(page: Int): MangaListPage {
        requireCapability(SourceCapability.BROWSE_LATEST)
        return blocking { source.fetchLatestUpdates(page) }?.let(Abi14Models::toMangaListPage) ?: MangaListPage.EMPTY
    }

    override suspend fun searchManga(page: Int, query: String, filters: SourceFilters): MangaListPage {
        requireCapability(SourceCapability.SEARCH)
        val result = blocking { source.fetchSearchManga(page, query, filters.applyTo(source.getFilterList())) }
        return result?.let(Abi14Models::toMangaListPage) ?: MangaListPage.EMPTY
    }

    override suspend fun mangaDetails(manga: MangaRef): Manga {
        requireCapability(SourceCapability.FETCH_DETAILS)
        return Abi14Models.toManga(source.getMangaDetails(ShuraSManga(manga.value)))
    }

    override suspend fun chapterList(manga: MangaRef): List<Chapter> {
        requireCapability(SourceCapability.FETCH_CHAPTERS)
        return source.getChapterList(ShuraSManga(manga.value)).map(Abi14Models::toChapter)
    }

    override suspend fun pageList(chapter: ChapterRef): List<PageRef> {
        requireCapability(SourceCapability.FETCH_PAGES)
        val pages = blocking { source.fetchPageList(ShuraSChapter(chapter.value)) }.orEmpty()
        return Abi14Models.toPageRefs(pages)
    }

    /**
     * RxJava 1 is a blocking API here, so the call must never run on the caller's dispatcher.
     *
     * RxJava 1 has no `blockingFirst`. `toList().toBlocking()` is the one combination that blocks
     * until the stream terminates and hands back every emission, which lets an empty stream be
     * told apart from a missing one instead of throwing `NoSuchElementException`. An error still
     * propagates, so the host keeps seeing the source's own exception.
     */
    private suspend fun <T> blocking(call: () -> Observable<T>): T? = withContext(io) {
        call().toList().toBlocking().firstOrDefault(null)?.firstOrNull()
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
         * Applies SHURA's flat name to value filters onto the source's own filter objects.
         *
         * The source owns the shape of its filters; the host only knows names. A name that the
         * source does not recognise is dropped rather than guessed at.
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
