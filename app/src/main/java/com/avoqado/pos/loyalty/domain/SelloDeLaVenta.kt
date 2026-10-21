package com.avoqado.pos.loyalty.domain

import com.avoqado.pos.loyalty.data.SaleStampOutcome
import com.avoqado.pos.loyalty.data.SaleStampReason

/**
 * Textos del renglón del sello en «Cobro exitoso». 🔴 Espejo EXACTO de iOS (mismo contrato): cambiar uno aquí sin
 * cambiarlo allá hace que el cajero lea cosas distintas según el aparato.
 */
object TextosDelSello {
    const val PREMIO_POR_COBRAR = "🎁 ¡Completó su tarjeta! Tiene un premio por cobrar"
    const val SELLO_SUMADO_SIN_CUENTA = "✓ +1 sello"
    const val OTRO_CLIENTE = "Esta venta ya sumó su sello a otro cliente"
    const val YA_RECIBIO_HOY = "Ya recibió su sello de hoy"
    const val FUERA_DE_PLAZO = "Pasaron más de 24 h: esta venta no suma sello"
    const val NO_PAGADA = "Esta venta no está pagada completa: no suma sello"
    const val SE_SUMA_AL_SINCRONIZAR = "El sello se suma al sincronizar"
    const val SIN_RED_AL_AGREGAR_CLIENTE =
        "Sin conexión: agrégalo cuando vuelva la red. Tienes 24 h para que sume el sello."

    /** Venta encolada que aún no sube: sin el id que le dé el servidor no hay a qué pago ligar al cliente. */
    const val VENTA_SIN_SINCRONIZAR =
        "Esta venta aún no se sincroniza. Agrega el cliente cuando aparezca «Venta sincronizada»."

    fun selloSumado(stampsEarned: Int, stampsRequired: Int): String =
        "✓ +1 sello · $stampsEarned de $stampsRequired"

    // MARK: - Tarjetita de sellos (21-oct)
    const val TITULO_SUMO = "+1 sello"
    const val TITULO_COMPLETA = "¡Completó su tarjeta!"

    fun cuenta(llenos: Int, total: Int): String = "$llenos de $total"

    fun faltan(faltan: Int, premio: String?): String =
        if (premio.isNullOrBlank()) "Faltan $faltan para el premio" else "Faltan $faltan para: $premio"

    fun premioPorCobrar(premio: String?): String =
        if (premio.isNullOrBlank()) "Tiene un premio por cobrar" else "Tiene un premio por cobrar: $premio"

    /** Completó, pero el premio ya se cobró (p. ej. desde otro aparato): no se promete «por cobrar». */
    fun premioGanado(premio: String?): String = if (premio.isNullOrBlank()) "Ganó su premio" else "Ganó: $premio"
}

/**
 * La tarjetita de «Cobro exitoso» cuando la venta SÍ sumó: [llenos] de [total] de SU cartilla y el premio. [completa] =
 * esa cartilla ya está llena; [porCobrar] = y el premio sigue sin cobrar.
 */
data class TarjetaDelSello(
    val llenos: Int,
    val total: Int,
    val completa: Boolean,
    val premio: String?,
    val porCobrar: Boolean = false,
) {
    val titulo: String get() = if (completa) TextosDelSello.TITULO_COMPLETA else TextosDelSello.TITULO_SUMO
    val cuenta: String get() = TextosDelSello.cuenta(llenos, total)
    val pie: String
        get() = when {
            !completa -> TextosDelSello.faltan(total - llenos, premio)
            porCobrar -> TextosDelSello.premioPorCobrar(premio)
            else -> TextosDelSello.premioGanado(premio)
        }

    /** Lo que lee TalkBack, en una sola frase. */
    val descripcion: String get() = "$titulo. $cuenta sellos. $pie"
}

/**
 * null = no se pinta tarjetita y queda el renglón de texto: no sumó, sellos apagados, servidor viejo sin `saleCard`, o
 * una cartilla sin tamaño. 🔴 Nunca se deduce de `stampsEarned` (es la cartilla abierta HOY, no la de la venta).
 */
fun tarjetaDelSello(outcome: SaleStampOutcome?): TarjetaDelSello? {
    if (outcome == null || !outcome.stampsEnabled || !outcome.stamped) return null
    val cartilla = outcome.saleCard ?: return null
    val total = cartilla.stampsRequired
    if (total < 1) return null
    val completa = cartilla.completed
    return TarjetaDelSello(
        llenos = if (completa) total else cartilla.stampsEarned.coerceIn(0, total),
        total = total,
        completa = completa,
        premio = cartilla.rewardLabel?.trim()?.takeIf { it.isNotEmpty() },
        porCobrar = completa && cartilla.rewardPending,
    )
}

// MARK: - ¿Caben los círculos? (espejo EXACTO de iOS: mismos números)
const val MAX_CIRCULOS_DE_SELLO = 12
const val SEPARACION_DE_SELLOS = 4f
const val SELLO_MINIMO = 22f
const val SELLO_MAXIMO = 30f

/**
 * Tamaño de cada círculo (en dp/pt) si los [total] sellos más el premio caben en [ancho]; null = no caben y va la
 * barra. Con más de [MAX_CIRCULOS_DE_SELLO] siempre barra: 20 puntitos no se leen aunque quepan.
 */
fun tamanoDeSello(ancho: Float, total: Int): Float? {
    if (total < 1 || total > MAX_CIRCULOS_DE_SELLO) return null
    val tamano = (ancho - SEPARACION_DE_SELLOS * total) / (total + 1)
    return if (tamano < SELLO_MINIMO) null else tamano.coerceAtMost(SELLO_MAXIMO)
}

/**
 * Qué texto se pinta para el sello de una venta. null = NADA: programa de sellos apagado, servidor viejo sin el campo,
 * o un motivo que no le dice nada al cajero (PENDING tras el reintento, ERROR, o uno que esta versión no conoce).
 */
fun textoDelSello(outcome: SaleStampOutcome?): String? {
    if (outcome == null || !outcome.stampsEnabled) return null
    if (outcome.stamped) {
        // Resuelto por el coordinador: la cartilla recién COMPLETADA deja abierta la nueva en 0 (sólo pasa al completar).
        // `rewardReady` puede venir de un premio viejo sin cobrar, así que no decide el texto cuando hay cuenta. Pero con
        // 0 y SIN premio pendiente no se promete premio (Codex r2 #1, 21-oct): otra venta llenó la cartilla y su premio
        // ya se cobró. Ahí sólo se dice que sumó.
        return if (outcome.stampsEarned == 0) {
            if (outcome.rewardReady) TextosDelSello.PREMIO_POR_COBRAR else TextosDelSello.SELLO_SUMADO_SIN_CUENTA
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
data class RenglonDelSello(val texto: String, val positivo: Boolean, val tarjeta: TarjetaDelSello? = null) {
    companion object {
        fun desde(outcome: SaleStampOutcome?): RenglonDelSello? =
            textoDelSello(outcome)?.let {
                RenglonDelSello(it, positivo = outcome?.stamped == true, tarjeta = tarjetaDelSello(outcome))
            }

        /** Venta en efectivo encolada sin red, con cliente: el servidor da el sello al reproducirla. */
        val SE_SUMA_AL_SINCRONIZAR = RenglonDelSello(TextosDelSello.SE_SUMA_AL_SINCRONIZAR, positivo = false)
    }
}
