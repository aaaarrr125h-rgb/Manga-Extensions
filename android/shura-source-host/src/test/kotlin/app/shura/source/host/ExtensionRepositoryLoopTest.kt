package app.shura.source.host

import app.shura.source.api.Chapter
import app.shura.source.api.ExtensionAbi
import app.shura.source.api.ExtensionContentWarning
import app.shura.source.api.MangaStatus
import app.shura.source.api.SourceCapability
import app.shura.source.api.SourceFilters
import app.shura.source.api.SourceLanguage
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The whole loop, over a real socket, against the real extension jars.
 *
 * Everything below the UI exists to turn a repository URL into sources that answer, and that claim is
 * only worth something if it is exercised end to end. So this test serves a real HTTP index and real
 * artifact bytes over a loopback socket, through the real [UrlHttpTransport], into a real
 * [ExtensionStore] on a real temporary directory, verified by the real [ExtensionSignatureVerifier],
 * and loaded by the real [ExtensionLoader] out of the real staged jars into isolated classloaders.
 * Then it walks the catalogue all the way to page URLs.
 *
 * Nothing here fakes the repository. The index that gets served is generated from the descriptors of
 * the jars actually being served, so the test cannot claim a loop the artifacts do not support, and
 * the certificate is a real digest computed from real bytes that the verifier really hashes.
 *
 * The refusals are asserted as carefully as the happy path. A wrong certificate, an unreachable
 * artifact and a failed upgrade all have to leave a previously installed extension loadable and
 * working, because that is the property that makes an update safe to run in the background.
 *
 * One limitation worth stating: the two staged fixtures declare the *same* package name, so an index
 * can publish only one of them at a time. Moving between the ABI levels is therefore covered the way
 * it happens in the field -- as an upgrade of one package -- and simultaneous loading of both levels
 * is covered by `ExtensionLoaderTest`.
 */
class ExtensionRepositoryLoopTest {

    /** The package both staged fixtures declare, which is also what the shipped fixtures use. */
    private val packageName = "eu.kanade.tachiyomi.extension.all.tachiyomix"

    /**
     * The signing certificate of an artifact, modelled the way a real one behaves: derived from the
     * artifact's own bytes.
     *
     * That matters for the tests. A constant certificate would let bytes be swapped on disk without
     * the fingerprint changing, which would make "tamper with the installed artifact" untestable and
     * would not describe what `PackageManager` does. [salt] stands for a *different* key having
     * signed the same bytes, which is the impostor case.
     */
    private fun certificateOf(artifact: File, salt: String = ""): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(artifact.readBytes() + salt.toByteArray())

    /** The key the repository publishes when it is serving [ABI16_FIXTURE] honestly. */
    private val publishedFingerprint: String =
        SigningFingerprint.ofCertificate(certificateOf(TestArtifacts.extension(ABI16_FIXTURE)))

    private val servers = mutableListOf<HttpServer>()
    private val roots = mutableListOf<File>()

    @AfterEach
    fun tearDown() {
        servers.forEach { it.stop(0) }
        servers.clear()
        roots.forEach { it.deleteRecursively() }
        roots.clear()
    }

    // ------------------------------------------------------------------ the repository under test

    /**
     * A loopback repository serving one real extension jar plus a real index that describes it.
     *
     * [publishVersionCode] lets a test publish the same jar at a different version, which is how the
     * upgrade and mismatch cases are built without rebuilding an artifact.
     */
    private inner class LoopbackRepository(
        val artifact: File,
        private val publishVersionCode: Long? = null,
    ) {
        private val jar: File get() = artifact
        private var signingKey: String = SigningFingerprint.ofCertificate(certificateOf(jar))
        private var unservedPackages: MutableSet<String> = mutableSetOf()
        private var emptyArtifact: Boolean = false
        private lateinit var baseUrl: String

        val manifest: ExtensionManifest = JarDescriptorInspector.manifestOf(jar)
        private val publishedVersion: Long get() = publishVersionCode ?: manifest.versionCode

        fun signWith(key: String) {
            signingKey = key
        }

        /** Makes one package's artifact 404, without changing the index that names it. */
        fun stopServing(pkg: String) {
            unservedPackages += pkg
        }

        /** Answers the artifact with a 200 and no bytes, the "download did nothing" case. */
        fun serveEmptyArtifact() {
            emptyArtifact = true
        }

        fun start(): LoopbackRepository {
            val started = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
            started.createContext("/index.json") { exchange ->
                val body = indexJson().toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            started.createContext("/artifacts/") { exchange ->
                when {
                    packageName in unservedPackages -> {
                        exchange.sendResponseHeaders(404, -1)
                        exchange.close()
                    }

                    emptyArtifact -> {
                        exchange.sendResponseHeaders(200, 0)
                        exchange.responseBody.close()
                    }

                    else -> {
                        val bytes = jar.readBytes()
                        exchange.sendResponseHeaders(200, bytes.size.toLong())
                        exchange.responseBody.use { it.write(bytes) }
                    }
                }
            }
            started.executor = null
            started.start()
            servers += started
            baseUrl = "http://127.0.0.1:${started.address.port}"
            return this
        }

        val indexUrl: String get() = "$baseUrl/index.json"

        private fun indexJson(): String = """
            {
              "name": "Shura loopback",
              "badgeLabel": "test",
              "signingKey": "$signingKey",
              "contact": { "website": "https://shura.example" },
              "extensionList": {
                "extensions": [
                  {
                    "name": "${manifest.displayName}",
                    "packageName": "$packageName",
                    "extensionLib": "${manifest.abi.version}",
                    "versionCode": "$publishedVersion",
                    "versionName": "${manifest.versionName}",
                    "contentWarning": "${manifest.contentWarning.toIndexValue()}",
                    "sources": ${sourcesJson()},
                    "resources": {
                      "apkUrl": "$baseUrl/artifacts/$packageName",
                      "iconUrl": "$baseUrl/artifacts/icon.png",
                      "jarUrl": "$baseUrl/artifacts/$packageName"
                    }
                  }
                ]
              }
            }
        """.trimIndent()

        /**
         * The sources this level really exposes, as the repository projects them.
         *
         * Read off the fixture rather than invented, and checked against what the extension actually
         * loads in the catalogue test below, so a fixture change cannot leave the index lying. The
         * `no latest` source subclasses the other one, so it declares the same language.
         */
        private fun sourcesJson(): String = when (manifest.abi) {
            ExtensionAbi.V1_4 -> """
                [
                  { "id": "3196562139457428713", "name": "TachiyomiMix", "language": "all",
                    "homeUrl": "https://one.example" },
                  { "id": "4507511004372246913", "name": "TachiyomiMix (no latest)", "language": "all",
                    "homeUrl": "https://two.example" }
                ]
            """.trimIndent()

            ExtensionAbi.V1_6 -> """
                [
                  { "id": "3196562139457428714", "name": "TachiyomiMix", "language": "all",
                    "homeUrl": "https://one.example" }
                ]
            """.trimIndent()
        }
    }

    private fun repository(
        jar: String = ABI16_FIXTURE,
        publishVersionCode: Long? = null,
    ) = LoopbackRepository(TestArtifacts.extension(jar), publishVersionCode).start()

    // ------------------------------------------------------------------ the host under test

    private fun newStore(): ExtensionStore {
        val root = Files.createTempDirectory("shura-loop").toFile()
        roots += root
        return ExtensionStore(root) { 1_700_000_000_000L }
    }

    private fun newRepository(
        repository: LoopbackRepository,
        store: ExtensionStore,
        certificateSalt: String = "",
        transport: HttpTransport = UrlHttpTransport(),
    ): ExtensionRepository = ExtensionRepository(
        // The loopback server speaks plaintext; the policy that permits exactly that is stated
        // rather than hidden behind a check that would have to be switched off.
        client = RepositoryClient(transport, repository.indexUrl, artifactUrlPolicy = ArtifactUrlPolicy.ALLOW_LOOPBACK),
        store = store,
        inspector = FileSignedInspector(repository.artifact, certificateSalt),
        loader = ExtensionLoader(AbiRegistry.fromDirectory(TestArtifacts.extensionArtifacts)),
        transport = transport,
    )

    // ------------------------------------------------------------------ discover

    @Test
    fun `the index is fetched over http and validated`() = runBlocking {
        val repository = repository()
        val host = newRepository(repository, newStore())

        val snapshot = host.discover()

        assertEquals(publishedFingerprint, snapshot.signingFingerprint)
        assertEquals(1, snapshot.size)
        val available = snapshot.extensions.single()
        assertEquals(packageName, available.packageName)
        assertEquals(ExtensionAbi.V1_6, available.abi)
        assertEquals(106_000L, available.versionCode)
        assertTrue(available.apkUrl.startsWith("http://127.0.0.1"), "the APK URL is the one the index published")
        assertTrue(snapshot.unusable.isEmpty(), "nothing about this repository should be unusable")

        // The catalogue is complete before a single byte of an extension is downloaded, which is what
        // lets a source list be built before anything is installed.
        assertEquals(1, available.sources.size)
        assertEquals("TachiyomiMix", available.sources.single().name)
        assertEquals(SourceLanguage.MULTIPLE, available.sources.single().language)
    }

    @Test
    fun `a repository with a bad signing key is refused before anything is installed`() = runBlocking {
        val repository = repository().apply { signWith("not-a-digest") }
        val store = newStore()
        val host = newRepository(repository, store)

        val failure = assertFailsWith<RepositoryIntegrityException> { host.discover() }
        assertContains(failure.message.orEmpty(), "SHA-256 digest")
        assertTrue(store.installed().isEmpty())
    }

    @Test
    fun `an unreachable repository is a reachability failure and installs nothing`() = runBlocking {
        val store = newStore()
        val host = newRepository(repository(), store, transport = UnreachableTransport())

        assertFailsWith<RepositoryUnreachableException> { host.discover() }
        assertTrue(store.installed().isEmpty())
    }

    // ------------------------------------------------------------------ install and load

    @Test
    fun `an extension is downloaded, verified, installed, registered and loaded`() = runBlocking {
        val repository = repository()
        val store = newStore()
        val host = newRepository(repository, store)
        host.discover()

        val outcome = host.install(host.snapshot!!.byPackage(packageName)!!)

        assertTrue(outcome is InstallOutcome.Installed, "was: $outcome")
        assertEquals(106_000L, outcome.installedVersionCode)

        // Registered, with the fingerprint the certificate really hashed to.
        val record = assertNotNull(store.installedVersion(packageName))
        assertEquals(publishedFingerprint, record.certificateSha256)
        assertEquals(ExtensionAbi.V1_6, record.abi)
        assertEquals(ExtensionContentWarning.SAFE, record.contentWarning)
        assertEquals(repository.indexUrl, record.repositoryUrl)
        assertTrue(store.isInstalled(packageName, 106_000L))

        // The artifact on disk is the bytes that were verified, at its final name, and the recorded
        // digest is of exactly those bytes.
        val artifact = store.artifactFile(record)
        assertEquals("106000.apk", artifact.name)
        assertTrue(artifact.isFile)
        assertEquals(ExtensionStore.sha256(artifact), record.artifactSha256)

        val loaded = host.open(record)
        try {
            assertEquals(packageName, loaded.packageName)
            assertEquals(ExtensionAbi.V1_6, loaded.abi)
            assertEquals("keiyoushi.source.Generated", loaded.entryClass)
            assertEquals(1, loaded.providers.size)

            // The provider comes out of the extension's own classloader, or none of the isolation
            // the loader provides means anything.
            assertEquals(loaded.classLoader, loaded.providers.single().javaClass.classLoader)
            assertEquals(AbiRegistry.BRIDGE_1_6, loaded.providers.single().javaClass.name)
        } finally {
            loaded.close()
        }
    }

    @Test
    fun `installing the same version twice changes nothing`() = runBlocking {
        val repository = repository()
        val store = newStore()
        val host = newRepository(repository, store)
        host.discover()
        val available = host.snapshot!!.byPackage(packageName)!!

        assertTrue(host.install(available).isInstalledOrUpgraded)
        val second = host.install(available)

        assertTrue(second is InstallOutcome.AlreadyInstalled, "was: $second")
        assertEquals(1, store.installed().size)
    }

    @Test
    fun `updateAll installs what the repository offers`() = runBlocking {
        val store = newStore()
        val host = newRepository(repository(), store)

        val outcomes = host.updateAll()

        assertEquals(1, outcomes.size)
        assertTrue(outcomes.single().isInstalledOrUpgraded, "was: $outcomes")
        assertEquals(1, store.installed().size)
    }

    // ------------------------------------------------------------------ the whole catalogue

    @Test
    fun `an installed 1_6 source is browsable, searchable and readable to its pages`() = runBlocking {
        val store = newStore()
        val host = newRepository(repository(ABI16_FIXTURE), store)
        host.updateAll()

        val installed = host.catalogue()
        assertEquals(1, installed.size)
        assertEquals(packageName, installed.single().packageName)

        val provider = installed.single().provider
        val descriptor = provider.descriptor
        assertTrue(SourceCapability.BROWSE_POPULAR in descriptor.capabilities)
        assertTrue(SourceCapability.INCREMENTAL_UPDATE in descriptor.capabilities)
        assertEquals(SourceLanguage.MULTIPLE, descriptor.language, "'all' normalises to 'mul'")

        // Browse, including the page number crossing the classloader boundary.
        val page = provider.popularManga(1)
        assertEquals("Popular entry 1", page.mangas.single().title)
        assertTrue(page.hasNextPage)
        assertEquals("Popular entry 2", provider.popularManga(2).mangas.single().title)

        // Search, once by query and once through the host's flat filter map.
        assertEquals("Search entry 1 for 'shura'", provider.searchManga(1, "shura", SourceFilters(emptyMap())).mangas.single().title)
        assertEquals(
            "Search entry 1 for 'needle'",
            provider.searchManga(1, "ignored", SourceFilters(mapOf("Search" to "needle"))).mangas.single().title,
        )

        // Details, from the ref the extension itself produced.
        val ref = page.mangas.single().ref
        val details = provider.mangaDetails(ref)
        assertEquals("fixture", details.author)
        assertEquals("fixture", details.artist)
        assertEquals(listOf("action", "fantasy"), details.genres)
        assertEquals(MangaStatus.ONGOING, details.status)
        assertTrue(details.initialized)
        assertNotNull(details.memo, "a 1.6 memo has to reach the host")

        // Chapters, including the 2.5 number that has to survive as "2.5".
        val chapters = provider.chapterList(ref)
        assertEquals(listOf("Chapter 1", "Chapter 2"), chapters.map(Chapter::name))
        assertEquals(listOf("1", "2.5"), chapters.map { it.number })
        assertTrue(chapters.all { it.memo?.contains("chapterSiteId") == true }, "was: ${chapters.map { it.memo }}")

        // Pages.
        val pages = provider.pageList(chapters.last().ref)
        assertEquals(3, pages.size)
        assertEquals(listOf(0, 1, 2), pages.map { it.index })
        assertTrue(pages.all { it.url.isNotBlank() })
    }

    @Test
    fun `an installed 1_4 source answers the same loop over the RxJava surface`() = runBlocking {
        val store = newStore()
        val host = newRepository(repository(ABI14_FIXTURE), store)
        host.updateAll()

        val installed = host.catalogue()
        assertEquals(2, installed.size, "the 1.4 fixture ships two sources")

        // The index named exactly these two sources, and that is what got installed.
        assertEquals(
            setOf("TachiyomiMix", "TachiyomiMix (no latest)"),
            installed.map { it.descriptor.name }.toSet(),
        )
        // The `no latest` source subclasses the other one, so both declare 'all' and both normalise
        // to 'mul'. The index has to say that too, or the projection would be a lie.
        assertEquals(setOf(SourceLanguage.MULTIPLE), installed.map { it.descriptor.language }.toSet())

        val provider = installed.single { it.descriptor.name == "TachiyomiMix" }.provider
        assertTrue(
            SourceCapability.INCREMENTAL_UPDATE !in provider.descriptor.capabilities,
            "1.4 has no getMangaUpdate and must not claim it",
        )

        val listed = provider.popularManga(1).mangas.single()
        assertEquals("Popular entry 1", listed.title)
        assertEquals(listed.url, listed.ref.value, "a listed entry's ref is the url it was given")

        val chapters = provider.chapterList(listed.ref)
        assertEquals(listOf("Chapter 1", "Chapter 2"), chapters.map { it.name })
        assertEquals(listOf("1", "2"), chapters.map { it.number })
        assertEquals(3, provider.pageList(chapters.first().ref).size)
    }

    // ------------------------------------------------------------------ refusals

    @Test
    fun `an artifact signed by the wrong certificate is never installed`() = runBlocking {
        val repository = repository()
        val store = newStore()
        // The index publishes the right key; the artifact that arrives is signed by another one.
        val host = newRepository(repository, store, certificateSalt = "a different certificate")
        host.discover()

        val outcome = host.install(host.snapshot!!.byPackage(packageName)!!)

        assertTrue(outcome is InstallOutcome.Refused, "was: $outcome")
        val reason = assertNotNull((outcome as InstallOutcome.Refused).reason)
        assertContains(reason, "repository publishes")
        assertContains(reason, "signed by")
        assertNull(outcome.installedVersionCode)

        assertTrue(store.installed().isEmpty(), "nothing may be registered")
        assertNull(store.installedVersion(packageName))
        assertFalse(
            store.stagingFile(packageName, 106_000L).exists(),
            "the refused download must not be left on disk",
        )
        assertTrue(host.catalogue().isEmpty(), "and nothing may be loadable")
    }

    @Test
    fun `an upgrade that fails verification leaves the installed version working`() = runBlocking {
        val store = newStore()
        val first = newRepository(repository(), store)
        first.updateAll()
        val before = assertNotNull(store.installedVersion(packageName))
        assertTrue(first.open(before).providers.isNotEmpty())

        // The repository now publishes a newer version of the same package, signed by a different
        // certificate. The user must keep the version they have.
        val impostor = repository(publishVersionCode = 106_001L)
        val host = newRepository(impostor, store, certificateSalt = "an impostor certificate")
        host.discover()

        val outcome = host.install(host.snapshot!!.byPackage(packageName)!!)

        assertTrue(outcome is InstallOutcome.Refused, "was: $outcome")
        assertEquals(106_000L, assertNotNull(store.installedVersion(packageName)).versionCode)
        assertTrue(store.artifactFile(before).isFile, "the working artifact must survive a failed upgrade")

        // And the surviving version still loads and still answers. It is opened through the honest
        // host, not the impostor one: the impostor key only ever reached the impostor's own
        // inspector, and re-verifying with it would fail for the right reason but the wrong point.
        first.open(assertNotNull(store.installedVersion(packageName))).use { loaded ->
            assertEquals("Popular entry 1", loaded.providers.single().popularManga(1).mangas.single().title)
        }
    }

    @Test
    fun `a verified upgrade across abi levels replaces the version and drops the old artifact`() = runBlocking {
        val store = newStore()
        val first = newRepository(repository(ABI14_FIXTURE), store)
        first.updateAll()
        val before = assertNotNull(store.installedVersion(packageName))
        assertEquals(ExtensionAbi.V1_4, before.abi)
        first.open(before).use { loaded ->
            assertEquals(2, loaded.providers.size)
        }

        // The same package migrates from 1.4 to 1.6, which is the real shape of an upgrade here.
        val second = repository(ABI16_FIXTURE)
        val host = newRepository(second, store)
        host.discover()

        val outcome = host.install(host.snapshot!!.byPackage(packageName)!!)

        assertTrue(outcome is InstallOutcome.Upgraded, "was: $outcome")
        assertEquals(104_000L, (outcome as InstallOutcome.Upgraded).fromVersionCode)
        assertEquals(106_000L, outcome.installedVersionCode)

        val after = assertNotNull(store.installedVersion(packageName))
        assertEquals(106_000L, after.versionCode)
        assertEquals(ExtensionAbi.V1_6, after.abi)
        assertEquals(1, store.installed().size)
        assertFalse(store.artifactFile(before).exists(), "the previous artifact should be gone")

        // The new level loads and serves, so the upgrade really replaced the working extension.
        host.open(after).use { loaded ->
            assertEquals(ExtensionAbi.V1_6, loaded.abi)
            assertEquals(AbiRegistry.BRIDGE_1_6, loaded.providers.single().javaClass.name)
            assertEquals("Popular entry 1", loaded.providers.single().popularManga(1).mangas.single().title)
        }
    }

    @Test
    fun `an artifact the artifact url does not serve is refused, not installed`() = runBlocking {
        val repository = repository().apply { stopServing(packageName) }
        val store = newStore()
        val host = newRepository(repository, store)
        host.discover()

        val outcome = host.install(host.snapshot!!.byPackage(packageName)!!)

        assertTrue(outcome is InstallOutcome.Refused, "was: $outcome")
        assertContains(assertNotNull((outcome as InstallOutcome.Refused).reason), "404")
        assertTrue(store.installed().isEmpty())
        assertNull(store.installedVersion(packageName))
    }

    @Test
    fun `an artifact that arrives with no bytes is refused, not installed`() = runBlocking {
        val repository = repository().apply { serveEmptyArtifact() }
        val store = newStore()
        val host = newRepository(repository, store)
        host.discover()

        val outcome = host.install(host.snapshot!!.byPackage(packageName)!!)

        assertTrue(outcome is InstallOutcome.Refused, "was: $outcome")
        assertContains(assertNotNull((outcome as InstallOutcome.Refused).reason), "empty")
        assertTrue(store.installed().isEmpty(), "an empty download may never be registered")
        assertFalse(store.stagingFile(packageName, 106_000L).exists())
    }

    @Test
    fun `installing before discovering is refused`() = runBlocking {
        val store = newStore()
        val host = newRepository(repository(), store)

        // Nothing has been fetched, so there is no signing key to verify against. Installing anyway
        // would mean verifying against nothing.
        val failure = assertFailsWith<RepositoryIntegrityException> {
            host.install(
                AvailableExtension(
                    published = RepositoryExtension(
                        packageName = packageName,
                        name = "TachiyomiMix",
                        extensionLib = "1.6",
                        versionCode = 106_000L,
                        versionName = "1.6.0",
                        contentWarning = "CONTENT_WARNING_SAFE",
                        apkUrl = "https://shura.example/apk",
                        iconUrl = null,
                        jarUrl = null,
                        sources = emptyList(),
                    ),
                    abi = ExtensionAbi.V1_6,
                    apkUrl = "https://shura.example/apk",
                ),
            )
        }
        assertContains(failure.message.orEmpty(), "discover()")
        assertTrue(store.installed().isEmpty())
    }

    @Test
    fun `an artifact changed on disk is refused when it is about to be loaded`() = runBlocking {
        val store = newStore()
        val host = newRepository(repository(), store)
        host.updateAll()

        // The registry holds the digest of what was verified, so bytes swapped on disk are caught at
        // the moment they would be executed and not only at install time.
        val record = assertNotNull(store.installedVersion(packageName))
        store.artifactFile(record).writeBytes(ByteArray(64))

        val failure = assertFailsWith<ExtensionVerificationException> { host.open(record) }
        assertContains(failure.message.orEmpty(), "repository publishes")
        assertTrue(host.catalogue().isEmpty())
    }

    @Test
    fun `an artifact that is gone is a storage failure, not an empty catalogue`() = runBlocking {
        val store = newStore()
        val host = newRepository(repository(), store)
        host.updateAll()
        val record = assertNotNull(store.installedVersion(packageName))
        store.artifactFile(record).delete()

        assertFailsWith<ExtensionStorageException> { host.open(record) }
    }

    private companion object {
        const val ABI16_FIXTURE = "shura-ext-tachiyomix-abi16"
        const val ABI14_FIXTURE = "shura-ext-tachiyomix-abi14"
    }

    @Test
    fun `an extension the index publishes at a level this host dropped is not installed`() = runBlocking {
        // A 1.6-only host must not install a 1.4 extension: the entry is skipped, not coerced.
        val store = newStore()
        val host = ExtensionRepository(
            client = RepositoryClient(
                UrlHttpTransport(),
                repository(ABI14_FIXTURE).indexUrl,
                setOf(ExtensionAbi.V1_6),
                ArtifactUrlPolicy.ALLOW_LOOPBACK,
            ),
            store = store,
            inspector = FileSignedInspector(
                TestArtifacts.extension(ABI14_FIXTURE),
                "",
            ),
            loader = ExtensionLoader(AbiRegistry.fromDirectory(TestArtifacts.extensionArtifacts)),
        )

        val snapshot = host.discover()
        assertTrue(snapshot.extensions.isEmpty())
        assertEquals(1, snapshot.unusable.size)
        assertContains(snapshot.unusable.single().reason, "1.4")
        assertTrue(host.updateAll().isEmpty())
        assertTrue(store.installed().isEmpty())
    }
}

/**
 * Stands in for the platform.
 *
 * On a device both answers come from `PackageManager`. Here the manifest comes from the artifact's own
 * jar descriptor, which is the strongest answer a JVM can give, and the certificate is derived from
 * the artifact's bytes, which is how a real signature behaves and is what makes tampering detectable.
 */
private class FileSignedInspector(
    private val artifact: File,
    private val certificateSalt: String,
) : ApkInspector {

    override fun certificateOf(apk: File): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(apk.readBytes() + certificateSalt.toByteArray())

    override fun manifestOf(apk: File): ExtensionManifest = JarDescriptorInspector.manifestOf(apk)
}

/** A transport that cannot reach anything, for the reachability half of the loop. */
private class UnreachableTransport : HttpTransport {
    override fun get(url: String): HttpResponse = throw RepositoryUnreachableException(url, "no such host")
}
