package app.shura.source.host

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RepositoryManagerTest {

    private val root: File = Files.createTempDirectory("shura-manager").toFile()
    private val official = "https://official.example/index.json"
    private val second = "https://two.example/index.json"
    private val third = "https://three.example/index.json"

    private fun manager(
        bodies: Map<String, String>,
        extras: List<Pair<String, String>> = emptyList(),
    ): RepositoryManager {
        val registry = RepositoryRegistry(File(root, "repositories.json"), "Shura Official", official)
        extras.forEach { (name, url) -> registry.add(name, url) }
        val store = ExtensionStore(File(root, "extensions"))
        val transport = MapTransport(bodies)
        val factory = RepositoryFactory { config ->
            ExtensionRepository(
                client = RepositoryClient(transport, config.url),
                store = store,
                inspector = FixedApkInspector(ByteArray(0)),
                loader = ExtensionLoader(AbiRegistry.fromDirectory(TestArtifacts.extensionArtifacts)),
                transport = transport,
            )
        }
        return RepositoryManager(registry, factory)
    }

    @Test
    fun `extensions from several repositories are merged, each pointing at its origin`() = runBlocking {
        val registry = manager(
            bodies = mapOf(
                official to index(extension("eu.kanade.tachiyomi.extension.all.one", "One", 100)),
                second to index(extension("eu.kanade.tachiyomi.extension.all.two", "Two", 100)),
            ),
            extras = listOf("Repo Two" to second),
        )

        val catalogue = registry.refresh()

        assertEquals(2, catalogue.reachableCount)
        assertEquals(2, catalogue.extensions.size)
        assertEquals(setOf("One", "Two"), catalogue.extensions.map { it.name }.toSet())
        assertEquals(official, catalogue.extensions.single { it.packageName.endsWith(".one") }.config.url)
        assertEquals(second, catalogue.extensions.single { it.packageName.endsWith(".two") }.config.url)
        assertTrue(catalogue.duplicates.isEmpty())
    }

    @Test
    fun `a package published twice is shown once and the default repository wins`() = runBlocking {
        val registry = manager(
            bodies = mapOf(
                official to index(extension("eu.kanade.tachiyomi.extension.all.shared", "Shared", 100)),
                second to index(extension("eu.kanade.tachiyomi.extension.all.shared", "Shared", 200)),
            ),
            extras = listOf("Repo Two" to second),
        )

        val catalogue = registry.refresh()

        val kept = catalogue.extensions.single()
        assertEquals(official, kept.config.url, "the default repository's copy wins over the newer one")
        assertEquals(100L, kept.versionCode)

        val duplicate = catalogue.duplicates.single()
        assertEquals(official, duplicate.kept.url)
        assertEquals(second, duplicate.ignored.url)
    }

    @Test
    fun `when no default publishes the package the higher version wins`() = runBlocking {
        val registry = manager(
            bodies = mapOf(
                official to index(extension("eu.kanade.tachiyomi.extension.all.other", "Other", 100)),
                second to index(extension("eu.kanade.tachiyomi.extension.all.pick", "Pick", 500)),
                third to index(extension("eu.kanade.tachiyomi.extension.all.pick", "Pick", 900)),
            ),
            extras = listOf("Repo Two" to second, "Repo Three" to third),
        )

        val catalogue = registry.refresh()

        assertEquals(third, catalogue.extensions.single { it.packageName.endsWith(".pick") }.config.url)
    }

    @Test
    fun `an unreachable repository is a status, not an exception, and the others still load`() = runBlocking {
        val registry = manager(
            bodies = mapOf(
                official to index(extension("eu.kanade.tachiyomi.extension.all.one", "One", 100)),
            ),
            extras = listOf("Repo Two" to second),
        )

        val catalogue = registry.refresh()

        val failed = assertNotNull(catalogue.statusFor(second))
        assertTrue(!failed.isReachable)
        assertNotNull(failed.error)
        assertEquals(0, failed.extensionCount)

        val ok = assertNotNull(catalogue.statusFor(official))
        assertTrue(ok.isReachable)
        assertEquals(1, ok.extensionCount)
        assertEquals(1, catalogue.extensions.size, "one broken repository must not empty the list")
    }

    @Test
    fun `refresh records the sync time and it survives a restart`() = runBlocking {
        val registryFile = File(root, "repositories.json")
        val registry = manager(
            bodies = mapOf(
                official to index(extension("eu.kanade.tachiyomi.extension.all.one", "One", 100)),
                second to index(extension("eu.kanade.tachiyomi.extension.all.two", "Two", 100)),
            ),
            extras = listOf("Repo Two" to second),
        )

        registry.refresh()

        assertNotNull(registry.lastSynced(official))
        assertNotNull(registry.lastSynced(second))
        val reopened = RepositoryRegistry(registryFile, "Shura Official", official)
        assertNotNull(reopened.lastSynced(official), "the sync time has to be on disk")
    }

    @Test
    fun `with equal versions and no default publisher the URL tie-break is stable`() = runBlocking {
        val registry = manager(
            bodies = mapOf(
                official to index(extension("eu.kanade.tachiyomi.extension.all.plain", "Plain", 100)),
                second to index(extension("eu.kanade.tachiyomi.extension.all.pick", "Pick", 700)),
                third to index(extension("eu.kanade.tachiyomi.extension.all.pick", "Pick", 700)),
            ),
            extras = listOf("Repo Two" to second, "Repo Three" to third),
        )

        val first = registry.refresh()
        val reloaded = registry.refresh()

        val kept = first.extensions.single { it.packageName.endsWith(".pick") }
        val again = reloaded.extensions.single { it.packageName.endsWith(".pick") }
        assertEquals(third, kept.config.url, "the lexicographically smaller URL is the stable winner")
        assertEquals(kept.config.url, again.config.url, "the choice must not flicker between refreshes")
    }

    @Test
    fun `a removed repository disappears from the next refresh`() = runBlocking {
        val registry = manager(
            bodies = mapOf(
                official to index(extension("eu.kanade.tachiyomi.extension.all.one", "One", 100)),
                second to index(extension("eu.kanade.tachiyomi.extension.all.two", "Two", 100)),
            ),
            extras = listOf("Repo Two" to second),
        )
        registry.refresh()

        assertTrue(registry.remove(second))

        val catalogue = registry.refresh()
        assertNull(catalogue.statusFor(second))
        assertEquals(1, catalogue.repositories.size)
        assertEquals(setOf("One"), catalogue.extensions.map { it.name }.toSet())
        assertTrue(!registry.remove(official), "the built-in repository can never be removed")
    }

    @Test
    fun `validate fetches a repository without configuring it`() = runBlocking {
        val registry = manager(
            bodies = mapOf(second to index(extension("eu.kanade.tachiyomi.extension.all.two", "Two", 100))),
        )

        val snapshot = registry.validate(RepositoryConfig("Candidate", second))

        assertEquals(1, snapshot.extensions.size)
        assertNull(registry.configs().firstOrNull { it.url == second }, "validate must not add it")
    }

    @Test
    fun `validate surfaces a repository that cannot be read`() = runBlocking {
        val registry = manager(bodies = mapOf(second to "<html>not an index</html>"))
        val failure = runCatching { registry.validate(RepositoryConfig("Candidate", second)) }.exceptionOrNull()
        assertNotNull(failure)
        assertContains(failure.message.orEmpty(), "not readable")
    }

    // ------------------------------------------------------------------ fixtures

    private fun index(vararg extensions: String): String =
        """{"name":"Test","badgeLabel":"t","signingKey":"$GOOD_KEY","extensionList":{"extensions":[${extensions.joinToString(",")}]}}"""

    private fun extension(packageName: String, name: String, versionCode: Long): String = """
        {"name":"$name","packageName":"$packageName","extensionLib":"1.6","versionCode":$versionCode,
         "versionName":"1.6.$versionCode","contentWarning":"CONTENT_WARNING_SAFE","sources":[],
         "resources":{"apkUrl":"https://cdn.example/$packageName.apk"}}
    """.trimIndent()

    private class MapTransport(private val bodies: Map<String, String>) : HttpTransport {
        override fun get(url: String): HttpResponse {
            val body = bodies[url] ?: throw RepositoryUnreachableException(url, "no such host")
            return HttpResponse(url, 200, "application/json", body.toByteArray())
        }
    }

    private companion object {
        const val GOOD_KEY = "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2"
    }
}
