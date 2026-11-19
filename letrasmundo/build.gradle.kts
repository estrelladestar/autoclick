plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Mi Mundo Letras: el juego de Mi Mundo (mascota, avatar, monedas y tienda) con preguntas de lectoescritura para 5 años (vocales, m, p, l, s, t, d, n, ñ, j), la letra que falta, escribir letras y formar palabras.
android {
    namespace = "com.estrella.mimundo"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.estrella.mimundo.letras"
        minSdk = 26
        targetSdk = 34
        val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = runNumber
        versionName = "1.$runNumber"
        setProperty("archivesBaseName", "MiMundoLetras")  // el APK se llama MiMundoLetras-debug.apk
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

