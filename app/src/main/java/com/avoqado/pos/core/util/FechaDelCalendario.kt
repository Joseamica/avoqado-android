package com.avoqado.pos.core.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Calendar
import java.util.TimeZone

/**
 * Fechas que entran y salen de los calendarios de la app.
 *
 * 🔴 El DatePicker de Material3 habla en MEDIANOCHE UTC, de entrada y de salida. Leerlo con la zona del
 * negocio (UTC-6 en México) recorría el día: en lista de espera y reagendar, elegir el 9 dejaba el 8, y
 * reagendar lo movía aunque sólo se diera «Aceptar». La zona del negocio entra DESPUÉS, al juntar la
 * fecha con la hora de la cita (mismo patrón que `DateTimeSection` de crear reserva).
 */
object FechaDelCalendario {

    /** La fecha que se le da al DatePicker de Material3 como seleccionada al abrirlo. */
    fun aMilisDelPicker(fecha: LocalDate): Long =
        fecha.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    /** El día que tocó el usuario, a partir de `selectedDateMillis` del DatePicker de Material3. */
    fun deMilisDelPicker(millis: Long): LocalDate =
        Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

    /**
     * «Desde» de un reporte: 00:00:00.000 del día en la zona del negocio. `month` es base 0, como lo entrega
     * el DatePickerDialog de Android. Sin fijar los milisegundos, `Calendar` conservaba los del reloj y el
     * rango dejaba fuera las ventas de esa fracción del primer segundo.
     */
    fun inicioDelDia(year: Int, month: Int, day: Int, zona: TimeZone): Long =
        Calendar.getInstance(zona).apply {
            set(year, month, day, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    /** «Hasta» de un reporte: 23:59:59.999 del día en la zona del negocio (ver [inicioDelDia]). */
    fun finDelDia(year: Int, month: Int, day: Int, zona: TimeZone): Long =
        Calendar.getInstance(zona).apply {
            set(year, month, day, 23, 59, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis
}
