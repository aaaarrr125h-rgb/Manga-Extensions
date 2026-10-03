package app.shura.manga

import android.content.Context
import app.shura.source.host.ArtifactUrlPolicy
import app.shura.source.host.ExtensionLoader
import app.shura.source.host.ExtensionRepository
import app.shura.source.host.ExtensionStore
import app.shura.source.host.HttpTransport
import app.shura.source.host.LibraryStore
import app.shura.source.host.DownloadStore
import app.shura.source.host.RepositoryClient
import app.shura.source.host.RepositoryFactory
import app.shura.source.host.RepositoryManager
import app.shura.source.host.RepositoryRegistry
import app.shura.source.host.UrlHttpTransport
import app.shura.source.host.ApkInspector
import java.io.File
import java.net.Proxy

/**
 * The repository wiring for the Android application.
 *
 * This is the set of repositories the app uses. Each configured repository is an ordinary
 * [ExtensionRepository] over HTTP (JSON only), sharing one private [ExtensionStore], one signature
 * verifier and one loader, so an extension installed from any repository lands in the same registry
 * and is loaded the same way.
 *
 * The contract is unchanged from the single-repository loop: a downloaded APK is never moved into
 * the path the loader reads from until its certificate has been verified against that repository's
 * `signingKey`, and an upgrade leaves the previous version loadable until the new one lands.
 */
class ShuraRepository private constructor(
    val repositories: RepositoryManager,
    val library: LibraryStore,
    val downloads: DownloadStore,
) {

    companion object {
        /**
         * The repository this build ships with.
         *
         * It is seeded into the repository list on first run and after an upgrade, so existing
         * installs keep the repository they were using without the user having to re-add it. Kept as
         * a constant so the device self-test can refer to it explicitly.
         */
        const val DEFAULT_REPOSITORY_URL = "https://raw.githubusercontent.com/aaaarrr125h-rgb/Manga-Extensions/main/repo/index.json"

        /** The display name of the repository this build ships with. */
        const val DEFAULT_REPOSITORY_NAME = "Shura Official"

        /** The name of the private directory the extension artifacts are stored in. */
        private const val EXTENSIONS_DIRECTORY = "extensions"

        /** The library and reading positions live here, under the app's private files directory. */
        private const val LIBRARY_DIRECTORY = "library"

        /** Downloaded chapters live here. Not a cache: nothing here is evicted automatically. */
        private const val DOWNLOADS_DIRECTORY = "downloads"

        /** The persistent list of configured repositories. Separate from every other store. */
        private const val REPOSITORIES_FILE = "repositories.json"

        fun create(context: Context): ShuraRepository {
            val transport: HttpTransport = UrlHttpTransport(
                connectTimeoutMillis = 15_000,
                readTimeoutMillis = 30_000,
                maxBodyBytes = 32L * 1024 * 1024,
                maxRedirects = 5,
                userAgent = "Shura/1.0 (+https://github.com/aaaarrr125h-rgb/Manga-Extensions)",
                proxy = Proxy.NO_PROXY,
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
            val registry = RepositoryRegistry(
                file = File(context.filesDir, REPOSITORIES_FILE),
                defaultName = DEFAULT_REPOSITORY_NAME,
                defaultUrl = DEFAULT_REPOSITORY_URL,
            )
            val factory = RepositoryFactory { config ->
                ExtensionRepository(
                    client = RepositoryClient(
                        transport = transport,
                        indexUrl = config.url,
                        // Every repository, built-in or user-added, gets the same TLS-only rule.
                        artifactUrlPolicy = ArtifactUrlPolicy.REQUIRE_HTTPS,
                    ),
                    store = store,
                    inspector = inspector,
                    loader = loader,
                    transport = transport,
                )
            }
            return ShuraRepository(
                repositories = RepositoryManager(registry, factory),
                library = LibraryStore(File(context.filesDir, LIBRARY_DIRECTORY)),
                downloads = DownloadStore(File(context.filesDir, DOWNLOADS_DIRECTORY)),
            )
        }
    }
}
