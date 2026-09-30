package com.avoqado.pos.escritorio.e2e

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Requiere el backend local en :3000, -Pavoqado.evidencia, -Pavoqado.servidor y las credenciales de la base LOCAL en
 * -Pavoqado.e2e.correo / -Pavoqado.e2e.clave (no se escriben en el código: este repo es público). Ver la tabla de la tarea 8.
 *
 * Se corre SIEMPRE con `AVQ_SIN_REUSO=1` delante de `avq-verify`: el resultado no depende sólo del código (que es lo que
 * compara la huella del reuso) sino de un backend externo que cambia —otra sesión lo reinicia, migra la base o cobra en el
 * mismo venue—. Un «♻️ REUSADO» de hace dos horas no dice nada de si hoy cobra.
 */
class CobroSinRedE2E {
    private val evidencia = File(checkNotNull(System.getProperty("avoqado.evidencia")) { "falta -Pavoqado.evidencia" })
    private val servidor = File(checkNotNull(System.getProperty("avoqado.servidor")) { "falta -Pavoqado.servidor" })
    private val correo = checkNotNull(System.getProperty("avoqado.e2e.correo")) { "falta -Pavoqado.e2e.correo (usuario de la base LOCAL)" }
    private val clave = checkNotNull(System.getProperty("avoqado.e2e.clave")) { "falta -Pavoqado.e2e.clave" }
    private val carpeta = Files.createTempDirectory("Avoqado POS e2e ñ")
    private val resumen = StringBuilder()
    private val procesos = mutableListOf<Process>()

    private data class Fila(val id: String, val centavos: Int, val estado: String)

    private fun fase(proxy: ProxyCortable, vararg args: String): Process =
        ProcessBuilder(
            System.getProperty("avoqado.java"), "-Djava.awt.headless=true",
            "-Davoqado.datos=$carpeta", "-Davoqado.api=http://127.0.0.1:${proxy.puerto}/api/v1",
            "-Davoqado.evidencia=$evidencia",
            "-Davoqado.e2e.correo=$correo", "-Davoqado.e2e.clave=$clave",
            "-cp", System.getProperty("avoqado.classpathPruebas"),
            "com.avoqado.pos.escritorio.e2e.FasesE2EKt", *args,
        ).redirectErrorStream(true).redirectOutput(bitacoraDe(*args)).start().also { procesos += it }

    private fun bitacoraDe(vararg args: String) = File(evidencia, "e2e-fase-${args.joinToString("-")}.log")

    private fun esperarLinea(fase: Process, linea: String, seg: Long, vararg args: String) {
        val log = bitacoraDe(*args)
        val hasta = System.nanoTime() + SECONDS.toNanos(seg)
        while (System.nanoTime() < hasta) {
            val viva = fase.isAlive   // ANTES de leer: si ya murió, lo leído es todo lo que escribió
            val lineas = if (log.exists()) log.readLines() else emptyList()
            if (lineas.any { it.startsWith(linea) }) return
            lineas.firstOrNull { it.startsWith("FASE_FALLO") }?.let { error("La fase ${args.toList()} falló: $it — ver ${log.path}") }
            check(viva) { "La fase ${args.toList()} murió sin avisar, código ${fase.exitValue()} — ver ${log.path}" }
            Thread.sleep(500)
        }
        error("La fase ${args.toList()} no llegó a «$linea» en $seg s — ver ${log.path}")
    }

    /** La cola del cobro rápido, leída directo del archivo de la app (no a través de la app). */
    private fun cola(): List<Fila> =
        BundledSQLiteDriver().open(carpeta.resolve("databases/avoqado_db").toString()).use { c ->
            c.prepare("SELECT id, amountCents, syncStatus FROM pending_payments ORDER BY createdAt").use { st ->
                buildList { while (st.step()) add(Fila(st.getText(0), st.getLong(1).toInt(), st.getText(2))) }
            }
        }

    private fun esperarEstado(id: String, estado: String, seg: Long): String? {
        val hasta = System.nanoTime() + SECONDS.toNanos(seg)
        var visto: String? = null
        while (System.nanoTime() < hasta) {
            visto = cola().firstOrNull { it.id == id }?.estado
            if (visto == estado) return visto
            Thread.sleep(2_000)
        }
        return visto
    }

    private class Conexion(val host: String, val puerto: Int, val usuario: String?, val clave: String?, val base: String)

    /**
     * Las partes del DATABASE_URL del backend. 🔴 Ningún mensaje lleva la cadena: si no se puede interpretar se lanza un
     * error SIN ella ni su causa (la de `URI` la repite entera, contraseña incluida).
     */
    private fun conexion(): Conexion {
        val url = File(servidor, ".env").readLines().firstOrNull { it.startsWith("DATABASE_URL=") }
            ?.substringAfter('=')?.trim()?.trim('"')
        val c = runCatching {
            val u = URI(url!!.replaceFirst(Regex("^postgres(ql)?://"), "http://"))
            val info = u.userInfo
            Conexion(
                host = u.host!!, puerto = if (u.port == -1) 5432 else u.port,
                usuario = info?.substringBefore(':')?.ifEmpty { null },
                clave = info?.takeIf { ':' in it }?.substringAfter(':'),
                base = u.path.removePrefix("/").ifEmpty { null }!!,
            )
        }.getOrNull() ?: throw IllegalStateException("DATABASE_URL de avoqado-server/.env no se pudo interpretar")
        check(c.host in setOf("localhost", "127.0.0.1", "::1", "[::1]")) { "El DATABASE_URL de avoqado-server no es local (${c.host}): no se consulta" }
        return c
    }

    /**
     * SÓLO SELECT (la sesión es de sólo lectura), y sólo si el DATABASE_URL del backend es de esta Mac. Nunca se imprime:
     * la contraseña viaja en `PGPASSWORD` del proceso hijo, no en sus argumentos (los argumentos los ve cualquiera con `ps`).
     */
    private fun psql(sql: String): String {
        val c = conexion()
        val binario = listOf("/opt/homebrew/bin/psql", "/usr/local/bin/psql").firstOrNull { File(it).canExecute() } ?: "psql"
        val argumentos = listOfNotNull(binario, "-h", c.host.trim('[', ']'), "-p", c.puerto.toString()) +
            (c.usuario?.let { listOf("-U", it) } ?: emptyList()) +
            listOf("-d", c.base, "-w", "-At", "-v", "ON_ERROR_STOP=1", "-c", sql)
        check(c.clave.isNullOrEmpty() || argumentos.none { c.clave in it }) { "la contraseña no puede ir en los argumentos de psql" }
        val salida = File.createTempFile("psql", ".txt")
        val p = ProcessBuilder(argumentos).apply {
            environment()["PGCONNECT_TIMEOUT"] = "10"
            environment()["PGOPTIONS"] = "-c default_transaction_read_only=on -c statement_timeout=15000"
            c.clave?.let { environment()["PGPASSWORD"] = it }
        }.redirectErrorStream(true).redirectOutput(salida).start()
        check(p.waitFor(30, SECONDS)) { p.destroyForcibly(); "psql no terminó en 30 s" }
        check(p.exitValue() == 0) { "psql falló: ${salida.readText()}" }
        return salida.readText().trim().also { salida.delete() }
    }

    private fun logActivo(): File = servidor.resolve("logs")
        .listFiles { f -> f.name.startsWith("development") && f.name.endsWith(".log") }!!.maxBy { it.lastModified() }

    @Test fun `efectivo con red, sin red con la app viva, y sin red con la app muerta llegan una sola vez al servidor`() {
        evidencia.mkdirs()
        // Sin restos de una corrida anterior: una captura vieja pasaría por evidencia de ésta. (Las del humo, no.)
        evidencia.listFiles { f ->
            (f.name.startsWith("e2e-") && f.name.endsWith(".png")) || (f.name.startsWith("e2e-fase-") && f.name.endsWith(".log")) ||
                f.name == "e2e-resumen.txt"
        }?.forEach { it.delete() }
        var paso = false
        // Hora de la base en UTC: Prisma guarda "createdAt" como timestamp sin zona, en UTC.
        val t0 = psql("SELECT (now() AT TIME ZONE 'UTC')::text")
        val logInicio = logActivo().let { it to it.readLines().size }
        try {
            ProxyCortable("127.0.0.1", 3000).use { proxy ->
                // 1. Con red
                val enLinea = fase(proxy, "en-linea")
                esperarLinea(enLinea, "FASE_OK", 240, "en-linea")
                check(enLinea.waitFor(30, SECONDS) && enLinea.exitValue() == 0) { "la fase en-linea no salió limpia" }
                resumen.appendLine("con red: cola local = ${cola()}")
                assertTrue(cola().none { it.estado != "SYNCED" }, "con red nada queda pendiente ni fallido: ${cola()}")

                // 2. Sin red; la red vuelve con la app ABIERTA
                proxy.cortar()
                val viva = fase(proxy, "sin-red", "8732")
                esperarLinea(viva, "ENCOLADO", 180, "sin-red", "8732")
                val k1 = cola().single { it.centavos == 8732 }
                assertEquals("PENDING", k1.estado, "sin red el cobro se guarda en el aparato ANTES de mandarse")
                resumen.appendLine("sin red, app viva: ${k1.id} ${k1.estado}")
                proxy.restaurar()
                assertEquals("SYNCED", esperarEstado(k1.id, "SYNCED", 120), "la red volvió con la app abierta y el cobro no se mandó solo")
                resumen.appendLine("sin red + red de vuelta con la app abierta: ${k1.id} SYNCED")
                viva.destroy(); viva.waitFor(15, SECONDS)

                // 3. Sin red; kill -9; la red vuelve; se reabre
                proxy.cortar()
                val muere = fase(proxy, "sin-red", "8733")
                esperarLinea(muere, "ENCOLADO", 180, "sin-red", "8733")
                val k2 = cola().single { it.centavos == 8733 }
                assertEquals("PENDING", k2.estado)
                resumen.appendLine("sin red, antes del kill -9: ${k2.id} ${k2.estado}")
                muere.destroyForcibly().waitFor(10, SECONDS)
                proxy.restaurar()
                val reabierta = fase(proxy, "reabrir")
                esperarLinea(reabierta, "ABIERTA", 120, "reabrir")
                assertEquals("SYNCED", esperarEstado(k2.id, "SYNCED", 120), "al reabrir con red el cobro encolado no se mandó")
                resumen.appendLine("sin red + kill -9 + reabrir con red: ${k2.id} SYNCED")
                resumen.appendLine("cola local al final = ${cola()}")
                assertTrue(cola().none { it.estado == "FAILED" }, "ningún cobro puede quedar FAILED: ${cola()}")

                // 4. Postgres: exactamente UN pago por llave, COMPLETED, con su importe; y uno solo de $87.31 desde t0.
                for ((fila, monto) in listOf(k1 to "87.32", k2 to "87.33")) {
                    val r = psql("""SELECT count(*) || '|' || coalesce(string_agg(status::text, ','), '') || '|' || coalesce(string_agg(amount::text, ','), '') FROM "Payment" WHERE "idempotencyKey" = '${fila.id}'""")
                    resumen.appendLine("Postgres ${fila.id}: $r")
                    assertEquals("1|COMPLETED|$monto", r, "pago ${fila.id}")
                }
                val enLineaPg = psql("""SELECT count(*) || '|' || coalesce(string_agg(status::text, ','), '') FROM "Payment" WHERE amount = 87.31 AND method = 'CASH' AND "createdAt" >= '$t0'::timestamp""")
                resumen.appendLine("Postgres \$87.31 desde $t0: $enLineaPg")
                assertEquals("1|COMPLETED", enLineaPg)

                // 5. Log del backend: los POST …/fast de esta corrida (el replay del cobro rápido va a /fast, no a /sync).
                val (archivo, desde) = logInicio
                val nuevas = (if (logActivo() == archivo) archivo.readLines().drop(desde)
                              else archivo.readLines().drop(desde) + logActivo().readLines())
                val fast = nuevas.filter { "Request End: POST" in it && "/fast" in it }
                resumen.appendLine("Log del backend:\n" + fast.joinToString("\n") { it.substringBefore(" {") })
                // EXACTAMENTE 3 (el cobro en línea y los dos replays): uno de más sería un cobro duplicado. Se cuentan sólo los
                // de ESTA app —su correlationId está en sus bitácoras (cabecera X-Correlation-ID)—, porque el backend es
                // compartido y otra sesión puede cobrar en el mismo venue a la misma hora.
                val nuestros = evidencia.listFiles { f -> f.name.startsWith("e2e-fase-") }!!.flatMap { it.readLines() }
                    .mapNotNull { Regex("""X-Correlation-ID: ([0-9a-f-]{36})""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }.toSet()
                val fast2xx = fast.filter { l -> Regex(""" - 2\d\d """).containsMatchIn(l) && nuestros.any { it in l } }
                resumen.appendLine("POST /fast 2xx de esta app en el log: ${fast2xx.size} (de ${fast.size} POST /fast en total)")
                assertEquals(3, fast2xx.size, "el log debe tener EXACTAMENTE 3 POST /fast 2xx de esta app: ${fast2xx.joinToString("\n")}")
            }
            paso = true
        } finally {
            procesos.forEach { it.destroyForcibly().waitFor(10, SECONDS) }
            // La carpeta de datos trae la sesión de la app (JWT local en claro): se borra DESPUÉS de matar las fases. Si la
            // corrida falló se conserva para investigar, y el resumen dice dónde.
            if (paso) carpeta.toFile().deleteRecursively()
            else resumen.appendLine("FALLÓ: la carpeta de datos se conservó en $carpeta (tiene la sesión: bórrala al terminar)")
            File(evidencia, "e2e-resumen.txt").writeText(resumen.toString())
            // La bitácora HTTP de la app (BuildConfig.DEBUG = cuerpo completo) trae tokens, la contraseña del login y —en
            // la respuesta de /fast— el hash de la contraseña del personal: se tachan antes de que alguien comparta la evidencia.
            evidencia.listFiles { f -> f.name.startsWith("e2e-fase-") }?.forEach { f ->
                f.writeText(
                    f.readText()
                        .replace(Regex("\"(accessToken|refreshToken|password|resetToken|emailVerificationCode|token)\":\"[^\"]*\""), "\"\$1\":\"<tachado>\"")
                        .replace(Regex("""eyJ[\w-]+\.[\w-]+\.[\w-]+"""), "<jwt tachado>"),
                )
            }
        }
    }
}
