package com.avoqado.escritorio.calendario

import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

/** Una fecha como la entrega android.app.DatePickerDialog: mes de 0 a 11. */
data class FechaDeAndroid(val anio: Int, val mes0: Int, val dia: Int)

private val UTC: TimeZone = TimeZone.getTimeZone("UTC")

/** Medianoche UTC de esa fecha, que es como habla el DatePicker de Material3. Leniente como Calendar (31 de sept = 1 de oct). */
fun FechaDeAndroid.aMilisUtc(): Long =
    GregorianCalendar(UTC).apply { clear(); set(anio, mes0, dia) }.timeInMillis

/** El día (UTC) de unos milisegundos del DatePicker de Material3. */
fun fechaDeMilisUtc(milis: Long): FechaDeAndroid =
    GregorianCalendar(UTC).apply { timeInMillis = milis }
        .let { FechaDeAndroid(it.get(Calendar.YEAR), it.get(Calendar.MONTH), it.get(Calendar.DAY_OF_MONTH)) }

/**
 * Material3 lanza si el año inicial no está en su rango (1900-2100): fuera de rango, el calendario abre en hoy (null).
 * El año va CON SIGNO (ISO, UTC): Calendar.YEAR pierde la era y un año -2025 se leería como «2026» (a. C.).
 */
@OptIn(ExperimentalMaterial3Api::class)
fun inicialSegura(fecha: FechaDeAndroid): Long? =
    fecha.aMilisUtc().takeIf { java.time.Instant.ofEpochMilli(it).atOffset(java.time.ZoneOffset.UTC).year in DatePickerDefaults.YearRange }

class SolicitudDeCalendario(val inicial: FechaDeAndroid, val alElegir: (FechaDeAndroid) -> Unit)

/**
 * El calendario imperativo de Android (`DatePickerDialog(...).show()`) en escritorio: `show()` deja aquí la solicitud y la
 * raíz de la ventana la dibuja con [CalendarioPendiente]. Una sola a la vez: la última gana, como un segundo diálogo
 * que tapa al primero.
 */
object CalendarioDeEscritorio {
    var pendiente: SolicitudDeCalendario? by mutableStateOf(null)
        private set

    fun pedir(solicitud: SolicitudDeCalendario) { pendiente = solicitud }

    fun elegir(fecha: FechaDeAndroid) {
        val solicitud = pendiente ?: return
        pendiente = null
        solicitud.alElegir(fecha)
    }

    fun cancelar() { pendiente = null }
}

/** Va en la raíz de la ventana, dentro del tema: el MISMO diálogo que usa la app en reservas («Aceptar» / «Cancelar»). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarioPendiente() {
    val solicitud = CalendarioDeEscritorio.pendiente ?: return
    key(solicitud) {   // otra solicitud = estado nuevo con SU fecha inicial
        val estado = rememberDatePickerState(initialSelectedDateMillis = inicialSegura(solicitud.inicial))
        DatePickerDialog(
            onDismissRequest = { CalendarioDeEscritorio.cancelar() },
            confirmButton = {
                TextButton(
                    onClick = { estado.selectedDateMillis?.let { CalendarioDeEscritorio.elegir(fechaDeMilisUtc(it)) } },
                    enabled = estado.selectedDateMillis != null,
                ) { Text("Aceptar") }
            },
            dismissButton = { TextButton(onClick = { CalendarioDeEscritorio.cancelar() }) { Text("Cancelar") } },
        ) {
            DatePicker(state = estado)
        }
    }
}
