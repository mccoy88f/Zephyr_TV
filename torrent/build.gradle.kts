import java.util.Properties

plugins {
    id("com.android.application")
}

// Zephyr Torrent: the engine that plays and downloads the add-ons' torrent streams for Zephyr TV. An APK of its own,
// installed from Zephyr TV's settings only by those who want it: libtorrent's native code weighs several megabytes,
// and a crash here can't take the home screen with it. Signed with Zephyr TV's key, which is how each knows the other.
android {
    namespace = "link.mccoy88f.zephyr.torrent"
    compileSdk = 36

    defaultConfig {
        applicationId = "link.mccoy88f.zephyr.torrent"
        minSdk = 24
        targetSdk = 36
        versionCode = providers.gradleProperty("torrentVersionCode").get().toInt()
        versionName = providers.gradleProperty("torrentVersionName").get()
        // TVs and TV boxes are ARM, as for Zephyr TV.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

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

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }

    buildTypes {
        // Next to the released engine, as Zephyr TV's debug build sits next to Zephyr TV: each debug talks to the other.
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

    // The native libraries come in jars (libtorrent4j's): kept compressed, the APK is half as big to download.
    packaging {
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    // libtorrent (libtorrent-rasterbar, BSD) through libtorrent4j (MIT): its Java side and its library for each ARM.
    val libtorrent4j = "2.1.0-39"
    implementation("org.libtorrent4j:libtorrent4j:$libtorrent4j")
    implementation("org.libtorrent4j:libtorrent4j-android-arm64:$libtorrent4j")
    implementation("org.libtorrent4j:libtorrent4j-android-arm:$libtorrent4j")
    testImplementation("junit:junit:4.13.2")
}
