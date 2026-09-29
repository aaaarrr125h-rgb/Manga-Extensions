package app.shura.source.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceLanguageTest {

    @Test
    fun `maps the legacy all code onto mul`() {
        assertEquals("mul", SourceLanguage.normalize("all"))
    }

    @Test
    fun `maps the legacy other code onto und`() {
        assertEquals("und", SourceLanguage.normalize("other"))
    }

    @Test
    fun `an empty or missing language is undetermined`() {
        assertEquals("und", SourceLanguage.normalize(""))
        assertEquals("und", SourceLanguage.normalize("   "))
        assertEquals("und", SourceLanguage.normalize(null))
    }

    @Test
    fun `keeps a region qualified tag as it is`() {
        assertEquals("pt-BR", SourceLanguage.normalize("pt-BR"))
        assertEquals("zh-Hant", SourceLanguage.normalize("zh-Hant"))
    }

    @Test
    fun `is idempotent`() {
        val once = SourceLanguage.normalize("all")
        assertEquals(once, SourceLanguage.normalize(once))
    }

    @Test
    fun `isNormalized rejects a legacy code`() {
        assertTrue(SourceLanguage.isNormalized("mul"))
        assertFalse(SourceLanguage.isNormalized("all"))
    }

    @Test
    fun `helpers agree with normalize`() {
        assertTrue(SourceLanguage.isMultiple("all"))
        assertTrue(SourceLanguage.isUndetermined("other"))
        assertFalse(SourceLanguage.isMultiple("en"))
    }
}

class SourceDescriptorTest {

    private val key = SourceKey.of("app.shura.ext.demo", 1L)

    @Test
    fun `exposes the package name and source id from the key`() {
        val descriptor = SourceDescriptor(
            key = key,
            name = "Demo",
            language = "en",
            abi = ExtensionAbi.V1_6,
            capabilities = SourceCapabilities.DEFAULT,
        )
        assertEquals("app.shura.ext.demo", descriptor.packageName)
        assertEquals(1L, descriptor.sourceId)
    }

    @Test
    fun `refuses a descriptor whose language was never normalized`() {
        assertFailsWith<IllegalArgumentException> {
            SourceDescriptor(
                key = key,
                name = "Demo",
                language = "all",
                abi = ExtensionAbi.V1_6,
                capabilities = SourceCapabilities.DEFAULT,
            )
        }
    }

    @Test
    fun `refuses a blank name`() {
        assertFailsWith<IllegalArgumentException> {
            SourceDescriptor(
                key = key,
                name = "  ",
                language = "en",
                abi = ExtensionAbi.V1_6,
                capabilities = SourceCapabilities.DEFAULT,
            )
        }
    }
}

class MangaModelTest {

    @Test
    fun `status codes match the ones extensions publish`() {
        assertEquals(0, MangaStatus.UNKNOWN.code)
        assertEquals(6, MangaStatus.ON_HIATUS.code)
        assertEquals(MangaStatus.COMPLETED, MangaStatus.fromCode(2))
    }

    @Test
    fun `an unknown status code degrades to UNKNOWN rather than throwing`() {
        assertEquals(MangaStatus.UNKNOWN, MangaStatus.fromCode(99))
    }

    @Test
    fun `a page index cannot be negative`() {
        assertFailsWith<IllegalArgumentException> { PageRef(-1, "https://example.org/1.jpg") }
    }

    @Test
    fun `a ref cannot be blank`() {
        assertFailsWith<IllegalArgumentException> { MangaRef("") }
        assertFailsWith<IllegalArgumentException> { ChapterRef(" ") }
    }

    @Test
    fun `index content warning parses the repository spelling`() {
        assertEquals(ExtensionContentWarning.SAFE, ExtensionContentWarning.fromIndexValue("CONTENT_WARNING_SAFE"))
        assertEquals(ExtensionContentWarning.MIXED, ExtensionContentWarning.fromIndexValue("CONTENT_WARNING_MIXED"))
        assertEquals(ExtensionContentWarning.NSFW, ExtensionContentWarning.fromIndexValue("CONTENT_WARNING_NSFW"))
        assertEquals(ExtensionContentWarning.SAFE, ExtensionContentWarning.fromIndexValue(null))
    }

    @Test
    fun `manifest content warning parses the integer spelling`() {
        assertEquals(ExtensionContentWarning.SAFE, ExtensionContentWarning.fromManifestValue("0"))
        assertEquals(ExtensionContentWarning.MIXED, ExtensionContentWarning.fromManifestValue("1"))
        assertEquals(ExtensionContentWarning.NSFW, ExtensionContentWarning.fromManifestValue("2"))
    }
}

class SourceFiltersTest {

    @Test
    fun `with adds an entry without mutating the original`() {
        val base = SourceFilters.EMPTY
        val extended = base.with("genre", "action")
        assertEquals(emptySet(), base.names)
        assertEquals(setOf("genre"), extended.names)
        assertEquals("action", extended["genre"])
    }

    @Test
    fun `a missing name reads as null`() {
        assertEquals(null, SourceFilters.EMPTY["nope"])
    }
}
