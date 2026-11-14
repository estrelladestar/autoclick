plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Star Joyas: juego de unir 3 joyas (assets/joyas.html dentro de un WebView). App independiente.
android {
    namespace = "com.estrella.joyas"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.estrella.joyas"
        minSdk = 26
        targetSdk = 34
        val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = runNumber
        versionName = "1.$runNumber"
        setProperty("archivesBaseName", "StarJoyas")  // el APK se llama StarJoyas-debug.apk
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
