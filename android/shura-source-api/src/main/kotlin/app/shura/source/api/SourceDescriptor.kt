package app.shura.source.api

/**
 * Everything the host knows about a source without asking it anything.
 *
 * [key], [name] and [language] are the identity; the rest is either read from the repository
 * index, from the extension manifest, or reported by the extension when it was loaded.
 */
data class SourceDescriptor(
    val key: SourceKey,
    val name: String,
    val language: String,
    val abi: ExtensionAbi,
    val capabilities: SourceCapabilities,
    val packageName: String = key.packageName,
    val versionName: String = "",
    val versionCode: Long = 0L,
    val contentWarning: ExtensionContentWarning = ExtensionContentWarning.SAFE,
    val homeUrl: String? = null,
    val iconUrl: String? = null,
    val apkUrl: String? = null,
) {
    init {
        require(name.isNotBlank()) { "SourceDescriptor name must not be blank" }
        require(SourceLanguage.isNormalized(language)) {
            "SourceDescriptor language '$language' is not normalized; use SourceLanguage.normalize()"
        }
    }

    val sourceId: Long get() = key.sourceId

    fun supports(capability: SourceCapability): Boolean = capability in capabilities
}

/** What the host read out of the extension's manifest before any class was loaded. */
data class ExtensionMetadata(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val contentWarning: ExtensionContentWarning = ExtensionContentWarning.SAFE,
) {
    init {
        require(SourceKey.isValidPackageName(packageName)) {
            "ExtensionMetadata package name is not valid: '$packageName'"
        }
    }
}

/** How an extension was found on disk, and what was checked about it. */
data class LoadedExtension(
    val metadata: ExtensionMetadata,
    val abi: ExtensionAbi,
    val entryClass: String,
    val providers: List<SourceProvider>,
    val classLoader: ClassLoader,
) {
    val packageName: String get() = metadata.packageName

    val sources: List<SourceDescriptor> get() = providers.map(SourceProvider::descriptor)

    fun close() {
        // URLClassLoader is the only closeable loader the host creates; DexClassLoader on
        // Android is closed by the platform and does not implement Closeable there.
        (classLoader as? java.io.Closeable)?.close()
    }
}
