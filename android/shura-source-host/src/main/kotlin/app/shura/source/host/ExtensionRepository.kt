package app.shura.source.host

import app.shura.source.api.ExtensionAbi
import app.shura.source.api.ExtensionContentWarning
import app.shura.source.api.LoadedExtension
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * The repository loop, in one place.
 *
 * This is the object the UI talks to, and it owns the ordering that makes the loop safe:
 *
 * ```
 *   discover()  fetch index -> validate signingKey -> filter to installable
 *   install()   download the signed APK to a staging file -> verify the certificate -> move it
 *               into place -> register it -> drop the previous version
 *   open()      load the registered APK and hand back its sources
 * ```
 *
 * The ordering is the security property, and it is worth stating plainly because it is the whole
 * reason this class exists:
 *
 * - a downloaded APK is **never** moved to the path the loader reads from until
 *   [ExtensionSignatureVerifier] has confirmed that the APK's signing certificate hashes to the
 *   repository's `signingKey`;
 * - it is **never** registered until it is at that path, and the previous version's files are removed
 *   only after the registry no longer points at them;
 * - so a failed verification leaves nothing for [open] to find, nothing for the registry to point at,
 *   and an upgrade leaves the previous version loadable right up until the new one has landed.
 */
class ExtensionRepository(
    private val client: RepositoryClient,
    private val store: ExtensionStore,
    private val inspector: ApkInspector,
    private val loader: ExtensionLoader,
    private val transport: HttpTransport = client.transport,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val verifier = ExtensionSignatureVerifier(inspector)

    /** The repository URL this loop talks to. */
    val repositoryUrl: String get() = client.indexUrl

    /** The last snapshot, answered without touching the network. */
    val snapshot: RepositorySnapshot? get() = cached

    private var cached: RepositorySnapshot? = null

    /** Fetches the index. Each call re-reads the repository, so this is the refresh action. */
    suspend fun discover(): RepositorySnapshot = io {
        client.discover().also { cached = it }
    }

    /**
     * Installs one extension.
     *
     * A refusal -- an unusable artifact, or one whose certificate does not match -- comes back as
     * [InstallOutcome.Refused] rather than as an exception, so a batch update can carry on past one
     * bad extension. Anything the caller cannot sensibly continue past, such as an unreachable
     * repository, still throws a [RepositoryException].
     */
    suspend fun install(available: AvailableExtension): InstallOutcome {
        if (!available.packageName.isValidPackageName()) {
            return InstallOutcome.Refused(
                available.packageName,
                "'${available.packageName}' is not a valid package name",
            )
        }
        if (store.isInstalled(available.packageName, available.versionCode)) {
            return InstallOutcome.AlreadyInstalled(available.packageName, available.versionCode)
        }
        // Asked for before anything is downloaded: with no snapshot there is no signing key, and
        // this is a caller ordering mistake rather than anything to do with this one extension, so
        // it must not be absorbed into the refusal path below.
        val fingerprint = signingFingerprint()
        store.ensureDirectories()

        val staging = store.stagingFile(available.packageName, available.versionCode)
        try {
            // Anything that goes wrong between "here is a URL" and "here is a verified file" is this
            // one extension's problem, not the caller's: one unreachable artifact must not be able
            // to stop an update, and one unverified artifact must not be able to stop the others.
            val verified = runCatching {
                io { downloadTo(available, staging) }
                io { verifier.verify(staging, fingerprint, available.packageName) }
            }.getOrElse { failure ->
                // The refusal path: the staging file is destroyed, the registry is untouched, and any
                // previously installed version of this package keeps loading.
                staging.delete()
                return InstallOutcome.Refused(
                    available.packageName,
                    failure.message ?: failure.javaClass.simpleName,
                )
            }
            val verifiedFingerprint = verified

            val target = File(
                store.packageDirectory(available.packageName),
                store.fileNameFor(available.versionCode),
            )
            return io {
                store.moveIntoPlace(staging, target)
                store.register(
                    packageName = available.packageName,
                    abi = available.abi,
                    versionCode = available.versionCode,
                    versionName = available.versionName,
                    name = available.name,
                    contentWarning = ExtensionContentWarning.fromIndexValue(available.contentWarning),
                    certificateSha256 = verifiedFingerprint,
                    repositoryUrl = client.indexUrl,
                    apkFile = target,
                )
            }
        } catch (failure: RepositoryException) {
            staging.delete()
            throw failure
        }
    }

    /** Installs one package by name from the current snapshot, or null if the index does not list it. */
    suspend fun install(packageName: String): InstallOutcome? {
        val discovered = cached ?: discover()
        val available = discovered.byPackage(packageName) ?: return null
        return install(available)
    }

    /**
     * Brings every extension in the repository up to date.
     *
     * One extension failing does not stop the others, which is the point: an unreachable artifact or
     * an unverified signature must not be able to hold up an otherwise good update.
     */
    suspend fun updateAll(): List<InstallOutcome> {
        val discovered = cached ?: discover()
        return discovered.extensions.map { extension ->
            runCatching { install(extension) }.getOrElse { failure ->
                InstallOutcome.Refused(
                    extension.packageName,
                    failure.message ?: failure.javaClass.simpleName,
                )
            }
        }
    }

    /**
     * Loads one installed extension.
     *
     * The file handed to the loader is the verified artifact itself. An APK is a zip containing
     * `classes.dex`, so the code that runs is the code whose certificate was checked -- there is no
     * second file that could differ from the one that was verified. The manifest is read from that
     * same file, and its package name and version are checked against what was registered, so a
     * mismatch is a hard failure rather than a silently mislabelled source.
     */
    suspend fun open(record: InstalledExtension): LoadedExtension = io {
        val file = store.artifactFile(record)
        if (!file.isFile) {
            throw ExtensionStorageException(file.path, "installed artifact is missing")
        }
        // Re-verified at load time: the file on disk is the only thing about to be executed, so it
        // is the thing that has to be checked, and it is checked against what was verified then.
        val fingerprint = verifier.verify(file, record.certificateSha256, record.packageName)
        check(fingerprint == record.certificateSha256) {
            "certificate of ${record.packageName} does not match the one it was installed with"
        }

        val manifest = inspector.manifestOf(file)
        if (manifest.packageName.isNotBlank() && manifest.packageName != record.packageName) {
            throw ExtensionLoadException(
                "installed artifact declares '${manifest.packageName}' but ${record.packageName} was registered",
            )
        }
        check(manifest.abi == record.abi) {
            "${record.packageName} declares ABI ${manifest.abi.version} but ${record.abi.version} was registered"
        }
        // The index is only allowed to say what the artifact itself says. A mismatch means the
        // registry and the file it points at are not the same release, and loading it would present
        // the wrong version to the user.
        check(manifest.versionCode == record.versionCode) {
            "${record.packageName} declares version ${manifest.versionCode} " +
                "but ${record.versionCode} was registered"
        }

        loader.load(file, manifest)
    }

    /** Loads every installed extension, skipping the ones that fail to load. */
    suspend fun openAll(): List<LoadedExtension> {
        val loaded = mutableListOf<LoadedExtension>()
        for (record in store.installed()) {
            runCatching { open(record) }.getOrNull()?.let { loaded += it }
        }
        return loaded
    }

    /** Every source available from the installed extensions, with the extension each came from. */
    suspend fun catalogue(): List<InstalledSource> {
        val sources = mutableListOf<InstalledSource>()
        for (record in store.installed()) {
            val extension = runCatching { open(record) }.getOrNull() ?: continue
            extension.providers.forEach { provider ->
                sources += InstalledSource(record, provider)
            }
        }
        return sources
    }

    fun installed(): List<InstalledExtension> = store.installed()

    fun installedVersion(packageName: String): Long? = store.installedVersion(packageName)?.versionCode

    private fun signingFingerprint(): String = cached?.signingFingerprint
        ?: throw RepositoryIntegrityException(
            client.indexUrl,
            "no repository snapshot has been read yet; call discover() before installing",
        )

    private fun downloadTo(available: AvailableExtension, destination: File) {
        destination.parentFile?.mkdirs()
        val response = transport.get(available.apkUrl)
        if (response.body.isEmpty()) {
            throw ExtensionArtifactUnavailableException(
                available.packageName,
                available.apkUrl,
                "artifact is empty",
            )
        }
        try {
            destination.writeBytes(response.body)
        } catch (e: IOException) {
            throw ExtensionArtifactUnavailableException(
                available.packageName,
                available.apkUrl,
                "cannot write the artifact: ${e.message}",
                e,
            )
        }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(ioDispatcher) { block() }

    private fun String.isValidPackageName(): Boolean = isNotBlank() &&
        !startsWith(".") &&
        !contains("..") &&
        split('.').all { part ->
            part.isNotEmpty() && part.first().isLetter() && part.all { it.isLetterOrDigit() || it == '_' }
        }
}

/** One source, together with the installed extension it came from. */
data class InstalledSource(
    val extension: InstalledExtension,
    val provider: app.shura.source.api.SourceProvider,
) {
    val descriptor get() = provider.descriptor
    val abi: ExtensionAbi get() = extension.abi
    val packageName: String get() = extension.packageName
}