plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Star Mates: app de matemáticas aparte (sumas, restas, multiplicar, dividir, tablas y reto de 1 minuto).
android {
    namespace = "com.estrella.starmates"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.estrella.starmates"
        minSdk = 26
        targetSdk = 34
        val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = runNumber
        versionName = "1.$runNumber"
        setProperty("archivesBaseName", "StarMates")  // el APK se llama StarMates-debug.apk
    }
    signingConfigs {
        getByName("debug") {
            storeFile = file("../app/debug.keystore")
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
