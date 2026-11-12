package com.avoqado.pos.payment.domain

import com.avoqado.pos.core.data.lan.CuentaEnElRegistro
import com.avoqado.pos.core.data.lan.CuentasEntreCajas
import com.avoqado.pos.core.data.lan.EstadoDeCuenta
import com.avoqado.pos.core.data.lan.Respuesta
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

    /** T12b (ronda 1, I1): el SERVIDOR contestó mal (5xx, 403…) y no hay nada que enseñar: la razón, nunca «No hay pedidos». */
    const val NO_SE_CARGARON = "No se pudieron cargar los pedidos"

    /** T12b: la lista se quedó como estaba porque el SERVIDOR contestó mal (5xx, 403…): no es «Sin conexión». */
    fun noSeCargaronDeLas(hora: String) = "$NO_SE_CARGARON · como estaba a las $hora"

    /** Final fix (F3): al volver la red, el servidor contestó mal (5xx) al pedido que se ve: se queda como estaba, no es «Sin conexión». */
    fun pedidoNoSeCargoDeLas(hora: String) = "No se pudo cargar el pedido · como estaba a las $hora"

    /** T12b (ronda 1, M1): sin red y la copia no trae cuentas: lo dice, nunca un «No hay pedidos» a secas. */
    const val SIN_CUENTAS_GUARDADAS = "Sin conexión: no hay cuentas abiertas guardadas en este aparato."

    /** T12b: sin red, una búsqueda o un filtro no se puede contestar: la lista se vacía y lo dice (nunca la copia entera como resultado). */
    const val BUSQUEDA_SIN_RED = "Sin conexión: para buscar o filtrar necesitas internet. En «Todos» o «Abiertas», sin búsqueda, ves las cuentas guardadas."

    /** T12b (O3): justo antes del efectivo, la copia ya dice otro saldo que el del carrito: no se cobra. */
    fun saldoCambio(cents: Int) = "El saldo de esta cuenta cambió a ${formatMoneyFromCents(cents)} y no se cobró. Vuelve a abrir la cuenta para cobrarlo."

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


    // MARK: - D12 · Opción D: la caja que abrió la cuenta da el permiso (`opcion-d-brief.md`). <caja> = el nombre de la caja.

    /** Mientras se le pregunta a la caja que la abrió (5 s o menos). */
    fun avisandoA(caja: String) = "Avisándole a $caja…"

    /** Otra caja la tiene apartada o comprometida. */
    fun laEstaCobrandoEn(quien: String, caja: String) = "${quien.ifBlank { "Alguien" }} la está cobrando en $caja. Espera a que termine."

    /** La caja que la abrió no se ve en la red; sin su nombre, [SE_ABRIO_EN_OTRA_CAJA]. */
    fun noLaEncuentro(caja: String?) =
        caja?.let { "Esta cuenta se abrió en $it y no la encuentro en la red. Cóbrala ahí o cuando vuelva la conexión." } ?: SE_ABRIO_EN_OTRA_CAJA

    /** Justo antes del cajón la caja que la abrió no contestó: no se recibe el efectivo. */
    fun sePerdioElContacto(caja: String) =
        "Se perdió el contacto con $caja: esta cuenta no se cobró aquí. No recibas el efectivo. Cóbrala en $caja o cuando vuelva la conexión."

    /** En la caja que la abrió, 2 min después de que otra la comprometió sin avisar «cobrada». */
    fun noConfirmo(quien: String, caja: String) = "${quien.ifBlank { "Alguien" }} empezó a cobrarla en $caja y no confirmó. Pregúntale antes de cobrar."
    const val NO_SE_COBRO_COBRAR_AQUI = "No se cobró: cobrar aquí"

    /** «Avisándole a las otras cajas…» cuando todavía no se sabe cuál la abrió. */
    const val LAS_OTRAS_CAJAS = "las otras cajas"

    /** M1: la cerró una lectura del SERVIDOR (no se sabe en qué caja): su propio chip, nunca el nombre de una caja. */
    const val YA_SE_COBRO = "Ya se cobró"

    /** En la lista (y como razón del gris): ya se cobró en esa caja. */
    fun cobradaEn(caja: String, faltaSubirla: Boolean) = if (faltaSubirla) "Cobrada en $caja · falta subirla" else "Cobrada en $caja"

    /** El texto de cada gris que sale de la FILA. */
    fun de(r: RazonSinRed): String = when (r) {
        RazonSinRed.NO_ES_DE_MOSTRADOR, RazonSinRed.SIN_SALDO_DEL_SERVIDOR -> TextosDeTarjetas.COBRAR_CUENTA_SIN_RED
        RazonSinRed.TARJETAS_DE_REGALO -> TextosDeTarjetas.SIN_RED
        RazonSinRed.SIN_SALDO -> SIN_SALDO
        RazonSinRed.CON_ABONO -> CON_ABONO
    }

    fun cobrarEnEfectivo(cents: Int) = "Cobrar ${formatMoneyFromCents(cents)} en efectivo"

    /** El botón mientras esta caja revisa su propio registro (la caja que la abrió; nunca en rojo). */
    const val REVISANDO = "Revisando…"

    /** Por qué el cobro de una cuenta hecho con la copia es sólo efectivo (`OrigenDelCobro.CUENTA_ABIERTA_DESDE_LA_COPIA`). */
    const val SOLO_EFECTIVO = "Sin conexión: esta cuenta se cobra sólo en efectivo."
}

/**
 * Opción D, en la lista de Pedidos de la caja que la ABRIÓ: «Cobrada en <caja>» (y «· falta subirla») si su registro la da cobrada. null =
 * nada que decir. [saldoPendienteCents]: lo que la fila dice que falta (null = no se sabe). [delServidor]: la lista es la respuesta del
 * SERVIDOR a lo que se pidió (`OrdersViewModel.copiaVistaA` nulo); si no, es la copia (o la última lectura buena) y su saldo es viejo.
 */
fun textoDeLaListaDe(r: CuentaEnElRegistro?, saldoPendienteCents: Int?, delServidor: Boolean): String? {
    if (r?.estado != EstadoDeCuenta.CERRADA) return null
    // M-1 y familia D: un abono no es «Cobrada». Con la lista del SERVIDOR y el cobro ya subido, su saldo dice si queda algo (venga de la
    // caja que venga). Con la copia su saldo es de ANTES del cobro (caso 3 de opcion-d: otra caja la cobró con internet): manda el registro.
    val debe = if (delServidor && !r.faltaSubirla && saldoPendienteCents != null) saldoPendienteCents > 0 else r.conAbono
    if (debe) return null
    return if (r.caja.isBlank()) TextosDeCuentaSinRed.YA_SE_COBRO else TextosDeCuentaSinRed.cobradaEn(r.caja, r.faltaSubirla)
}

/** La llave de una cuenta en la red del local (`CuentasPorLaRed`). */
fun llaveDeCuenta(orderId: String): String = com.avoqado.pos.core.data.lan.CuentasPorLaRed.llave(orderId)

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
    /** Opción D: otra caja de la sucursal que anuncia `d=1` (con el hub Premium encendido aquí). */
    val hayOtraCajaDelNegocio: Boolean,
    /** Opción D: si esta caja la abrió, lo que dice su registro (null = LIBRE). */
    val registro: CuentaEnElRegistro? = null,
    /** Opción D: el registro ya deja declarar «No se cobró» (otra caja la comprometió hace 2 min o más). */
    val puedeDeclararNoSeCobro: Boolean = false,
    /** Opción D: el `deviceId` de esta caja (lo suyo en su registro no la bloquea antes del toque). */
    val miDeviceId: String = "",
    /** Opción D: el nombre de la caja que la abrió, si alguna vez contestó; null = no se sabe. */
    val cajaQueLaAbrio: String? = null,
)

sealed interface DecisionSinRed {
    /** [pedirPermiso]: al tocar se aparta con la caja que la abrió (en local si es ésta). */
    data class Cobrar(val saldoCents: Int, val pedirPermiso: Boolean = true) : DecisionSinRed
    data class Gris(val texto: String) : DecisionSinRed

    /** Opción D, en la caja que la abrió: otra la comprometió y no confirmó en 2 min. Una persona decide con «No se cobró: cobrar aquí». */
    data class NoConfirmo(val texto: String, val saldoCents: Int) : DecisionSinRed
}

/**
 * Quién puede cobrar sin red una cuenta abierta (Opción D: el permiso lo da la caja que la ABRIÓ). Pura. [permiso] null = aún no se pide
 * (antes del toque); si no, lo que contestó la caja que la abrió (o esta misma, en local).
 */
fun decidirCobroSinRed(d: DatosDelCobroSinRed, permiso: CuentasEntreCajas.DelDueno?): DecisionSinRed {
    val c = d.cuenta?.cuenta
    if (c == null || !estaAbierta(c.status, c.paymentStatus, c.refundState)) {
        return DecisionSinRed.Gris(TextosDeTarjetas.COBRAR_CUENTA_SIN_RED)
    }
    razonParaNoCobrar(c)?.let { return DecisionSinRed.Gris(TextosDeCuentaSinRed.de(it)) }
    val saldo = c.saldoPendienteCents ?: return DecisionSinRed.Gris(TextosDeTarjetas.COBRAR_CUENTA_SIN_RED)
    if (!d.puedeCobrar) return DecisionSinRed.Gris(TextosDeCuentaSinRed.SIN_PERMISO)
    if (d.cobroLocalPendiente) return DecisionSinRed.Gris(TextosDeTarjetas.COBRO_SIN_SINCRONIZAR)
    if (d.huboEfectivoSubido) return DecisionSinRed.Gris(TextosDeCuentaSinRed.YA_SUBIO_UN_COBRO)
    if (d.abiertaAqui) {
        val r = d.registro
        return when {
            r?.estado == EstadoDeCuenta.CERRADA ->
                DecisionSinRed.Gris(CuentasEntreCajas.textoDe(Respuesta.Cerrada(r.caja, r.faltaSubirla, r.conAbono), r.caja))
            r != null && r.por != d.miDeviceId && d.puedeDeclararNoSeCobro -> DecisionSinRed.NoConfirmo(TextosDeCuentaSinRed.noConfirmo(r.quien, r.caja), saldo)
            r != null && r.por != d.miDeviceId -> DecisionSinRed.Gris(TextosDeCuentaSinRed.laEstaCobrandoEn(r.quien, r.caja))
            permiso != null -> deLaRespuesta(permiso, saldo)
            else -> DecisionSinRed.Cobrar(saldo)
        }
    }
    if (!d.premiumAcreditado || !d.hayOtraCajaDelNegocio) return DecisionSinRed.Gris(TextosDeCuentaSinRed.noLaEncuentro(d.cajaQueLaAbrio))
    return if (permiso == null) DecisionSinRed.Cobrar(saldo) else deLaRespuesta(permiso, saldo)
}

private fun deLaRespuesta(p: CuentasEntreCajas.DelDueno, saldo: Int): DecisionSinRed = when (val r = p.respuesta) {
    Respuesta.Concedida -> DecisionSinRed.Cobrar(saldo)
    null -> DecisionSinRed.Gris(TextosDeCuentaSinRed.noLaEncuentro(p.caja))
    else -> DecisionSinRed.Gris(CuentasEntreCajas.textoDe(r, p.caja))
}
