plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.akshit.hotwheels"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.akshit.hotwheels"
        minSdk = 29          // Android 10+
        targetSdk = 36       // Android 16
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        // Debug build only: it is signed with the standard debug key, so the
        // APK installs by sideloading without any signing setup.
        getByName("debug") { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// No third-party dependencies on purpose. Everything used here is in the
// Android framework itself, which keeps the build fast and unbreakable.
dependencies { }
