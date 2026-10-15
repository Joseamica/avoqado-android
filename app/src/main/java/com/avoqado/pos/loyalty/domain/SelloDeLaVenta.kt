package com.avoqado.pos.loyalty.domain

import com.avoqado.pos.loyalty.data.SaleStampOutcome
import com.avoqado.pos.loyalty.data.SaleStampReason

/**
 * Textos del renglón del sello en «Cobro exitoso». 🔴 Espejo EXACTO de iOS (mismo contrato): cambiar uno aquí sin
 * cambiarlo allá hace que el cajero lea cosas distintas según el aparato.
 */
object TextosDelSello {
    const val PREMIO_POR_COBRAR = "🎁 ¡Completó su tarjeta! Tiene un premio por cobrar"
    const val OTRO_CLIENTE = "Esta venta ya sumó su sello a otro cliente"
    const val YA_RECIBIO_HOY = "Ya recibió su sello de hoy"
    const val FUERA_DE_PLAZO = "Pasaron más de 24 h: esta venta no suma sello"
    const val NO_PAGADA = "Esta venta no está pagada completa: no suma sello"
    const val SE_SUMA_AL_SINCRONIZAR = "El sello se suma al sincronizar"
    const val SIN_RED_AL_AGREGAR_CLIENTE =
        "Sin conexión: agrégalo cuando vuelva la red. Tienes 24 h para que sume el sello."

    fun selloSumado(stampsEarned: Int, stampsRequired: Int): String =
        "✓ +1 sello · $stampsEarned de $stampsRequired"
}

/**
 * Qué texto se pinta para el sello de una venta. null = NADA: programa de sellos apagado, servidor viejo sin el campo,
 * o un motivo que no le dice nada al cajero (PENDING tras el reintento, ERROR, o uno que esta versión no conoce).
 */
fun textoDelSello(outcome: SaleStampOutcome?): String? {
    if (outcome == null || !outcome.stampsEnabled) return null
    if (outcome.stamped) {
        // Resuelto por el coordinador: la cartilla recién COMPLETADA deja abierta la nueva en 0 (sólo pasa al completar).
        // `rewardReady` puede venir de un premio viejo sin cobrar, así que NO decide el texto.
        return if (outcome.stampsEarned == 0) {
            TextosDelSello.PREMIO_POR_COBRAR
        } else {
            TextosDelSello.selloSumado(outcome.stampsEarned, outcome.stampsRequired)
        }
    }
    return when (outcome.reasonCode) {
        SaleStampReason.OTHER_CUSTOMER -> TextosDelSello.OTRO_CLIENTE
        SaleStampReason.DAILY_LIMIT -> TextosDelSello.YA_RECIBIO_HOY
        SaleStampReason.WINDOW_EXPIRED -> TextosDelSello.FUERA_DE_PLAZO
        SaleStampReason.NOT_PAID -> TextosDelSello.NO_PAGADA
        SaleStampReason.PENDING, SaleStampReason.ERROR, null -> null
    }
}

/** El renglón listo para pintar. [positivo] = sí sumó (verde); un motivo es información, no éxito (neutro). */
data class RenglonDelSello(val texto: String, val positivo: Boolean) {
    companion object {
        fun desde(outcome: SaleStampOutcome?): RenglonDelSello? =
            textoDelSello(outcome)?.let { RenglonDelSello(it, positivo = outcome?.stamped == true) }

        /** Venta en efectivo encolada sin red, con cliente: el servidor da el sello al reproducirla. */
        val SE_SUMA_AL_SINCRONIZAR = RenglonDelSello(TextosDelSello.SE_SUMA_AL_SINCRONIZAR, positivo = false)
    }
}
