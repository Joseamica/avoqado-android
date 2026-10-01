package com.avoqado.pos.escritorio.base

import android.util.Log
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private const val RESPALDOS_QUE_QUEDAN = 3
private val FORMATO = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

private fun copiaAtomica(origen: Path, destino: Path) { Files.copy(origen, destino, StandardCopyOption.REPLACE_EXISTING) }

/**
 * Si [base] existe y su PRAGMA user_version es > 0 y < [versionDeLaApp], la respalda y devuelve la ruta; si no, null.
 * Respaldo = checkpoint COMPLETO del WAL (si no se completa, lanza) + copia a un `.parcial` que se publica con un
 * movimiento atómico a `<carpeta de la base>/respaldos/avoqado_db-v<vieja>-<secuencia de 6 dígitos>-<yyyyMMdd-HHmmss>`.
 * Nunca reemplaza un respaldo publicado. Se quedan los 3 de secuencia más alta (el orden NO depende del reloj).
 * Si no se puede listar `respaldos/`, lanza ANTES de copiar: sin la lista, la secuencia volvería a empezar y en Windows
 * el movimiento atómico reemplaza un archivo existente. [copiar] y [listar] existen para probar esos fallos.
 */
fun respaldarSiVaAMigrar(
    base: File,
    versionDeLaApp: Int,
    ahora: LocalDateTime = LocalDateTime.now(),
    listar: (File) -> Array<File>? = { it.listFiles() },
    copiar: (Path, Path) -> Unit = ::copiaAtomica,
): File? {
    if (!base.isFile) return null
    val vieja = BundledSQLiteDriver().open(base.path).use { c ->
        val v = c.prepare("PRAGMA user_version").use { it.step(); it.getLong(0).toInt() }
        if (v <= 0 || v >= versionDeLaApp) return null
        // Sin un checkpoint completo la copia perdería lo que todavía vive en el -wal (los últimos cobros).
        c.prepare("PRAGMA wal_checkpoint(TRUNCATE)").use {
            check(it.step()) { "wal_checkpoint no devolvió resultado" }
            val ocupado = it.getLong(0); val enLog = it.getLong(1); val pasadas = it.getLong(2)
            check(ocupado == 0L && (enLog == -1L || enLog == pasadas)) {
                "No se pudo completar el checkpoint del WAL (busy=$ocupado, log=$enLog, checkpointed=$pasadas): hay otra conexión leyendo la base"
            }
        }
        v
    }
    val carpeta = File(base.parentFile, "respaldos").apply { mkdirs() }
    listar(carpeta)?.filter { it.name.endsWith(".parcial") }?.forEach { if (!it.delete()) Log.w("Base", "No se pudo borrar ${it.name}") }

    val formato = Regex("""${Regex.escape(base.name)}-v\d+-(\d{6})-\d{8}-\d{6}""")
    fun publicados(archivos: Array<File>): List<Pair<Int, File>> =
        archivos.mapNotNull { f -> formato.matchEntire(f.name)?.let { it.groupValues[1].toInt() to f } }

    val existentes = listar(carpeta) ?: throw IOException("No se pudo listar ${carpeta.path}: no se respalda para no pisar un respaldo")
    val secuencia = (publicados(existentes).maxOfOrNull { it.first } ?: 0) + 1
    val destino = File(carpeta, "${base.name}-v$vieja-%06d-${ahora.format(FORMATO)}".format(secuencia))
    if (destino.exists()) throw IOException("Ya existe ${destino.name}: no se reemplaza un respaldo")
    val parcial = File(carpeta, "${destino.name}.parcial")
    try {
        copiar(base.toPath(), parcial.toPath())
        Files.move(parcial.toPath(), destino.toPath(), StandardCopyOption.ATOMIC_MOVE)
    } catch (e: Throwable) {
        if (parcial.exists() && !parcial.delete()) Log.w("Base", "No se pudo borrar ${parcial.name}")
        throw e
    }
    val despues = listar(carpeta) ?: return destino.also { Log.w("Base", "No se pudo listar ${carpeta.path} para podar") }
    publicados(despues).sortedByDescending { it.first }.drop(RESPALDOS_QUE_QUEDAN).forEach { (_, f) ->
        if (f != destino && !f.delete()) Log.w("Base", "No se pudo borrar el respaldo viejo ${f.name}")
    }
    return destino
}
