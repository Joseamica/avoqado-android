package com.avoqado.pos.payment.domain

/** D12: textos de la cuenta abierta sin red. Los de ANDROID son los oficiales (iOS los copia); las Tareas 7, 9 y 11 agregan los suyos. */
object TextosDeCuentaSinRed {
    /** La lista o el detalle vienen de la copia guardada; [hora] en la zona del negocio (`horaDeLaCopia`). */
    fun copiaDeLas(hora: String) = "Sin conexión · como estaba a las $hora"

    /** El cobro hecho con la copia subió, pero la cuenta había crecido en otra caja: [cents] es lo que quedó debiendo de más. */
    fun quedoDebiendo(cents: Int) = "La cuenta quedó debiendo ${com.avoqado.pos.core.util.formatMoneyFromCents(cents)}"
}
