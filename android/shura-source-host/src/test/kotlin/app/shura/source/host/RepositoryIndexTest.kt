package app.shura.source.host

import app.shura.source.api.ExtensionAbi
import app.shura.source.api.ExtensionContentWarning
import app.shura.source.api.SourceLanguage
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The index parser, run against the real `repo/index.json` this repository publishes.
 *
 * A fixture would only prove the parser agrees with itself. These run over the actual bytes, so
 * the two facts the index is awkward about are checked where they actually appear: `versionCode`
 * and a source `id` are `int64` in the schema but quoted as strings in the published JSON.
 */
class RepositoryIndexTest {

    private val index = RepositoryIndexParser.parse(TestArtifacts.repoIndex)

    @Test
    fun `parses every published extension`() {
        assertEquals(579, index.extensions.size, "repo/index.json extension count changed")
        assertEquals(830, index.extensions.sumOf { it.sources.size }, "repo/index.json source count changed")
        assertEquals(ExtensionAbi.NEWEST.version, "1.6")
        assertTrue(index.signingKey.isNotBlank(), "the index has to publish a signing key")
    }

    @Test
    fun `only declares ABI levels this host can host`() {
        val levels = index.extensionLibHistogram.keys
        assertEquals(
            setOf("1.4", "1.6"),
            levels,
            "an extension at a level outside ${ExtensionAbi.entries.map(ExtensionAbi::version)} is published",
        )
        assertTrue(
            index.extensions.all { it.abi != null },
            "every published extension must map onto a supported ABI level",
        )
        // Both levels have to keep working; either one going to zero would make its module dead code.
        index.extensions.groupingBy { it.extensionLib }.eachCount().forEach { (level, count) ->
            assertTrue(count > 0, "no extensions published at level $level")
        }
    }

    @Test
    fun `reads versionCode and source ids out of quoted strings`() {
        val extension = index.byPackage("eu.kanade.tachiyomi.extension.all.comicgrowl")
        assertEquals(106_001L, extension!!.versionCode)
        assertEquals("1.6.1", extension.versionName)
        assertTrue(extension.versionCode > 0, "a quoted versionCode must still become a Long")
    }

    @Test
    fun `builds a unique source key for every published source`() {
        val keys = index.extensions.flatMap { extension ->
            extension.sources.map { it.key }
        }
        assertEquals(keys.size, keys.toSet().size, "source keys must be unique across the whole index")
        assertTrue(keys.all { it.packageName.contains('.') })
        assertTrue(keys.all { it.sourceId > 0L })
    }

    @Test
    fun `normalises the legacy language codes once, here`() {
        val languages = index.extensions.flatMap { it.sources }.map { it.language }.toSet()
        assertTrue(SourceLanguage.isNormalized("mul"))
        assertEquals(
            setOf<String>(),
            languages - languages.filter(SourceLanguage::isNormalized).toSet(),
            "every stored language must already be normalised: $languages",
        )
        // "all" is the code the index actually uses for a multi language source.
        assertTrue(
            index.extensions.flatMap { it.sources }.any { it.language == SourceLanguage.MULTIPLE },
            "the index should contain at least one 'all' source mapped to '${SourceLanguage.MULTIPLE}'",
        )
    }

    @Test
    fun `reads the content warning and the download urls`() {
        val published = index.extensions.map { it.contentWarning }.toSet()
        val known = setOf("CONTENT_WARNING_SAFE", "CONTENT_WARNING_MIXED", "CONTENT_WARNING_NSFW")
        assertTrue(
            known.containsAll(published),
            "the index uses an unexpected content warning spelling: $published",
        )
        // Every published warning must survive the mapping into the host enum.
        assertTrue(index.extensions.all { ExtensionContentWarning.fromIndexValue(it.contentWarning) in ExtensionContentWarning.entries })

        val withUrls = index.extensions.filter { it.jarUrl != null }
        assertTrue(withUrls.isNotEmpty(), "the index must publish jar urls for the host to download")
        assertTrue(withUrls.all { it.jarUrl!!.startsWith("https://") })
    }

    @Test
    fun `a source without an id is dropped rather than inventing one`() {
        val parsed = RepositoryIndexParser.parse(
            """
            {
              "name": "broken",
              "extensionList": { "extensions": [
                { "packageName": "a.b.c", "name": "no sources", "extensionLib": "1.6",
                  "versionCode": "1", "versionName": "1",
                  "sources": [ { "name": "nameless" } ] },
                { "name": "no package", "extensionLib": "1.6" }
              ] }
            }
            """.trimIndent(),
        )

        assertEquals(1, parsed.extensions.size)
        assertEquals(0, parsed.extensions.single().sources.size)
    }

    @Test
    fun `reports a broken file instead of returning an empty catalogue`() {
        val failure = assertThrows<RepositoryIndexException> { RepositoryIndexParser.parse("not json") }
        assertTrue(failure.message!!.isNotBlank())

        assertThrows<RepositoryIndexException> { RepositoryIndexParser.parse("[]") }
    }
}
