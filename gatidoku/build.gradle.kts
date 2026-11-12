plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Gatidoku: puzles de lógica con gatos (assets/gatidoku.html dentro de un WebView). App independiente.
android {
    namespace = "com.estrella.gatidoku"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.estrella.gatidoku"
        minSdk = 26
        targetSdk = 34
        val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = runNumber
        versionName = "1.$runNumber"
        setProperty("archivesBaseName", "Gatidoku")  // el APK se llama Gatidoku-debug.apk
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
