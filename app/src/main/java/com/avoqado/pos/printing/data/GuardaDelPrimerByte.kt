package com.avoqado.pos.printing.data

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * D661 · Ruling 50: el permiso de mandar el PRIMER byte de un trabajo de impresión. Viaja en el contexto de la corrutina: quien imprime algo
 * que sólo vale un tiempo (la comanda de un pedido en línea y su reclamo de 90 s) lo pone, y [PrinterService] lo pregunta justo antes de
 * escribir, después de toda espera (el turno de la impresora, la conexión, el permiso USB). Si dice que no, no sale nada:
 * [PermisoParaImprimirVencido]. Sin guarda en el contexto (recibos, cortes, todo lo demás) nada cambia.
 */
class GuardaDelPrimerByte(val puedeEscribir: () -> Boolean) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<GuardaDelPrimerByte>
}

/** El trabajo esperó su impresora más de lo que dura su permiso: no salió ni un byte. */
class PermisoParaImprimirVencido : Exception("El permiso para imprimir venció mientras se esperaba la impresora")
