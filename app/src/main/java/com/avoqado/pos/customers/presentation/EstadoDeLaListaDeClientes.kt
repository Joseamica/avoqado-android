package com.avoqado.pos.customers.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Qué se pinta debajo del buscador de clientes. */
internal enum class ContenidoDeLaLista { CARGANDO, SIN_RESULTADOS, LISTA }

internal data class EstadoDeLaListaDeClientes(
    val contenido: ContenidoDeLaLista,
    /** La barra delgada bajo el buscador: el servidor va en camino, también cuando ya hay una lista a la vista. */
    val barraDeCarga: Boolean,
)

/**
 * Una sola decisión para los TRES layouts de la lista de clientes (checkout/cobro/mesa, tablet y teléfono).
 *
 * 🔴 Cargando con la lista vacía es el cargador, NUNCA «No se encontraron» + «Crear cliente»: entre teclas, o mientras
 * el servidor contesta en WiFi malo, eso empujaba a dar de alta OTRA VEZ a un cliente que sí existe (duplicado).
 */
internal fun estadoDeLaListaDeClientes(cargando: Boolean, hayClientes: Boolean, texto: String): EstadoDeLaListaDeClientes =
    EstadoDeLaListaDeClientes(
        contenido = when {
            cargando && !hayClientes -> ContenidoDeLaLista.CARGANDO
            !hayClientes && texto.isNotBlank() -> ContenidoDeLaLista.SIN_RESULTADOS
            else -> ContenidoDeLaLista.LISTA
        },
        barraDeCarga = cargando,
    )

/**
 * La búsqueda va al servidor y en WiFi malo tarda (hasta el plazo del cliente HTTP compartido, 30 s): mientras tanto
 * la lista enseña lo guardado que cumple el texto y esta barra dice que viene la respuesta. Ocupa su alto siempre, para
 * que la lista no brinque con cada tecla.
 */
@Composable
internal fun BarraDeCargaDeClientes(cargando: Boolean, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().height(2.dp)) {
        if (cargando) LinearProgressIndicator(modifier = Modifier.fillMaxSize())
    }
}
