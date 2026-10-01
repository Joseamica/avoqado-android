package com.avoqado.pos.escritorio.teclado

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

private val LETRAS = listOf("qwertyuiop", "asdfghjklñ", "zxcvbnm")
private val SIMBOLOS = listOf("@#$%&*-+()", "!\"':;,.?/\\", "_=<>[]{}~^`|")
private const val NUMEROS = "1234567890"

/** Todo lo que se puede teclear con el panel (letras en minúscula: la mayúscula sale de Mayús; el espacio, de Espacio). */
internal fun caracteresDelTeclado(): Set<Char> = buildSet {
    (LETRAS + SIMBOLOS).forEach { addAll(it.toList()) }
    addAll(NUMEROS.toList()); add('@'); add('.'); add(' ')
}

/** Dos páginas (letras con ñ / números y símbolos), Mayús, Acento, Borrar, Espacio, Enter, Ocultar. Filas de 52 dp, sin margen exterior. */
@Composable
fun TecladoEnPantalla(alPresionar: (Tecla) -> Unit, estado: EstadoDeTeclas, modifier: Modifier = Modifier) {
    val filas = if (estado.pagina == 0) LETRAS else SIMBOLOS
    Column(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        Fila { NUMEROS.forEach { Tec(Tecla.Letra(it), it.toString(), 1f, alPresionar) } }
        Fila { filas[0].forEach { Tec(Tecla.Letra(it), etiqueta(it, estado), 1f, alPresionar) } }
        Fila { filas[1].forEach { Tec(Tecla.Letra(it), etiqueta(it, estado), 1f, alPresionar) } }
        Fila {
            filas[2].forEach { Tec(Tecla.Letra(it), etiqueta(it, estado), 1f, alPresionar) }
            Tec(Tecla.Borrar, "⌫", 1.5f, alPresionar)
        }
        Fila {
            Tec(Tecla.Pagina, if (estado.pagina == 0) "123" else "ABC", 1.5f, alPresionar, pagina = estado.pagina)
            Tec(Tecla.Acento, "´", 1f, alPresionar, activa = estado.acentoPendiente)
            Tec(Tecla.Mayus, "⇧", 1f, alPresionar, activa = estado.mayus)
            Tec(Tecla.Espacio, "espacio", 4f, alPresionar)
            Tec(Tecla.Letra('@'), "@", 1f, alPresionar)
            Tec(Tecla.Letra('.'), ".", 1f, alPresionar)
            Tec(Tecla.Enter, "⏎", 1.5f, alPresionar)
            Tec(Tecla.Ocultar, "▾", 1f, alPresionar)
        }
    }
}

private fun etiqueta(c: Char, e: EstadoDeTeclas): String = if (e.mayus) c.uppercaseChar().toString() else c.toString()

@Composable
private fun Fila(contenido: @Composable RowScope.() -> Unit) =
    Row(Modifier.fillMaxWidth().height(52.dp), content = contenido)

private fun descripcion(t: Tecla, pagina: Int): String = when (t) {
    is Tecla.Letra -> t.c.toString()
    Tecla.Mayus -> "Mayúsculas"
    Tecla.Acento -> "Acento"
    Tecla.Borrar -> "Borrar"
    Tecla.Enter -> "Enter"
    Tecla.Espacio -> "Espacio"
    Tecla.Pagina -> if (pagina == 0) "Números y símbolos" else "Letras"
    Tecla.Ocultar -> "Ocultar teclado"
}

/**
 * Con detectTapGestures y sin foco propio: tocar una tecla no le quita el foco al campo (clickable sí lo haría).
 * El área táctil es TODA la caja de 52 dp; el padding sólo dibuja la separación.
 */
@Composable
private fun RowScope.Tec(tecla: Tecla, texto: String, peso: Float, alPresionar: (Tecla) -> Unit, activa: Boolean = false, pagina: Int = 0) {
    val colores = MaterialTheme.colorScheme
    val nombre = descripcion(tecla, pagina)
    val estadoTecla = when (tecla) {
        Tecla.Mayus -> if (activa) "Activadas" else "Desactivadas"
        Tecla.Acento -> if (activa) "Pendiente" else "Sin acento"
        else -> null
    }
    Box(
        Modifier.weight(peso).height(52.dp)
            .focusProperties { canFocus = false }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = nombre
                estadoTecla?.let { stateDescription = it }
                onClick { alPresionar(tecla); true }
            }
            .pointerInput(tecla) { detectTapGestures { alPresionar(tecla) } },
    ) {
        Box(
            Modifier.fillMaxSize().padding(2.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (activa) colores.primary else colores.surface),
            contentAlignment = Alignment.Center,
        ) {
            Text(texto, color = if (activa) colores.onPrimary else colores.onSurface)
        }
    }
}
