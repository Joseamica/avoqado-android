import java.util.zip.ZipFile
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
    implementation("net.java.dev.jna:jna-platform:5.19.1")   // puente táctil de Windows (WM_POINTER); no-op fuera de Windows
    runtimeOnly("org.jetbrains.compose.desktop:desktop-jvm-macos-arm64:$cmp")   // Skia nativo para la Mac; Windows usa su propio artefacto (tarea 9)

    testImplementation(kotlin("test-junit"))
    testImplementation("androidx.room:room-testing:2.8.4")
    testImplementation("org.jetbrains.compose.ui:ui-test-junit4:$cmp")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

// --- BuildConfig: los 4 campos que la app lee (BASE_URL, DASHBOARD_URL, DEBUG, VERSION_NAME) ---
val versionAndroid = Regex("""versionName\s*=\s*"([^"]+)"""")
    .find(appAndroid.resolve("build.gradle.kts").readText())?.groupValues?.get(1) ?: "0.0.0"
// -Pavoqado.produccion=true: build de PRODUCCIÓN (DEBUG=false, hosts fijos de avoqado.io, carpeta de datos segura). Por defecto, PRUEBA.
val produccion = providers.gradleProperty("avoqado.produccion").map {
    when (it) {
        "true" -> true
        "false" -> false
        else -> throw GradleException("avoqado.produccion sólo acepta true o false (recibí «$it»). Sin la propiedad, el build es de PRUEBA.")
    }
}.getOrElse(false)
val apiPorDefecto = if (produccion) "https://api.avoqado.io/api/v1"
    else providers.gradleProperty("avoqado.api").getOrElse("http://localhost:3000/api/v1")
val dashboardPorDefecto = if (produccion) "https://dashboard.avoqado.io"
    else providers.gradleProperty("avoqado.dashboard").getOrElse("http://localhost:5173")

val generarBuildConfig by tasks.registering {
    val archivo = layout.buildDirectory.file("generated/buildconfig/com/avoqado/pos/BuildConfig.kt")
    inputs.property("version", versionAndroid)
    inputs.property("produccion", produccion)
    inputs.property("api", apiPorDefecto)
    inputs.property("dashboard", dashboardPorDefecto)
    outputs.file(archivo)
    doLast {
        archivo.get().asFile.apply { parentFile.mkdirs() }.writeText(
            """
            |package com.avoqado.pos
            |
            |/** Generado por desktop/pos/build.gradle.kts: los campos que la app lee del BuildConfig de Android (+ PRODUCCION). */
            |object BuildConfig {
            |    const val PRODUCCION: Boolean = $produccion
            |    const val DEBUG: Boolean = ${!produccion}
            |    const val VERSION_NAME: String = "$versionAndroid-escritorio"
            |    val BASE_URL: String = com.avoqado.escritorio.Urls.api(porDefecto = "$apiPorDefecto", produccion = PRODUCCION)
            |    val DASHBOARD_URL: String = com.avoqado.escritorio.Urls.dashboard(porDefecto = "$dashboardPorDefecto", produccion = PRODUCCION)
            |}
            |""".trimMargin(),
        )
    }
}

// --- Migraciones de Room: las MISMAS de Android. Se copian con dos imports cambiados (Migration/SupportSQLiteDatabase no existen en Room JVM) ---
// -Pavoqado.migracionesDeAndroid=<ruta>: sólo para PROBAR la tarea con una copia alterada; por defecto, la de Android.
val migracionesOriginal = providers.gradleProperty("avoqado.migracionesDeAndroid").map { File(it) }
    .getOrElse(fuentesAndroid.resolve("com/avoqado/pos/core/data/local/database/AvoqadoDatabaseMigrations.kt"))
val baseDeAndroid = fuentesAndroid.resolve("com/avoqado/pos/core/data/local/database/AvoqadoDatabase.kt")
val copiarMigracionesDeAndroid by tasks.registering {
    val origen = migracionesOriginal
    val fuenteBase = baseDeAndroid
    val destino = layout.buildDirectory.file("generated/migraciones/com/avoqado/pos/core/data/local/database/AvoqadoDatabaseMigrations.kt")
    val destinoVersion = layout.buildDirectory.file("generated/migraciones/com/avoqado/pos/escritorio/base/VersionDeLaBase.kt")
    inputs.file(origen)
    inputs.file(fuenteBase)
    outputs.files(destino, destinoVersion)
    doLast {
        val reemplazos = mapOf(
            "import androidx.room.migration.Migration" to "import com.avoqado.pos.escritorio.base.MigracionDeAndroid as Migration",
            "import androidx.sqlite.db.SupportSQLiteDatabase" to "import com.avoqado.pos.escritorio.base.BaseDeAndroid as SupportSQLiteDatabase",
        )
        val lineas = origen.readLines().toMutableList()
        for ((original, nuevo) in reemplazos) {
            val donde = lineas.indices.filter { lineas[it] == original }
            check(donde.size == 1) {
                "AvoqadoDatabaseMigrations.kt de Android cambió sus imports: revisa la copia de escritorio (falta: $original)"
            }
            lineas[donde.single()] = nuevo
        }
        check(lineas.none { it.startsWith("import androidx.room") || it.startsWith("import androidx.sqlite") }) {
            "AvoqadoDatabaseMigrations.kt de Android cambió sus imports: revisa la copia de escritorio (queda un import de androidx)"
        }
        // La versión REAL de Room sale de @Database(version = N); las migraciones automáticas no se soportan (ni se descubrirían).
        val textoBase = fuenteBase.readText()
        check("autoMigrations" !in textoBase) {
            "AvoqadoDatabase.kt de Android declara autoMigrations: el POS de escritorio todavía no las soporta (migraciones y respaldo sólo cubren las manuales)"
        }
        val version = Regex("""version\s*=\s*(\d+)""").findAll(textoBase).map { it.groupValues[1] }.toList().singleOrNull()
            ?: error("No se encontró un único `version = N` en la anotación @Database de AvoqadoDatabase.kt")
        destino.get().asFile.apply { parentFile.mkdirs() }.writeText(lineas.joinToString("\n", postfix = "\n"))
        destinoVersion.get().asFile.apply { parentFile.mkdirs() }.writeText(
            "package com.avoqado.pos.escritorio.base\n\n/** Generado: la version de @Database en AvoqadoDatabase.kt de Android. */\nconst val VERSION_DE_LA_BASE: Int = $version\n",
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
    kotlin.srcDir(layout.buildDirectory.dir("generated/migraciones"))
    // Se filtra por RUTA ABSOLUTA: un patrón relativo excluiría también nuestro reemplazo con el mismo nombre.
    kotlin.exclude { el -> el.file.startsWith(fuentesAndroid) && el.relativePath.pathString in excluidos }
}

tasks.named("compileKotlin") { dependsOn(generarBuildConfig, copiarMigracionesDeAndroid, verificarExcluidos) }

ksp {
    // 🔴 Sin esto Room buscaría dónde exportar el esquema y podría escribir en ../app/schemas.
    arg("room.schemaLocation", layout.buildDirectory.dir("room-schemas").get().asFile.absolutePath)
    arg("room.generateKotlin", "true")
}
tasks.matching { it.name == "kspKotlin" }.configureEach {
    dependsOn(generarBuildConfig, copiarMigracionesDeAndroid)
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
    systemProperty("avoqado.esquemas", appAndroid.resolve("schemas").absolutePath)   // los JSON que exporta Android (3.json…)
    inputs.dir(appAndroid.resolve("schemas"))
        .withPropertyName("esquemasAndroid")
        .withPathSensitivity(PathSensitivity.RELATIVE)
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
// -Pavoqado.jreWindows=<JRE de Windows ya descomprimido> -Pavoqado.salida=<ruta ABSOLUTA del árbol real> -Pavoqado.ipMac=<IP> (sólo en prueba)
val runtimeWindows by configurations.creating {
    extendsFrom(configurations.runtimeClasspath.get())
    isCanBeConsumed = false
    // Los mismos atributos del runtimeClasspath (JVM estándar): sin ellos Gradle no sabe elegir entre skiko-android y skiko-awt.
    attributes.addAllLater(configurations.runtimeClasspath.get().attributes)
    exclude(group = "org.jetbrains.compose.desktop", module = "desktop-jvm-macos-arm64")
    exclude(group = "org.jetbrains.skiko", module = "skiko-awt-runtime-macos-arm64")
}
dependencies { runtimeWindows("org.jetbrains.compose.desktop:desktop-jvm-windows-x64:$cmp") }

// Una DLL de SQLite NATIVA para Windows va en jre/bin del paquete: androidx.sqlite la busca ahí ANTES de copiarla a %TEMP%.
// (No existe para ARM64: en una Windows ARM se usa este JRE x64 bajo emulación.)
val rutaDeLaDll = "natives/windows_x64/sqliteJni.dll"
// -Pavoqado.prefijoJarSqlite: sólo para PROBAR el mensaje cuando el jar falta (un prefijo que no existe).
val prefijoDelJarDeSqlite = providers.gradleProperty("avoqado.prefijoJarSqlite").getOrElse("sqlite-bundled-jvm")
// Perezoso: si el jar falta, el error en español sale AQUÍ (y no el «provider has no value» de Gradle).
val jarDeSqlite = provider {
    runtimeWindows.files.firstOrNull { it.name.startsWith(prefijoDelJarDeSqlite) }
        ?: throw GradleException("No encontré $prefijoDelJarDeSqlite entre las dependencias de Windows: sin su DLL nativa, SQLite se copiaría a %TEMP% en cada arranque")
}

// -Pavoqado.produccion=true: lanzador sin `set` (hosts de producción, sin -Pavoqado.ipMac) y ZIP «…-produccion.zip».
val empaquetarWindows by tasks.registering(Zip::class) {
    val jre = providers.gradleProperty("avoqado.jreWindows")        // carpeta del JRE ya descomprimido
    val salida = providers.gradleProperty("avoqado.salida")
    val ipMac = providers.gradleProperty("avoqado.ipMac")
    archiveFileName.set(if (produccion) "avoqado-pos-windows-produccion.zip" else "avoqado-pos-windows-prueba.zip")
    destinationDirectory.set(layout.dir(salida.map { File(it) }))
    doFirst {
        val jar = jarDeSqlite.get()
        val trae = ZipFile(jar).use { it.getEntry(rutaDeLaDll) != null }
        if (!trae) throw GradleException("${jar.name} no trae $rutaDeLaDll: sin ella SQLite se copiaría a %TEMP% en cada arranque")
    }
    into("Avoqado POS") {
        from(tasks.jar) { into("lib") }
        from(runtimeWindows) { into("lib") }
        from(jre) { into("jre") }
        from(jarDeSqlite.map { zipTree(it) }) {
            include(rutaDeLaDll)
            eachFile { relativePath = RelativePath(true, "Avoqado POS", "jre", "bin", "sqliteJni.dll") }
            includeEmptyDirs = false
        }
        // Diagnóstico y «qué ve Windows» miran SÓLO la carpeta de datos de este modo (la misma que resuelve CarpetaDeDatos).
        val carpetaBat = if (produccion) "%USERPROFILE%\\.avoqado-pos" else "%APPDATA%\\Avoqado POS"
        val carpetaPs = if (produccion) "Join-Path \$env:USERPROFILE '.avoqado-pos'" else "Join-Path \$env:APPDATA 'Avoqado POS'"
        from("windows/Diagnostico.bat") {
            filter { it.replace("@CARPETA_DE_DATOS@", carpetaBat) }
            filter(org.apache.tools.ant.filters.FixCrLfFilter::class, "eol" to org.apache.tools.ant.filters.FixCrLfFilter.CrLf.newInstance("crlf"))
            filteringCharset = "UTF-8"
        }
        from("windows/QueVeWindows.bat")   // qué impresoras, COM y pantallas ve Windows (sólo lee)
        from("windows/QueVeWindows.ps1") {
            filter { it.replace("@CARPETA_DE_DATOS_PS@", carpetaPs) }
            filter(org.apache.tools.ant.filters.FixCrLfFilter::class, "eol" to org.apache.tools.ant.filters.FixCrLfFilter.CrLf.newInstance("crlf"))
            filteringCharset = "UTF-8"
        }
        if (produccion) {
            from("windows/Iniciar-produccion.bat") { rename { "Iniciar.bat" } }
        } else {
            from("windows/Iniciar.bat.plantilla") {
                rename { "Iniciar.bat" }
                filter { it.replace("@IP_DE_LA_MAC@", ipMac.get()) }
                // El filtro por renglón de Gradle deja LF (medido: run-avoqado-android.ul3Vwv); cmd.exe quiere CRLF.
                filter(org.apache.tools.ant.filters.FixCrLfFilter::class, "eol" to org.apache.tools.ant.filters.FixCrLfFilter.CrLf.newInstance("crlf"))
                filteringCharset = "UTF-8"
            }
        }
    }
}
