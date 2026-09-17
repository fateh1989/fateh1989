plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val tiktokClientKey = (project.findProperty("TIKTOK_CLIENT_KEY") as String?)
    ?: System.getenv("TIKTOK_CLIENT_KEY")
    ?: ""
val tiktokRedirectUrl = (project.findProperty("TIKTOK_REDIRECT_URL") as String?)
    ?: System.getenv("TIKTOK_REDIRECT_URL")
    ?: "https://open-platform.tiktokapis.com/callback"

android {
    namespace = "com.ym.lite"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ym.lite.stable"
        minSdk = 26
        targetSdk = 35
        versionCode = 29
        versionName = "0.29.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "TIKTOK_CLIENT_KEY", "\"${tiktokClientKey.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("String", "TIKTOK_REDIRECT_URL", "\"${tiktokRedirectUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
    }

    signingConfigs {
        create("ymStableDebug") {
            storeFile = file("ym-debug.jks")
            storePassword = "ymdebug123"
            keyAlias = "ymdebug"
            keyPassword = "ymdebug123"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("ymStableDebug")
        }
    }

    buildFeatures {
        viewBinding = false
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("com.google.android.material:material:1.12.0")

    implementation("androidx.media3:media3-exoplayer:1.9.4")
    implementation("androidx.media3:media3-ui:1.9.4")

    implementation("com.tiktok.open.sdk:tiktok-open-sdk-core:2.3.0")
    implementation("com.tiktok.open.sdk:tiktok-open-sdk-auth:2.3.0")

    testImplementation(kotlin("test"))
}
