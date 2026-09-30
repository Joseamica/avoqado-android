package com.avoqado.escritorio

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE

/**
 * Una sola app por carpeta de datos: dos procesos reproducirían la misma cola de cobros dos veces.
 * El sistema operativo suelta el candado cuando el proceso muere, aunque sea a la fuerza.
 */
object CandadoDeInstancia {
    @Volatile private var vivo: FileLock? = null   // referencia fuerte: si el GC cierra el canal, se suelta el candado

    fun tomar(carpeta: Path): Boolean {
        val canal = FileChannel.open(carpeta.resolve(".instancia.lock"), CREATE, WRITE)
        val candado = try { canal.tryLock() } catch (e: OverlappingFileLockException) { null }
        if (candado == null) { canal.close(); return false }
        vivo = candado
        return true
    }
}
