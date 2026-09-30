plugins {
    kotlin("jvm")
    kotlin("plugin.compose")
    kotlin("plugin.serialization")
    id("com.google.devtools.ksp")
}

kotlin { jvmToolchain(17) }

val cmp = "1.7.3"
// 🔴 SÓLO LECTURA. Este build lee las fuentes y 2 recursos de Android de aquí y JAMÁS escribe en ../app.
val appAndroid = rootDir.resolve("../app").canonicalFile
val fuentesAndroid = appAndroid.resolve("src/main/java")

val excluidos: Set<String> = file("excluidos.txt").readLines()
    .map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.toSet()

dependencies {
    implementation(project(":plataforma"))
    implementation("org.jetbrains.compose.material3:material3-window-size-class:$cmp")
    implementation("org.jetbrains.compose.material:material-icons-extended:$cmp")
    implementation("org.jetbrains.androidx.navigation:navigation-compose:2.8.0-alpha10")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.sqlite:sqlite-bundled:2.6.2")
    ksp("androidx.room:room-compiler:2.8.4")
    implementation("com.google.zxing:core:3.5.3")
    runtimeOnly("org.jetbrains.compose.desktop:desktop-jvm-macos-arm64:$cmp")   // Skia nativo para la Mac; Windows usa su propio artefacto (tarea 9)

    testImplementation(kotlin("test-junit"))
    testImplementation("org.jetbrains.compose.ui:ui-test-junit4:$cmp")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

// --- BuildConfig: los 4 campos que la app lee (BASE_URL, DASHBOARD_URL, DEBUG, VERSION_NAME) ---
val versionAndroid = Regex("""versionName\s*=\s*"([^"]+)"""")
    .find(appAndroid.resolve("build.gradle.kts").readText())?.groupValues?.get(1) ?: "0.0.0"
val apiPorDefecto = providers.gradleProperty("avoqado.api").getOrElse("http://localhost:3000/api/v1")
val dashboardPorDefecto = providers.gradleProperty("avoqado.dashboard").getOrElse("http://localhost:5173")

val generarBuildConfig by tasks.registering {
    val archivo = layout.buildDirectory.file("generated/buildconfig/com/avoqado/pos/BuildConfig.kt")
    inputs.property("version", versionAndroid)
    inputs.property("api", apiPorDefecto)
    inputs.property("dashboard", dashboardPorDefecto)
    outputs.file(archivo)
    doLast {
        archivo.get().asFile.apply { parentFile.mkdirs() }.writeText(
            """
            |package com.avoqado.pos
            |
            |/** Generado por desktop/pos/build.gradle.kts: los 4 campos que la app lee del BuildConfig de Android. */
            |object BuildConfig {
            |    const val DEBUG: Boolean = true
            |    const val VERSION_NAME: String = "$versionAndroid-escritorio"
            |    val BASE_URL: String = com.avoqado.escritorio.Urls.api(porDefecto = "$apiPorDefecto")
            |    val DASHBOARD_URL: String = com.avoqado.escritorio.Urls.dashboard(porDefecto = "$dashboardPorDefecto")
            |}
            |""".trimMargin(),
        )
    }
}

val verificarExcluidos by tasks.registering {
    val faltan = excluidos.filterNot { fuentesAndroid.resolve(it).isFile }
    doLast {
        check(faltan.isEmpty()) { "Estos archivos de excluidos.txt ya no existen en app/ (¿los movieron o renombraron?): $faltan" }
    }
}

sourceSets.main {
    kotlin.srcDir(fuentesAndroid)
    kotlin.srcDir(layout.buildDirectory.dir("generated/buildconfig"))
    // Se filtra por RUTA ABSOLUTA: un patrón relativo excluiría también nuestro reemplazo con el mismo nombre.
    kotlin.exclude { el -> el.file.startsWith(fuentesAndroid) && el.relativePath.pathString in excluidos }
}

tasks.named("compileKotlin") { dependsOn(generarBuildConfig, verificarExcluidos) }

ksp {
    // 🔴 Sin esto Room buscaría dónde exportar el esquema y podría escribir en ../app/schemas.
    arg("room.schemaLocation", layout.buildDirectory.dir("room-schemas").get().asFile.absolutePath)
    arg("room.generateKotlin", "true")
}
tasks.matching { it.name == "kspKotlin" }.configureEach {
    dependsOn(generarBuildConfig)
    // -PsinKsp: durante el ciclo de compilación de la tarea 6, para que un error de KSP no tape los de Kotlin.
    enabled = !providers.gradleProperty("sinKsp").isPresent
}

// --- Los 2 recursos de Android que la app pinta (R.drawable.*); el video del login se sustituye por un fondo fijo ---
tasks.processResources {
    from(appAndroid.resolve("src/main/res")) {
        include("drawable-nodpi/avoqado_logo_mark.png", "drawable/ic_whatsapp.xml")
        into("android-res")
    }
}

// --- Pruebas ---
val evidencia = providers.gradleProperty("avoqado.evidencia")
tasks.test {
    systemProperty("java.awt.headless", "true")
    systemProperty("avoqado.api", "http://127.0.0.1:9/api/v1")    // puerto cerrado: ninguna prueba unitaria llega a un servidor
    systemProperty("avoqado.clasesMain", sourceSets.main.get().output.classesDirs.asPath)
    evidencia.orNull?.let { systemProperty("avoqado.evidencia", it) }
    filter { excludeTestsMatching("*E2E") }
}
val e2e by tasks.registering(Test::class) {
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter { includeTestsMatching("*E2E") }
    systemProperty("java.awt.headless", "true")
    systemProperty("avoqado.classpathPruebas", sourceSets.test.get().runtimeClasspath.asPath)
    systemProperty("avoqado.java", javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) }
        .get().executablePath.asFile.absolutePath)
    evidencia.orNull?.let { systemProperty("avoqado.evidencia", it) }
    providers.gradleProperty("avoqado.servidor").orNull?.let { systemProperty("avoqado.servidor", it) }
    // Credenciales de la base LOCAL, sólo al correr: el repo es público y no se escriben en el código.
    providers.gradleProperty("avoqado.e2e.correo").orNull?.let { systemProperty("avoqado.e2e.correo", it) }
    providers.gradleProperty("avoqado.e2e.clave").orNull?.let { systemProperty("avoqado.e2e.clave", it) }
    outputs.upToDateWhen { false }
}

// --- Paquete portátil para Windows: jar + dependencias con el Skia de Windows + JRE 17 + Iniciar.bat ---
// -Pavoqado.jreWindows=<JRE de Windows ya descomprimido> -Pavoqado.salida=<ruta ABSOLUTA del árbol real> -Pavoqado.ipMac=<IP>
val runtimeWindows by configurations.creating {
    extendsFrom(configurations.runtimeClasspath.get())
    isCanBeConsumed = false
    // Los mismos atributos del runtimeClasspath (JVM estándar): sin ellos Gradle no sabe elegir entre skiko-android y skiko-awt.
    attributes.addAllLater(configurations.runtimeClasspath.get().attributes)
    exclude(group = "org.jetbrains.compose.desktop", module = "desktop-jvm-macos-arm64")
    exclude(group = "org.jetbrains.skiko", module = "skiko-awt-runtime-macos-arm64")
}
dependencies { runtimeWindows("org.jetbrains.compose.desktop:desktop-jvm-windows-x64:$cmp") }

val empaquetarWindows by tasks.registering(Zip::class) {
    val jre = providers.gradleProperty("avoqado.jreWindows")        // carpeta del JRE ya descomprimido
    val salida = providers.gradleProperty("avoqado.salida")
    val ipMac = providers.gradleProperty("avoqado.ipMac")
    archiveFileName.set("avoqado-pos-windows-prueba.zip")
    destinationDirectory.set(layout.dir(salida.map { File(it) }))
    into("Avoqado POS") {
        from(tasks.jar) { into("lib") }
        from(runtimeWindows) { into("lib") }
        from(jre) { into("jre") }
        from("windows/Diagnostico.bat")
        from("windows/Iniciar.bat.plantilla") {
            rename { "Iniciar.bat" }
            filter { it.replace("@IP_DE_LA_MAC@", ipMac.get()) }
            // El filtro por renglón de Gradle deja LF (medido: run-avoqado-android.ul3Vwv); cmd.exe quiere CRLF.
            filter(org.apache.tools.ant.filters.FixCrLfFilter::class, "eol" to org.apache.tools.ant.filters.FixCrLfFilter.CrLf.newInstance("crlf"))
            filteringCharset = "UTF-8"
        }
    }
}
