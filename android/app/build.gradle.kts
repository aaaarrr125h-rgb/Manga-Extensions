import org.gradle.jvm.tasks.Jar
import java.io.File
import java.util.Properties
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val shuraCompileSdk = 34
val shuraMinSdk = 26

/** What d8 emits, what the task copies back, and what the staging task throws away. */
val DEX_ENTRY_NAME = "classes.dex"
val CLASS_SUFFIX = ".class"
val RESOURCE_SUFFIX = ".with-resources"

// Build identity. The device screen must be able to name the exact commit it is running, so the
// launcher activity can never be confused with a stale install. Both values are supplied by CI as
// Gradle properties; a local build without them says "unknown" rather than lying about a commit.
val shuraGitSha: String = (project.findProperty("shuraGitSha") as String?)
    ?: System.getenv("SHURA_GIT_SHA")
    ?: "unknown"

val shuraBuildTime: String = (project.findProperty("shuraBuildTime") as String?)
    ?: System.getenv("SHURA_BUILD_TIME")
    ?: "unknown"

// A fixed debug key, so a newer APK can actually replace an older one on a device.
//
// Android signs a debug build with a throwaway key generated on the machine doing the build. On CI
// that means a *new* key per run, so every run's APK carries a different certificate. Installing
// the new APK over the old one then fails with INSTALL_FAILED_UPDATE_INCOMPATIBLE, the previous
// version stays installed, and the build that was just fixed never reaches the screen. Caching one
// key across runs is what makes updates install.
//
// No private key is committed: CI generates this keystore once into the Actions cache and restores
// it afterwards. Locally it is simply absent, and the default debug key is used. A real key can be
// supplied with -PshuraKeystore / -PshuraKeystorePassword or the matching environment variables.
val shuraKeystore: File = (project.findProperty("shuraKeystore") as String?)
    ?.let(::File)
    ?: File(System.getProperty("user.home"), ".android/shura-ci.keystore")

val shuraKeystorePassword: String = (project.findProperty("shuraKeystorePassword") as String?)
    ?: System.getenv("SHURA_KEYSTORE_PASSWORD")
    ?: "shura-ci"

val shuraUseCiKey = shuraKeystore.isFile

android {
    namespace = "app.shura.manga"
    compileSdk = shuraCompileSdk

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (shuraUseCiKey) {
            create("ci") {
                storeFile = shuraKeystore
                storePassword = shuraKeystorePassword
                keyAlias = "shura-ci"
                keyPassword = shuraKeystorePassword
            }
        }
    }

    defaultConfig {
        applicationId = "app.shura.manga"
        minSdk = shuraMinSdk
        targetSdk = shuraCompileSdk
        versionCode = 2
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "GIT_SHA", "\"$shuraGitSha\"")
        buildConfigField("String", "BUILD_TIME", "\"$shuraBuildTime\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        debug {
            if (shuraUseCiKey) {
                signingConfig = signingConfigs.getByName("ci")
            }
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // The release is signed with the same stable key as the debug build, cached by CI and
            // never committed. No new keystore is created for release: one key per app is what
            // makes a release installable over a debug build and over the previous release. With
            // no key present (a local build) this stays unsigned.
            if (shuraUseCiKey) {
                signingConfig = signingConfigs.getByName("ci")
            }
        }
    }

    sourceSets {
        getByName("main") {
            // The ABI jars travel inside the APK as opaque assets and are unpacked at first run.
            // They reach the device as files, never as dex inputs: neither ABI module is declared
            // as a dependency of this one, so nothing merges eu.kanade.tachiyomi.* into the host
            // classpath. No resource exclusion is needed, and AndroidSourceSet has no such API.
            assets.srcDirs(layout.buildDirectory.dir("generated/shura-assets"))
        }
    }
}

dependencies {
    implementation(project(":shura-source-host"))

    // Supplied to every extension classloader, so they belong to the host's own classpath.
    implementation(libs.rxjava)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.androidx.core.ktx)
    // ComponentActivity for the launcher Activity.
    implementation(libs.androidx.activity)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
}

/**
 * The SDK, resolved exactly the way `settings.gradle.kts` resolves it to decide whether this
 * module is in the build at all. d8 and android.jar both live inside it.
 */
val shuraSdkDir: File = sequenceOf("ANDROID_HOME", "ANDROID_SDK_ROOT")
    .mapNotNull(System::getenv)
    .map(::File)
    .firstOrNull { it.isDirectory }
    ?: rootProject.file("local.properties")
        .takeIf { it.isFile }
        ?.let { propertiesFile ->
            Properties().apply { propertiesFile.inputStream().use { load(it) } }
                .getProperty("sdk.dir")
        }
        ?.let { File(it) }
        ?.takeIf { it.isDirectory }
    ?: error("No Android SDK found; :app cannot dex its ABI assets.")

/** The class library every jar is dexed against, so android.* references resolve at build time. */
val shuraAndroidJar: File = File(shuraSdkDir, "platforms/android-$shuraCompileSdk/android.jar")
    .also { check(it.isFile) { "Missing platform android-$shuraCompileSdk: $it" } }

/**
 * d8, taken from the newest build-tools that ships one.
 *
 * The version is discovered rather than pinned so the same build works against whichever
 * build-tools the machine has; build-tools is a tool provider here, not a compatibility target.
 */
val shuraD8: File = File(shuraSdkDir, "build-tools")
    .listFiles { directory: File -> directory.resolve("d8").isFile }
    .orEmpty()
    .filter { it.name.substringAfterLast('.').toIntOrNull() != null }
    .maxByOrNull { it.name.substringAfterLast('.').toInt() }
    ?.resolve("d8")
    ?.takeIf { it.isFile }
    ?: error("No d8 found under ${File(shuraSdkDir, "build-tools")}")

/**
 * The jars that get dexed, reached through `evaluationDependsOn`.
 *
 * Gradle configures `:app` before `:shura-abi:*` and `:shura-extensions:*` (alphabetical by
 * path), so their `jar` tasks do not exist yet when this file is first evaluated. Depending on
 * those projects explicitly is what makes the lookup below legal, and it says so in one place
 * rather than as a side effect of task ordering.
 */
val shuraAbi14Project = project(":shura-abi:shura-abi-1.4")
val shuraAbi16Project = project(":shura-abi:shura-abi-1.6")
val shuraFixture14Project = project(":shura-extensions:shura-ext-tachiyomix-abi14")
val shuraFixture16Project = project(":shura-extensions:shura-ext-tachiyomix-abi16")
listOf(shuraAbi14Project, shuraAbi16Project, shuraFixture14Project, shuraFixture16Project)
    .forEach { evaluationDependsOn(it.path) }

val shuraAbi14Jar: TaskProvider<Jar> = shuraAbi14Project.tasks.named<Jar>("jar")
val shuraAbi16Jar: TaskProvider<Jar> = shuraAbi16Project.tasks.named<Jar>("jar")
val shuraFixture14Jar: TaskProvider<Jar> = shuraFixture14Project.tasks.named<Jar>("jar")
val shuraFixture16Jar: TaskProvider<Jar> = shuraFixture16Project.tasks.named<Jar>("jar")

/**
 * Registers a task that dexes one jar into the APK assets.
 *
 * The `jar` tasks produce `.class` bytecode, which is what a JVM reads and what Android cannot:
 * `DexClassLoader` only reads `classes.dex`. Shipping the raw jar would install cleanly and then
 * fail every load on the device, so the jars are dexed here, at build time, and d8 is never run
 * on the device. The output keeps the `.jar` name because that is the name
 * `AbiRegistry.fromDirectory` looks for once the installer has unpacked it.
 */
fun registerDexAsset(
    taskName: String,
    jarTask: TaskProvider<Jar>,
    assetPath: String,
): TaskProvider<Exec> = tasks.register<Exec>(taskName) {
    group = "build"
    description = "Dexes $assetPath; a DexClassLoader cannot read .class bytecode."

    val jarFile = jarTask.flatMap { it.archiveFile }
    val outputFile = layout.buildDirectory.file("generated/shura-dex/$assetPath")
    dependsOn(jarTask)

    inputs.file(jarFile)
    inputs.file(shuraAndroidJar)
    inputs.property("d8", shuraD8.absolutePath)
    outputs.file(outputFile)

    doFirst {
        val destination = outputFile.get().asFile
        destination.parentFile.mkdirs()
        // d8 refuses to overwrite, and a stale jar from a previous run would otherwise survive a
        // failed dexing and be staged as if it were current.
        destination.delete()
    }

    commandLine(
        shuraD8.absolutePath,
        "--min-api", "$shuraMinSdk",
        "--lib", shuraAndroidJar.absolutePath,
        "--output", outputFile.get().asFile.absolutePath,
        jarFile.get().asFile.absolutePath,
    )

    // d8 writes `classes.dex` and nothing else into its output jar, so every non-class entry of
    // the input is dropped -- including `META-INF/tachiyomi/extension.properties`, the descriptor
    // that says which entry class to instantiate and at which ABI level. A jar staged as an asset
    // without it cannot be loaded the way a downloaded one is, which is a property of *this*
    // staging pipeline rather than of the extension.
    //
    // The resources are put back here, in the one place a staged jar is built, rather than worked
    // around by naming the entry class in code. It applies to every jar this task dexes, so an
    // ABI jar or a fixture staged later keeps whatever its author shipped.
    doLast {
        val destination = outputFile.get().asFile
        val source = jarFile.get().asFile
        val resources = ZipFile(source).use { zip ->
            zip.entries().asSequence()
                .filterNot { it.isDirectory }
                .filterNot { it.name.endsWith(CLASS_SUFFIX) }
                .map { entry -> entry.name to zip.getInputStream(entry).readBytes() }
                .toList()
        }
        if (resources.isEmpty()) return@doLast

        val rebuilt = File(destination.parentFile, "${destination.name}$RESOURCE_SUFFIX")
        JarOutputStream(rebuilt.outputStream()).use { out ->
            ZipFile(destination).use { zip ->
                zip.entries().asSequence().filter { it.name == DEX_ENTRY_NAME }.forEach { entry ->
                    out.putNextEntry(ZipEntry(entry.name))
                    zip.getInputStream(entry).copyTo(out)
                    out.closeEntry()
                }
            }
            resources.forEach { (name, bytes) ->
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
        destination.delete()
        if (!rebuilt.renameTo(destination)) error("could not put ${destination.name}'s resources back")
        rebuilt.delete()
    }
    // d8 is a shell wrapper that ends in `exec java ...`, so it resolves `java` from PATH and
    // ignores JAVA_HOME. The Gradle JVM is already the JDK that compiled the input, so its `bin`
    // is put in front of PATH rather than trusting whatever `java` the machine happens to expose
    // first: a container with several JDKs installed can easily have a different one as default,
    // and d8 then fails for reasons that have nothing to do with this build.
    val gradleJavaBin = File(System.getProperty("java.home"), "bin")
    environment("PATH", listOf(gradleJavaBin.absolutePath, System.getenv("PATH")).joinToString(File.pathSeparator))
    environment("JAVA_HOME", System.getProperty("java.home"))
}

val shuraAbi14Dex = registerDexAsset(
    taskName = "dexShuraAbi14",
    jarTask = shuraAbi14Jar,
    assetPath = "shura-abi/shura-abi-1.4.jar",
)
val shuraAbi16Dex = registerDexAsset(
    taskName = "dexShuraAbi16",
    jarTask = shuraAbi16Jar,
    assetPath = "shura-abi/shura-abi-1.6.jar",
)
val shuraFixture14Dex = registerDexAsset(
    taskName = "dexShuraFixture14",
    jarTask = shuraFixture14Jar,
    assetPath = "shura-test-fixtures/tachiyomix-abi14.jar",
)
val shuraFixture16Dex = registerDexAsset(
    taskName = "dexShuraFixture16",
    jarTask = shuraFixture16Jar,
    assetPath = "shura-test-fixtures/tachiyomix-abi16.jar",
)

// The dexed jars are copied into the APK's assets by the `shuraAbiAssets` task below, rather than
// being consumed as project dependencies. They must reach the device as opaque files that the
// first-run unpacker writes to disk, exactly like a downloaded extension jar.
//
// The fixtures go alongside the ABI jars so the on-device self test has something real to load;
// they are test inputs, not part of the shipped library.
val shuraAbiAssets by tasks.registering(Sync::class) {
    group = "build"
    description = "Stages the dexed ABI jars and the dexed test fixtures as assets."

    from(shuraAbi14Dex) { into("shura-abi") }
    from(shuraAbi16Dex) { into("shura-abi") }
    from(shuraFixture14Dex) { into("shura-test-fixtures") }
    from(shuraFixture16Dex) { into("shura-test-fixtures") }
    into(layout.buildDirectory.dir("generated/shura-assets"))
}

tasks.named("preBuild") { dependsOn(shuraAbiAssets) }
