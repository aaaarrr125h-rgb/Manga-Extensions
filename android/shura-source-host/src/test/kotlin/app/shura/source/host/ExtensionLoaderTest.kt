package app.shura.source.host

import app.shura.source.api.Chapter
import app.shura.source.api.ExtensionAbi
import app.shura.source.api.MangaRef
import app.shura.source.api.MangaStatus
import app.shura.source.api.SourceCapability
import app.shura.source.api.SourceFilters
import app.shura.source.api.SourceLanguage
import app.shura.source.api.UnsupportedSourceOperationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * End to end over the real jars: a fixture extension is loaded through the same path a
 * downloaded APK takes, and every catalogue call goes through the bridge.
 *
 * The fixtures are contract fixtures, not scrapers, so every value asserted here is a fixed
 * value the fixture returns. What is under test is the path, not TachiyomiMix.
 */
class ExtensionLoaderTest {

    private val loader = ExtensionLoader(AbiRegistry.fromDirectory(TestArtifacts.extensionArtifacts))

    @AfterEach
    fun closeLoader() = loader.close()

    private fun loadAbi14() = loader.loadFromJar(TestArtifacts.extension("shura-ext-tachiyomix-abi14"))
    private fun loadAbi16() = loader.loadFromJar(TestArtifacts.extension("shura-ext-tachiyomix-abi16"))

    @Test
    fun `loads a 1_4 extension and reports its descriptor`() {
        val extension = loadAbi14()
        val provider = extension.providers.single { it.descriptor.name == "TachiyomiMix" }

        assertEquals(ExtensionAbi.V1_4, extension.abi)
        assertEquals("eu.kanade.tachiyomi.extension.all.tachiyomix", extension.packageName)
        assertEquals(104_000L, extension.metadata.versionCode)
        assertEquals("keiyoushi.source.Generated", extension.entryClass)

        val descriptor = provider.descriptor
        assertEquals("TachiyomiMix", descriptor.name)
        assertEquals(SourceLanguage.MULTIPLE, descriptor.language, "'all' has to be normalised to 'mul'")
        assertEquals(3_196_562_139_457_428_713L, descriptor.sourceId)
        assertEquals(ExtensionAbi.V1_4, descriptor.abi)
        assertEquals(extension.packageName, descriptor.packageName)
    }

    @Test
    fun `a source factory really can contribute more than one source`() {
        val extension = loadAbi14()

        assertEquals(2, extension.providers.size, "the fixture ships two sources on purpose")
        assertEquals(2, extension.sources.map { it.sourceId }.toSet().size, "both sources need their own id")
        assertTrue(extension.sources.all { it.packageName == extension.packageName })
    }

    @Test
    fun `advertises exactly what the source implements`() {
        val capabilities = loadAbi14()
            .providers.single { it.descriptor.name == "TachiyomiMix" }
            .descriptor.capabilities

        assertTrue(SourceCapability.BROWSE_POPULAR in capabilities)
        assertTrue(SourceCapability.BROWSE_LATEST in capabilities, "the fixture sets supportsLatest = true")
        assertTrue(SourceCapability.LATEST_SUPPORTED in capabilities)
        assertTrue(SourceCapability.SEARCH in capabilities)
        assertTrue(SourceCapability.SEARCH_FILTERS in capabilities, "the fixture returns a non empty filter list")
        assertTrue(SourceCapability.FETCH_DETAILS in capabilities)
        assertTrue(SourceCapability.FETCH_CHAPTERS in capabilities)
        assertTrue(SourceCapability.FETCH_PAGES in capabilities)
        // 1.4 has no getMangaUpdate, so the host must not claim to be able to update a half.
        assertTrue(
            SourceCapability.INCREMENTAL_UPDATE !in capabilities,
            "ABI 1.4 has no getMangaUpdate and must not advertise INCREMENTAL_UPDATE",
        )
        assertTrue(SourceCapability.CONFIGURABLE !in capabilities, "the fixture is not a ConfigurableSource")
    }

    @Test
    fun `refuses a call the source never advertised`() {
        val provider = loadAbi14().providers.single { it.descriptor.name == "TachiyomiMix (no latest)" }

        assertTrue(
            SourceCapability.BROWSE_LATEST !in provider.descriptor.capabilities,
            "supportsLatest is false, so the host must not advertise a latest list",
        )

        val failure = assertThrows<UnsupportedSourceOperationException> {
            runBlocking { provider.latestUpdates(1) }
        }
        assertEquals(SourceCapability.BROWSE_LATEST, failure.capability)
        assertEquals(provider.descriptor.key, failure.key)
    }

    @Test
    fun `serves a browse page from a 1_4 RxJava source`() = runBlocking {
        val provider = loadAbi14().providers.single { it.descriptor.name == "TachiyomiMix" }

        val first = provider.popularManga(1)
        assertEquals(1, first.mangas.size)
        assertEquals("Popular entry 1", first.mangas.single().title)
        assertTrue(first.hasNextPage)

        // The fixture's hasNextPage is `page < 2`, so this also proves the page number crossed
        // the classloader boundary intact.
        val last = provider.popularManga(2)
        assertEquals("Popular entry 2", last.mangas.single().title)
        assertTrue(!last.hasNextPage)

        assertEquals("Latest entry 1", provider.latestUpdates(1).mangas.single().title)
    }

    @Test
    fun `serves details, chapters and pages from a 1_4 source`() = runBlocking {
        val provider = loadAbi14().providers.single { it.descriptor.name == "TachiyomiMix" }

        // Start from a real browse result, so the ref the host hands back is one the extension
        // itself produced, rather than a string this test made up.
        val listed = provider.popularManga(1).mangas.single()
        val ref = listed.ref
        assertEquals(listed.url, ref.value, "a listed entry's ref has to be the url it was given")

        val details = provider.mangaDetails(ref)
        assertEquals("fixture", details.author)
        assertEquals("fixture", details.artist)
        assertEquals(ref.value, details.url)
        assertEquals("https://example.invalid/cover/${ref.value}.jpg", details.thumbnailUrl)
        assertTrue(details.initialized)
        assertTrue(details.status == MangaStatus.ONGOING)
        assertEquals(listOf("action", "fantasy"), details.genres, "'action, fantasy' has to be split")

        val chapters = provider.chapterList(ref)
        assertEquals(listOf("Chapter 1", "Chapter 2"), chapters.map(Chapter::name))
        assertEquals(listOf("1", "2"), chapters.map { it.number })
        assertTrue(chapters.all { it.scanlators == listOf("fixture") }, "was: ${chapters.map(Chapter::scanlators)}")
        assertTrue(chapters.all { it.url.startsWith(ref.value) })

        val pages = provider.pageList(chapters.first().ref)
        assertEquals(3, pages.size)
        assertEquals(listOf(0, 1, 2), pages.map { it.index })
        assertEquals("${chapters.first().url}/page-1", pages.first().url)
    }

    @Test
    fun `passes a filter value through to the extension's own filter object`() = runBlocking {
        val provider = loadAbi14().providers.single { it.descriptor.name == "TachiyomiMix" }

        // The fixture prefers its own "Search" text filter over the query argument, so a
        // non-empty title proves the host's flat map reached the extension's Filter<Text>.
        val page = provider.searchManga(1, "ignored", SourceFilters(mapOf("Search" to "needle")))
        assertEquals("Search entry 1 for 'needle'", page.mangas.single().title)

        // A name the source does not publish is dropped rather than guessed at.
        val unnamed = provider.searchManga(1, "query", SourceFilters(mapOf("Nope" to "x")))
        assertEquals("Search entry 1 for 'query'", unnamed.mangas.single().title)
    }

    @Test
    fun `loads a 1_6 extension and serves the same API`() = runBlocking {
        val extension = loadAbi16()
        val provider = extension.providers.single()

        assertEquals(ExtensionAbi.V1_6, extension.abi)
        assertEquals(106_000L, extension.metadata.versionCode)
        assertEquals(ExtensionAbi.V1_6, provider.descriptor.abi)
        assertTrue(
            SourceCapability.INCREMENTAL_UPDATE in provider.descriptor.capabilities,
            "1.6 has getMangaUpdate with independent fetch flags",
        )

        assertEquals("Popular entry 1", provider.popularManga(1).mangas.single().title)

        val ref = MangaRef("/manga/popular-1")
        assertEquals(listOf("action", "fantasy"), provider.mangaDetails(ref).genres)

        val chapters = provider.chapterList(ref)
        // 2.5 has to survive the Float chapter number as "2.5", not "2" and not "2.500000".
        assertEquals(listOf("1", "2.5"), chapters.map { it.number })
        assertEquals(3, provider.pageList(chapters.last().ref).size)
    }

    @Test
    fun `a 1_6 source's memo survives a round trip between calls`() = runBlocking {
        val provider = loadAbi16().providers.single()
        val ref = provider.popularManga(1).mangas.single().ref

        // The fixture counts visits in its memo and echoes back the value it was last given, so a
        // count that keeps climbing proves the host handed the previous memo back rather than an
        // empty one. An extension that relies on memo would silently re-scrape or fail otherwise.
        val first = provider.mangaDetails(ref)
        assertTrue(first.memo != null, "a 1.6 source's memo has to reach the host")
        assertTrue(first.memo!!.contains("\"visits\":1"), "was: ${first.memo}")
        assertTrue(first.memo!!.contains("siteId"), "was: ${first.memo}")

        val second = provider.mangaDetails(ref)
        assertTrue(second.memo!!.contains("\"visits\":2"), "the memo was not handed back: ${second.memo}")

        // A different entry has its own memo; the cache is keyed, not a single slot.
        val other = provider.mangaDetails(provider.popularManga(2).mangas.single().ref)
        assertTrue(other.memo!!.contains("\"visits\":1"), "was: ${other.memo}")
    }

    @Test
    fun `a chapter memo reaches the host and is not confused with the manga's`() = runBlocking {
        val provider = loadAbi16().providers.single()
        val ref = provider.popularManga(1).mangas.single().ref
        val chapters = provider.chapterList(ref)

        assertTrue(chapters.all { it.memo?.contains("chapterSiteId") == true }, "was: ${chapters.map { it.memo }}")
    }

    @Test
    fun `a 1_4 source simply has no memo, and the host does not invent one`() = runBlocking {
        val provider = loadAbi14().providers.single { it.descriptor.name == "TachiyomiMix" }
        val ref = provider.popularManga(1).mangas.single().ref

        // ABI 1.4 has no memo field at all, so null is the only honest value here.
        assertEquals(null, provider.mangaDetails(ref).memo)
        assertTrue(provider.chapterList(ref).all { it.memo == null })
    }

    @Test
    fun `the two levels are genuinely different classes at the same time`() {
        val fourteen = loadAbi14()
        val sixteen = loadAbi16()
        try {
            val source14 = Class.forName("eu.kanade.tachiyomi.source.Source", false, fourteen.classLoader)
            val source16 = Class.forName("eu.kanade.tachiyomi.source.Source", false, sixteen.classLoader)
            assertNotSame(source14, source16)
            assertNotSame(fourteen.classLoader, sixteen.classLoader)

            // One type to the host, two different bridges underneath: that is the entire point.
            assertEquals(AbiRegistry.BRIDGE_1_4, fourteen.providers.first().javaClass.name)
            assertEquals(AbiRegistry.BRIDGE_1_6, sixteen.providers.first().javaClass.name)
        } finally {
            fourteen.close()
            sixteen.close()
        }
    }

    @Test
    fun `the bridge instance comes from the extension classloader, not the host's`() {
        val extension = loadAbi14()
        val provider = extension.providers.single { it.descriptor.name == "TachiyomiMix" }

        assertEquals(AbiRegistry.BRIDGE_1_4, provider.javaClass.name)
        assertEquals(extension.classLoader, provider.javaClass.classLoader)
    }

    @Test
    fun `closing a loaded extension releases its classloader`() {
        val extension = loadAbi14()
        val loaderOfExtension = extension.classLoader
        assertTrue(loaderOfExtension is java.io.Closeable)

        extension.close()
        // Closing twice has to be safe: the app closes an extension when it is upgraded, removed
        // and shut down, and those paths can overlap.
        extension.close()

        // Closing releases the jar handles, it does not undefine the classes already loaded, so a
        // provider that was handed out stays usable. What must not happen is the loader outliving
        // the extension and serving classes out of a jar that has been replaced.
        assertEquals(
            "keiyoushi.source.Generated",
            Class.forName("keiyoushi.source.Generated", false, loaderOfExtension).name,
        )
    }

    @Test
    fun `refuses a jar with no descriptor`() {
        val empty = createTempJar()

        val failure = assertThrows<ExtensionLoadException> { loader.loadFromJar(empty) }
        assertTrue(
            failure.message!!.contains(ExtensionLoader.JAR_DESCRIPTOR_PATH),
            "the message should name the missing descriptor, was: ${failure.message}",
        )
    }

    @Test
    fun `refuses a jar whose entry class is not in the jar`() {
        val jar = createTempJar(
            ExtensionLoader.JAR_PROPERTY_PACKAGE to "eu.kanade.tachiyomi.extension.all.absent",
            ExtensionLoader.JAR_PROPERTY_VERSION_CODE to "104000",
            ExtensionLoader.JAR_PROPERTY_VERSION_NAME to "1.4.0",
            "tachiyomi.extension.class" to "keiyoushi.source.Missing",
            "tachiyomix.extensionLib" to "1.4",
        )

        val failure = assertThrows<ExtensionLoadException> { loader.loadFromJar(jar) }
        assertTrue(
            failure.message!!.contains("keiyoushi.source.Missing"),
            "the message should name the entry class, was: ${failure.message}",
        )
    }

    @Test
    fun `refuses an entry class that is neither a SourceFactory nor a Source`() {
        val jar = createTempJar(
            ExtensionLoader.JAR_PROPERTY_PACKAGE to "eu.kanade.tachiyomi.extension.all.notasource",
            ExtensionLoader.JAR_PROPERTY_VERSION_CODE to "104000",
            ExtensionLoader.JAR_PROPERTY_VERSION_NAME to "1.4.0",
            "tachiyomi.extension.class" to "java.lang.Object",
            "tachiyomix.extensionLib" to "1.4",
        )

        val failure = assertThrows<ExtensionLoadException> { loader.loadFromJar(jar) }
        assertTrue(
            failure.message!!.contains("SourceFactory"),
            "the message should say what was expected, was: ${failure.message}",
        )
    }

    @Test
    fun `refuses an extension whose level has no ABI jar staged`() {
        val staging = Files.createTempDirectory("shura-abi-1.6-only").toFile()
        TestArtifacts.abiJar("1.6").copyTo(staging.resolve("shura-abi-1.6.jar"))
        val registry = AbiRegistry.fromDirectory(staging)
        assertEquals(setOf(ExtensionAbi.V1_6), registry.supported)

        // The 1.4 fixture cannot be hosted by a host that only ships the 1.6 surface. Failing at
        // the registry, before a classloader is opened, is the point.
        val failure = assertThrows<UnsupportedAbiException> {
            ExtensionLoader(registry).use { narrow ->
                narrow.loadFromJar(TestArtifacts.extension("shura-ext-tachiyomix-abi14"))
            }
        }
        assertEquals(ExtensionAbi.V1_4, failure.abi)
    }

    @Test
    fun `refuses a file that is not there`() {
        val missing = File(TestArtifacts.extensionArtifacts, "does-not-exist.jar")

        assertFailsWith<IllegalArgumentException> { loader.loadFromJar(missing) }
    }

    private fun createTempJar(vararg properties: Pair<String, String>): File {
        val jar = File.createTempFile("shura-test-extension", ".jar")
        jar.deleteOnExit()
        java.util.jar.JarOutputStream(jar.outputStream().buffered()).use { out ->
            if (properties.isNotEmpty()) {
                out.putNextEntry(java.util.zip.ZipEntry(ExtensionLoader.JAR_DESCRIPTOR_PATH))
                out.write(properties.joinToString("\n") { "${it.first}=${it.second}" }.toByteArray())
                out.closeEntry()
            }
        }
        return jar
    }
}
