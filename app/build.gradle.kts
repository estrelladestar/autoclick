plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.autoclick"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.example.autoclick"
        minSdk = 26
        targetSdk = 34
        val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = runNumber
        versionName = "1.$runNumber"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }  // 64 y 32 bits (p. ej. Redmi 8A)
    }
    // Dos apps con el mismo código: «Star App» y «Star App Historias» (que además puede subir la publicación a la historia)
    flavorDimensions += "tipo"
    productFlavors {
        create("normal") {
            dimension = "tipo"
            manifestPlaceholders["appLabel"] = "Star App"
        }
        create("historias") {
            dimension = "tipo"
            applicationIdSuffix = ".historias"
            manifestPlaceholders["appLabel"] = "Star App Historias"
        }
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
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("org.opencv:opencv:4.10.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
