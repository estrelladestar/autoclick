plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Mi Mundo: juego aparte (avatar, mascota y preguntas). No tiene nada que ver con Star App.
android {
    namespace = "com.estrella.mimundo"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.estrella.mimundo"
        minSdk = 26
        targetSdk = 34
        val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = runNumber
        versionName = "1.$runNumber"
        setProperty("archivesBaseName", "MiMundo")  // el APK se llama MiMundo-debug.apk
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

