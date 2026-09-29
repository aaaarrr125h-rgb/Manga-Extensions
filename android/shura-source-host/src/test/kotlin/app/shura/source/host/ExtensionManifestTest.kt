package app.shura.source.host

import app.shura.source.api.ExtensionAbi
import app.shura.source.api.ExtensionContentWarning
import app.shura.source.api.SourceKey
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The manifest keys, both spellings, and the refusals.
 *
 * The two spellings are not a nicety: this repository still publishes 175 extensions built
 * against 1.4, which used the `tachiyomi.extension.*` names, while 1.6 uses `tachiyomix.*`.
 * A host that only reads one of them silently drops half the catalogue.
 */
class ExtensionManifestTest {

    private fun metaData(vararg pairs: Pair<String, String>) = mapOf(*pairs)

    private val current = metaData(
        ExtensionManifestKeys.ENTRY_CLASS to "keiyoushi.source.Generated",
        ExtensionManifestKeys.NAME_CURRENT to "TachiyomiMix",
        ExtensionManifestKeys.EXTENSION_LIB_CURRENT to "1.6",
        ExtensionManifestKeys.CONTENT_WARNING_CURRENT to "2",
    )

    @Test
    fun `reads the current tachiyomix meta-data spelling`() {
        val manifest = ExtensionManifestParser.fromMetaData(
            packageName = "eu.kanade.tachiyomi.extension.all.tachiyomix",
            versionCode = 1_060_000L,
            versionName = "1.6.0",
            metaData = current,
        )

        assertEquals(ExtensionAbi.V1_6, manifest.abi)
        assertEquals("keiyoushi.source.Generated", manifest.entryClass)
        assertEquals("TachiyomiMix", manifest.displayName)
        assertEquals("1.6", manifest.extensionLib)
        assertEquals(1_060_000L, manifest.versionCode)
        assertEquals(ExtensionContentWarning.NSFW, manifest.contentWarning)
    }

    @Test
    fun `reads the legacy tachiyomi extension meta-data spelling`() {
        val manifest = ExtensionManifestParser.fromMetaData(
            packageName = "eu.kanade.tachiyomi.extension.all.comicskingdom",
            versionCode = 104_003L,
            versionName = "1.4.3",
            metaData = metaData(
                ExtensionManifestKeys.ENTRY_CLASS to "keiyoushi.source.Generated",
                ExtensionManifestKeys.NAME_LEGACY to "Comics Kingdom",
                ExtensionManifestKeys.EXTENSION_LIB_LEGACY to "1.4",
                ExtensionManifestKeys.CONTENT_WARNING_LEGACY to "0",
            ),
        )

        assertEquals(ExtensionAbi.V1_4, manifest.abi)
        assertEquals("Comics Kingdom", manifest.displayName)
        assertEquals("1.4", manifest.extensionLib)
        assertEquals(ExtensionContentWarning.SAFE, manifest.contentWarning)
    }

    @Test
    fun `prefers the current spelling when an extension carries both`() {
        val manifest = ExtensionManifestParser.fromMetaData(
            packageName = "eu.kanade.tachiyomi.extension.en.both",
            versionCode = 1L,
            versionName = "1",
            metaData = metaData(
                ExtensionManifestKeys.ENTRY_CLASS to "keiyoushi.source.Generated",
                ExtensionManifestKeys.NAME_CURRENT to "Current",
                ExtensionManifestKeys.NAME_LEGACY to "Legacy",
                ExtensionManifestKeys.EXTENSION_LIB_CURRENT to "1.6",
                ExtensionManifestKeys.EXTENSION_LIB_LEGACY to "1.4",
                ExtensionManifestKeys.CONTENT_WARNING_CURRENT to "1",
                ExtensionManifestKeys.CONTENT_WARNING_LEGACY to "2",
            ),
        )

        assertEquals("Current", manifest.displayName)
        assertEquals(ExtensionAbi.V1_6, manifest.abi)
        assertEquals(ExtensionContentWarning.MIXED, manifest.contentWarning)
    }

    @Test
    fun `resolves a leading dot against the extension package, as Android does`() {
        val manifest = ExtensionManifestParser.fromMetaData(
            packageName = "eu.kanade.tachiyomi.extension.all.dotname",
            versionCode = 1L,
            versionName = "1",
            metaData = metaData(
                ExtensionManifestKeys.ENTRY_CLASS to ".source.Generated",
                ExtensionManifestKeys.EXTENSION_LIB_CURRENT to "1.6",
            ),
        )

        assertEquals("eu.kanade.tachiyomi.extension.all.dotname.source.Generated", manifest.entryClass)
    }

    @Test
    fun `falls back to the package name when no display name is published`() {
        val manifest = ExtensionManifestParser.fromMetaData(
            packageName = "eu.kanade.tachiyomi.extension.all.noname",
            versionCode = 1L,
            versionName = "1",
            metaData = metaData(
                ExtensionManifestKeys.ENTRY_CLASS to "keiyoushi.source.Generated",
                ExtensionManifestKeys.EXTENSION_LIB_CURRENT to "1.6",
            ),
        )

        assertEquals("eu.kanade.tachiyomi.extension.all.noname", manifest.displayName)
    }

    @Test
    fun `refuses a manifest with no entry class`() {
        val failure = assertThrows<ExtensionManifestException> {
            ExtensionManifestParser.fromMetaData(
                packageName = "eu.kanade.tachiyomi.extension.all.noentry",
                versionCode = 1L,
                versionName = "1",
                metaData = metaData(ExtensionManifestKeys.EXTENSION_LIB_CURRENT to "1.6"),
            )
        }

        assertTrue(
            failure.message!!.contains(ExtensionManifestKeys.ENTRY_CLASS),
            "the message should name the missing key, was: ${failure.message}",
        )
    }

    @Test
    fun `refuses a manifest that declares neither extension lib key`() {
        val failure = assertThrows<ExtensionManifestException> {
            ExtensionManifestParser.fromMetaData(
                packageName = "eu.kanade.tachiyomi.extension.all.nolib",
                versionCode = 1L,
                versionName = "1",
                metaData = metaData(ExtensionManifestKeys.ENTRY_CLASS to "keiyoushi.source.Generated"),
            )
        }

        assertTrue(failure.message!!.contains("extensionLib"), "was: ${failure.message}")
    }

    @Test
    fun `refuses an ABI level this host does not implement, before the class is loaded`() {
        assertFailsWith<app.shura.source.api.UnsupportedExtensionAbiException> {
            ExtensionManifestParser.fromMetaData(
                packageName = "eu.kanade.tachiyomi.extension.all.fromfuture",
                versionCode = 107_000L,
                versionName = "1.7.0",
                metaData = metaData(
                    ExtensionManifestKeys.ENTRY_CLASS to "keiyoushi.source.Generated",
                    ExtensionManifestKeys.EXTENSION_LIB_CURRENT to "1.7",
                ),
            )
        }
    }

    @Test
    fun `refuses a package name that is not a package name`() {
        assertFailsWith<IllegalArgumentException> {
            ExtensionManifestParser.fromMetaData(
                packageName = "not a package",
                versionCode = 1L,
                versionName = "1",
                current,
            )
        }
    }

    @Test
    fun `reads the same manifest out of a properties file`() {
        val properties = java.util.Properties().apply {
            setProperty(ExtensionManifestKeys.ENTRY_CLASS, "keiyoushi.source.Generated")
            setProperty(ExtensionManifestKeys.NAME_CURRENT, "TachiyomiMix")
            setProperty(ExtensionManifestKeys.EXTENSION_LIB_CURRENT, "1.4")
            setProperty(ExtensionManifestKeys.CONTENT_WARNING_CURRENT, "1")
        }

        val manifest = ExtensionManifestParser.fromProperties(
            packageName = "eu.kanade.tachiyomi.extension.all.tachiyomix",
            versionCode = 104_000L,
            versionName = "1.4.0",
            properties = properties,
        )

        assertEquals(ExtensionAbi.V1_4, manifest.abi)
        assertEquals(ExtensionContentWarning.MIXED, manifest.contentWarning)
        assertEquals(
            SourceKey.of("eu.kanade.tachiyomi.extension.all.tachiyomix", 1L).packageName,
            SourceKey.of(manifest.packageName, 1L).packageName,
        )
    }
}
