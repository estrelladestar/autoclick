plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Caminando Letras: app de lectoescritura para niños de 5 años (vocales, m, p, l, s, t, d, n, ñ, j).
android {
    namespace = "com.estrella.acentos"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.estrella.acentos"
        minSdk = 26
        targetSdk = 34
        val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = runNumber
        versionName = "1.$runNumber"
        setProperty("archivesBaseName", "AcentosPalabras")  // el APK se llama AcentosPalabras-debug.apk
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
