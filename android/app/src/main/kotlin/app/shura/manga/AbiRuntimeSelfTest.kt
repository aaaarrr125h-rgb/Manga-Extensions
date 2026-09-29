package app.shura.manga

import android.content.Context
import app.shura.source.api.ExtensionAbi
import app.shura.source.host.AbiRegistry
import java.io.File
import java.util.zip.ZipFile

/** One line of the self test report, and whether it counts as a pass. */
private data class Report(val label: String, val detail: String, val passed: Boolean)

/**
 * The self test: does the ABI host actually work on a device?
 *
 * Everything here runs for real. The ABI jars are unpacked off `assets` by the same
 * [AbiAssetsInstaller] production uses, loaded through a real `DexClassLoader`, and each fixture's
 * entry class is instantiated off it. Nothing asserts that a class exists: every result is the
 * outcome of a call that either returned or threw.
 *
 * It lives in `:app` and touches no other module, so what it reports is what the shipped app has,
 * not a test-only arrangement.
 */
class AbiRuntimeSelfTest(private val context: Context) {

    private val report = mutableListOf<Report>()

    fun run(): String {
        report.clear()
        val registry = runInstaller() ?: return render()
        stageFixtures()
        val loaders = ExtensionAbi.entries.mapNotNull { abi -> loadAbiLevel(abi, registry) }.toMap()
        loaders.forEach { (abi, loader) -> reportFixtureLoad(abi, loader) }
        reportIsolation(loaders)
        reportNoHostLeak()
        return render()
    }

    /** Step 1: the real installer, against the real APK assets. */
    private fun runInstaller(): AbiRegistry? = try {
        val registry = AbiAssetsInstaller(context).install()
        val directory = File(context.filesDir, "shura-abi")
        val installed = directory.listFiles()?.filter(File::isFile)?.map(File::getName)?.sorted().orEmpty()
        report += Report(
            "AbiAssetsInstaller.install()",
            "filesDir/shura-abi = ${if (installed.isEmpty()) "EMPTY" else installed.joinToString()}",
            registry.supported == ExtensionAbi.entries.toSet(),
        )
        // Also per level, because "the registry has two entries" and "both files are really on
        // disk" are different claims, and a partial copy would satisfy only the first.
        ExtensionAbi.entries.forEach { abi ->
            val jar = directory.resolve("shura-abi-${abi.version}.jar")
            report += Report(
                "ABI ${abi.version} unpacked",
                if (jar.isFile) "${jar.length()} bytes, classes.dex=${hasClassesDex(jar)}"
                else "MISSING ${jar.name}",
                jar.isFile && hasClassesDex(jar),
            )
        }
        registry
    } catch (failure: Throwable) {
        report += Report("AbiAssetsInstaller.install()", "THREW ${failure.describe()}", false)
        null
    }

    /**
     * Step 2: the fixture jars have to reach the device as files too.
     *
     * They are copied the same way the installer copies, so this is the path a downloaded
     * extension takes, not a shortcut that reads straight out of `assets`.
     */
    private fun stageFixtures() {
        FIXTURES.forEach { (name) ->
            val target = File(fixtureDirectory, name)
            if (target.isFile && target.length() > 0) return@forEach
            runCatching {
                context.assets.open("shura-test-fixtures/$name").use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }.onFailure {
                report += Report("stage $name", "THREW ${it.describe()}", false)
            }
        }
    }

    private val fixtureDirectory: File
        get() = File(context.filesDir, "shura-test-fixtures").also(File::mkdirs)

    /**
     * Step 3: a real `DexClassLoader` per ABI level.
     *
     * Each loader gets that level's ABI jar and the fixture built against it, and nothing else.
     * If the two levels shared a loader they would necessarily share classes, so one loader per
     * level is the arrangement the isolation check depends on.
     *
     * The parent is the app's own loader, which is what the host passes in production: it supplies
     * `app.shura.source.api`, RxJava and kotlinx-serialization, and must supply no `eu.kanade.*`.
     */
    private fun loadAbiLevel(abi: ExtensionAbi, registry: AbiRegistry): Pair<ExtensionAbi, AndroidAbiClassLoader>? {
        val apiJar = runCatching { registry.require(abi).apiJar }.getOrNull()
        if (apiJar == null || !apiJar.isFile) {
            report += Report("DexClassLoader ABI ${abi.version}", "no ABI jar on disk", false)
            return null
        }
        val fixture = File(fixtureDirectory, FIXTURES.getValue(abi))
        if (!fixture.isFile) {
            report += Report("DexClassLoader ABI ${abi.version}", "fixture not staged", false)
            return null
        }
        return runCatching {
            val optimized = File(context.codeCacheDir, "shura-dex/${abi.version}").also(File::mkdirs)
            // Fixture first, ABI second: the entry class and its `keiyoushi.source.*` come from
            // the fixture, the `eu.kanade.tachiyomi.*` they compile against from the ABI jar.
            abi to AndroidAbiClassLoader(
                dexJars = listOf(fixture, apiJar),
                optimizedDirectory = optimized,
                parent = AbiRuntimeSelfTest::class.java.classLoader,
            )
        }.getOrElse {
            report += Report("DexClassLoader ABI ${abi.version}", "THREW ${it.describe()}", false)
            null
        }
    }

    /**
     * Step 4: load the fixture's entry class and build its sources for real.
     *
     * This is the step a raw `.class` jar cannot pass: the classes are only reachable if the
     * loader read `classes.dex`, and the instances only appear if this level's ABI surface
     * resolved the fixture's `implements` clauses.
     */
    private fun reportFixtureLoad(abi: ExtensionAbi, loader: AndroidAbiClassLoader) {
        runCatching {
            val entry = loader.loadClass(ENTRY_CLASS).getDeclaredConstructor().newInstance()
            val factory = loader.loadClass(FACTORY_INTERFACE)
            require(factory.isInstance(entry)) { "$ENTRY_CLASS is not a $FACTORY_INTERFACE" }
            val created = factory.getMethod("createSources").invoke(entry) as List<*>
            val sources = created.filterNotNull()
            val catalogue = loader.loadClass(CATALOGUE_INTERFACE)
            check(sources.isNotEmpty()) { "SourceFactory returned nothing" }
            check(sources.all { catalogue.isInstance(it) }) {
                "a source does not implement this level's $CATALOGUE_INTERFACE"
            }
            val names = sources.map { it.javaClass.simpleName }
            report += Report(
                "DexClassLoader ABI ${abi.version}",
                "loaded $ENTRY_CLASS -> ${sources.size} source(s) $names, " +
                    "all bound to this level's CatalogueSource",
                true,
            )
        }.onFailure {
            report += Report("DexClassLoader ABI ${abi.version}", "THREW ${it.describe()}", false)
        }
    }

    /**
     * Step 5: the collision check.
     *
     * 27 class names exist in both ABI jars, `eu.kanade.tachiyomi.source.Source` among them, with
     * members that do not match. Were the host to resolve one of them, both levels would end up on
     * the same `Class` and a 1.4 extension would be handed 1.6 method signatures.
     */
    private fun reportIsolation(loaders: Map<ExtensionAbi, AndroidAbiClassLoader>) {
        val (firstAbi, secondAbi) = ExtensionAbi.OLDEST to ExtensionAbi.NEWEST
        val first = runCatching { loaders[firstAbi]?.loadClass(COLLISION_PROBE) }.getOrNull()
        val second = runCatching { loaders[secondAbi]?.loadClass(COLLISION_PROBE) }.getOrNull()
        when {
            first == null || second == null ->
                report += Report("isolation $firstAbi vs $secondAbi", "a level did not load", false)
            first == second -> report += Report(
                "isolation $firstAbi vs $secondAbi",
                "COLLISION: both resolved $COLLISION_PROBE to the same Class",
                false,
            )
            else -> report += Report(
                "isolation $firstAbi vs $secondAbi",
                "$COLLISION_PROBE is a distinct Class per level " +
                    "(${first.classLoader.hashCode().toString(16)} vs " +
                    "${second.classLoader.hashCode().toString(16)})",
                true,
            )
        }
    }

    /**
     * Step 6: the host classpath must not have picked any of it up.
     *
     * Every check above could still pass if `eu.kanade.*` had been merged into the app's own dex,
     * with the loaders merely re-finding it there. This is the check that would catch that.
     */
    private fun reportNoHostLeak() {
        val host = AbiRuntimeSelfTest::class.java.classLoader
        val leaked = runCatching { host.loadClass(COLLISION_PROBE) }.getOrNull()
        report += Report(
            "no eu.kanade.* on host",
            if (leaked == null) "host cannot resolve $COLLISION_PROBE"
            else "LEAK: host resolved it via ${leaked.classLoader}",
            leaked == null,
        )
    }

    private fun hasClassesDex(jar: File): Boolean =
        runCatching { ZipFile(jar).use { it.getEntry("classes.dex") != null } }.getOrDefault(false)

    private fun Throwable.describe(): String =
        "$javaClass.simpleName: ${message?.lines()?.firstOrNull()?.take(160).orEmpty()}"

    private fun render(): String = buildString {
        appendLine("Shura ABI self test")
        appendLine("${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, API ${android.os.Build.VERSION.SDK_INT}")
        appendLine("-".repeat(36))
        report.forEach { line ->
            appendLine("${if (line.passed) "PASS" else "FAIL"}  ${line.label}")
            appendLine("      ${line.detail}")
        }
        appendLine("-".repeat(36))
        appendLine("${report.count(Report::passed)}/${report.size} passed")
    }

    private companion object {
        const val ENTRY_CLASS = "keiyoushi.source.Generated"
        const val FACTORY_INTERFACE = "eu.kanade.tachiyomi.source.SourceFactory"
        const val CATALOGUE_INTERFACE = "eu.kanade.tachiyomi.source.CatalogueSource"

        /** Exists in both ABI jars with incompatible members, so it is the collision probe. */
        const val COLLISION_PROBE = "eu.kanade.tachiyomi.source.Source"

        /** The fixture staged for each level; entry class and package are the same in both. */
        val FIXTURES: Map<ExtensionAbi, String> = mapOf(
            ExtensionAbi.V1_4 to "tachiyomix-abi14.jar",
            ExtensionAbi.V1_6 to "tachiyomix-abi16.jar",
        )
    }
}
