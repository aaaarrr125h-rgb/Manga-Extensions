pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "shura"

include(":shura-source-api")
include(":shura-source-host")
include(":shura-android-stubs")
include(":shura-abi:shura-abi-1.4")
include(":shura-abi:shura-abi-1.6")
include(":shura-extensions:shura-ext-tachiyomix-abi14")
include(":shura-extensions:shura-ext-tachiyomix-abi16")

// The Android application module can only be configured when an Android SDK is available.
// Keeping it out of the build otherwise is what lets `./gradlew build` run on a machine that
// has no SDK (CI, containers, PRoot), without pretending the app module was verified.
val sdkDir: String? = run {
    val fromLocal = file("local.properties")
        .takeIf { it.isFile }
        ?.let { props ->
            java.util.Properties()
                .apply { props.inputStream().use { load(it) } }
                .getProperty("sdk.dir")
        }
    fromLocal?.takeIf { File(it).isDirectory }
        ?: sequenceOf("ANDROID_HOME", "ANDROID_SDK_ROOT")
            .mapNotNull(System::getenv)
            .firstOrNull { File(it).isDirectory }
}

if (sdkDir != null) {
    include(":app")
} else {
    println(
        "[shura] No Android SDK found (ANDROID_HOME / ANDROID_SDK_ROOT / local.properties:sdk.dir). " +
            ":app is excluded from this build. Everything else still builds and is still tested.",
    )
}
