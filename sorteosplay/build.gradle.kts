plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Sorteos Star para GOOGLE PLAY. Usa la misma página y el mismo código que «sorteos», pero:
//  - sin el permiso de accesibilidad (Google no lo deja para leer otras apps): las publicaciones se
//    cargan con Apify y los directos con la web;
//  - los planes se pagan con suscripciones de Google Play (no con códigos);
//  - Android 16 (API 36), y el .aab sale firmado con la clave de subida (secreto PLAY_UPLOAD_PASS).
android {
    namespace = "com.estrella.sorteos"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.estrella.sorteosstar"
        minSdk = 26
        targetSdk = 36
        val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = runNumber
        versionName = "2.$runNumber"
        setProperty("archivesBaseName", "SorteosStarPlay")
        buildConfigField("String", "LIC_KEY", "\"\"")          // en Play no hay códigos de activación
        val apify = (System.getenv("APIFY_TOKEN") ?: "").trim().filter { it.isLetterOrDigit() || it == '_' || it == '-' }
        buildConfigField("String", "APIFY_TOKEN", "\"$apify\"")
        buildConfigField("boolean", "PLAY", "true")
    }
    buildFeatures { buildConfig = true }
    sourceSets {
        getByName("main") {
            java.srcDirs("../sorteos/src/main/java", "src/main/java")
            assets.srcDirs("../sorteos/src/main/assets")
            res.srcDirs("../sorteos/src/main/res", "src/main/res")
        }
    }
    signingConfigs {
        getByName("debug") {
            storeFile = file("../app/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("subida") {
            // upload.jks se descifra en GitHub Actions con el secreto PLAY_UPLOAD_PASS
            storeFile = file("upload.jks")
            storePassword = System.getenv("PLAY_UPLOAD_PASS") ?: ""
            keyAlias = "upload"
            keyPassword = System.getenv("PLAY_UPLOAD_PASS") ?: ""
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = if (file("upload.jks").exists()) signingConfigs.getByName("subida") else null
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { checkReleaseBuilds = false; abortOnError = false }
}

dependencies {
    implementation("com.android.billingclient:billing:8.0.0")
}
