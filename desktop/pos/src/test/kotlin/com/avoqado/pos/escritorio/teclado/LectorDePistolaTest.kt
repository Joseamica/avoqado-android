package com.avoqado.pos.escritorio.teclado

import java.awt.Component
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * La pistola de códigos en Windows (caso real: La Galeterie, 7-oct — el vale salía bien y la caja «no lo leía»).
 * Ante Windows la pistola ES el teclado: mismo aparato, sin deviceId. Lo que la separa es el ritmo, y como no se puede
 * saber de quién es una tecla hasta ver la siguiente, las teclas se RETIENEN un instante y se DEVUELVEN en orden si no
 * eran de la pistola. Lo que estas pruebas cuidan: la persona nunca pierde una tecla, y el código nunca llega a medias.
 */
class LectorDePistolaTest {
    private val origen = object : Component() {}
    private val reenviadas = mutableListOf<KeyEvent>()
    private val codigos = mutableListOf<String>()
    private val pendientes = mutableListOf<() -> Unit>()
    private val lector = LectorDePistola(
        reenviar = { reenviadas += it },
        emitir = { codigos += it },
        programar = { _, f -> pendientes += f },
    )

    /** Lo que un temporizador de Swing haría al vencer: corre lo programado (lo viejo no debe hacer nada). */
    private fun vencenLosTemporizadores() { val l = pendientes.toList(); pendientes.clear(); l.forEach { it() } }

    private fun codigoDe(c: Char): Int = when {
        c.isDigit() -> KeyEvent.VK_0 + (c - '0')
        c.isLetter() -> KeyEvent.VK_A + (c.uppercaseChar() - 'A')
        c == '\n' -> KeyEvent.VK_ENTER
        c == '\t' -> KeyEvent.VK_TAB
        else -> KeyEvent.VK_UNDEFINED
    }

    /** Las tres que manda Windows por cada tecla que escribe: abajo, el carácter y arriba. */
    private fun tecla(c: Char, ms: Long, mods: Int = 0): List<KeyEvent> = listOf(
        KeyEvent(origen, KeyEvent.KEY_PRESSED, ms, mods, codigoDe(c), c),
        KeyEvent(origen, KeyEvent.KEY_TYPED, ms, mods, KeyEvent.VK_UNDEFINED, c),
        KeyEvent(origen, KeyEvent.KEY_RELEASED, ms + 2, mods, codigoDe(c), c),
    )

    /** @return lo que contestó el lector por cada evento: true = no siguió su camino (todavía). */
    private fun mandar(eventos: List<KeyEvent>, escribiendo: Boolean = false): List<Boolean> =
        eventos.map { lector.procesar(it, escribiendo) }

    /** Una pistola: un carácter cada 8 ms y Enter al final. */
    private fun pistola(texto: String, desdeMs: Long = 1_000, fin: Char = '\n'): List<KeyEvent> =
        (texto + fin).flatMapIndexed { i, c -> tecla(c, desdeMs + i * 8L) }

    @Test fun `P1 la pistola entrega el vale entero y ninguna de sus teclas llega a la pantalla`() {
        val respuestas = mandar(pistola("9040586673"))
        vencenLosTemporizadores()

        assertEquals(listOf("9040586673"), codigos)
        assertTrue(respuestas.all { it }, "ninguna tecla del código (ni su Enter) debe llegar a la app")
        assertTrue(reenviadas.isEmpty(), "nada se devuelve: era la pistola")
    }

    @Test fun `P1 una persona que teclea no pierde ninguna tecla y le llegan en su orden`() {
        // El caso de LectorHidHallazgoTest: un par tecleado a 80 ms, y otro a 30 ms (rápido de verdad).
        val h = tecla('h', 1_000); val o = tecla('o', 1_080); val l = tecla('l', 1_110); val a = tecla('a', 1_400)
        mandar(h); mandar(o); mandar(l)
        vencenLosTemporizadores()   // la persona se detiene: se devuelve lo retenido
        val respuestaA = mandar(a)
        vencenLosTemporizadores()

        assertEquals(h + o + l + a, reenviadas, "todas, una sola vez y en el orden en que se teclearon")
        assertTrue(codigos.isEmpty())
        assertTrue(respuestaA.all { it }, "se retuvo y luego se devolvió: no pasó dos veces")
    }

    @Test fun `P1 con un campo escribiendo la pistola teclea en el campo como un teclado`() {
        // Así funciona hoy la pantalla «Escanear» (su campo de código manual) y cualquier buscador.
        val respuestas = mandar(pistola("9040586673"), escribiendo = true)

        assertTrue(respuestas.none { it }, "con un campo escribiendo no se toca nada")
        assertTrue(codigos.isEmpty())
        assertTrue(reenviadas.isEmpty())
    }

    @Test fun `lo retenido sale ANTES que la tecla que si pasa`() {
        val unoDos = tecla('1', 1_000) + tecla('2', 1_008)
        mandar(unoDos)
        // Se abrió un campo a media ráfaga: la tecla nueva pasa, pero lo retenido tiene que llegar primero.
        val tres = tecla('3', 1_016)
        val respuesta = lector.procesar(tres.first(), escribiendo = true)

        assertFalse(respuesta)
        assertEquals(unoDos, reenviadas)
    }

    @Test fun `el Enter de una persona pasa y lo que tecleo llega antes`() {
        val digitos = "1234".flatMapIndexed { i, c -> tecla(c, 1_000 + i * 150L) }
        mandar(digitos)
        val enter = tecla('\n', 1_600)
        val respuestas = mandar(enter)

        assertTrue(respuestas.none { it }, "el Enter de una persona sigue su camino")
        assertEquals(digitos, reenviadas)
        assertTrue(codigos.isEmpty())
    }

    @Test fun `menos de 4 caracteres no es un codigo aunque llegue rapido`() {
        val eventos = pistola("12")
        val respuestas = mandar(eventos)

        assertTrue(codigos.isEmpty())
        assertEquals(eventos.take(6), reenviadas)
        assertTrue(respuestas.takeLast(3).none { it }, "el Enter pasa")
    }

    @Test fun `P1 si el temporizador se adelanto a la pistola no sale un codigo cortado`() {
        // La PC trabada: el temporizador devolvió «90» aunque, por el reloj de las teclas, la pistola seguía escribiendo.
        val inicio = tecla('9', 1_000) + tecla('0', 1_008)
        mandar(inicio)
        vencenLosTemporizadores()
        val resto = "40586673\n".flatMapIndexed { i, c -> tecla(c, 1_016 + i * 8L) }
        val respuestas = mandar(resto)

        assertTrue(codigos.isEmpty(), "«40586673» sería un vale que no existe: mejor ninguno")
        assertEquals(inicio, reenviadas, "lo retenido se devolvió una sola vez")
        assertTrue(respuestas.none { it }, "el resto de esa ráfaga pasa tal cual, como hoy")
    }

    @Test fun `un temporizador viejo no suelta una rafaga que siguio`() {
        mandar(tecla('9', 1_000))
        val viejo = pendientes.toList()
        mandar(tecla('0', 1_008))
        viejo.forEach { it() }   // vence el del '9' cuando ya llegó el '0'

        assertTrue(reenviadas.isEmpty(), "la ráfaga sigue viva: nada se devuelve")
    }

    @Test fun `mayusculas con Shift el Shift pasa y el codigo sale entero`() {
        val shift = KeyEvent(origen, KeyEvent.KEY_PRESSED, 1_000, InputEvent.SHIFT_DOWN_MASK, KeyEvent.VK_SHIFT, KeyEvent.CHAR_UNDEFINED)
        val soltarShift = KeyEvent(origen, KeyEvent.KEY_RELEASED, 1_020, 0, KeyEvent.VK_SHIFT, KeyEvent.CHAR_UNDEFINED)
        assertFalse(lector.procesar(shift, escribiendo = false))
        mandar(tecla('A', 1_002, InputEvent.SHIFT_DOWN_MASK) + tecla('B', 1_010, InputEvent.SHIFT_DOWN_MASK))
        assertFalse(lector.procesar(soltarShift, escribiendo = false))
        mandar("12\n".flatMapIndexed { i, c -> tecla(c, 1_024 + i * 8L) })

        assertEquals(listOf("AB12"), codigos)
        assertTrue(reenviadas.isEmpty())
    }

    @Test fun `un atajo con Ctrl suelta lo retenido y pasa`() {
        val unoDos = tecla('1', 1_000) + tecla('2', 1_008)
        mandar(unoDos)
        val ctrlC = KeyEvent(origen, KeyEvent.KEY_PRESSED, 1_016, InputEvent.CTRL_DOWN_MASK, KeyEvent.VK_C, '\u0003')

        assertFalse(lector.procesar(ctrlC, escribiendo = false))
        assertEquals(unoDos, reenviadas)
    }

    @Test fun `una tecla que no escribe suelta lo retenido y pasa`() {
        val unoDos = tecla('1', 1_000) + tecla('2', 1_008)
        mandar(unoDos)
        val flecha = KeyEvent(origen, KeyEvent.KEY_PRESSED, 1_016, 0, KeyEvent.VK_LEFT, KeyEvent.CHAR_UNDEFINED)

        assertFalse(lector.procesar(flecha, escribiendo = false))
        assertEquals(unoDos, reenviadas)
    }

    @Test fun `el soltar de una tecla del codigo que llega despues del Enter tambien se traga`() {
        // Algunas pistolas encimen: el '3' sube DESPUÉS de que baja el Enter.
        val cuerpo = "904058667".flatMapIndexed { i, c -> tecla(c, 1_000 + i * 8L) }
        val tres = tecla('3', 1_072)
        val enter = tecla('\n', 1_080)
        val respuestas = mandar(cuerpo + tres.take(2) + enter.take(2) + tres.last() + enter.last())

        assertEquals(listOf("9040586673"), codigos)
        assertTrue(respuestas.all { it })
        assertTrue(reenviadas.isEmpty())
    }

    @Test fun `Tab tambien cierra un codigo`() {
        mandar(pistola("7501055310838", fin = '\t'))

        assertEquals(listOf("7501055310838"), codigos)
        assertTrue(reenviadas.isEmpty())
    }
}
