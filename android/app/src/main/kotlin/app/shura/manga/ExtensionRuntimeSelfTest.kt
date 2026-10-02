package app.shura.manga

import android.content.Context
import app.shura.source.api.ExtensionAbi
import app.shura.source.api.ExtensionMetadata
import app.shura.source.api.Manga
import app.shura.source.api.MangaListPage
import app.shura.source.api.MangaRef
import app.shura.source.api.SourceCapability
import app.shura.source.api.SourceFilters
import app.shura.source.api.SourceLanguage
import app.shura.source.api.SourceProvider
import app.shura.source.host.AbiRegistry
import app.shura.source.host.ExtensionManifest
import app.shura.source.host.ExtensionManifestParser
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Properties
import java.util.jar.JarFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** One line of the extension report, and whether it counts as a pass. */
private data class Finding(val label: String, val detail: String, val passed: Boolean)

/**
 * Loads a real extension out of the APK and drives it the way the host will.
 *
 * [AbiRuntimeSelfTest] stops at the point where an extension's sources can be created. That proves
 * the classloader reads `classes.dex` and that an `implements` clause resolved, but no catalogue
 * call is ever made and nothing crosses back out of the loader except the sources themselves.
 *
 * This goes the whole way. For each ABI level it builds one isolated `DexClassLoader`, instantiates
 * the entry class through the `SourceFactory` path, wraps each source in that level's bridge —
 * loaded from inside the extension's own classloader, never from the host — and then calls the
 * host-facing [SourceProvider] for real: identity, popular, search, details, chapters and pages,
 * plus a capability the source does not advertise and must refuse.
 *
 * Everything is measured by what came back. No check asserts that a class exists or that a
 * constructor is present, and none is satisfied by a value this file could have invented: the
 * identity is compared against what the extension reports about itself, the catalogue entries
 * against the fixture's own titles, and the object that answered has to have been defined by the
 * extension's classloader rather than the host's.
 *
 * It lives in `:app` and touches no other module, and it needs no network: the fixtures return a
 * fixed catalogue, so this measures the host, not a site.
 */
class ExtensionRuntimeSelfTest(private val context: Context) {

    private val findings = mutableListOf<Finding>()

    /**
     * The parent every extension loader delegates to.
     *
     * Same parent production uses, and the same requirement on it: it supplies
     * `app.shura.source.api`, RxJava and kotlinx-serialization, and it must supply no
     * `eu.kanade.*`. That is what makes the cast to [SourceProvider] in here meaningful, and
     * [reportHostIsolation] is what checks it rather than assuming it.
     */
    private val hostLoader: ClassLoader =
        ExtensionRuntimeSelfTest::class.java.classLoader
            ?: error("The app classloader cannot be null")

    fun run(): String {
        findings.clear()
        val registry = installAbiJars() ?: return render()
        stageExtensions()
        val loaders = buildLoaders(registry)
        if (loaders.isNotEmpty()) {
            reportHostIsolation(loaders)
            EXTENSIONS.forEach { (abi, asset) -> exerciseLevel(abi, asset, loaders[abi]) }
        }
        return render()
    }

    /** The same installer the ABI self test uses, so both start from an identical device state. */
    private fun installAbiJars(): AbiRegistry? = try {
        AbiAssetsInstaller(context).install()
    } catch (failure: Throwable) {
        finding("AbiAssetsInstaller.install()", "THREW ${failure.describe()}", false)
        null
    }

    /**
     * The extension jars have to reach the device as files, copied out of `assets` the same way a
     * downloaded extension would arrive. Reading them straight out of `assets` would not exercise
     * anything a real extension does not.
     */
    private fun stageExtensions() {
        EXTENSIONS.values.distinct().forEach { name ->
            val target = File(extensionDirectory, name)
            if (target.isFile && target.length() > 0) return@forEach
            runCatching {
                context.assets.open("$ASSET_DIRECTORY/$name").use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }.onFailure { finding("stage $name", "THREW ${it.describe()}", false) }
        }
    }

    /**
     * One loader per level, all kept alive at once.
     *
     * They have to coexist: the only way to show that the two levels are genuinely different
     * surfaces is to hold both `eu.kanade.tachiyomi.source.CatalogueSource` types at the same
     * time and check they are not the same class.
     */
    private fun buildLoaders(registry: AbiRegistry): Map<ExtensionAbi, AndroidAbiClassLoader> {
        val loaders = mutableMapOf<ExtensionAbi, AndroidAbiClassLoader>()
        EXTENSIONS.forEach { (abi, asset) ->
            val apiJar = runCatching { registry.require(abi).apiJar }.getOrNull()
            val extensionJar = File(extensionDirectory, asset)
            if (apiJar == null || !apiJar.isFile) {
                finding("loader ABI ${abi.version}", "no ABI jar on disk", false)
                return@forEach
            }
            if (!extensionJar.isFile) {
                finding("loader ABI ${abi.version}", "$asset not staged", false)
                return@forEach
            }
            // Extension first, ABI jar second: the entry class and its `keiyoushi.source.*` come
            // from the extension, the `eu.kanade.tachiyomi.*` it was compiled against from the
            // ABI jar, and neither is on the host's own classpath.
            runCatching {
                AndroidAbiClassLoader(
                    dexJars = listOf(extensionJar, apiJar),
                    // A directory of its own: the ABI self test already owns `shura-dex`, and two
                    // runs sharing an optimised dex cache would be one more thing to explain.
                    optimizedDirectory = codeCache("shura-dex-ext/${abi.version}"),
                    parent = hostLoader,
                )
            }.onSuccess { loaders[abi] = it }
                .onFailure { finding("loader ABI ${abi.version}", "THREW ${it.describe()}", false) }
        }
        return loaders
    }

    /**
     * The two properties the whole design rests on, checked rather than assumed: the host does not
     * have either extension surface, and the two levels do not share one.
     */
    private fun reportHostIsolation(loaders: Map<ExtensionAbi, AndroidAbiClassLoader>) {
        val leaked = listOf(CATALOGUE_INTERFACE, FACTORY_INTERFACE)
            .filter { runCatching { hostLoader.loadClass(it) }.isSuccess }
        finding(
            "host isolation",
            "the app classloader resolves " +
                (if (leaked.isEmpty()) "no" else leaked.joinToString()) +
                " eu.kanade.tachiyomi.source type out of the two it would need",
            leaked.isEmpty(),
        )

        val catalogueTypes = ExtensionAbi.entries.map { abi ->
            abi to runCatching { loaders[abi]?.loadClass(CATALOGUE_INTERFACE) }.getOrNull()
        }
        val problems = buildList {
            catalogueTypes.filter { it.second == null }.forEach { add("${it.first.version}: unresolved") }
            catalogueTypes.mapNotNull { it.second }.forEach { type ->
                if (loaders.values.none { it === type.classLoader }) {
                    add("${type.name} was defined by ${describe(type.classLoader)}, not by a level's loader")
                }
            }
            val distinct = catalogueTypes.mapNotNull { it.second }.distinct()
            if (distinct.size != catalogueTypes.size) {
                add("only ${distinct.size} of ${catalogueTypes.size} CatalogueSource types are distinct")
            }
        }
        finding(
            "level surfaces",
            catalogueTypes.joinToString("; ") { (abi, type) ->
                "${abi.version} browse=${type?.browseEntryPoints().orEmpty()} " +
                    "from ${describe(type?.classLoader)}"
            },
            problems.isEmpty(),
        )
        if (problems.isNotEmpty()) finding("level surfaces", "PROBLEMS: $problems", false)
    }

    /** The browse entry points this level declares: RxJava at 1.4, `suspend` at 1.6. */
    private fun Class<*>.browseEntryPoints(): List<String> = methods
        .map { it.name }
        .filter { it == "fetchPopularManga" || it == "getPopularManga" }
        .distinct()
        .sorted()

    private fun exerciseLevel(
        abi: ExtensionAbi,
        asset: String,
        loader: AndroidAbiClassLoader?,
    ) {
        if (loader == null) return
        val manifest = readManifest(abi, asset) ?: return
        if (manifest.abi != abi) {
            finding(
                "descriptor ABI ${abi.version}",
                "${asset} declares ${manifest.extensionLib}, expected ${abi.version}",
                false,
            )
            return
        }

        val catalogueType = runCatching { loader.loadClass(CATALOGUE_INTERFACE) }.getOrElse {
            finding("surface ABI ${abi.version}", "THREW ${it.describe()}", false)
            return
        }
        val browses = catalogueType.browseEntryPoints()
        val expected = BROWSE_SURFACE.getValue(abi)
        finding(
            "surface ABI ${abi.version}",
            "${catalogueType.name} declares $browses, and ${manifest.entryClass} will be linked " +
                "against it and nothing else",
            expected in browses,
        )

        val sources = runCatching {
            val entry = loader.loadClass(manifest.entryClass).getDeclaredConstructor().newInstance()
            val factory = loader.loadClass(FACTORY_INTERFACE)
            check(factory.isInstance(entry)) { "${manifest.entryClass} is not a $FACTORY_INTERFACE" }
            @Suppress("UNCHECKED_CAST")
            (factory.getMethod("createSources").invoke(entry) as List<*>).filterNotNull()
        }.getOrElse {
            finding("entry class ABI ${abi.version}", "THREW ${it.describe()}", false)
            return
        }
        if (sources.isEmpty()) {
            finding("entry class ABI ${abi.version}", "SourceFactory returned nothing", false)
            return
        }
        finding(
            "entry class ABI ${abi.version}",
            "${manifest.entryClass} -> ${sources.size} source(s) " +
                sources.map { it.javaClass.name.substringAfterLast('.') } +
                ", all from ${describe(loader)}",
            true,
        )

        sources.forEach { source -> driveSource(abi, manifest, catalogueType, loader, source) }
    }

    /**
     * Reads the extension's own descriptor out of the jar that reached the device.
     *
     * This is the descriptor-driven path, not a shortcut around it: the entry class, the package
     * name and the ABI level all come from `META-INF/tachiyomi/extension.properties` exactly as
     * [ExtensionLoader.loadFromJar] reads them, so nothing here depends on the test knowing the
     * fixture. If the packaging ever drops that file again the report goes red with the list of
     * entries the jar actually holds, rather than quietly falling back to a name written down here.
     */
    private fun readManifest(abi: ExtensionAbi, asset: String): ExtensionManifest? {
        val jar = File(extensionDirectory, asset)
        val entries = runCatching {
            JarFile(jar).use { file -> file.entries().toList().map { it.name } }
        }.getOrElse {
            finding("descriptor ${abi.version}", "THREW ${it.describe()}", false)
            return null
        }
        if (entries.none { it == DESCRIPTOR_PATH }) {
            finding(
                "descriptor ${abi.version}",
                "$asset holds ${entries.sorted().joinToString()} but no $DESCRIPTOR_PATH; " +
                    "the packaged jar cannot be loaded the way a downloaded one is",
                false,
            )
            return null
        }
        return runCatching {
            val properties = JarFile(jar).use { file ->
                file.getInputStream(file.getJarEntry(DESCRIPTOR_PATH)).use { stream ->
                    Properties().apply { load(stream) }
                }
            }
            ExtensionManifestParser.fromProperties(
                packageName = properties.getProperty(JAR_PROPERTY_PACKAGE)
                    ?: error("$asset declares no $JAR_PROPERTY_PACKAGE"),
                versionCode = properties.getProperty(JAR_PROPERTY_VERSION_CODE)?.toLongOrNull()
                    ?: error("$asset declares no usable $JAR_PROPERTY_VERSION_CODE"),
                versionName = properties.getProperty(JAR_PROPERTY_VERSION_NAME).orEmpty(),
                properties = properties,
            )
        }.getOrElse {
            finding("descriptor ${abi.version}", "THREW ${it.describe()}", false)
            null
        }.also { manifest ->
            if (manifest != null) {
                finding(
                    "descriptor ${abi.version}",
                    "$asset carries ${entries.sorted().joinToString()}; " +
                        "${manifest.packageName} v${manifest.versionName} (${manifest.versionCode}), " +
                        "lib ${manifest.extensionLib}, entry ${manifest.entryClass}, " +
                        "'${manifest.displayName}'",
                    true,
                )
            }
        }
    }

    /**
     * Wraps one extension source in that level's bridge and calls it through the host API.
     *
     * The bridge is the seam this whole report exists to test. It is loaded off the extension's
     * own classloader, so it and the `eu.kanade.*` it implements come from the ABI jar, while the
     * [SourceProvider] it implements comes from the parent. If either half were resolved from the
     * wrong side, this would fail with `NoClassDefFoundError` or `ClassCastException` instead of
     * returning data.
     */
    private fun driveSource(
        abi: ExtensionAbi,
        manifest: ExtensionManifest,
        catalogueType: Class<*>,
        loader: AndroidAbiClassLoader,
        source: Any,
    ) {
        val label = source.javaClass.name.substringAfterLast('.')
        if (!catalogueType.isInstance(source)) {
            finding(
                "bridge $label",
                "${source.javaClass.name} does not implement " +
                    "${catalogueType.name}, so ABI ${abi.version} is the wrong level for it",
                false,
            )
            return
        }
        val provider = runCatching { buildProvider(abi, manifest, catalogueType, loader, source) }.getOrElse {
            finding("bridge $label", "THREW ${it.describe()}", false)
            return
        }

        // The object answering the call has to be the one from inside the loader, and it still has
        // to be a SourceProvider. Both halves at once, or neither half is proven.
        val origin = provider.javaClass.classLoader
        if (origin !== loader) {
            finding(
                "bridge $label",
                "provider came from ${describe(origin)}, not the extension loader",
                false,
            )
            return
        }
        finding(
            "bridge $label",
            "${provider.javaClass.name} defined by ${describe(origin)}, cast to SourceProvider OK",
            true,
        )

        reportIdentity(provider, manifest, catalogueType, source, abi, label)
        reportUnadvertisedRefusal(provider, label)
        val firstEntry = reportPopular(provider, label)
        reportSearch(provider, label)
        firstEntry?.let { reportDetailsAndPages(provider, label, it, abi) }
    }

    /**
     * Builds the host-facing provider, the way the host's own loader does.
     *
     * The constructor is chosen by shape rather than by a hard coded arity, because the two levels
     * genuinely differ: the 1.4 bridge also takes a dispatcher, since RxJava 1 browsing has to be
     * blocked off the caller's thread, while the 1.6 surface is `suspend` throughout and has
     * nothing to hand over. Getting this wrong is the one mistake that would make the report lie
     * about which level it is exercising.
     */
    private fun buildProvider(
        abi: ExtensionAbi,
        manifest: ExtensionManifest,
        catalogueType: Class<*>,
        loader: AndroidAbiClassLoader,
        source: Any,
    ): SourceProvider {
        val bridgeType = loader.loadClass(AbiRegistry.bridgeClassFor(abi))
        val metadata = ExtensionMetadata(
            packageName = manifest.packageName,
            versionName = manifest.versionName,
            versionCode = manifest.versionCode,
            contentWarning = manifest.contentWarning,
        )
        val dispatcher: CoroutineDispatcher = Dispatchers.IO
        val constructor = bridgeType.declaredConstructors
            .filter { candidate ->
                val parameters = candidate.parameterTypes
                parameters.firstOrNull() == catalogueType &&
                    ExtensionMetadata::class.java in parameters &&
                    parameters.all {
                        it == catalogueType ||
                            it == ExtensionMetadata::class.java ||
                            it == CoroutineDispatcher::class.java
                    }
            }
            .maxByOrNull { it.parameterCount }
            ?: error("Bridge ${bridgeType.name} has no (CatalogueSource, ExtensionMetadata[, dispatcher]) constructor")

        return when (constructor.parameterCount) {
            3 -> constructor.newInstance(source, metadata, dispatcher) as SourceProvider
            else -> constructor.newInstance(source, metadata) as SourceProvider
        }
    }

    /**
     * What the host can say about a source without asking it for anything, checked against the
     * four things the extension itself reports about itself.
     *
     * `id`, `name`, `lang` and `supportsLatest` are read straight off the source through the
     * level's own `eu.kanade` interface, so the descriptor is compared with an independently
     * obtained value rather than with something this file wrote down. The package name, version
     * and ABI level are compared with what the extension's descriptor said, so a bridge built
     * from the wrong metadata cannot pass either.
     */
    private fun reportIdentity(
        provider: SourceProvider,
        manifest: ExtensionManifest,
        catalogueType: Class<*>,
        source: Any,
        abi: ExtensionAbi,
        label: String,
    ) {
        val descriptor = provider.descriptor
        val declaredId = catalogueType.getMethod("getId").invoke(source) as Long
        val declaredName = catalogueType.getMethod("getName").invoke(source) as String
        val declaredLang = catalogueType.getMethod("getLang").invoke(source) as String
        val declaredLatest = catalogueType.getMethod("getSupportsLatest").invoke(source) as Boolean
        val caps = descriptor.capabilities.asNames()

        val problems = buildList {
            if (descriptor.abi != abi) add("abi=${descriptor.abi}, expected $abi")
            if (descriptor.sourceId != declaredId) add("sourceId=${descriptor.sourceId}, extension says $declaredId")
            if (descriptor.name != declaredName) add("name='${descriptor.name}', extension says '$declaredName'")
            if (descriptor.language != SourceLanguage.normalize(declaredLang)) {
                add("language='${descriptor.language}', extension says '${SourceLanguage.normalize(declaredLang)}'")
            }
            if (descriptor.supports(SourceCapability.BROWSE_LATEST) != declaredLatest) {
                add("BROWSE_LATEST=${descriptor.supports(SourceCapability.BROWSE_LATEST)}, extension says $declaredLatest")
            }
            if (descriptor.supports(SourceCapability.LATEST_SUPPORTED) != declaredLatest) {
                add("LATEST_SUPPORTED disagrees with supportsLatest=$declaredLatest")
            }
            if (descriptor.key.packageName != manifest.packageName) {
                add("package='${descriptor.key.packageName}', descriptor says '${manifest.packageName}'")
            }
            if (descriptor.versionName != manifest.versionName) {
                add("versionName='${descriptor.versionName}', descriptor says '${manifest.versionName}'")
            }
            if (descriptor.versionCode != manifest.versionCode) {
                add("versionCode=${descriptor.versionCode}, descriptor says ${manifest.versionCode}")
            }
            if (descriptor.contentWarning != manifest.contentWarning) {
                add("contentWarning=${descriptor.contentWarning}, descriptor says ${manifest.contentWarning}")
            }
            if (caps.isEmpty()) add("no capabilities")
        }
        finding(
            "identity $label",
            "key=${descriptor.key}, name='${descriptor.name}', lang=${descriptor.language} " +
                "(extension reports id=$declaredId name='$declaredName' lang='$declaredLang' " +
                "supportsLatest=$declaredLatest), v${descriptor.versionName} " +
                "(${descriptor.versionCode}) warning=${descriptor.contentWarning}, " +
                "${caps.size} caps $caps",
            problems.isEmpty(),
        )
        if (problems.isNotEmpty()) finding("identity $label", "PROBLEMS: $problems", false)
    }

    /**
     * Browse. The titles are checked against the fixture's own convention on purpose: an empty or
     * defaulted list would satisfy every other invariant, and only the extension can produce
     * these strings.
     */
    private fun reportPopular(provider: SourceProvider, label: String): Manga? {
        val page = runBlocking { provider.popularManga(FIRST_PAGE) }
        val entry = page.mangas.firstOrNull()
        val problems = buildList {
            if (page.mangas.isEmpty()) add("no entries")
            if (entry != null && entry.title != "Popular entry $FIRST_PAGE") add("title='${entry.title}'")
            if (entry != null && !entry.ref.value.startsWith(MANGA_URL_PREFIX)) add("ref='${entry.ref.value}'")
            if (page.hasNextPage != EXPECTED_HAS_NEXT_PAGE) add("hasNextPage=${page.hasNextPage}")
        }
        finding(
            "popular $label",
            page.describeEntries() + if (page.hasNextPage) " hasNextPage=true" else " hasNextPage=false",
            problems.isEmpty(),
        )
        if (problems.isNotEmpty()) finding("popular $label", "PROBLEMS: $problems", false)
        return entry
    }

    /**
     * Search, through both routes a real host has: the query, and a named filter.
     *
     * The filter is the stronger of the two. The host knows only a name to value map and never the
     * source's own filter types, so this passing means the bridge translated a flat map onto the
     * source's `Filter.Text` and the source read it back.
     */
    private fun reportSearch(provider: SourceProvider, label: String) {
        val byQuery = runBlocking { provider.searchManga(FIRST_PAGE, QUERY) }
        val byFilter = runBlocking {
            provider.searchManga(FIRST_PAGE, QUERY, SourceFilters.EMPTY.with(FILTER_NAME, FILTER_VALUE))
        }
        val byQueryTitle = byQuery.mangas.firstOrNull()?.title
        val byFilterTitle = byFilter.mangas.firstOrNull()?.title
        val expectedQuery = "Search entry $FIRST_PAGE for '$QUERY'"
        val expectedFilter = "Search entry $FIRST_PAGE for '$FILTER_VALUE'"
        val problems = buildList {
            if (byQueryTitle != expectedQuery) add("query title='$byQueryTitle'")
            if (byFilterTitle != expectedFilter) add("filter title='$byFilterTitle', expected '$expectedFilter'")
        }
        finding(
            "search $label",
            "query -> '$byQueryTitle'; filter '$FILTER_NAME=$FILTER_VALUE' -> '$byFilterTitle'",
            problems.isEmpty(),
        )
        if (problems.isNotEmpty()) finding("search $label", "PROBLEMS: $problems", false)
    }

    /**
     * Details, chapters and pages, for the entry the browse call actually returned, so the ref
     * travelling back into the extension is one it issued rather than one invented here.
     *
     * Returns the memo's own visit counter when the level has a memo, so the round trip can be
     * measured relative to this call instead of assuming where in the sequence it lands.
     */
    private fun reportDetailsAndPages(
        provider: SourceProvider,
        label: String,
        entry: Manga,
        abi: ExtensionAbi,
    ) {
        val details = runBlocking { provider.mangaDetails(entry.ref) }
        val detailProblems = buildList {
            if (details.ref.value != entry.ref.value) add("ref changed: '${details.ref.value}'")
            if (details.author == null) add("author is null")
            if (details.genres.isEmpty()) add("genres is empty")
            if (details.description.isNullOrBlank()) add("description is blank")
            if (!details.initialized) add("initialized=false")
        }
        finding(
            "details $label",
            "ref=${details.ref.value}, author=${details.author}, artist=${details.artist}, " +
                "status=${details.status}, genres=${details.genres}, " +
                "thumbnail=${details.thumbnailUrl != null}, memo=${details.memo ?: "none"}",
            detailProblems.isEmpty(),
        )
        if (detailProblems.isNotEmpty()) finding("details $label", "PROBLEMS: $detailProblems", false)

        val chapters = runBlocking { provider.chapterList(entry.ref) }
        val chapterProblems = buildList {
            if (chapters.isEmpty()) add("no chapters")
            if (chapters.map { it.name } != EXPECTED_CHAPTERS) add("names=${chapters.map { it.name }}")
            if (chapters.any { it.number == null }) add("a chapter has no number")
        }
        finding(
            "chapters $label",
            chapters.joinToString(prefix = "[", postfix = "]") {
                "${it.name} num=${it.number} upload=${it.uploadDateMillis} scan=${it.scanlators}" +
                    (it.memo?.let { memo -> " memo=$memo" } ?: " memo=none")
            },
            chapterProblems.isEmpty(),
        )
        if (chapterProblems.isNotEmpty()) finding("chapters $label", "PROBLEMS: $chapterProblems", false)

        val first = chapters.firstOrNull() ?: return
        val pages = runBlocking { provider.pageList(first.ref) }
        val pageProblems = buildList {
            if (pages.size != EXPECTED_PAGES) add("count=${pages.size}")
            if (pages.map { it.index } != pages.indices.toList()) add("indices=${pages.map { it.index }}")
            if (pages.any { it.url.isBlank() }) add("an entry has a blank url")
        }
        finding(
            "pages $label",
            pages.joinToString(prefix = "[", postfix = "]") { "index=${it.index} url=${it.url}" },
            pageProblems.isEmpty(),
        )
        if (pageProblems.isNotEmpty()) finding("pages $label", "PROBLEMS: $pageProblems", false)

        if (abi.isSuspendOnly) reportMemoRoundTrip(provider, label, entry, details.memo)
    }

    /**
     * ABI 1.6 only: the source stores per entry state in `memo` and expects it back next time.
     *
     * This is the one check the host cannot pass by accident. The bridge builds a fresh stub
     * object for every call, so without its memo cache the extension would be handed an empty memo
     * each time and would report the same visit count forever. The fixture counts its calls, so
     * two more calls after the details call have to read back one higher and then one higher
     * again: the value crossed out of the extension and came back.
     */
    private fun reportMemoRoundTrip(
        provider: SourceProvider,
        label: String,
        entry: Manga,
        detailsMemo: String?,
    ) {
        val baseline = detailsMemo?.let(::firstVisitsCount)?.toIntOrNull()
        val seen = (0..1).map {
            runBlocking { provider.mangaDetails(MangaRef(entry.ref.value)) }.memo
                ?.let(::firstVisitsCount)
        }
        val expected = baseline?.let { listOf("${it + 1}", "${it + 2}") }
        val problems = buildList {
            if (baseline == null) add("the details call reported no visits counter")
            if (baseline == 0) add("the details call was not itself counted")
            if (expected != null && seen != expected) add("read back $seen, expected $expected")
        }
        finding(
            "memo round trip $label",
            "details reported visits=$baseline; the next two calls for ${entry.ref.value} " +
                "reported $seen",
            problems.isEmpty(),
        )
        if (problems.isNotEmpty()) finding("memo round trip $label", "PROBLEMS: $problems", false)
    }

    /**
     * A capability the source never advertised has to be refused, not answered.
     *
     * `TachiyomiMixNoLatest` reports `supportsLatest = false`, so the bridge must leave
     * `BROWSE_LATEST` out of its capabilities and throw when asked for a latest page. A source
     * that quietly returned something would be a wrong answer, not a degraded one.
     */
    private fun reportUnadvertisedRefusal(provider: SourceProvider, label: String) {
        if (provider.descriptor.supports(SourceCapability.BROWSE_LATEST)) return
        val refused = runCatching { runBlocking { provider.latestUpdates(FIRST_PAGE) } }.exceptionOrNull()
        finding(
            "refusal $label",
            "BROWSE_LATEST not advertised; latestUpdates($FIRST_PAGE) threw " +
                (refused?.let { it.javaClass.name } ?: "NOTHING, it returned a page"),
            refused != null,
        )
    }

    private val extensionDirectory: File
        get() = File(context.filesDir, EXTENSION_DIRECTORY).also(File::mkdirs)

    private fun codeCache(name: String): File =
        File(context.codeCacheDir, name).also(File::mkdirs)

    private fun MangaListPage.describeEntries(): String =
        mangas.joinToString(prefix = "[", postfix = "]") {
            "'${it.title}' ref=${it.ref.value} status=${it.status} genres=${it.genres}"
        }

    /** Reads the `visits` counter out of the memo without parsing JSON into a model. */
    private fun firstVisitsCount(memo: String): String? =
        VISITS_PATTERN.find(memo)?.groupValues?.get(1)

    private fun finding(label: String, detail: String, passed: Boolean) {
        findings += Finding(label, detail, passed)
    }

    private fun describe(loader: ClassLoader?): String = when (loader) {
        null -> "no loader"
        is AndroidAbiClassLoader -> "DexClassLoader ${loader.hashCode().toString(16)}"
        else -> "${loader.javaClass.simpleName} ${loader.hashCode().toString(16)}"
    }

    private fun Throwable.describe(): String {
        val buffer = StringWriter()
        PrintWriter(buffer).use { printStackTrace(it) }
        return buffer.toString().trimEnd()
    }

    private fun render(): String = buildString {
        appendLine("Shura extension runtime test")
        appendLine(
            "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, " +
                "API ${android.os.Build.VERSION.SDK_INT}",
        )
        appendLine(RULE)
        findings.forEach { line ->
            appendLine("${if (line.passed) "PASS" else "FAIL"}  ${line.label}")
            line.detail.lineSequence().forEach { appendLine("      $it") }
        }
        appendLine(RULE)
        appendLine("${findings.count(Finding::passed)}/${findings.size} passed")
    }

    private companion object {
        const val DESCRIPTOR_PATH = "META-INF/tachiyomi/extension.properties"
        const val JAR_PROPERTY_PACKAGE = "packageName"
        const val JAR_PROPERTY_VERSION_CODE = "versionCode"
        const val JAR_PROPERTY_VERSION_NAME = "versionName"
        const val FACTORY_INTERFACE = "eu.kanade.tachiyomi.source.SourceFactory"
        const val CATALOGUE_INTERFACE = "eu.kanade.tachiyomi.source.CatalogueSource"
        const val ASSET_DIRECTORY = "shura-test-fixtures"
        const val EXTENSION_DIRECTORY = "shura-test-fixtures"
        const val QUERY = "shura"
        const val FILTER_NAME = "Search"
        const val FILTER_VALUE = "filtered"
        const val MANGA_URL_PREFIX = "/manga/"
        const val FIRST_PAGE = 1
        const val EXPECTED_PAGES = 3
        val RULE = "-".repeat(40)

        val EXPECTED_CHAPTERS = listOf("Chapter 1", "Chapter 2")

        /** The fixture pages with `hasNextPage = page < 2`, so page 1 is never the last one. */
        const val EXPECTED_HAS_NEXT_PAGE = true

        val VISITS_PATTERN = Regex(""""visits"\s*:\s*(\d+)""")

        /**
         * How each level's browse API has to look, read off the `CatalogueSource` the loader
         * resolved. This is the measurement that says which level is really in play: an ABI 1.4
         * surface has no `getPopularManga` at all, and a 1.6 one cannot answer with RxJava.
         */
        val BROWSE_SURFACE: Map<ExtensionAbi, String> = mapOf(
            ExtensionAbi.V1_4 to "fetchPopularManga",
            ExtensionAbi.V1_6 to "getPopularManga",
        )

        /**
         * The extension staged for each level.
         *
         * Only the asset name is known here. Everything else -- entry class, package, version,
         * ABI level -- is read out of the staged jar's own descriptor, so this test never encodes
         * a fact about the extension that the extension itself does not also publish.
         */
        val EXTENSIONS: Map<ExtensionAbi, String> = mapOf(
            ExtensionAbi.V1_4 to "tachiyomix-abi14.jar",
            ExtensionAbi.V1_6 to "tachiyomix-abi16.jar",
        )
    }
}
