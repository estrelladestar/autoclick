plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Tablas Quest: app de tablas de multiplicar (assets/tablas.html dentro de la app).
android {
    namespace = "com.estrella.tablasquest"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.estrella.tablasquest"
        minSdk = 26
        targetSdk = 34
        val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = runNumber
        versionName = "1.$runNumber"
        setProperty("archivesBaseName", "TablasQuest")  // el APK se llama TablasQuest-debug.apk
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
