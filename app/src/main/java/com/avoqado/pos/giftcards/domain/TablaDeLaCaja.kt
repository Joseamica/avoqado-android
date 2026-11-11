package com.avoqado.pos.giftcards.domain

import com.avoqado.pos.giftcards.data.EstadoDeAjustes

enum class FilaDeLaCaja { T0,T1,T2,T3,T4,T5,T6,T7,T8,T9,T10,T11,T12,T13 }
data class ConductaDeTarjeta(val fila: FilaDeLaCaja, val visible: Boolean, val habilitada: Boolean, val motivo: String?)
enum class OfertaDeLaCuenta { OFRECER, COBRO_SIN_SINCRONIZAR, NADA }
fun ofertaDeLaCuenta(restanteDelServerCents: Int?, cuentaTerminada: Boolean, laTraeUnCarrito: Boolean, cobroLocalPendiente: Boolean): OfertaDeLaCuenta = when {
    cuentaTerminada || restanteDelServerCents == null || restanteDelServerCents <= 0 || laTraeUnCarrito -> OfertaDeLaCuenta.NADA
    cobroLocalPendiente -> OfertaDeLaCuenta.COBRO_SIN_SINCRONIZAR
    else -> OfertaDeLaCuenta.OFRECER
}
/** Sin red, qué se le dice a la cajera de una cuenta abierta. Sólo nombra las tarjetas de regalo si la cuenta las trae. */
fun avisoDeCuentaSinRed(esCuentaDeTarjetas: Boolean, cobrosConSaldo: Int?, sePuedeCobrar: Boolean): String? = when {
    esCuentaDeTarjetas || (cobrosConSaldo ?: 0) >= 1 -> TextosDeTarjetas.SIN_RED
    sePuedeCobrar -> TextosDeTarjetas.COBRAR_CUENTA_SIN_RED
    else -> null
}
fun estadoDeLaVenta(ajustes: EstadoDeAjustes, enLinea: Boolean, puedeCobrar: Boolean, cuentaDeTarjetas: Boolean,
    llevaVales: Boolean, llevaMembresia: Boolean, hayOrdenOArticulos: Boolean, mesaProvisional: Boolean,
    creacionEnDuda: Boolean, vieneDeCarritoGuardado: Boolean, cuentaTrabada: Boolean, enMesa: Boolean,
    desdeCuentasAbiertas: Boolean, cuentaPorPagoPrevio: Boolean, cobroLocalPendiente: Boolean,
    puedeLeer: Boolean = true, puedeCrear: Boolean = true): ConductaDeTarjeta {
    fun oculta(fila: FilaDeLaCaja) = ConductaDeTarjeta(fila, false, false, null)
    fun gris(fila: FilaDeLaCaja, texto: String) = ConductaDeTarjeta(fila, true, false, texto)
    if (cuentaDeTarjetas) return oculta(FilaDeLaCaja.T9)
    if (!puedeLeer || ajustes == EstadoDeAjustes.SinPermiso) return oculta(FilaDeLaCaja.T11)
    if (ajustes !is EstadoDeAjustes.Disponible || !ajustes.ajustes.filaDeCobro) return oculta(FilaDeLaCaja.T12)
    if (!enLinea) return gris(FilaDeLaCaja.T0, TextosDeTarjetas.SIN_RED)
    val nueva = !cuentaPorPagoPrevio && !enMesa && !desdeCuentasAbiertas && !mesaProvisional && !creacionEnDuda && !vieneDeCarritoGuardado
    if (!puedeCobrar || nueva && !puedeCrear) return gris(FilaDeLaCaja.T11, TextosDeTarjetas.SIN_PERMISO_COBRAR)
    if (llevaVales || llevaMembresia || !hayOrdenOArticulos) return gris(FilaDeLaCaja.T10, when { llevaVales -> TextosDeTarjetas.VALES_SIN_SALDO; llevaMembresia -> TextosDeTarjetas.MEMBRESIA_SIN_SALDO; else -> TextosDeTarjetas.VENTA_RAPIDA_SIN_ARTICULOS })
    if (mesaProvisional) return gris(FilaDeLaCaja.T3, TextosDeTarjetas.ESPERA_SINCRONIZAR)
    if (creacionEnDuda) return gris(FilaDeLaCaja.T4, TextosDeTarjetas.VENTA_LOCAL_SIN_COBROS)
    if (vieneDeCarritoGuardado) return gris(FilaDeLaCaja.T6, if (cuentaPorPagoPrevio) TextosDeTarjetas.VENTA_GUARDADA_CON_PAGO else TextosDeTarjetas.VENTA_GUARDADA_SIN_PAGO)
    if (cuentaTrabada || cobroLocalPendiente) return gris(FilaDeLaCaja.T13, if (cuentaTrabada) TextosDeTarjetas.COMPROBANDO_COBRO else TextosDeTarjetas.COBRO_SIN_SINCRONIZAR)
    val fila = when { enMesa -> FilaDeLaCaja.T8; desdeCuentasAbiertas -> FilaDeLaCaja.T7; cuentaPorPagoPrevio -> FilaDeLaCaja.T2; else -> FilaDeLaCaja.T1 }
    return ConductaDeTarjeta(fila, true, true, null)
}

/**
 * Tienda híbrida, parte 2 (A1): el cobro que abre la bandeja «Pedidos en línea». Sólo un folio ofrece saldo (sobre su cuenta, T2); un
 * pedido de plataforma o de «Mi equipo», no. Abierto sin el saldo del servidor (`PEDIDO_EN_LINEA_SIN_SALDO_ACTUALIZADO`), en gris T0
 * aunque la red vuelva con la pantalla abierta: lo que cobraría salió de la lista guardada, como la tarjeta de la terminal. Lo demás
 * (permisos, ajustes, un cobro en duda) queda como en la venta de siempre. PURA.
 */
fun conOrigen(c: ConductaDeTarjeta, origen: com.avoqado.pos.payment.domain.OrigenDelCobro, esFolio: Boolean): ConductaDeTarjeta = when {
    !origen.esPedidoEnLinea -> c
    !origen.ofreceSaldo(esFolio) -> ConductaDeTarjeta(FilaDeLaCaja.T12, false, false, null)
    origen == com.avoqado.pos.payment.domain.OrigenDelCobro.PEDIDO_EN_LINEA_SIN_SALDO_ACTUALIZADO && c.habilitada ->
        ConductaDeTarjeta(FilaDeLaCaja.T0, true, false, TextosDeTarjetas.SIN_RED)
    else -> c
}
