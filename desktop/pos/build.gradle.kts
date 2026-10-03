import java.io.ByteArrayOutputStream
import java.security.MessageDigest
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

// Los renglones «@manifiesto …» no se excluyen de nada: sólo se vigilan (ver Paridad, abajo).
val excluidos: Set<String> = file("excluidos.txt").readLines()
    .map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() && !it.startsWith("@") }.toSet()

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

// --- Paridad en el tiempo: cada renglón de excluidos.txt lleva la huella SHA-256 del archivo de Android con el que se
// reconcilió su reemplazo de escritorio. Si Android lo cambia, verificarExcluidos truena (antes de compilar y en el CI de
// escritorio) y dice cuál y qué hacer; actualizarHuellas las reescribe A PROPÓSITO, tras poner al día el reemplazo. ---
object Paridad {
    const val MANIFIESTO = "@manifiesto"
    const val COMO_ACTUALIZAR = "./gradlew -p desktop :pos:actualizarHuellas"
    private val HUELLA = Regex("""\|\s*huella:\s*(\S*)""")
    private val HEX = Regex("[0-9a-f]{64}")

    /**
     * Un renglón que vigila un archivo de Android. [ruta] es como la escribe excluidos.txt; [rutaGit], relativa a la raíz
     * de avoqado-android (para el `git log`); [huella], la anotada (null si falta o no son 64 hex en minúscula).
     */
    class Vigilado(val renglon: Int, val ruta: String, val rutaGit: String, val archivo: File, val nota: String, val huella: String?)

    /**
     * Lee los renglones de excluidos.txt: comentarios (#) y vacíos se ignoran; `<ruta> # <tipo>: <nota> | huella: <hex>`
     * vigila app/src/main/java/<ruta>; `@manifiesto <ruta> | huella: <hex>` vigila <ruta> desde la raíz de Android.
     */
    fun leer(renglones: List<String>, raizAndroid: File): List<Vigilado> = renglones.mapIndexedNotNull { i, renglon ->
        val texto = renglon.trim()
        if (texto.isEmpty() || texto.startsWith("#")) return@mapIndexedNotNull null
        val anotada = HUELLA.find(texto)?.groupValues?.get(1)
        val sinHuella = HUELLA.replace(texto, "").trim()
        val cabeza = sinHuella.substringBefore('#').trim()
        val nota = sinHuella.substringAfter('#', "").trim()
        val huella = anotada?.takeIf { HEX.matches(it) }
        if (cabeza.startsWith("@")) {
            val directiva = cabeza.substringBefore(' ')
            if (directiva != MANIFIESTO) {
                throw GradleException("excluidos.txt, renglón ${i + 1}: no conozco «$directiva» (sólo $MANIFIESTO)")
            }
            val ruta = cabeza.removePrefix(MANIFIESTO).trim()
            Vigilado(i, ruta, ruta, raizAndroid.resolve(ruta), nota.ifEmpty {
                "servicios, receptores y permisos: lo que el sistema arranca en Android, escritorio lo replica a mano en escritorio/Arranque.kt"
            }, huella)
        } else {
            Vigilado(i, cabeza, "app/src/main/java/$cabeza", raizAndroid.resolve("app/src/main/java/$cabeza"), nota, huella)
        }
    }

    /** SHA-256 del CONTENIDO con CRLF → LF: un archivo con otro fin de línea (git en Windows) no es un cambio de Android. */
    fun huella(archivo: File): String {
        val bytes = archivo.readBytes()
        val normal = ByteArrayOutputStream(bytes.size)
        for (i in bytes.indices) {
            if (bytes[i] == '\r'.code.toByte() && i + 1 < bytes.size && bytes[i + 1] == '\n'.code.toByte()) continue
            normal.write(bytes[i].toInt())
        }
        return MessageDigest.getInstance("SHA-256").digest(normal.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /** El mismo renglón con [huella] en su campo `huella:` (lo agrega al final si no lo tenía). Nada más cambia. */
    fun conHuella(renglon: String, huella: String): String = when {
        HUELLA.containsMatchIn(renglon) -> HUELLA.replace(renglon) { "| huella: $huella" }
        renglon.trimStart().startsWith("@") || '#' in renglon -> "${renglon.trimEnd()} | huella: $huella"
        else -> "${renglon.trimEnd()}  # | huella: $huella"
    }

    fun faltantes(vigilados: List<Vigilado>): List<String> = vigilados.filterNot { it.archivo.isFile }.map { it.ruta }

    /** Un mensaje por renglón cuya huella falta o ya no es la del archivo de Android. Todos, no sólo el primero. */
    fun diferencias(vigilados: List<Vigilado>): List<String> = vigilados.filter { it.archivo.isFile }.mapNotNull { v ->
        val actual = huella(v.archivo)
        val anotada = v.huella
        when {
            anotada == null ->
                "${v.ruta} no tiene huella en excluidos.txt. Revisa que su reemplazo de escritorio esté al día (${v.nota}) y corre `$COMO_ACTUALIZAR`."
            anotada != actual ->
                "Android cambió ${v.ruta} (huella ${anotada.take(8)}… → ${actual.take(8)}…). Revisa el cambio (`git log -p -- ${v.rutaGit}`), " +
                    "pon al día su reemplazo de escritorio (${v.nota}) y corre `$COMO_ACTUALIZAR`."
            else -> null
        }
    }
}

// -Pavoqado.paridad.excluidos / -Pavoqado.paridad.android: sólo para PROBAR las alarmas con un árbol de prueba
// (desktop/herramientas/probar-huellas.sh). No cambian qué se compila: la exclusión de arriba lee SIEMPRE excluidos.txt.
val excluidosDeParidad = providers.gradleProperty("avoqado.paridad.excluidos").map { File(it) }.getOrElse(file("excluidos.txt"))
val raizAndroidDeParidad = providers.gradleProperty("avoqado.paridad.android").map { File(it) }.getOrElse(appAndroid.parentFile)

val verificarExcluidos by tasks.registering {
    description = "Que cada archivo de Android que escritorio reemplaza (o vigila) exista y conserve la huella de excluidos.txt."
    val archivo = excluidosDeParidad
    val raiz = raizAndroidDeParidad
    doLast {
        val vigilados = Paridad.leer(archivo.readLines(), raiz)
        val faltan = Paridad.faltantes(vigilados)
        val avisos = buildList {
            if (faltan.isNotEmpty()) add("Estos archivos de excluidos.txt ya no existen en app/ (¿los movieron o renombraron?): $faltan")
            addAll(Paridad.diferencias(vigilados))
        }
        if (avisos.isNotEmpty()) {
            throw GradleException(
                "El POS de Windows se quedó atrás de Android en ${avisos.size} punto(s) (desktop/pos/excluidos.txt):\n" +
                    avisos.joinToString("\n") { "  • $it" },
            )
        }
    }
}

val actualizarHuellas by tasks.registering {
    description = "Reescribe el campo «huella:» de cada renglón de excluidos.txt con la del archivo de Android de hoy. Sólo tras revisar."
    val archivo = excluidosDeParidad
    val raiz = raizAndroidDeParidad
    doLast {
        val renglones = archivo.readLines()
        val vigilados = Paridad.leer(renglones, raiz)
        val faltan = Paridad.faltantes(vigilados)
        if (faltan.isNotEmpty()) {
            throw GradleException("No puedo poner la huella de archivos que ya no existen en Android (quítalos o corrige la ruta en excluidos.txt): $faltan")
        }
        val nuevos = renglones.toMutableList()
        val cambiaron = vigilados.mapNotNull { v ->
            val huella = Paridad.huella(v.archivo)
            nuevos[v.renglon] = Paridad.conHuella(renglones[v.renglon], huella)
            v.ruta.takeIf { v.huella != huella }
        }
        archivo.writeText(nuevos.joinToString("\n", postfix = "\n"))
        logger.lifecycle("Huellas al día: ${vigilados.size}. Cambiaron: ${cambiaron.ifEmpty { listOf("ninguna") }.joinToString(", ")}")
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

// --- MSIX: el ZIP lleva msix/ (manifiesto sellado por modo, logo, version.txt y los scripts de PowerShell) ---
// Versión MSIX = versionName de Android + «.0» (2.21.1 → 2.21.1.0); cada parte 0-65535. Perezosa: sólo truena al empaquetar.
val versionMsix: Provider<String> = provider {
    val partes = versionAndroid.split('.')
    val numeros = partes.map { parte -> parte.takeIf { it.matches(Regex("""\d{1,5}""")) }?.toInt()?.takeIf { it in 0..65535 } }
    if (partes.size != 3 || numeros.any { it == null }) {
        throw GradleException("La versión de Android «$versionAndroid» no se puede convertir a versión MSIX X.Y.Z.0")
    }
    numeros.joinToString(".") + ".0"
}
val nombreMsix = if (produccion) "Avoqado.POS" else "Avoqado.POS.Prueba"
val nombreVisibleMsix = if (produccion) "Avoqado POS" else "Avoqado POS (prueba)"
val generarVersionMsix by tasks.registering {
    val archivo = layout.buildDirectory.file("generated/msix/version.txt")
    inputs.property("versionMsix", versionMsix)
    outputs.file(archivo)
    doLast { archivo.get().asFile.apply { parentFile.mkdirs() }.writeText(versionMsix.get()) }   // sin salto de línea
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
        from("windows/LEEME-PANTALLA-DEL-CLIENTE.txt")   // cómo probar la pantalla del cliente con dos monitores
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
        // msix/: lo que armar-msix.ps1 necesita en Windows. @EDITOR@ NO se sella aquí: lo pone Windows (certificado o la Store).
        into("msix") {
            from("windows/msix/AppxManifest.plantilla.xml") {
                rename { "AppxManifest.xml" }
                filter { it.replace("@NOMBRE_VISIBLE@", nombreVisibleMsix).replace("@NOMBRE@", nombreMsix).replace("@VERSION@", versionMsix.get()) }
                filteringCharset = "UTF-8"
            }
            from(appAndroid.resolve("src/main/res/drawable-nodpi/avoqado_logo_mark.png")) { rename { "logo.png" } }
            from(generarVersionMsix)
            from("windows/msix") { include("*.ps1") }   // herramientas, certificado-de-prueba y armar-msix
        }
    }
    inputs.property("produccion", produccion)
    inputs.property("versionMsix", versionMsix)
}
