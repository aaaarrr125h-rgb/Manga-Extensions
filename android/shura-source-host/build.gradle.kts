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

dependencies {
    api(project(":shura-source-api"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    // The host supplies rx.Observable, kotlinx-serialization and android.* to every extension
    // classloader, so they are parent classpath dependencies here, not per extension.
    testImplementation(libs.rxjava)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(project(":shura-android-stubs"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// The host test needs real ABI jars and real extension jars to load into isolated classloaders.
// They are copied next to the test classes so the test never has them on its own classpath:
// that is the whole point of the isolation.
val stageExtensionArtifacts by tasks.registering(Sync::class) {
    group = "verification"
    description = "Stages the ABI jars and the sample extensions where the host tests can load them."

    dependsOn(":shura-abi:shura-abi-1.4:jar")
    dependsOn(":shura-abi:shura-abi-1.6:jar")
    dependsOn(":shura-extensions:shura-ext-tachiyomix-abi14:jar")
    dependsOn(":shura-extensions:shura-ext-tachiyomix-abi16:jar")

    from(project(":shura-abi:shura-abi-1.4").tasks.named("jar")) { rename { "shura-abi-1.4.jar" } }
    from(project(":shura-abi:shura-abi-1.6").tasks.named("jar")) { rename { "shura-abi-1.6.jar" } }
    from(project(":shura-extensions:shura-ext-tachiyomix-abi14").tasks.named("jar")) {
        rename { "shura-ext-tachiyomix-abi14.jar" }
    }
    from(project(":shura-extensions:shura-ext-tachiyomix-abi16").tasks.named("jar")) {
        rename { "shura-ext-tachiyomix-abi16.jar" }
    }

    into(layout.buildDirectory.dir("extension-artifacts"))
}

tasks.test {
    dependsOn(stageExtensionArtifacts)
    systemProperty(
        "shura.extensionArtifacts",
        layout.buildDirectory.dir("extension-artifacts").get().asFile.absolutePath,
    )
    // Real repo/index.json from the repository root, so the parser is tested against the
    // bytes that are actually published rather than a fixture that can drift away from them.
    systemProperty("shura.repoIndex", rootProject.layout.projectDirectory.file("../repo/index.json").asFile.absolutePath)
}
