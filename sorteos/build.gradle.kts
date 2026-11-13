import java.security.MessageDigest

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
        // Clave de los códigos de activación: sale del secreto SORTEOS_CLAVE de GitHub (nunca está en el código).
        // Se guarda solo su huella (SHA-256). Sin secreto, la app funciona en plan Gratis y no acepta códigos.
        val clave = System.getenv("SORTEOS_CLAVE")?.trim() ?: ""
        val huella = if (clave.isEmpty()) "" else MessageDigest.getInstance("SHA-256")
            .digest(clave.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        buildConfigField("String", "LIC_KEY", "\"$huella\"")
        // Clave de Apify (cargar todos los comentarios de golpe): secreto APIFY_TOKEN de GitHub
        val apify = (System.getenv("APIFY_TOKEN") ?: "").trim().filter { it.isLetterOrDigit() || it == '_' || it == '-' }
        buildConfigField("String", "APIFY_TOKEN", "\"$apify\"")
    }
    buildFeatures { buildConfig = true }
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
