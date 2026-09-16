plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.opendisplayandroid"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.opendisplayandroid"
        minSdk = 26   // MediaCodec async APIs used here are fine from 21+, 26 is a safe modern floor
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Intentionally no extra libraries: org.json is built into Android,
    // and networking/decoding use only the platform SDK (java.net, MediaCodec, NsdManager).
}
