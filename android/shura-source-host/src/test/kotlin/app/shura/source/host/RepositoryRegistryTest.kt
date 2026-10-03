package app.shura.source.host

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RepositoryRegistryTest {

    private val root: File = Files.createTempDirectory("shura-repositories").toFile()
    private val official = "https://official.example/repo/index.json"
    private val second = "https://two.example/index.json"

    private fun registry(file: File = File(root, "repositories.json")) =
        RepositoryRegistry(file, "Shura Official", official) { 1_700_000_000_000L }

    @Test
    fun `the official repository is seeded on first run`() {
        val configs = registry().configs()
        assertEquals(1, configs.size)
        assertEquals(official, configs.single().url)
        assertEquals("Shura Official", configs.single().name)
        assertTrue(configs.single().isDefault)
        assertTrue(configs.single().builtIn)
    }

    @Test
    fun `a repository is added, remembered and survives a restart`() {
        val added = registry().add("Repo Two", second)
        assertEquals("Repo Two", added.name)
        assertEquals(second, added.url)
        assertFalse(added.isDefault)
        assertFalse(added.builtIn)

        val reopened = registry()
        assertEquals(2, reopened.configs().size)
        assertEquals("Repo Two", reopened.find(second)!!.name)
        // The one that was seeded stays the default until the user says otherwise.
        assertEquals(official, reopened.default()!!.url)
    }

    @Test
    fun `a blank name falls back to the URL host`() {
        val added = registry().add("   ", second)
        assertEquals("two.example", added.name)
    }

    @Test
    fun `the same URL is a duplicate, whatever it is named`() {
        val reg = registry()
        reg.add("Repo Two", second)
        assertFailsWith<DuplicateRepositoryException> { reg.add("Repo Two again", second) }
        assertEquals(2, reg.configs().size)
    }

    @Test
    fun `a URL that is empty, plaintext or malformed is refused`() {
        val reg = registry()
        val rejected = listOf(
            "",
            "   ",
            "http://two.example/index.json",
            "not a url",
            "https://",
            "ftp://two.example/index.json",
        )
        rejected.forEach { bad ->
            assertFailsWith<RepositoryUrlException>(bad) { reg.add("Bad", bad) }
        }
        assertEquals(1, reg.configs().size, "a refused URL must not be stored")
    }

    @Test
    fun `a user repository can be removed, the official one cannot`() {
        val reg = registry()
        reg.add("Repo Two", second)
        assertTrue(reg.remove(second))
        assertNull(reg.find(second))
        assertFalse(reg.remove(official), "the built-in repository is not removable")
        assertFalse(reg.remove("https://missing.example/index.json"))
        assertEquals(1, reg.configs().size)
    }

    @Test
    fun `changing the default moves the flag and removal promotes the official repository`() {
        val reg = registry()
        reg.add("Repo Two", second)
        assertTrue(reg.setDefault(second))
        assertEquals(second, reg.default()!!.url)
        assertEquals(1, reg.configs().count { it.isDefault })

        assertTrue(reg.remove(second))
        assertEquals(official, reg.default()!!.url)
    }

    @Test
    fun `setDefault refuses a URL that is not configured`() {
        assertFalse(registry().setDefault("https://missing.example/index.json"))
    }

    @Test
    fun `several repositories coexist`() {
        val reg = registry()
        reg.add("Repo Two", second)
        reg.add("Repo Three", "https://three.example/index.json")
        assertEquals(3, reg.configs().size)
        assertEquals(
            listOf(official, second, "https://three.example/index.json"),
            reg.configs().map { it.url },
        )
    }

    @Test
    fun `the official repository is added to a pre-existing list, keeping the user's default`() {
        val file = File(root, "repositories.json")
        file.writeText(
            """{"formatVersion":1,"repositories":[""" +
                """{"name":"Repo Two","url":"$second","isDefault":true,"builtIn":false}], "sync":{}}""",
        )
        val configs = registry(file).configs()
        assertEquals(2, configs.size)
        assertEquals(second, configs.first { it.isDefault }.url, "the user's default is preserved")
        assertTrue(configs.any { it.url == official && it.builtIn }, "the official repository is migrated in")
    }

    @Test
    fun `last sync times persist across a restart`() {
        val reg = registry()
        reg.markSynced(official, at = 1_700_000_000_000L)
        assertEquals(1_700_000_000_000L, registry().lastSynced(official))
        assertNull(registry().lastSynced(second))
    }

    @Test
    fun `a corrupt file is recovered by re-seeding the official repository`() {
        val file = File(root, "repositories.json")
        file.writeText("{ not json")
        val configs = registry(file).configs()
        assertEquals(1, configs.size)
        assertEquals(official, configs.single().url)
    }
}
