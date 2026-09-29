plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "app.shura.manga"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.shura.manga"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

// The ABI jars are copied into the APK's assets by the `shuraAbiAssets` task below, rather than
// being consumed as project dependencies. They must reach the device as opaque files that the
// first-run unpacker writes to disk, exactly like a downloaded extension jar.
val shuraAbiAssets by tasks.registering(Sync::class) {
    group = "build"
    description = "Stages the ABI jars as assets, and the repository index next to them."

    from(project(":shura-abi:shura-abi-1.4").tasks.named("jar")) { rename { "shura-abi-1.4.jar" } }
    from(project(":shura-abi:shura-abi-1.6").tasks.named("jar")) { rename { "shura-abi-1.6.jar" } }
    into(layout.buildDirectory.dir("generated/shura-assets/shura-abi"))
}

tasks.named("preBuild") { dependsOn(shuraAbiAssets) }
