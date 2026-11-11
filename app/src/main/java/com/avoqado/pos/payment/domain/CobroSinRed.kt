package com.avoqado.pos.payment.domain

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

    /** Por qué el cobro de una cuenta hecho con la copia es sólo efectivo (`OrigenDelCobro.CUENTA_ABIERTA_DESDE_LA_COPIA`). */
    const val SOLO_EFECTIVO = "Sin conexión: esta cuenta se cobra sólo en efectivo."
}
