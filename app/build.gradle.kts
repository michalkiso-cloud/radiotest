plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.michal.radiotest"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.michal.radiotest"
        minSdk = 29
        targetSdk = 35
        versionCode = 47
        versionName = "4.7"
        manifestPlaceholders["redirectSchemeName"] = "radiotest"
        manifestPlaceholders["redirectHostName"] = "callback"
        manifestPlaceholders["redirectPathPattern"] = ".*"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    androidResources {
        noCompress += "tflite"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    // Odtwarzanie radia w tle
    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("com.google.guava:guava:33.3.1-android")
    // wykrycie rozłączenia Android Auto
    implementation("androidx.car.app:app:1.4.0")

    // YAMNet (wykrywanie mowy) przez LiteRT; model pobierany w GitHub Actions do assets
    implementation("com.google.ai.edge.litert:litert:1.0.1")

    // Spotify App Remote (AAR pobierany w GitHub Actions do app/libs)
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.fasterxml.jackson.core:jackson-annotations:2.17.2")
    implementation("androidx.browser:browser:1.8.0")
}
