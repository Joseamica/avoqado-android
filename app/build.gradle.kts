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

android {
    namespace = "com.avoqado.pos"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.avoqado.pos"
        minSdk = 26
        targetSdk = 35
        versionCode = 13
        versionName = "2.3.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "BASE_URL", "\"$releaseBaseUrl\"")
        buildConfigField("String", "ENVIRONMENT_NAME", "\"production\"")
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
            isMinifyEnabled = false
            buildConfigField("String", "BASE_URL", "\"$debugBaseUrl\"")
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

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
