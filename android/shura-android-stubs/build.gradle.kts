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

// This module exists so the ABI modules can be compiled without the Android SDK. On a device
// these classes come from the platform instead, and the jar produced here is never packaged
// into an extension: the ABI modules consume it with `compileOnly`.
