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

    // rx.Observable, kotlinx-serialization and android.* are supplied by the host, never
    // packaged. An extension that implements the published 1.6 surface links against exactly
    // these signatures.
    compileOnly(libs.rxjava)
    compileOnly(libs.kotlinx.serialization.json)
    compileOnly(project(":shura-android-stubs"))

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.rxjava)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(project(":shura-android-stubs"))
}
