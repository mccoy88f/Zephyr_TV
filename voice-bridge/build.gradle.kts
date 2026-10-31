plugins { id("com.android.application") }

android {
    namespace = "link.mccoy88f.zephyr.bridge"
    compileSdk = 36
    defaultConfig {
        // Alexa's YouTube integration addresses this package, not an arbitrary app.
        applicationId = "com.amazon.firetv.youtube"
        minSdk = 24
        targetSdk = 36
        versionCode = 45
        versionName = "0.1.0-prototype"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies { testImplementation("junit:junit:4.13.2") }
