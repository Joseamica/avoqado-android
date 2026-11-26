package com.avoqado.pos.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.delay

/**
 * Modo ligero del POS de Windows (hz:a5fa2439:D2): la ventana dibuja sin la tarjeta de video y se quitan unas cuantas
 * animaciones (indicador de carga, transiciones entre pantallas, reposo de la pantalla del cliente). En Android nadie lo
 * provee: siempre es `false` y todo se comporta como siempre.
 */
val LocalModoLigero = staticCompositionLocalOf { false }

/** En modo ligero, lo que en reposo se movía sin parar avanza a saltos: uno cada 30 s, contra la imagen quemada. */
const val PASO_EN_REPOSO_MS = 30_000L

/** Milisegundos desde que se montó, que sólo avanzan de [PASO_EN_REPOSO_MS] en [PASO_EN_REPOSO_MS]: un cuadro por paso. */
@Composable
fun relojAPasos(): State<Long> {
    val ms = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(PASO_EN_REPOSO_MS)
            ms.longValue += PASO_EN_REPOSO_MS
        }
    }
    return ms
}
