package app.shura.source.host

import app.shura.source.api.ExtensionAbi
import app.shura.source.api.ExtensionContentWarning
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RepositoryClientTest {

    private fun clientOver(body: String, supported: Set<ExtensionAbi> = setOf(ExtensionAbi.V1_4, ExtensionAbi.V1_6)) =
        RepositoryClient(StubTransport(body), "https://shura.example/repo/index.json", supported)

    private fun stub(body: String) = object : HttpTransport {
        override fun get(url: String): HttpResponse =
            HttpResponse(url, 200, "application/json", body.toByteArray())
    }

    private fun indexJson(
        signingKey: String = GOOD_KEY,
        extensions: String = """
        [
          {
            "name": "Fixture One",
            "packageName": "eu.kanade.tachiyomi.extension.all.fixtureone",
            "extensionLib": "1.6",
            "versionCode": "106001",
            "versionName": "1.6.1",
            "contentWarning": "CONTENT_WARNING_SAFE",
            "sources": [
              { "id": 1, "name": "One", "language": "fr", "homeUrl": "https://one.example" }
            ],
            "resources": {
              "apkUrl": "https://cdn.example/one.apk",
              "iconUrl": "https://cdn.example/one.png",
              "jarUrl": "https://cdn.example/one.jar"
            }
          }
        ]
        """.trimIndent(),
    ) = """{"name":"Shura","badgeLabel":"b","signingKey":"$signingKey",
            "extensionList":{"extensions":$extensions}}"""

    @Test
    fun `the published index is read and its signing key is trusted`() {
        val snapshot = RepositoryClient(realIndexTransport(), "https://shura.example/index.json").discover()

        assertEquals(
            "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2",
            snapshot.signingFingerprint,
        )
        assertTrue(snapshot.extensions.isNotEmpty(), "the published index should list installable extensions")
        assertTrue(snapshot.extensions.size <= snapshot.index.extensions.size)
        assertEquals(snapshot.index.extensions.size, snapshot.extensions.size + snapshot.unusable.size)
    }

    @Test
    fun `every published extension names an https apk url`() {
        val snapshot = RepositoryClient(realIndexTransport(), "https://shura.example/index.json").discover()
        snapshot.extensions.forEach { available ->
            assertTrue(
                available.apkUrl.startsWith("https://"),
                "${available.packageName} is served over ${available.apkUrl}",
            )
        }
    }

    @Test
    fun `the published index is entirely at levels this host implements`() {
        val snapshot = RepositoryClient(realIndexTransport(), "https://shura.example/index.json").discover()
        // Worth asserting because it is a property of the repository, not of this code: the index
        // publishes 1.4 and 1.6 only, so nothing is skipped for ABI reasons and nothing is dropped.
        assertEquals(
            setOf(ExtensionAbi.V1_4, ExtensionAbi.V1_6),
            snapshot.extensions.map { it.abi }.toSet(),
        )
        assertTrue(snapshot.unsupportedAbis.isEmpty(), "no published entry names an unsupported level")
        assertTrue(
            snapshot.unusable.isEmpty(),
            "every published entry should be installable, unusable were ${snapshot.unusable}",
        )
    }

    @Test
    fun `a well formed index yields one installable extension`() {
        val snapshot = clientOver(indexJson()).discover()
        assertEquals(1, snapshot.extensions.size)
        val available = snapshot.extensions.single()
        assertEquals("eu.kanade.tachiyomi.extension.all.fixtureone", available.packageName)
        assertEquals("https://cdn.example/one.apk", available.apkUrl)
        assertEquals(ExtensionAbi.V1_6, available.abi)
        assertEquals(106_001L, available.versionCode)
        assertEquals(1, available.sources.size)
        assertEquals("One", available.sources.single().name)
    }

    @Test
    fun `a signing key that is not a digest is refused outright`() {
        val failure = assertFailsWith<RepositoryIntegrityException> {
            clientOver(indexJson(signingKey = "too-short")).discover()
        }
        assertContains(failure.message.orEmpty(), "64 character lower case SHA-256 digest")
    }

    @Test
    fun `an empty signing key is refused`() {
        assertFailsWith<RepositoryIntegrityException> { clientOver(indexJson(signingKey = "")).discover() }
    }

    @Test
    fun `an index with no extensions is a response failure`() {
        val failure = assertFailsWith<RepositoryResponseException> { clientOver(indexJson(extensions = "[]")).discover() }
        assertContains(failure.message.orEmpty(), "no extensions")
    }

    @Test
    fun `an empty body is a response failure`() {
        val failure = assertFailsWith<RepositoryResponseException> { clientOver("").discover() }
        assertContains(failure.message.orEmpty(), "empty")
    }

    @Test
    fun `bytes that are not json are a response failure`() {
        val failure = assertFailsWith<RepositoryResponseException> { clientOver("<html>404</html>").discover() }
        assertContains(failure.message.orEmpty(), "not readable")
    }

    @Test
    fun `an unsupported abi is skipped and counted`() {
        val extensions = """
        [
          { "name": "Level 9", "packageName": "eu.kanade.tachiyomi.extension.all.levelnine",
            "extensionLib": "9.9", "versionCode": 990001, "versionName": "9.9.0",
            "contentWarning": "CONTENT_WARNING_SAFE", "sources": [],
            "resources": { "apkUrl": "https://cdn.example/nine.apk" } },
          { "name": "Level 1.4", "packageName": "eu.kanade.tachiyomi.extension.all.levelfour",
            "extensionLib": "1.4", "versionCode": 104001, "versionName": "1.4.1",
            "contentWarning": "CONTENT_WARNING_SAFE", "sources": [],
            "resources": { "apkUrl": "https://cdn.example/four.apk" } }
        ]
        """.trimIndent()
        val snapshot = clientOver(indexJson(extensions = extensions)).discover()

        assertEquals(1, snapshot.extensions.size)
        assertEquals(ExtensionAbi.V1_4, snapshot.extensions.single().abi)
        assertEquals(mapOf("9.9" to 1), snapshot.unsupportedAbis)
        assertContains(snapshot.unusable.single().reason, "9.9")
    }

    @Test
    fun `a host that supports one level filters to exactly that level`() {
        val extensions = """
        [
          { "name": "Level 1.4", "packageName": "eu.kanade.tachiyomi.extension.all.levelfour",
            "extensionLib": "1.4", "versionCode": 104001, "versionName": "1.4.1",
            "contentWarning": "CONTENT_WARNING_SAFE", "sources": [],
            "resources": { "apkUrl": "https://cdn.example/four.apk" } },
          { "name": "Level 1.6", "packageName": "eu.kanade.tachiyomi.extension.all.levelsix",
            "extensionLib": "1.6", "versionCode": 106001, "versionName": "1.6.1",
            "contentWarning": "CONTENT_WARNING_SAFE", "sources": [],
            "resources": { "apkUrl": "https://cdn.example/six.apk" } }
        ]
        """.trimIndent()
        val snapshot = clientOver(indexJson(extensions = extensions), setOf(ExtensionAbi.V1_4)).discover()
        assertEquals(listOf(ExtensionAbi.V1_4), snapshot.extensions.map { it.abi })
        assertEquals(mapOf("1.6" to 1), snapshot.unsupportedAbis)
        assertContains(snapshot.unusable.single { it.packageName.endsWith("levelsix") }.reason, "1.6")
    }

    @Test
    fun `an entry with only a jar url is refused rather than installed unverified`() {
        // The index publishes both urls; only the APK carries the certificate that signingKey names,
        // so a jar-only entry cannot be verified at all.
        val extensions = """
        [
          { "name": "Jar only", "packageName": "eu.kanade.tachiyomi.extension.all.jaronly",
            "extensionLib": "1.6", "versionCode": 106001, "versionName": "1.6.1",
            "contentWarning": "CONTENT_WARNING_SAFE", "sources": [],
            "resources": { "jarUrl": "https://cdn.example/jaronly.jar" } }
        ]
        """.trimIndent()
        val snapshot = clientOver(indexJson(extensions = extensions)).discover()
        assertTrue(snapshot.extensions.isEmpty())
        assertContains(snapshot.unusable.single().reason, "jarUrl")
        assertContains(snapshot.unusable.single().reason, "signingKey")
    }

    @Test
    fun `an entry with no artifact url at all is refused`() {
        val extensions = """
        [
          { "name": "Nothing", "packageName": "eu.kanade.tachiyomi.extension.all.nothing",
            "extensionLib": "1.6", "versionCode": 106001, "versionName": "1.6.1",
            "contentWarning": "CONTENT_WARNING_SAFE", "sources": [], "resources": {} }
        ]
        """.trimIndent()
        val snapshot = clientOver(indexJson(extensions = extensions)).discover()
        assertContains(snapshot.unusable.single().reason, "apkUrl")
    }

    @Test
    fun `a plaintext apk url is refused`() {
        val extensions = """
        [
          { "name": "Plaintext", "packageName": "eu.kanade.tachiyomi.extension.all.plaintext",
            "extensionLib": "1.6", "versionCode": 106001, "versionName": "1.6.1",
            "contentWarning": "CONTENT_WARNING_SAFE", "sources": [],
            "resources": { "apkUrl": "http://cdn.example/plaintext.apk" } }
        ]
        """.trimIndent()
        val snapshot = clientOver(indexJson(extensions = extensions)).discover()
        assertContains(snapshot.unusable.single().reason, "not an https URL")
    }

    @Test
    fun `a package listed twice is reported rather than guessed at`() {
        val entry = """
          { "name": "Twice", "packageName": "eu.kanade.tachiyomi.extension.all.twice",
            "extensionLib": "1.6", "versionCode": %d, "versionName": "1.6.1",
            "contentWarning": "CONTENT_WARNING_SAFE", "sources": [],
            "resources": { "apkUrl": "https://cdn.example/twice-%d.apk" } }
        """
        val snapshot = clientOver(indexJson(extensions = "[$entry,$entry]".format(106001, 1, 106002, 2))).discover()
        assertEquals(1, snapshot.extensions.size)
        assertContains(snapshot.unusable.single().reason, "more than once")
    }

    @Test
    fun `the source catalogue is available before anything is installed`() {
        val snapshot = clientOver(indexJson()).discover()
        val sources = snapshot.extensions.flatMap { it.sources }
        assertEquals(1, sources.size)
        assertEquals("One", sources.single().name)
        assertEquals("fr", sources.single().language)
    }

    @Test
    fun `an unreachable repository never becomes an empty snapshot`() {
        val client = RepositoryClient(
            object : HttpTransport {
                override fun get(url: String): HttpResponse =
                    throw RepositoryUnreachableException(url, "no such host")
            },
            "https://shura.example/index.json",
        )
        assertFailsWith<RepositoryUnreachableException> { client.discover() }
    }

    @Test
    fun `fetchIndex does not require a valid signing key`() {
        // Reading the index and trusting it are separate questions: the catalogue can be shown from
        // an untrusted index, and nothing can be installed from one.
        val index = clientOver(indexJson(signingKey = "nope")).fetchIndex()
        assertEquals(1, index.extensions.size)
        assertFailsWith<RepositoryIntegrityException> { clientOver(indexJson(signingKey = "nope")).discover() }
    }

    private fun realIndexTransport(): HttpTransport {
        val bytes = TestArtifacts.repoIndex.readBytes()
        return StubTransport(String(bytes))
    }

    private companion object {
        const val GOOD_KEY = "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2"
    }
}

class ExtensionStoreTest {

    private val root: File = Files.createTempDirectory("shura-store").toFile()
    private var now = 1_700_000_000_000L

    private fun store() = ExtensionStore(root) { now }

    private fun artifact(packageName: String, versionCode: Long, content: String = "apk-$versionCode"): File {
        val directory = File(root, "extensions/$packageName").also { it.mkdirs() }
        return File(directory, "$versionCode.apk").also { it.writeText(content) }
    }

    private fun register(
        store: ExtensionStore,
        packageName: String = "eu.kanade.tachiyomi.extension.all.one",
        versionCode: Long = 100,
        abi: ExtensionAbi = ExtensionAbi.V1_6,
        contentWarning: ExtensionContentWarning = ExtensionContentWarning.SAFE,
    ): InstallOutcome {
        val file = artifact(packageName, versionCode)
        return store.register(
            packageName = packageName,
            abi = abi,
            versionCode = versionCode,
            versionName = "1.0.$versionCode",
            name = "One",
            contentWarning = contentWarning,
            certificateSha256 = GOOD_KEY,
            repositoryUrl = "https://shura.example/repo/index.json",
            apkFile = file,
        )
    }

    @Test
    fun `an empty store reports nothing installed`() {
        assertTrue(store().installed().isEmpty())
        assertNull(store().installedVersion("eu.kanade.tachiyomi.extension.all.one"))
        assertFalse(store().isInstalled("eu.kanade.tachiyomi.extension.all.one", 100))
    }

    @Test
    fun `a first install is recorded and loadable by path`() {
        val store = store()
        val outcome = register(store)
        assertTrue(outcome is InstallOutcome.Installed)
        assertEquals(100L, outcome.installedVersionCode)
        assertNull((outcome as InstallOutcome.Installed).previousVersionCode)

        val record = store.installedVersion("eu.kanade.tachiyomi.extension.all.one")!!
        assertEquals(100L, record.versionCode)
        assertEquals(ExtensionAbi.V1_6, record.abi)
        assertEquals("1.6", record.extensionLib)
        assertEquals(GOOD_KEY, record.certificateSha256)
        assertTrue(store.artifactFile(record).isFile)
        assertTrue(store.isInstalled("eu.kanade.tachiyomi.extension.all.one", 100))
    }

    @Test
    fun `registering the same version again is a no-op`() {
        val store = store()
        register(store)
        val second = register(store)
        assertTrue(second is InstallOutcome.AlreadyInstalled)
        assertEquals(1, store.installed().size)
    }

    @Test
    fun `an upgrade replaces the version and removes the old artifact`() {
        val store = store()
        register(store, versionCode = 100)
        val old = artifact("eu.kanade.tachiyomi.extension.all.one", 100)
        val outcome = register(store, versionCode = 200)

        assertTrue(outcome is InstallOutcome.Upgraded)
        assertEquals(100L, (outcome as InstallOutcome.Upgraded).fromVersionCode)
        assertEquals(200L, outcome.installedVersionCode)
        assertFalse(old.exists(), "the previous artifact must go once the registry points elsewhere")
        assertEquals(200L, store.installedVersion("eu.kanade.tachiyomi.extension.all.one")!!.versionCode)
    }

    @Test
    fun `a tampered artifact is not considered installed`() {
        val store = store()
        register(store)
        // The registry remembers the digest, so bytes changed on disk are detected and reinstalled
        // rather than loaded.
        store.artifactFile(store.installed().single()).writeText("tampered")
        assertFalse(store.isInstalled("eu.kanade.tachiyomi.extension.all.one", 100))
    }

    @Test
    fun `a missing artifact is not considered installed`() {
        val store = store()
        register(store)
        store.artifactFile(store.installed().single()).delete()
        assertFalse(store.isInstalled("eu.kanade.tachiyomi.extension.all.one", 100))
    }

    @Test
    fun `the registry survives a new store over the same directory`() {
        register(store())
        val reopened = store()
        assertEquals(1, reopened.installed().size)
        assertEquals(GOOD_KEY, reopened.installed().single().certificateSha256)
        assertEquals(1_700_000_000_000L, reopened.installed().single().installedAtMillis)
    }

    @Test
    fun `an nsfw extension stays nsfw across a restart`() {
        // The registry must store the CONTENT_WARNING_* spelling. Storing the enum's own name would
        // round trip back to SAFE and silently un-gate a source.
        val packageName = "eu.kanade.tachiyomi.extension.all.nsfwone"
        register(store(), packageName = packageName, contentWarning = ExtensionContentWarning.NSFW)
        val reopened = store().installedVersion(packageName)!!
        assertEquals(ExtensionContentWarning.NSFW, reopened.contentWarning)
        assertFalse(reopened.isTachiyomiSafe)
    }

    @Test
    fun `a mixed extension stays mixed across a restart`() {
        val packageName = "eu.kanade.tachiyomi.extension.all.mixedone"
        register(store(), packageName = packageName, contentWarning = ExtensionContentWarning.MIXED)
        assertEquals(ExtensionContentWarning.MIXED, store().installedVersion(packageName)!!.contentWarning)
    }

    @Test
    fun `two packages coexist`() {
        val store = store()
        register(store, packageName = "eu.kanade.tachiyomi.extension.all.one", versionCode = 100)
        register(store, packageName = "eu.kanade.tachiyomi.extension.all.two", versionCode = 100, abi = ExtensionAbi.V1_4)
        assertEquals(2, store.installed().size)
        assertEquals(1, store.installedForAbi(ExtensionAbi.V1_4).size)
        assertEquals(1, store.installedForAbi(ExtensionAbi.V1_6).size)
    }

    @Test
    fun `registering an artifact that is not where it belongs is refused`() {
        val stray = File(root, "stray.apk").also { it.writeText("bytes") }
        val failure = assertFailsWith<ExtensionStorageException> {
            ExtensionStore(root).register(
                packageName = "eu.kanade.tachiyomi.extension.all.one",
                abi = ExtensionAbi.V1_6,
                versionCode = 100,
                versionName = "1.0.0",
                name = "One",
                contentWarning = ExtensionContentWarning.SAFE,
                certificateSha256 = GOOD_KEY,
                repositoryUrl = "https://shura.example/repo/index.json",
                apkFile = stray,
            )
        }
        assertContains(failure.message.orEmpty(), "not at its final location")
    }

    @Test
    fun `the registry is written atomically, leaving no partial file behind`() {
        val store = store()
        register(store)
        assertFalse(File(root, "installed.json.partial").exists())
        assertTrue(File(root, "installed.json").isFile)
    }

    @Test
    fun `a staging leftover is swept`() {
        val store = store()
        register(store)
        val leftover = store.stagingFile("eu.kanade.tachiyomi.extension.all.one", 999).also { it.writeText("half") }
        assertTrue(leftover.exists())
        store.sweepStaging()
        assertFalse(leftover.exists(), "an interrupted download must not be left where it could be loaded")
    }

    @Test
    fun `uninstall removes the record and the files`() {
        val store = store()
        register(store)
        assertTrue(store.uninstall("eu.kanade.tachiyomi.extension.all.one"))
        assertTrue(store.installed().isEmpty())
        assertFalse(File(root, "extensions/eu.kanade.tachiyomi.extension.all.one").exists())
        assertFalse(store.uninstall("eu.kanade.tachiyomi.extension.all.one"))
    }

    @Test
    fun `a corrupt registry is reported rather than silently read as empty`() {
        // Reading it as empty would look like "no extensions installed" and quietly hide an
        // extension the user had, so it is a storage error instead.
        register(store())
        File(root, "installed.json").writeText("{ not json")
        val failure = assertFailsWith<ExtensionStorageException> { store().installed() }
        assertContains(failure.message.orEmpty(), "not a JSON object")
    }

    @Test
    fun `a record missing its package or its file is dropped, and the rest survive`() {
        val store = store()
        register(store, packageName = "eu.kanade.tachiyomi.extension.all.one")
        register(store, packageName = "eu.kanade.tachiyomi.extension.all.two")
        val registry = File(root, "installed.json")
        val junk = listOf(
            // No package name, so the entry cannot be named.
            """{"extensionLib":"1.6","versionCode":100,"fileName":"100.apk"}""",
            // No file name, so the entry cannot be pointed at.
            """{"packageName":"eu.kanade.tachiyomi.extension.all.three","extensionLib":"1.6","versionCode":100}""",
            // A level this host does not implement.
            """{"packageName":"eu.kanade.tachiyomi.extension.all.four","extensionLib":"9.9","versionCode":100,"fileName":"100.apk"}""",
            // No version code.
            """{"packageName":"eu.kanade.tachiyomi.extension.all.five","extensionLib":"1.6","fileName":"100.apk"}""",
        )
        registry.writeText(
            registry.readText().replace(""""extensions":[""", """"extensions":[${junk.joinToString(",")},"""),
        )
        // An entry that cannot be named, pointed at, versioned, or placed at a supported level is
        // skipped rather than half read, and skipping one must not cost the entries around it.
        assertEquals(2, store.installed().size)
        assertTrue(store.installed().all { it.fileName.isNotBlank() && it.packageName.isNotBlank() })
    }

    private companion object {
        const val GOOD_KEY = "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2"
    }
}