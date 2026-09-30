plugins {
    kotlin("jvm")
    kotlin("plugin.compose")
}

kotlin { jvmToolchain(17) }

// Compose Multiplatform 1.7.3 = el mismo Compose 1.7 / Material3 1.3.1 del BOM 2025.01.01 de Android.
val cmp = "1.7.3"

dependencies {
    api("org.jetbrains.compose.runtime:runtime:$cmp")
    api("org.jetbrains.compose.foundation:foundation:$cmp")
    api("org.jetbrains.compose.ui:ui:$cmp")
    api("org.jetbrains.compose.material3:material3:$cmp")
    api("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    api("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-savedstate:2.8.4")
    api("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    api("com.google.inject:guice:6.0.0")          // 🔴 no 7: Guice 7 ya no lee javax.inject
    api("javax.inject:javax.inject:1")
    api("com.google.dagger:dagger:2.57.2")        // sólo por @Module/@Provides/@Binds
    api("com.google.dagger:hilt-core:2.57.2")     // sólo por @InstallIn/SingletonComponent/@EntryPoint
    api("androidx.datastore:datastore-preferences-core:1.1.1")
    api("org.json:json:20231013")                  // la misma que usan las pruebas de Android
    testImplementation(kotlin("test-junit"))
    testRuntimeOnly("org.jetbrains.compose.desktop:desktop-jvm-macos-arm64:$cmp")
}

tasks.test { systemProperty("java.awt.headless", "true") }
