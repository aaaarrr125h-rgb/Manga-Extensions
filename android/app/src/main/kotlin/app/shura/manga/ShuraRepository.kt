package app.shura.manga

import android.content.Context
import app.shura.source.api.ExtensionContentWarning
import app.shura.source.host.ArtifactUrlPolicy
import app.shura.source.host.ExtensionRepository
import app.shura.source.host.ExtensionStore
import app.shura.source.host.HttpTransport
import app.shura.source.host.LibraryStore
import app.shura.source.host.DownloadStore
import app.shura.source.host.RepositoryClient
import app.shura.source.host.UrlHttpTransport
import app.shura.source.host.ApkInspector
import app.shura.source.host.ExtensionLoader
import java.io.File
import java.net.Proxy

/**
 * The repository wiring for the Android application.
 *
 * This repository is the one the app uses, not the one the self-tests exercise in isolation. It
 * reads the repository index over HTTP (JSON only), downloads signed APKs to a private directory,
 * verifies their signing certificate against the repository's `signingKey` using Android's
 * [PackageManager], installs them atomically into the app's private store, and loads their sources
 * into isolated classloaders via the host layer.
 *
 * The contract is the same as the JVM loop: a downloaded APK is never moved into the path the loader
 * reads from until its certificate has been verified, and an upgrade leaves the previous version
 * loadable until the new one lands.
 */
class ShuraRepository private constructor(
    val repository: ExtensionRepository,
    val library: LibraryStore,
    val downloads: DownloadStore,
    private val store: ExtensionStore,
) {

    companion object {
        /**
         * The default repository URL this build points at.
         *
         * Kept as a constant so the device self-test can refer to it explicitly.
         */
        const val DEFAULT_REPOSITORY_URL = "https://raw.githubusercontent.com/aaaarrr125h-rgb/Manga-Extensions/main/repo/index.json"

        /** The name of the private directory the extension artifacts are stored in. */
        private const val EXTENSIONS_DIRECTORY = "extensions"

        /** The library and reading positions live here, under the app's private files directory. */
        private const val LIBRARY_DIRECTORY = "library"

        /** Downloaded chapters live here. Not a cache: nothing here is evicted automatically. */
        private const val DOWNLOADS_DIRECTORY = "downloads"

        fun create(context: Context): ShuraRepository {
            val transport: HttpTransport = UrlHttpTransport(
                connectTimeoutMillis = 15_000,
                readTimeoutMillis = 30_000,
                maxBodyBytes = 32L * 1024 * 1024,
                maxRedirects = 5,
                userAgent = "Shura/1.0 (+https://github.com/aaaarrr125h-rgb/Manga-Extensions)",
                proxy = Proxy.NO_PROXY,
            )
            val client = RepositoryClient(
                transport = transport,
                indexUrl = DEFAULT_REPOSITORY_URL,
                artifactUrlPolicy = ArtifactUrlPolicy.REQUIRE_HTTPS,
            )
            val root = File(context.filesDir, EXTENSIONS_DIRECTORY)
            val store = ExtensionStore(root)
            val inspector: ApkInspector = AndroidApkInspector(context)
            val loader = ExtensionLoader(
                abiRegistry = AbiAssetsInstaller(context).install(),
                // Without this the host would use its JVM URLClassLoader, which cannot read the
                // classes.dex inside a downloaded APK, so every installed extension would install
                // cleanly and then fail to produce a single source on device.
                classLoaderFactory = AndroidExtensionClassLoaderFactory(context),
            )
            val repository = ExtensionRepository(
                client = client,
                store = store,
                inspector = inspector,
                loader = loader,
                transport = transport,
            )
            return ShuraRepository(
                repository = repository,
                library = LibraryStore(File(context.filesDir, LIBRARY_DIRECTORY)),
                downloads = DownloadStore(File(context.filesDir, DOWNLOADS_DIRECTORY)),
                store = store,
            )
        }
    }
}