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
        versionCode = 9
        versionName = "0.9-alpha"
    }
    buildTypes { release { isMinifyEnabled = false } }
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
