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

    /** Lo que le llega al campo, en el orden en que le llega: lo devuelto y lo que pasó directo (ver [alCampo]). */
    private val llegadas = mutableListOf<KeyEvent>()
    private val lector = LectorDePistola(
        reenviar = { reenviadas += it; llegadas += it },
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
        c == '-' -> KeyEvent.VK_MINUS
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

    /**
     * Como llega a Windows lo que manda SendKeys (y una pistola lenta): cada mayúscula es Shift↓ letra↓ letra↑ Shift↑, un evento
     * por cada salto del reloj ([pasoMs]). Medido el 2-nov en la Alienware: con SendKeys, 62-64 ms entre dos mayúsculas, 47 al pasar
     * de una mayúscula a otra cosa y 31 entre dos números. Con [shiftAntes], la pistola suelta el Shift antes que la letra.
     */
    private fun conShift(texto: String, desdeMs: Long = 1_000, pasoMs: Long = 16, shiftAntes: Boolean = false): List<KeyEvent> {
        var ms = desdeMs
        fun paso() = ms.also { ms += pasoMs }
        fun shift(id: Int) = KeyEvent(origen, id, paso(), if (id == KeyEvent.KEY_PRESSED) InputEvent.SHIFT_DOWN_MASK else 0, KeyEvent.VK_SHIFT, KeyEvent.CHAR_UNDEFINED)
        return texto.flatMap { c ->
            val mods = if (c.isUpperCase()) InputEvent.SHIFT_DOWN_MASK else 0
            buildList {
                if (c.isUpperCase()) add(shift(KeyEvent.KEY_PRESSED))
                val abajo = paso()
                add(KeyEvent(origen, KeyEvent.KEY_PRESSED, abajo, mods, codigoDe(c), c))
                add(KeyEvent(origen, KeyEvent.KEY_TYPED, abajo, mods, KeyEvent.VK_UNDEFINED, c))
                if (c.isUpperCase() && shiftAntes) add(shift(KeyEvent.KEY_RELEASED))
                add(KeyEvent(origen, KeyEvent.KEY_RELEASED, paso(), mods, codigoDe(c), c))
                if (c.isUpperCase() && !shiftAntes) add(shift(KeyEvent.KEY_RELEASED))
            }
        }
    }

    @Test fun `P1 un codigo con mayusculas al ritmo de Windows llega entero`() {
        // Visto el 1-nov en la Alienware: «PIZZAS-001» + Enter sólo captaba «S-001», y P-I-Z-Z-A le caían a la pantalla.
        mandar(conShift("PIZZAS-001\n"))

        assertEquals(listOf("PIZZAS-001"), codigos)
        assertTrue(reenviadas.isEmpty(), "ninguna letra del código llega a la pantalla")
    }

    @Test fun `P1 la pistola entrega cualquier forma de codigo a cualquier ritmo prometido`() {
        // La pasada completa de la familia (Codex r1 y r2): 2 a 16 ms por evento, soltando el Shift antes o después de la letra. 16 ms
        // es lo que se promete: medido en Windows real (Alienware, 3-nov), a 20 ms el reloj ya parte «S-» y sale «-004», como antes.
        val formas = listOf("PIZZAS-001", "AB12", "abc-DEF", "A1B2C3", "aBcD", "ABCDEFGHIJ", "9040586673", "a-Z-a-Z")
        var desde = 1_000L
        for (paso in listOf(2L, 8L, 16L)) for (shiftAntes in listOf(false, true)) for (forma in formas) {
            codigos.clear()
            desde += 10_000
            mandar(conShift(forma + "\n", desdeMs = desde, pasoMs = paso, shiftAntes = shiftAntes))
            assertEquals(listOf(forma), codigos, "«$forma» a $paso ms por evento, shiftAntes=$shiftAntes")
        }
        assertTrue(reenviadas.isEmpty(), "ninguna tecla de una lectura llega a la pantalla")
    }

    /** Una persona: una letra cada [cadaMs] y Enter [enterMs] después de la última. Las mayúsculas, con Shift sostenido o uno por letra. */
    private fun persona(texto: String, cadaMs: Long, enterMs: Long, shiftPorLetra: Boolean, desdeMs: Long): Pair<List<KeyEvent>, List<KeyEvent>> {
        fun shift(id: Int, ms: Long) = KeyEvent(origen, id, ms, if (id == KeyEvent.KEY_PRESSED) InputEvent.SHIFT_DOWN_MASK else 0, KeyEvent.VK_SHIFT, KeyEvent.CHAR_UNDEFINED)
        val letras = mutableListOf<KeyEvent>()
        val todas = mutableListOf<KeyEvent>()
        texto.forEachIndexed { i, c ->
            val ms = desdeMs + i * cadaMs
            val mods = if (c.isUpperCase()) InputEvent.SHIFT_DOWN_MASK else 0
            val siguienteEsMayuscula = texto.getOrNull(i + 1)?.isUpperCase() == true
            if (c.isUpperCase() && (shiftPorLetra || texto.getOrNull(i - 1)?.isUpperCase() != true)) todas += shift(KeyEvent.KEY_PRESSED, ms - 10)
            val eventos = tecla(c, ms, mods)
            letras += eventos
            todas += eventos
            if (c.isUpperCase() && (shiftPorLetra || !siguienteEsMayuscula)) todas += shift(KeyEvent.KEY_RELEASED, ms + 5)
        }
        todas += tecla('\n', desdeMs + (texto.length - 1) * cadaMs + enterMs)
        return letras to todas
    }

    @Test fun `P1 una persona nunca se vuelve pistola ni pierde una tecla`() {
        // Cada tecla a 61 ms o más (a 60 ya era una ráfaga antes de este cambio) y un Enter tan pegado como 10 ms, con las dos manos.
        var desde = 1_000L
        val conSostenido = listOf(61L, 80L, 100L, 150L, 300L)
        val porLetra = listOf(100L, 150L, 300L) // bajar y subir el Shift en cada letra no se puede hacer más rápido
        for (texto in listOf("Hola", "HOLA", "hola", "HoLa", "A1B2", "1234")) {
            for (porCadaLetra in listOf(false, true)) for (cada in if (porCadaLetra) porLetra else conSostenido) for (enter in listOf(10L, 40L, 60L, 100L)) {
                codigos.clear(); reenviadas.clear(); pendientes.clear()
                desde += 10_000
                val (letras, todas) = persona(texto, cada, enter, porCadaLetra, desde)
                mandar(todas)
                vencenLosTemporizadores()
                val caso = "«$texto» cada $cada ms, Enter a $enter ms, Shift por letra=$porCadaLetra"
                assertTrue(codigos.isEmpty(), "no es un código: $caso")
                assertEquals(letras, reenviadas, "todas, una sola vez y en su orden: $caso")
            }
        }
    }

    @Test fun `P1 una lectura justo despues de una tecla de una persona sale limpia`() {
        // Codex r2: una persona toca una tecla y con la otra mano dispara la pistola; la lectura llega 110 ms después.
        listOf('1', 'X').forEachIndexed { i, tecleada ->
            codigos.clear(); reenviadas.clear(); pendientes.clear()
            val inicio = 1_000L + i * 10_000
            val mods = if (tecleada.isUpperCase()) InputEvent.SHIFT_DOWN_MASK else 0
            val suTecla = tecla(tecleada, inicio, mods)
            mandar(suTecla)
            if (mods != 0) lector.procesar(KeyEvent(origen, KeyEvent.KEY_RELEASED, inicio + 5, 0, KeyEvent.VK_SHIFT, KeyEvent.CHAR_UNDEFINED), false)
            vencenLosTemporizadores()
            mandar(conShift("ABCD-001\n", desdeMs = inicio + 108, pasoMs = 2))

            assertEquals(listOf("ABCD-001"), codigos, "tras «$tecleada»")
            assertEquals(suTecla, reenviadas, "su tecla le llega a la persona")
        }
    }

    @Test fun `P1 un codigo con mayusculas que el temporizador partio no sale cortado`() {
        // La PC trabada: el temporizador soltó «P» a media ráfaga. El resto, con sus Shift, tampoco es un código.
        val eventos = conShift("PIZZAS-001\n")
        mandar(eventos.take(2))     // Shift↓ y P↓
        vencenLosTemporizadores()
        mandar(eventos.drop(2))

        assertTrue(codigos.isEmpty(), "«S-001» sería un código que no existe: mejor ninguno")
    }

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

    /** Manda [eventos] con un campo escribiendo y anota en [llegadas] los que pasan directo, después de lo que se devolvió. */
    private fun alCampo(eventos: List<KeyEvent>): List<Boolean> =
        eventos.map { e -> lector.procesar(e, escribiendo = true).also { retenida -> if (!retenida) llegadas += e } }

    @Test fun `P1 con un campo escribiendo la pistola teclea en el campo como un teclado`() {
        // Así funciona la pantalla «Escanear» (su campo de código manual) y cualquier buscador: un código que NO es la tarjeta
        // de un cliente llega al campo entero y en su orden (D56 sólo aparta la tarjeta).
        val eventos = pistola("9040586673")
        alCampo(eventos)
        vencenLosTemporizadores()

        assertTrue(codigos.isEmpty())
        assertEquals(eventos, llegadas, "todas, una sola vez y en su orden, con su Enter al final")
    }

    @Test fun `P1 D56 con un campo escribiendo la tarjeta del cliente va al lector y no al campo`() {
        // hz:b37dd7ea:D56: en WINDOWS, con el buscador (F3) o el campo manual de «Escanear» con texto, la pistola le pegaba los 48
        // caracteres de la tarjeta digital al texto y salía «Crear producto» con el QR como nombre. Ahora gana el lector, como fuera
        // de un campo: la tarjeta va a Cobrar y el campo se queda con lo que la persona escribió.
        val camisa = "camisa".flatMapIndexed { i, c -> tecla(c, 1_000 + i * 150L) }
        alCampo(camisa)
        vencenLosTemporizadores()
        val respuestas = alCampo(pistola(TARJETA, desdeMs = 5_000))
        vencenLosTemporizadores()

        assertEquals(listOf(TARJETA), codigos)
        assertTrue(respuestas.all { it }, "ninguna tecla de la tarjeta (ni su Enter) llega al campo")
        assertEquals(camisa, llegadas, "el campo sólo tiene lo que tecleó la persona, en su orden")
    }

    @Test fun `D56 la tarjeta en mayusculas con Shift tambien va al lector`() {
        alCampo(conShift(TARJETA.uppercase() + "\n", pasoMs = 2))

        assertEquals(listOf(TARJETA.uppercase()), codigos)
        assertTrue(llegadas.none { it.keyCode != KeyEvent.VK_SHIFT }, "sólo los Shift pasan, como fuera de un campo")
    }

    @Test fun `P1 D56 en un campo una persona no pierde teclas ni cambia su orden`() {
        // Lo que se teclea en un buscador, un monto o un nombre: letras que no pueden ser de la tarjeta pasan al instante y sueltan
        // antes lo retenido; las que sí (0-9, a-f) esperan un instante y llegan en su orden. Ni con un Enter pegado sale un código.
        var desde = 1_000L
        for (texto in listOf("camisa", "250.50", "Café", "abc1", "dead beef", "1234567890abcdef")) for (cada in listOf(30L, 80L, 150L)) for (enter in listOf(10L, 100L)) {
            codigos.clear(); llegadas.clear(); pendientes.clear()
            desde += 10_000
            val letras = texto.flatMapIndexed { i, c -> tecla(c, desde + i * cada) }
            val eventos = letras + tecla('\n', desde + (texto.length - 1) * cada + enter)
            alCampo(eventos)
            vencenLosTemporizadores()
            val caso = "«$texto» cada $cada ms, Enter a $enter ms"
            assertTrue(codigos.isEmpty(), "no es una tarjeta: $caso")
            assertEquals(eventos, llegadas, "todas, una sola vez y en su orden: $caso")
        }
    }

    @Test fun `D56 en un campo un codigo de 48 que no es la tarjeta llega al campo`() {
        // 48 caracteres con una «g»: no tiene la forma de la tarjeta (sólo 0-9 y a-f).
        val casi = TARJETA.dropLast(1) + "g"
        val eventos = pistola(casi)
        alCampo(eventos)
        vencenLosTemporizadores()

        assertTrue(codigos.isEmpty())
        assertEquals(eventos, llegadas)
    }

    @Test fun `lo retenido sale ANTES que la tecla que si pasa`() {
        val unoDos = tecla('1', 1_000) + tecla('2', 1_008)
        mandar(unoDos)
        // Se abrió un campo a media ráfaga: la tecla nueva (que no puede ser de la tarjeta) pasa, pero lo retenido llega primero.
        val equis = tecla('x', 1_016)
        val respuesta = lector.procesar(equis.first(), escribiendo = true)

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

    private companion object {
        /** La forma de la tarjeta digital de un cliente: 48 caracteres de 0-9 y a-f (`pareceTarjetaDeCliente`). */
        const val TARJETA = "0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
