package com.avoqado.pos.scale

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.avoqado.pos.pos.data.model.DEFAULT_SCALE_READING_MAX_AGE_MILLIS
import com.avoqado.pos.pos.data.model.NormalizedScaleReading
import kotlinx.coroutines.delay

/**
 * D15: lo que una pantalla puede ofrecer de la báscula AHORA. Una lectura (estable o no) sólo cuenta si llegó desde que se
 * abrió este producto ([desdeEpochMillis]) y hace 2 s o menos (la regla de `validateScaleReading`). Si no, se pinta
 * «Lista»: no hay peso que ofrecer.
 *
 * Por qué: el lector deja el último estado tal cual cuando la báscula calla con el puerto abierto (apagada con el cable
 * puesto) o manda ceros (plato vacío: esa trama se ignora). Sin esta regla, «Agregar» cobraba ese peso a otro producto.
 */
fun ScaleConnectionState.vigente(desdeEpochMillis: Long, ahoraEpochMillis: Long): ScaleConnectionState {
    val (perfil, lectura) = perfilYLectura() ?: return this
    val edad = ahoraEpochMillis - lectura.observedAtEpochMillis
    val cuenta = lectura.observedAtEpochMillis >= desdeEpochMillis && edad in 0..DEFAULT_SCALE_READING_MAX_AGE_MILLIS
    return if (cuenta) this else ScaleConnectionState.Ready(perfil)
}

/**
 * [vigente] en la pantalla de [producto]: cuenta desde que se abre (o cambia) el producto, y se vuelve a calcular sola
 * cuando la lectura cumple 2 s sin que llegue otra (la báscula calló y el estado ya no cambia).
 */
@Composable
fun ScaleConnectionState.vigentePara(producto: Any?): ScaleConnectionState {
    val desde = remember(producto) { System.currentTimeMillis() }
    var caducada by remember(this, desde) { mutableStateOf(false) }
    val estado = vigente(desde, System.currentTimeMillis())
    val conLectura = estado.perfilYLectura()
    LaunchedEffect(conLectura) {
        val lectura = conLectura?.second ?: return@LaunchedEffect
        delay(lectura.observedAtEpochMillis + DEFAULT_SCALE_READING_MAX_AGE_MILLIS + 1 - System.currentTimeMillis())
        caducada = true
    }
    return if (caducada && conLectura != null) ScaleConnectionState.Ready(conLectura.first) else estado
}

private fun ScaleConnectionState.perfilYLectura(): Pair<String, NormalizedScaleReading>? = when (this) {
    is ScaleConnectionState.Stable -> profileName to reading
    is ScaleConnectionState.Unstable -> profileName to reading
    else -> null
}
