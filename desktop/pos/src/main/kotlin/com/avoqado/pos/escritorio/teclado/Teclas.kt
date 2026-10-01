package com.avoqado.pos.escritorio.teclado

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.awt.Component
import java.awt.event.KeyEvent

/** Una tecla del teclado en pantalla. */
sealed interface Tecla {
    data class Letra(val c: Char) : Tecla   // letras, números y símbolos tal cual se ven
    data object Mayus : Tecla
    data object Acento : Tecla
    data object Borrar : Tecla
    data object Enter : Tecla
    data object Espacio : Tecla
    data object Pagina : Tecla
    data object Ocultar : Tecla
}

sealed interface Accion {
    data class Escribir(val texto: String) : Accion
    data object Borrar : Accion
    data object Enter : Accion
}

/** Estado del teclado (mayúsculas, acento pendiente, página) y lo que escribe cada tecla. Puro. */
class EstadoDeTeclas {
    // Estados de Compose: el panel se recompone solo al cambiar Mayús, Acento o página.
    var mayus by mutableStateOf(false)
    var acentoPendiente by mutableStateOf(false)
    var pagina by mutableStateOf(0)   // 0 = letras, 1 = números y símbolos

    /** Lo que hay que ESCRIBIR al presionar [t], o null si sólo cambia el estado. */
    fun presionar(t: Tecla): Accion? = when (t) {
        is Tecla.Letra -> {
            var c = t.c
            if (acentoPendiente) c = ACENTUADAS[c] ?: c
            if (mayus) c = c.uppercaseChar()
            acentoPendiente = false
            mayus = false
            Accion.Escribir(c.toString())
        }
        Tecla.Mayus -> { mayus = !mayus; null }
        Tecla.Acento -> { acentoPendiente = !acentoPendiente; null }
        Tecla.Pagina -> { pagina = 1 - pagina; null }
        Tecla.Borrar -> Accion.Borrar
        Tecla.Enter -> Accion.Enter
        Tecla.Espacio -> Accion.Escribir(" ")
        Tecla.Ocultar -> null
    }

    private companion object {
        val ACENTUADAS = mapOf('a' to 'á', 'e' to 'é', 'i' to 'í', 'o' to 'ó', 'u' to 'ú')
    }
}

/** Convierte una Accion en los KeyEvent que haría un teclado físico, y los entrega por [enviar]. */
class EscritorDeTeclas(private val origen: Component, private val enviar: (KeyEvent) -> Unit) {
    fun escribir(a: Accion) {
        when (a) {
            is Accion.Escribir -> a.texto.forEach { c -> tecla(KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, c) }
            Accion.Borrar -> pulsar(KeyEvent.VK_BACK_SPACE, '\b')
            Accion.Enter -> pulsar(KeyEvent.VK_ENTER, '\n')
        }
    }

    private fun pulsar(codigo: Int, c: Char) {
        tecla(KeyEvent.KEY_PRESSED, codigo, c)
        tecla(KeyEvent.KEY_RELEASED, codigo, c)
    }

    private fun tecla(id: Int, codigo: Int, c: Char) =
        enviar(KeyEvent(origen, id, System.currentTimeMillis(), 0, codigo, c))
}

private val aCompose by lazy {
    Class.forName("androidx.compose.ui.input.key.KeyEvent_desktopKt").declaredMethods.first { it.name.startsWith("toComposeEvent") }
}

/**
 * Lo que ComposeSceneMediator hace con cada KeyEvent de AWT (`toComposeEvent` es internal en CMP 1.7.3: por reflexión).
 * Las teclas del teclado en pantalla van DIRECTO a la escena: medido en Windows (30-sep), mandarlas por AWT
 * (`lienzo.dispatchEvent`) no escribía nada en el campo.
 */
fun teclaDeCompose(e: java.awt.event.KeyEvent): androidx.compose.ui.input.key.KeyEvent =
    androidx.compose.ui.input.key.KeyEvent(aCompose.invoke(null, e)!!)
