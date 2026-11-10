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
 * D15: lo que una pantalla puede ofrecer de la báscula AHORA. Una lectura (estable o no) sólo cuenta si es NUEVA, distinta
 * de la que ya estaba cuando se abrió este producto ([lecturaAlAbrir]), y de hace 2 s o menos (la regla de
 * `validateScaleReading`). Si no, se pinta «Lista»: no hay peso que ofrecer.
 *
 * Por qué: el lector deja el último estado tal cual cuando la báscula calla con el puerto abierto (apagada con el cable
 * puesto) o manda ceros (plato vacío: esa trama se ignora). Sin esta regla, «Agregar» cobraba ese peso a otro producto.
 * «Nueva» se decide por la lectura, no por la hora (Codex r1): con un reloj que se atrasa o una lectura del mismo
 * milisegundo en que se abrió el producto, la hora mentía.
 */
fun ScaleConnectionState.vigente(lecturaAlAbrir: NormalizedScaleReading?, ahoraEpochMillis: Long): ScaleConnectionState {
    val (perfil, lectura) = perfilYLectura() ?: return this
    val edad = ahoraEpochMillis - lectura.observedAtEpochMillis
    val cuenta = lectura != lecturaAlAbrir && edad in 0..DEFAULT_SCALE_READING_MAX_AGE_MILLIS
    return if (cuenta) this else ScaleConnectionState.Ready(perfil)
}

/**
 * El peso estable que se puede usar en ESTE instante, o null. «Agregar», F12 y «Usar este peso» lo vuelven a preguntar al
 * tocarse (Codex r1): un toque justo cuando el peso caduca, antes del siguiente cuadro, ya no lo usa.
 */
fun ScaleConnectionState.pesoEstableVigente(
    lecturaAlAbrir: NormalizedScaleReading?,
    ahoraEpochMillis: Long = System.currentTimeMillis(),
): NormalizedScaleReading? = (vigente(lecturaAlAbrir, ahoraEpochMillis) as? ScaleConnectionState.Stable)?.reading

/** La lectura que ya estaba al abrir (o cambiar) [producto]: era de antes, de otro producto o de una báscula callada. */
@Composable
fun ScaleConnectionState.lecturaAlAbrir(producto: Any?): NormalizedScaleReading? =
    remember(producto) { perfilYLectura()?.second }

/** [vigente] en pantalla, y se vuelve a calcular sola cuando la lectura cumple 2 s sin que llegue otra. */
@Composable
fun ScaleConnectionState.vigentePara(lecturaAlAbrir: NormalizedScaleReading?): ScaleConnectionState {
    var caducada by remember(this, lecturaAlAbrir) { mutableStateOf(false) }
    val estado = vigente(lecturaAlAbrir, System.currentTimeMillis())
    val conLectura = estado.perfilYLectura()
    LaunchedEffect(conLectura, lecturaAlAbrir) {
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
