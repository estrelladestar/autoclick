plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Mates 3: juego de mates (operaciones por niveles, pista, pizarra y modo Vestir).
android {
    namespace = "com.estrella.mates3"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.estrella.mates3"
        minSdk = 26
        targetSdk = 34
        val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = runNumber
        versionName = "1.$runNumber"
        setProperty("archivesBaseName", "Mates3")  // el APK se llama Mates3-debug.apk
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
