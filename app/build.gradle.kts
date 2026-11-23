plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "jp.example.floatsaver"
    compileSdk = 35

    defaultConfig {
        applicationId = "jp.example.floatsaver"
        minSdk = 29
        targetSdk = 35
        versionCode = 14
        versionName = "0.14-alpha"
        // Lightweight APK for arm64 Android phones, including Xiaomi 13T.
        ndk {
            abiFilters.add("arm64-v8a")
        }
    }
    // CI reads the stable signing key from an encrypted repository file.
    // Local builds without these env vars can still use the standard debug key.
    val signingFile = System.getenv("FLOATSAVER_SIGNING_KEYSTORE").orEmpty()
    val signingPassword = System.getenv("FLOATSAVER_SIGNING_SECRET").orEmpty()
    signingConfigs {
        if (signingFile.isNotBlank() && signingPassword.isNotBlank()) {
            create("stableDebug") {
                storeFile = file(signingFile)
                storePassword = signingPassword
                keyPassword = signingPassword
                keyAlias = "floatsaver"
                storeType = "PKCS12"
            }
        }
    }
    buildTypes {
        getByName("debug") {
            if (signingFile.isNotBlank() && signingPassword.isNotBlank()) {
                signingConfig = signingConfigs.getByName("stableDebug")
            }
        }
        getByName("release") { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    val dlVersion = "0.18.1"
    implementation("io.github.junkfood02.youtubedl-android:library:$dlVersion")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$dlVersion")
    testImplementation("junit:junit:4.13.2")
}
