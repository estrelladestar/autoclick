plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Sorteos Star: sorteos de Instagram y TikTok para tiendas (assets/sorteos.html en un WebView). App independiente.
android {
    namespace = "com.estrella.sorteos"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.estrella.sorteos"
        minSdk = 26
        targetSdk = 34
        val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = runNumber
        versionName = "1.$runNumber"
        setProperty("archivesBaseName", "SorteosStar")  // el APK se llama SorteosStar-debug.apk
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
