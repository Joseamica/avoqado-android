plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

// Load keystore properties
import java.util.Properties
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) load(keystorePropertiesFile.inputStream())
}
val localPropertiesFile = rootProject.file("local.properties")
val localProperties = Properties().apply {
    if (localPropertiesFile.exists()) load(localPropertiesFile.inputStream())
}

fun configValue(key: String, defaultValue: String): String {
    return providers.gradleProperty(key).orNull
        ?: localProperties.getProperty(key)
        ?: defaultValue
}

val releaseBaseUrl = "https://api.avoqado.io/api/v1"
val defaultDebugBaseUrl = "https://patchiest-noncommemorational-willia.ngrok-free.dev/api/v1"
val debugBaseUrl = configValue("avoqado.devBaseUrl", defaultDebugBaseUrl).trim()

check(debugBaseUrl != releaseBaseUrl) {
    "Debug/development BASE_URL must not point to production. " +
        "Set avoqado.devBaseUrl in local.properties or pass -Pavoqado.devBaseUrl=..."
}

// Base del DASHBOARD (dominio distinto al del API). Es donde vive la página de
// recibo del cliente final — la que tiene calificación Y autofactura (CFDI).
// Sólo se usa como RESPALDO para el QR del ticket: lo normal es que el server
// mande la URL ya armada en `digitalReceipt.receiptUrl`. Espeja el FRONTEND_URL
// del backend (avoqado-server/render.yaml).
val releaseDashboardUrl = "https://dashboard.avoqado.io"
val defaultDebugDashboardUrl = "https://develop.avoqado-web-dashboard.pages.dev"
val debugDashboardUrl = configValue("avoqado.devDashboardUrl", defaultDebugDashboardUrl).trim()

android {
    namespace = "com.avoqado.pos"
    // Android 16 (API 36). Play RECHAZA actualizaciones que no lo targeteen a
    // partir del 30/08/2026 — no es opcional ni cosmético.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.avoqado.pos"
        minSdk = 26
        targetSdk = 36
        versionCode = 29
        versionName = "2.15.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "BASE_URL", "\"$releaseBaseUrl\"")
        buildConfigField("String", "DASHBOARD_URL", "\"$releaseDashboardUrl\"")
        buildConfigField("String", "ENVIRONMENT_NAME", "\"production\"")

        // App label per environment (overridden in debug below). Manifest reads ${appLabel}.
        // Corto A PROPÓSITO: el lanzador recorta a ~10-12 caracteres, así que
        // "Avoqado - Punto de venta" se leería "Avoqado - …" bajo el icono. El nombre
        // largo vive en el TÍTULO de la ficha de Play Store, que es lo que alimenta la
        // búsqueda de la tienda — `android:label` no influye en ese ranking.
        manifestPlaceholders["appLabel"] = "Avoqado"
    }

    signingConfigs {
        create("release") {
            storeFile = file("../${keystoreProperties["storeFile"] ?: "avoqado-release.keystore"}")
            storePassword = keystoreProperties["storePassword"] as? String ?: ""
            keyAlias = keystoreProperties["keyAlias"] as? String ?: "avoqado"
            keyPassword = keystoreProperties["keyPassword"] as? String ?: ""
        }
    }

    buildTypes {
        debug {
            // Distinct package so DEV (ngrok) and PROD (api.avoqado.io) coexist on the same device.
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            manifestPlaceholders["appLabel"] = "Avoqado DEV"
            isMinifyEnabled = false
            buildConfigField("String", "BASE_URL", "\"$debugBaseUrl\"")
            buildConfigField("String", "DASHBOARD_URL", "\"$debugDashboardUrl\"")
            buildConfigField("String", "ENVIRONMENT_NAME", "\"development\"")
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            ndk { debugSymbolLevel = "FULL" }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Compose BOM
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    // Navigation
    implementation(libs.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)

    // Storage
    implementation(libs.security.crypto)
    implementation(libs.datastore.preferences)

    // Biometric
    implementation(libs.biometric)

    // Image Loading
    implementation(libs.coil.compose)

    // AndroidX Core
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.activity.compose)

    // Camera & Barcode
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.barcode.scanning)
    // QR para la pantalla del cliente (recibo digital) — misma versión que avoqado-tpv.
    implementation("com.google.zxing:core:3.5.3")

    // Impresora TÉRMICA INTEGRADA de los POS Sunmi (D3, T3, V2…). No es un
    // periférico: no aparece por USB, Bluetooth ni red — vive detrás del
    // servicio AIDL `woyou.aidlservice.jiuiv5`. Se usa el SDK oficial en vez de
    // escribir el AIDL a mano porque el orden de los métodos define los
    // transaction ids: equivocarse no falla, invoca OTRA función de la impresora.
    // En equipos no-Sunmi el bind falla y todo queda igual que antes.
    implementation("com.sunmi:printerlibrary:1.0.24")

    // Básculas USB/serial (RS-232 mediante adaptador USB y puertos virtuales USB).
    // El parser del protocolo sigue siendo propio y se activa sólo con un ScaleProfile
    // certificado por terminal.
    implementation("com.github.mik3y:usb-serial-for-android:3.10.0")

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Adaptive Layout
    implementation(libs.material3.window.size)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.turbine)
    testImplementation(libs.sqlite.jdbc)
    // `org.json` del SDK es un stub en los tests JVM (`unitTests.isReturnDefaultValues`): devuelve
    // valores por default en vez de parsear. Sin la implementación real, un test de parseo pasaría
    // con el código roto — que es exactamente lo que no queremos de un test.
    testImplementation("org.json:json:20231013")

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
