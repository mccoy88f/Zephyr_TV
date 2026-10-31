import java.util.Properties

plugins {
    id("com.android.application")
}

// No dependencies on purpose: framework Views only, to keep RAM and APK size minimal. Two exceptions, the player
// for the Stremio add-ons' channels and the JavaScript engine for Nuvio's plugins (see dependencies).
android {
    namespace = "link.mccoy88f.zephyr"
    compileSdk = 36

    defaultConfig {
        applicationId = "link.mccoy88f.zephyr"
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        targetSdk = 36
        versionCode = 72
        versionName = "3.6.0"
        // What's new: only this version's section of CHANGELOG.md ships, one entry per line, without ** marks.
        val notes = rootProject.file("CHANGELOG.md").readText().replace("\r", "")
            .substringAfter("\n## $versionName\n", "").substringBefore("\n## ")
            .lines().filter { it.startsWith("- ") }.map { it.removePrefix("- ").replace("**", "") }
        require(notes.isNotEmpty()) { "CHANGELOG.md has no entries under \"## $versionName\"" }
        // TVs and TV boxes are ARM: QuickJS's library (see dependencies) for those alone, not the emulators' x86 ones.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        resValue("string", "whats_new", notes.joinToString("\\n") { it.replace("'", "\\'").replace("\"", "\\\"") })
    }

    buildFeatures {
        resValues = true
    }

    // Release key lives outside the repo; keystore.properties (git-ignored) points at it. Without that file
    // (a fresh clone) release builds fall back to the debug key, so anyone can still build and sideload.
    val keys = rootProject.file("keystore.properties").takeIf { it.exists() }
        ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }
    signingConfigs {
        if (keys != null) create("release") {
            storeFile = file(keys.getProperty("storeFile"))
            storePassword = keys.getProperty("storePassword")
            keyAlias = keys.getProperty("keyAlias")
            keyPassword = keys.getProperty("keyPassword")
        }
    }

    // Android 7: java.time and java.util.Base64 (Android 8's) from the desugaring library.
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }

    lint {
        // Predictive back is a touch gesture; on a TV Back is the remote's key, which still reaches onBackPressed.
        disable += "GestureBackNavigation"
    }

    buildTypes {
        // Debug builds next to the installed release (another id), so a TV keeps its Zephyr TV and its settings.
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
}

// The user guide (docs/guida.md, docs/guide.md) shown in Settings → System → Guide: copied into the app's assets
// at build time, so the guide is written once, in docs.
val guideAssets = file("build/generated/guide-assets")
val copyGuide = tasks.register<Copy>("copyGuide") {
    from(rootProject.file("docs")) { include("guide.md", "guida.md") }
    into(File(guideAssets, "guide"))
}
android.sourceSets.getByName("main").assets.srcDir(guideAssets)
tasks.named("preBuild") { dependsOn(copyGuide) }

val QUICKJS = "1.0.5" // the last built with Kotlin 2.3: the compiler here reads no newer

// A desktop JVM can't load QuickJS's Android library: the unit tests take its JVM build.
configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("io.github.dokar3:quickjs-kt-android")).using(module("io.github.dokar3:quickjs-kt-jvm:$QUICKJS"))
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    // The one exception to "no dependencies": Media3's ExoPlayer, for the Stremio add-ons' channels (see PlayerActivity).
    val media3 = "1.11.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-exoplayer-rtsp:$media3") // rtsp:// streams (IP cameras, some IPTV)
    implementation("androidx.media3:media3-datasource-rtmp:$media3") // rtmp:// streams
    // Sound the device has no decoder for decoded in software: Dolby (AC3, E-AC3), DTS and the like on phones and
    // tablets, TrueHD or DTS on many TVs (which decode the rest themselves or pass it on to a receiver). Without it
    // their sound is left out. Jellyfin's build of Media3's FFmpeg decoder (GPL-3.0).
    implementation("androidx.media3:media3-decoder:$media3")
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.9.0+1")
    // WARP for Zephyr TV alone: WireGuard's own Android library (Apache-2.0), its tunnel as this app's VPN (see Warp).
    implementation("com.wireguard.android:tunnel:1.0.20260102")
    // The second exception: QuickJS, the JavaScript engine Nuvio's plugins run in (see NuvioPlugins).
    implementation("io.github.dokar3:quickjs-kt:$QUICKJS")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2") // what it runs on; used directly too
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2") // tests on a TV: the scripts' processes
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("org.json:json:20250517") // Android's own org.json is only stubs in unit tests
}
