package app.shura.source.host

import app.shura.source.api.ExtensionAbi
import app.shura.source.api.ExtensionContentWarning
import app.shura.source.api.SourceKey
import java.util.Properties

/**
 * What an extension says about itself, before any of its code runs.
 *
 * The key names are the ones a published extension actually carries. `tachiyomix.*` is the
 * current spelling and `tachiyomi.extension.*` is the older one; both are accepted because the
 * repository still serves extensions built against either.
 */
data class ExtensionManifest(
    val packageName: String,
    val entryClass: String,
    val displayName: String,
    val extensionLib: String,
    val versionCode: Long,
    val versionName: String,
    val contentWarning: ExtensionContentWarning = ExtensionContentWarning.SAFE,
) {
    val abi: ExtensionAbi get() = ExtensionAbi.parse(extensionLib)

    init {
        require(SourceKey.isValidPackageName(packageName)) {
            "Extension package name is not a valid package name: '$packageName'"
        }
        require(entryClass.isNotBlank()) { "Extension entry class must not be blank" }
    }
}

object ExtensionManifestKeys {
    const val FEATURE = "tachiyomi.extension"

    const val ENTRY_CLASS = "tachiyomi.extension.class"
    const val NAME_CURRENT = "tachiyomix.name"
    const val NAME_LEGACY = "tachiyomi.extension.name"
    const val CONTENT_WARNING_CURRENT = "tachiyomix.contentWarning"
    const val CONTENT_WARNING_LEGACY = "tachiyomi.extension.nsfw"
    const val EXTENSION_LIB_CURRENT = "tachiyomix.extensionLib"
    const val EXTENSION_LIB_LEGACY = "tachiyomi.extension.libVersion"
}

class ExtensionManifestException(message: String) : IllegalArgumentException(message)

/**
 * Builds an [ExtensionManifest] from the raw manifest meta-data an APK carries.
 *
 * Kept free of Android types on purpose: the same parsing runs for an APK read through
 * `PackageManager` and for the properties file a plain jar carries, so there is one
 * implementation and one set of tests.
 */
object ExtensionManifestParser {

    fun fromMetaData(
        packageName: String,
        versionCode: Long,
        versionName: String,
        metaData: Map<String, String>,
    ): ExtensionManifest {
        val entryClass = metaData[ExtensionManifestKeys.ENTRY_CLASS]?.trim()
        if (entryClass.isNullOrEmpty()) {
            throw ExtensionManifestException(
                "Extension $packageName has no '${ExtensionManifestKeys.ENTRY_CLASS}' meta-data",
            )
        }

        val extensionLib = (metaData[ExtensionManifestKeys.EXTENSION_LIB_CURRENT]
            ?: metaData[ExtensionManifestKeys.EXTENSION_LIB_LEGACY])
            ?.trim()
        if (extensionLib.isNullOrEmpty()) {
            throw ExtensionManifestException(
                "Extension $packageName declares neither " +
                    "'${ExtensionManifestKeys.EXTENSION_LIB_CURRENT}' nor " +
                    "'${ExtensionManifestKeys.EXTENSION_LIB_LEGACY}'",
            )
        }
        // Fail here rather than at the first catalogue call: an extension built for a level this
        // host does not implement must never reach the registry.
        ExtensionAbi.parse(extensionLib)

        val name = (metaData[ExtensionManifestKeys.NAME_CURRENT]
            ?: metaData[ExtensionManifestKeys.NAME_LEGACY])
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: packageName

        val contentWarning = metaData[ExtensionManifestKeys.CONTENT_WARNING_CURRENT]?.let {
            ExtensionContentWarning.fromManifestValue(it)
        } ?: ExtensionContentWarning.fromManifestValue(metaData[ExtensionManifestKeys.CONTENT_WARNING_LEGACY])

        return ExtensionManifest(
            packageName = packageName,
            entryClass = resolveEntryClass(packageName, entryClass),
            displayName = name,
            extensionLib = extensionLib,
            versionCode = versionCode,
            versionName = versionName,
            contentWarning = contentWarning,
        )
    }

    fun fromProperties(
        packageName: String,
        versionCode: Long,
        versionName: String,
        properties: Properties,
    ): ExtensionManifest = fromMetaData(
        packageName = packageName,
        versionCode = versionCode,
        versionName = versionName,
        metaData = properties.stringPropertyNames().associateWith { properties.getProperty(it) },
    )

    /** A leading dot means "relative to the extension package", exactly as in Android. */
    private fun resolveEntryClass(packageName: String, entryClass: String): String =
        if (entryClass.startsWith(".")) packageName + entryClass else entryClass
}
