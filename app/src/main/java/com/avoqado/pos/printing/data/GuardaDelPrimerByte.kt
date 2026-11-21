package com.avoqado.pos.printing.data

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * D661 · Rulings 50 y 81: el permiso de mandar el PRIMER byte de un trabajo de impresión. Viaja en el contexto de la corrutina: quien imprime
 * algo que sólo vale un tiempo (la comanda de tarjeta de «Nadie» y su reclamo de 90 s) lo pone. Se pregunta UNA vez (su primer «sí» se
 * queda), justo antes del primer byte y después de toda espera: [PrinterService] para WiFi y Bluetooth, ya con su turno y su conexión; la
 * USB y la integrada, dentro de su transporte, tras su propia preparación (la cola de WINDOWS abre el documento y la página; la Sunmi inicia
 * el cabezal). Si dice que no, no sale nada: [PermisoParaImprimirVencido]. Sin guarda en el contexto (recibos, cortes, todo lo demás) nada
 * cambia.
 */
class GuardaDelPrimerByte(val puedeEscribir: () -> Boolean) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<GuardaDelPrimerByte>
}

/** El trabajo esperó su impresora más de lo que dura su permiso: no salió ni un byte. */
class PermisoParaImprimirVencido : Exception("El permiso para imprimir venció mientras se esperaba la impresora")
