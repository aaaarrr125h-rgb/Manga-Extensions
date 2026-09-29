plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    // :app is the only Android module. The Kotlin Android plugin has to be declared here too:
    // kotlin-jvm already puts the Kotlin Gradle Plugin on the build classpath, so a module that
    // requests org.jetbrains.kotlin.android without a version fails to resolve it.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.android.application) apply false
}

allprojects {
    group = "app.shura"
    version = "0.1.0"
}

subprojects {
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // The build has to stay green on machines that cannot resolve the full dependency
        // graph, and a failing test must never be reported as "no failures, but skipped".
        testLogging {
            events("failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            showStandardStreams = false
        }
    }
}
