plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Mi Mundo Decimales: el mismo juego que Mi Mundo, con las fichas de repaso de números decimales de 5.º (lectura, valor, recta, comparar, redondeo y problemas) y pizarra para cuentas (app aparte).
android {
    namespace = "com.estrella.mimundo"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.estrella.mimundo.decimales"
        minSdk = 26
        targetSdk = 34
        val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = runNumber
        versionName = "1.$runNumber"
        setProperty("archivesBaseName", "MiMundoDecimales")  // el APK se llama MiMundoDecimales-debug.apk
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

