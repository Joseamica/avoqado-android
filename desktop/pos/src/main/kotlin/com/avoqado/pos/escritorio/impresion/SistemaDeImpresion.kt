package com.avoqado.pos.escritorio.impresion

import com.avoqado.pos.printing.data.model.PrinterException

interface CanalAbierto : AutoCloseable { fun escribir(bytes: ByteArray) }

interface SistemaDeImpresion {
    fun colas(): List<ColaInfo>
    fun cola(nombre: String): ColaInfo?
    fun puertos(): List<PuertoInfo>
    fun imprimirEnCola(nombre: String, documento: String, bytes: ByteArray)
    /** `baudios` null = la velocidad que Windows tiene configurada en ese puerto. */
    fun abrirPuerto(puerto: String, baudios: Int?): CanalAbierto
}

/** Fuera de Windows (la Mac de desarrollo): no hay colas ni puertos, y abrir lo dice. */
object SistemaFueraDeWindows : SistemaDeImpresion {
    override fun colas() = emptyList<ColaInfo>()
    override fun cola(nombre: String): ColaInfo? = null
    override fun puertos() = emptyList<PuertoInfo>()
    override fun imprimirEnCola(nombre: String, documento: String, bytes: ByteArray) =
        throw PrinterException.ConnectionFailed(SOLO_EN_WINDOWS)
    override fun abrirPuerto(puerto: String, baudios: Int?): CanalAbierto =
        throw PrinterException.ConnectionFailed(SOLO_EN_WINDOWS)
}

object ImpresionDeEscritorio {
    @Volatile var sistema: SistemaDeImpresion =
        if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) SistemaWindows() else SistemaFueraDeWindows
}
