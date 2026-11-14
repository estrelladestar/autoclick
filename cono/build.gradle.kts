plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Mi Mundo Cono: el mismo juego que Mi Mundo, con el Tema 3 de Conocimiento del Medio (la materia y las fuerzas), app aparte.
android {
    namespace = "com.estrella.mimundo"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.estrella.mimundo.cono"
        minSdk = 26
        targetSdk = 34
        val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = runNumber
        versionName = "1.$runNumber"
        setProperty("archivesBaseName", "MiMundoCono")  // el APK se llama MiMundoCono-debug.apk
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

