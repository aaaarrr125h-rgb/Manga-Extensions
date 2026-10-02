package app.shura.source.host

import app.shura.source.api.ExtensionAbi
import app.shura.source.api.ExtensionContentWarning
import app.shura.source.host.json.longOrNull
import app.shura.source.host.json.stringOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.security.MessageDigest

/**
 * One extension this host has installed, as recorded on disk.
 *
 * [artifactSha256] is recorded but never used to authorise loading: the index publishes no
 * per-extension digest, so the certificate is what an install is authorised by and this digest only
 * detects a file that has changed on disk since it was recorded. [certificateSha256] is the
 * repository `signingKey` the install was verified against, kept so the store can be audited without
 * going back to the network.
 */
data class InstalledExtension(
    val packageName: String,
    val abi: ExtensionAbi,
    val versionCode: Long,
    val versionName: String,
    val name: String,
    val contentWarning: ExtensionContentWarning,
    val fileName: String,
    val artifactSha256: String,
    val certificateSha256: String,
    val repositoryUrl: String,
    val installedAtMillis: Long,
) {
    /** The API level, as the index spelled it. */
    val extensionLib: String get() = abi.version

    val isTachiyomiSafe: Boolean get() = contentWarning == ExtensionContentWarning.SAFE
}

/** What happened to one requested install. */
sealed class InstallOutcome {

    abstract val packageName: String

    /** The version on disk once the call returned, or null if nothing is installed. */
    abstract val installedVersionCode: Long?

    /** Nothing to do: the requested version was already installed and its bytes still match. */
    data class AlreadyInstalled(
        override val packageName: String,
        override val installedVersionCode: Long,
    ) : InstallOutcome()

    /** No version of this package was installed before. */
    data class Installed(
        override val packageName: String,
        override val installedVersionCode: Long,
        val previousVersionCode: Long?,
    ) : InstallOutcome()

    /** A previous version was replaced. The previous files went only after the new one landed. */
    data class Upgraded(
        override val packageName: String,
        val fromVersionCode: Long,
        override val installedVersionCode: Long,
    ) : InstallOutcome()

    /** Nothing was installed and the previous version was left exactly as it was. */
    data class Refused(
        override val packageName: String,
        val reason: String,
    ) : InstallOutcome() {
        override val installedVersionCode: Long? get() = null
    }

    val isInstalledOrUpgraded: Boolean get() = this is Installed || this is Upgraded

    val isRefused: Boolean get() = this is Refused

    /** The reason, when this was a refusal. */
    val refusalReason: String? get() = (this as? Refused)?.reason
}

/**
 * The on-disk extension directory and the JSON record of what is in it.
 *
 * Layout under [root]:
 *
 * ```
 * <root>/installed.json                       the registry
 * <root>/extensions/<packageName>/<versionCode>.apk   verified artifacts
 * ```
 *
 * Two properties matter and both are enforced here rather than in the caller:
 *
 * - **atomicity.** A reader either sees the old registry or the new one, never a half written file.
 *   The registry is written to a sibling temporary file, flushed, and moved into place with
 *   [File.renameTo], and artifact files are moved into place the same way. A failure part way
 *   through therefore leaves the previously installed version registered and loadable.
 * - **idempotence.** [isInstalled] re-hashes the artifact, so asking twice is cheap and a file that
 *   was truncated or swapped on disk is reinstalled rather than loaded.
 */
class ExtensionStore(
    private val root: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    val registryFile: File get() = File(root, REGISTRY_NAME)
    val extensionsDirectory: File get() = File(root, "extensions")

    /** The version currently registered for [packageName], or null. */
    fun installedVersion(packageName: String): InstalledExtension? =
        readRegistry().firstOrNull { it.packageName == packageName }

    fun installed(): List<InstalledExtension> = readRegistry().sortedBy { it.packageName }

    fun installedForAbi(abi: ExtensionAbi): List<InstalledExtension> =
        installed().filter { it.abi == abi }

    /** The file an installed version lives in. */
    fun artifactFile(record: InstalledExtension): File =
        File(packageDirectory(record.packageName), record.fileName)

    /**
     * True when [versionCode] for [packageName] is registered *and* its artifact is still byte for
     * byte what was verified at install time.
     */
    fun isInstalled(packageName: String, versionCode: Long): Boolean {
        val record = installedVersion(packageName) ?: return false
        if (record.versionCode != versionCode) return false
        val file = artifactFile(record)
        return file.isFile && sha256(file) == record.artifactSha256
    }

    /**
     * Registers a verified artifact, replacing any other version of the same package.
     *
     * [apkFile] must already be at its final path; this method verifies that fact and the file's
     * digest before touching the registry, so a record can never point at a file that was never
     * fully written.
     *
     * @return what changed, for the caller to report.
     */
    fun register(
        packageName: String,
        abi: ExtensionAbi,
        versionCode: Long,
        versionName: String,
        name: String,
        contentWarning: ExtensionContentWarning,
        certificateSha256: String,
        repositoryUrl: String,
        apkFile: File,
    ): InstallOutcome {
        val expected = File(packageDirectory(packageName), fileNameFor(versionCode))
        if (apkFile.canonicalFile != expected.canonicalFile) {
            throw ExtensionStorageException(
                apkFile.path,
                "artifact is not at its final location ${expected.path}",
            )
        }
        if (!apkFile.isFile) {
            throw ExtensionStorageException(apkFile.path, "artifact is missing")
        }
        val digest = sha256(apkFile)

        val previous = installedVersion(packageName)
        val outcome = when {
            previous != null && previous.versionCode == versionCode && previous.artifactSha256 == digest ->
                InstallOutcome.AlreadyInstalled(packageName, versionCode)

            previous == null -> InstallOutcome.Installed(packageName, versionCode, null)

            else -> InstallOutcome.Upgraded(packageName, previous.versionCode, versionCode)
        }

        val record = InstalledExtension(
            packageName = packageName,
            abi = abi,
            versionCode = versionCode,
            versionName = versionName,
            name = name,
            contentWarning = contentWarning,
            fileName = apkFile.name,
            artifactSha256 = digest,
            certificateSha256 = certificateSha256,
            repositoryUrl = repositoryUrl,
            installedAtMillis = clock(),
        )
        writeRegistry(readRegistry().filterNot { it.packageName == packageName } + record)

        // Only after the new version is registered are older files removed, so a crash between the
        // two leaves a working install rather than a registered file that is not there.
        if (previous != null) pruneOtherVersions(packageName, keep = apkFile.name)
        return outcome
    }

    /** Removes [packageName] entirely. Used by "remove extension", not by an install path. */
    fun uninstall(packageName: String): Boolean {
        val removed = readRegistry().any { it.packageName == packageName }
        writeRegistry(readRegistry().filterNot { it.packageName == packageName })
        packageDirectory(packageName).deleteRecursively()
        return removed
    }

    fun packageDirectory(packageName: String): File = File(extensionsDirectory, packageName)

    /** Where an artifact is written before it is verified. Inside the package, never loaded from. */
    fun stagingFile(packageName: String, versionCode: Long): File =
        File(packageDirectory(packageName), "$STAGING_PREFIX$versionCode$APK_SUFFIX")

    fun ensureDirectories() {
        try {
            if (!extensionsDirectory.isDirectory && !extensionsDirectory.mkdirs() && !extensionsDirectory.isDirectory) {
                throw ExtensionStorageException(root.path, "cannot create extensions directory")
            }
        } catch (e: java.io.IOException) {
            throw ExtensionStorageException(root.path, "cannot create extensions directory: ${e.message}", e)
        }
    }

    /**
     * Moves [from] to [to] atomically, falling back to a copy when the two live on different
     * filesystems (which happens when the store sits on removable storage).
     */
    fun moveIntoPlace(from: File, to: File): File {
        try {
            if (!from.renameTo(to)) {
                from.copyTo(to, overwrite = true)
                from.delete()
            }
        } catch (e: java.io.IOException) {
            throw ExtensionStorageException(to.path, "cannot move artifact into place: ${e.message}", e)
        }
        return to
    }

    private fun pruneOtherVersions(packageName: String, keep: String) {
        // Every other artifact in the package directory goes, including staging leftovers, so an
        // upgrade does not leave the previous APK on disk forever. This runs only after the registry
        // has stopped pointing at them.
        packageDirectory(packageName).listFiles()?.forEach { file ->
            if (file.name != keep && file.name.endsWith(APK_SUFFIX)) file.delete()
        }
    }

    fun fileNameFor(versionCode: Long): String = "$versionCode$APK_SUFFIX"

    /** Deletes staging leftovers from an interrupted earlier run. */
    fun sweepStaging() {
        extensionsDirectory.listFiles()?.forEach { directory ->
            directory.listFiles()?.forEach { file ->
                if (file.name.startsWith("$STAGING_PREFIX")) file.delete()
            }
        }
    }

    internal fun readRegistry(): List<InstalledExtension> {
        val file = registryFile
        if (!file.isFile) return emptyList()
        val text = try {
            file.readText()
        } catch (e: java.io.IOException) {
            throw ExtensionStorageException(file.path, "cannot read registry: ${e.message}", e)
        }
        if (text.isBlank()) return emptyList()
        val root = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: throw ExtensionStorageException(file.path, "registry is not a JSON object")
        val entries = (root[EXTENSIONS_FIELD] as? JsonArray).orEmpty()
        return entries.mapNotNull(::readRecord)
    }

    private fun writeRegistry(records: List<InstalledExtension>) {
        ensureDirectories()
        val payload = JsonObject(
            buildMap {
                put("formatVersion", JsonPrimitive(REGISTRY_FORMAT))
                put(
                    EXTENSIONS_FIELD,
                    JsonArray(records.map(::writeRecord)),
                )
            }
        )
        val temporary = File(root, "$REGISTRY_NAME$STAGING_SUFFIX")
        try {
            temporary.writeText(payload.toString())
            if (!temporary.renameTo(registryFile)) {
                registryFile.writeText(payload.toString())
                temporary.delete()
            }
        } catch (e: java.io.IOException) {
            temporary.delete()
            throw ExtensionStorageException(registryFile.path, "cannot write registry: ${e.message}", e)
        }
    }

    private fun writeRecord(record: InstalledExtension): JsonObject = JsonObject(
        mapOf(
            "packageName" to JsonPrimitive(record.packageName),
            "extensionLib" to JsonPrimitive(record.extensionLib),
            "versionCode" to JsonPrimitive(record.versionCode),
            "versionName" to JsonPrimitive(record.versionName),
            "name" to JsonPrimitive(record.name),
            "contentWarning" to JsonPrimitive(record.contentWarning.toIndexValue()),
            "fileName" to JsonPrimitive(record.fileName),
            "artifactSha256" to JsonPrimitive(record.artifactSha256),
            "certificateSha256" to JsonPrimitive(record.certificateSha256),
            "repositoryUrl" to JsonPrimitive(record.repositoryUrl),
            "installedAt" to JsonPrimitive(record.installedAtMillis),
        )
    )

    private fun readRecord(element: JsonElement): InstalledExtension? {
        val node = element as? JsonObject ?: return null
        val packageName = node.stringOrNull("packageName")?.takeIf { it.isNotBlank() } ?: return null
        val abi = ExtensionAbi.parseOrNull(node.stringOrNull("extensionLib")) ?: return null
        val fileName = node.stringOrNull("fileName")?.takeIf { it.isNotBlank() } ?: return null
        return InstalledExtension(
            packageName = packageName,
            abi = abi,
            versionCode = node.longOrNull("versionCode") ?: return null,
            versionName = node.stringOrNull("versionName").orEmpty(),
            name = node.stringOrNull("name").orEmpty(),
            contentWarning = ExtensionContentWarning.fromIndexValue(node.stringOrNull("contentWarning")),
            fileName = fileName,
            artifactSha256 = node.stringOrNull("artifactSha256").orEmpty(),
            certificateSha256 = node.stringOrNull("certificateSha256").orEmpty(),
            repositoryUrl = node.stringOrNull("repositoryUrl").orEmpty(),
            installedAtMillis = node.longOrNull("installedAt") ?: 0L,
        )
    }

    internal companion object {
        const val REGISTRY_NAME = "installed.json"
        const val REGISTRY_FORMAT = 1
        const val APK_SUFFIX = ".apk"
        const val STAGING_PREFIX = ".staging-"
        const val STAGING_SUFFIX = ".partial"
        const val EXTENSIONS_FIELD = "extensions"

        /** SHA-256 of a file's bytes, as lower case hexadecimal. */
        fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
