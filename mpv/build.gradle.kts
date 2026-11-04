import java.util.Properties

plugins {
    id("com.android.application")
}

// Zephyr MPV: the mpv player (FFmpeg's demuxers and decoders: Blu-ray .m2ts, AVI, DTS-HD, TrueHD, ASS subtitles…)
// for what Media3 can't play. An app of its own, installed from Zephyr TV's settings (see the app's Mpv), so Zephyr
// TV's APK stays small. Published as ZephyrMPV.apk next to ZephyrTV.apk in the GitHub releases.
android {
    namespace = "link.mccoy88f.zephyr.mpv"
    compileSdk = 36

    defaultConfig {
        applicationId = "link.mccoy88f.zephyr.mpv"
        minSdk = 26 // libmpv's
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        // TVs and TV boxes are ARM, as in Zephyr TV.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // Signed with Zephyr TV's key (keystore.properties, as in app/): each checks the other before anything passes.
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

    buildTypes {
        // No ".debug" suffix, unlike Zephyr TV: its debug build looks for this same package (signed with the debug key).
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    lint {
        // On purpose: no launcher entry, on a TV or elsewhere. Zephyr TV opens it, with a stream to play.
        disable += "MissingLeanbackLauncher"
        // Predictive back is a touch gesture; on a TV Back is the remote's key, as in Zephyr TV.
        disable += "GestureBackNavigation"
    }

    packaging {
        // libmpv's libraries as they are: extracted, as its loader expects.
        jniLibs { useLegacyPackaging = true }
    }
}

// The output's name, as the releases have it.
base { archivesName.set("ZephyrMPV") }

dependencies {
    // libmpv for Android (Findroid's build, MIT; mpv LGPL, FFmpeg): MPVLib, its Java side.
    implementation("dev.jdtech.mpv:libmpv:0.4.1")
}
