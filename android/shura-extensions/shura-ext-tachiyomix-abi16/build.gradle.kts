import java.util.Properties
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

val extensionLibrary = "1.6"
val extensionPatch = 0
val extensionPackage = "eu.kanade.tachiyomi.extension.all.tachiyomix"
val entryClass = "keiyoushi.source.Generated"

dependencies {
    // Exactly how a published extension declares the API it compiles against: the stubs are
    // compile only, and the host supplies them at runtime. Nothing from the ABI jar may end up
    // in this jar, or the classloader isolation would be defeated by the extension itself.
    compileOnly(project(":shura-abi:shura-abi-1.6"))
    compileOnly(project(":shura-android-stubs"))
    compileOnly(libs.kotlinx.serialization.json)
}

val versionCode: Long = 106000L + extensionPatch

val writeDescriptor by tasks.registering {
    val outputFile = layout.buildDirectory
        .file("generated/descriptor/META-INF/tachiyomi/extension.properties")
    outputs.file(outputFile)

    doLast {
        val properties = Properties().apply {
            setProperty("packageName", extensionPackage)
            setProperty("versionCode", versionCode.toString())
            setProperty("versionName", "$extensionLibrary.$extensionPatch")
            setProperty("tachiyomi.extension.class", entryClass)
            setProperty("tachiyomix.name", "TachiyomiMix (ABI 1.6 fixture)")
            setProperty("tachiyomix.contentWarning", "0")
            setProperty("tachiyomix.extensionLib", extensionLibrary)
        }
        val file = outputFile.get().asFile
        file.parentFile.mkdirs()
        file.outputStream().use { properties.store(it, "Written by the build; do not edit") }
    }
}

// The descriptor path inside the jar is the contract: the host looks the file up at exactly
// META-INF/tachiyomi/extension.properties. Pointing `jar.from` at the task copies the single
// file and flattens the path to the jar root, so the directory is added instead.
val descriptorDir = layout.buildDirectory.dir("generated/descriptor")

tasks.jar {
    dependsOn(writeDescriptor)
    from(descriptorDir)
    archiveBaseName.set("shura-ext-tachiyomix-abi16")
}

tasks.register("verifyNotPackaged") {
    group = "verification"
    description = "Fails if the extension jar ships classes it must compile against instead."
    val jarFile = tasks.jar.flatMap { it.archiveFile }
    inputs.file(jarFile)
    doLast {
        val forbidden = listOf(
            "eu/kanade/tachiyomi/source/Source.class",
            "eu/kanade/tachiyomi/source/CatalogueSource.class",
            "eu/kanade/tachiyomi/source/model/SMangaUpdate.class",
            "app/shura/abi/v16/ShuraAbi16Bridge.class",
            "app/shura/source/api/SourceProvider.class",
            "android/net/Uri.class",
        )
        val present = ZipFile(jarFile.get().asFile).use { zip ->
            zip.entries().asSequence().map { it.name }.filter { it in forbidden }.toList()
        }
        check(present.isEmpty()) {
            "Extension jar ${jarFile.get().asFile.name} packages classes that must come from the host: $present"
        }
        logger.lifecycle("verified: ${jarFile.get().asFile.name} packages none of the host-owned classes")
    }
}

tasks.named("check") { dependsOn("verifyNotPackaged") }
