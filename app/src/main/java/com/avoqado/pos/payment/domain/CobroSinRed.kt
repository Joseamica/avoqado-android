package com.avoqado.pos.payment.domain

import com.avoqado.pos.core.data.lan.LeaseOutcome
import com.avoqado.pos.core.util.formatMoneyFromCents
import com.avoqado.pos.giftcards.domain.TextosDeTarjetas
import com.avoqado.pos.orders.data.CuentaGuardada
import com.avoqado.pos.orders.data.RazonSinRed
import com.avoqado.pos.orders.data.model.estaAbierta
import com.avoqado.pos.orders.data.razonParaNoCobrar

/** D12: textos de la cuenta abierta sin red. Los de ANDROID son los oficiales (iOS los copia); las Tareas 7, 9 y 11 agregan los suyos. */
object TextosDeCuentaSinRed {
    /** La lista o el detalle vienen de la copia guardada; [hora] en la zona del negocio (`horaDeLaCopia`). */
    fun copiaDeLas(hora: String) = "Sin conexión · como estaba a las $hora"

    /**
     * El cobro hecho con la copia subió, pero la cuenta había crecido en otra caja: [cents] = saldo del servidor − saldo esperado.
     * El texto supone saldo esperado 0 (la compuerta de abonos está cerrada y la Tarea 9 manda 0): ahí la diferencia ES lo que se
     * debe. Con un esperado > 0 diría sólo lo de más, no el total.
     */
    fun quedoDebiendo(cents: Int) = "La cuenta quedó debiendo ${com.avoqado.pos.core.util.formatMoneyFromCents(cents)}"

    const val SE_ABRIO_EN_OTRA_CAJA = "Sin conexión: esta cuenta se abrió en otra caja. Cóbrala ahí o cuando vuelva la conexión."
    const val YA_SUBIO_UN_COBRO = "Sin conexión: esta caja ya subió un cobro de esta cuenta. Vuelve a consultarla cuando regrese la conexión."
    const val SIN_SALDO = "Esta cuenta no tiene saldo pendiente."
    const val SIN_PERMISO = "No tienes permiso para cobrar. Pídeselo a tu gerente."
    const val CON_ABONO = "Esta cuenta ya tiene un abono: cóbrala con internet."

    /** Codex r3 H3: la caja ajena perdió el candado antes de recibir el efectivo. */
    const val CANDADO_PERDIDO = "Se perdió el contacto con las otras cajas: esta cuenta no se cobró aquí."

    /** El texto de cada gris que sale de la FILA. */
    fun de(r: RazonSinRed): String = when (r) {
        RazonSinRed.NO_ES_DE_MOSTRADOR, RazonSinRed.SIN_SALDO_DEL_SERVIDOR -> TextosDeTarjetas.COBRAR_CUENTA_SIN_RED
        RazonSinRed.TARJETAS_DE_REGALO -> TextosDeTarjetas.SIN_RED
        RazonSinRed.SIN_SALDO -> SIN_SALDO
        RazonSinRed.CON_ABONO -> CON_ABONO
    }

    fun laEstaCobrando(quien: String) = "La está cobrando $quien"
    fun cobrarDeTodosModos(quien: String) = "$quien la está cobrando. ¿Cobrar aquí de todos modos?"
    fun cobrarEnEfectivo(cents: Int) = "Cobrar ${formatMoneyFromCents(cents)} en efectivo"

    /** El botón mientras se pide el candado a las otras cajas (puede tardar unos segundos; nunca en rojo). */
    const val REVISANDO = "Revisando…"
    const val COBRAR_AQUI = "Cobrar aquí"
    const val VOLVER = "Volver"

    /** Por qué el cobro de una cuenta hecho con la copia es sólo efectivo (`OrigenDelCobro.CUENTA_ABIERTA_DESDE_LA_COPIA`). */
    const val SOLO_EFECTIVO = "Sin conexión: esta cuenta se cobra sólo en efectivo."
}

/** La llave del candado del hub LAN: texto opaco, el cable no cambia. */
fun llaveDeCuenta(orderId: String): String = "cuenta:$orderId"

data class DatosDelCobroSinRed(
    /** null = no hay copia VIGENTE de la cuenta. */
    val cuenta: CuentaGuardada?,
    val puedeCobrar: Boolean,
    val abiertaAqui: Boolean,
    /** Un cobro local pendiente de la cuenta, O una fila REVISAR de PayLaterQueue para su orden. */
    val cobroLocalPendiente: Boolean,
    /** `CashPaymentRepository.huboEfectivoSubido(orderId)` TAL CUAL, estricto (Codex r2 #2). */
    val huboEfectivoSubido: Boolean,
    val premiumAcreditado: Boolean,
    val hayOtraCajaDelNegocio: Boolean,
    val hubEncendido: Boolean,
)

sealed interface DecisionSinRed {
    data class Cobrar(val saldoCents: Int, val pedirCandado: Boolean, val confirmar: String? = null) : DecisionSinRed
    data class Gris(val texto: String) : DecisionSinRed
}

/** Quién puede cobrar sin red una cuenta abierta. Pura. [candado] null = aún no se pide (antes del toque). */
fun decidirCobroSinRed(d: DatosDelCobroSinRed, candado: LeaseOutcome?): DecisionSinRed {
    val c = d.cuenta?.cuenta
    if (c == null || !estaAbierta(c.status, c.paymentStatus, c.refundState)) {
        return DecisionSinRed.Gris(TextosDeTarjetas.COBRAR_CUENTA_SIN_RED)
    }
    razonParaNoCobrar(c)?.let { return DecisionSinRed.Gris(TextosDeCuentaSinRed.de(it)) }
    val saldo = c.saldoPendienteCents ?: return DecisionSinRed.Gris(TextosDeTarjetas.COBRAR_CUENTA_SIN_RED)
    if (!d.puedeCobrar) return DecisionSinRed.Gris(TextosDeCuentaSinRed.SIN_PERMISO)
    if (d.cobroLocalPendiente) return DecisionSinRed.Gris(TextosDeTarjetas.COBRO_SIN_SINCRONIZAR)
    if (d.huboEfectivoSubido) return DecisionSinRed.Gris(TextosDeCuentaSinRed.YA_SUBIO_UN_COBRO)
    // Degradar, nunca bloquear: quien abrió la cuenta siempre cobra, a lo más con aviso.
    if (d.abiertaAqui) {
        val aviso = (candado as? LeaseOutcome.Taken)?.let { TextosDeCuentaSinRed.cobrarDeTodosModos(it.holderName) }
        return DecisionSinRed.Cobrar(saldo, pedirCandado = d.hubEncendido, confirmar = aviso)
    }
    if (!d.premiumAcreditado || !d.hayOtraCajaDelNegocio) return DecisionSinRed.Gris(TextosDeCuentaSinRed.SE_ABRIO_EN_OTRA_CAJA)
    return when (candado) {
        null, is LeaseOutcome.Granted -> DecisionSinRed.Cobrar(saldo, pedirCandado = true)
        is LeaseOutcome.Taken -> DecisionSinRed.Gris(TextosDeCuentaSinRed.laEstaCobrando(candado.holderName))
        LeaseOutcome.NoHub -> DecisionSinRed.Gris(TextosDeCuentaSinRed.SE_ABRIO_EN_OTRA_CAJA)
    }
}
