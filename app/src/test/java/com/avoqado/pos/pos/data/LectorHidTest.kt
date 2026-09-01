package com.avoqado.pos.pos.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * El lector de pistola: distinguir una RÁFAGA de máquina de una persona tecleando.
 *
 * 🔴 Nació de un caso real (Testarudo Café, 2026-09-01): su POS de mostrador es un
 * Sunmi D3, que **no tiene cámara**, así que el QR de la tarjeta digital del cliente
 * no se puede escanear ahí. La salida es un lector de pistola por USB, que ante
 * Android se presenta como un TECLADO: escribe el código carácter por carácter y
 * termina con Enter.
 *
 * Lo que hace difícil esto es que un teclado de verdad se ve IGUAL. La señal que los
 * separa es el RITMO: un lector escribe cada carácter en ~10 ms (un EAN-13 completo
 * en ~130 ms); una persona tarda 150-300 ms entre teclas. De ahí el umbral.
 *
 * 🔴 Y la regla que evita el daño peor: **no se consume una tecla hasta que la ráfaga
 * ya se demostró.** Si se consumiera desde la primera, un negocio con un teclado USB
 * real se quedaría sin poder escribir — que es mucho peor que el defecto que esto
 * arregla. Se paga con un carácter suelto en el campo con foco la primera vez que un
 * lector nuevo dispara; a partir de ahí ese lector ya es conocido y no se escapa nada.
 */
class LectorHidTest {

    /** Escribe una ráfaga como la de un lector: 10 ms entre caracteres. */
    private fun LectorHid.rafaga(
        texto: String,
        desdeMs: Long = 1_000,
        pasoMs: Long = 10,
        deviceId: Int = 7,
    ): List<TeclaHid> = texto.mapIndexed { i, c ->
        procesar(caracter = c, esTerminador = false, esFisico = true, deviceId = deviceId, ahoraMs = desdeMs + i * pasoMs)
    }

    @Test
    fun `una rafaga rapida que termina en Enter entrega el codigo completo`() {
        val lector = LectorHid()
        val token = "0123456789abcdef0123456789abcdef0123456789abcdef" // la tarjeta: 48 hex

        lector.rafaga(token)
        val fin = lector.procesar(caracter = null, esTerminador = true, esFisico = true, deviceId = 7, ahoraMs = 1_500)

        assertEquals(TeclaHid.Codigo(token), fin)
    }

    @Test
    fun `la primera tecla se deja pasar y el resto de la rafaga se consume`() {
        // 🔴 El primer carácter todavía no prueba nada: podría ser una persona. Se deja
        // pasar para no comerle teclas a quien escribe en un teclado de verdad.
        val lector = LectorHid()

        val decisiones = lector.rafaga("7501055310838")

        assertEquals(TeclaHid.DejarPasar, decisiones.first())
        assertEquals(List(12) { TeclaHid.Consumir }, decisiones.drop(1))
    }

    @Test
    fun `un EAN-13 escaneado entrega el codigo`() {
        val lector = LectorHid()
        lector.rafaga("7501055310838")
        val fin = lector.procesar(caracter = null, esTerminador = true, esFisico = true, deviceId = 7, ahoraMs = 1_200)
        assertEquals(TeclaHid.Codigo("7501055310838"), fin)
    }

    @Test
    fun `una persona tecleando NUNCA pierde una tecla ni dispara un codigo`() {
        // 🔴 La prueba que protege al negocio con teclado USB: 200 ms entre teclas.
        val lector = LectorHid()

        val decisiones = lector.rafaga("hola", pasoMs = 200)
        val fin = lector.procesar(caracter = null, esTerminador = true, esFisico = true, deviceId = 7, ahoraMs = 2_000)

        assertEquals(List(4) { TeclaHid.DejarPasar }, decisiones)
        assertEquals(TeclaHid.DejarPasar, fin)
    }

    @Test
    fun `el teclado EN PANTALLA nunca es un lector`() {
        // El IME manda sus eventos sin dispositivo físico. Aunque el usuario teclee
        // rapidísimo, esto no puede convertirse en un escaneo.
        val lector = LectorHid()

        val decisiones = "123456".mapIndexed { i, c ->
            lector.procesar(caracter = c, esTerminador = false, esFisico = false, deviceId = -1, ahoraMs = 1_000 + i * 5L)
        }
        val fin = lector.procesar(caracter = null, esTerminador = true, esFisico = false, deviceId = -1, ahoraMs = 1_040)

        assertEquals(List(6) { TeclaHid.DejarPasar }, decisiones)
        assertEquals(TeclaHid.DejarPasar, fin)
    }

    @Test
    fun `un lector YA CONOCIDO consume desde la primera tecla`() {
        // Tras el primer escaneo el aparato queda identificado, así que el segundo ya
        // no deja escapar ningún carácter al campo con foco.
        val lector = LectorHid()
        lector.rafaga("7501055310838")
        lector.procesar(caracter = null, esTerminador = true, esFisico = true, deviceId = 7, ahoraMs = 1_200)

        val segunda = lector.rafaga("7501055310838", desdeMs = 5_000)

        assertEquals(List(13) { TeclaHid.Consumir }, segunda)
    }

    @Test
    fun `un aparato conocido no le presta su fama a otro`() {
        val lector = LectorHid()
        lector.rafaga("7501055310838", deviceId = 7)
        lector.procesar(caracter = null, esTerminador = true, esFisico = true, deviceId = 7, ahoraMs = 1_200)

        val otro = lector.rafaga("7501055310838", desdeMs = 5_000, deviceId = 9)

        assertEquals(TeclaHid.DejarPasar, otro.first())
    }

    @Test
    fun `un Enter suelto sigue siendo un Enter`() {
        // Sin este caso, el Enter de "aceptar" de cualquier diálogo se lo comería el
        // lector y el cajero se quedaría sin poder confirmar con el teclado.
        val lector = LectorHid()

        val fin = lector.procesar(caracter = null, esTerminador = true, esFisico = true, deviceId = 7, ahoraMs = 1_000)

        assertEquals(TeclaHid.DejarPasar, fin)
    }

    @Test
    fun `un Enter tardio despues de una rafaga NO la convierte en codigo`() {
        // Una persona que tecleó rápido cuatro caracteres, se detuvo a pensar y luego
        // confirmó con Enter no escaneó nada: el lector manda su Enter a ~10 ms del
        // último carácter, nunca segundos después.
        val lector = LectorHid()

        lector.rafaga("7501055310838")
        val fin = lector.procesar(caracter = null, esTerminador = true, esFisico = true, deviceId = 7, ahoraMs = 3_000)

        assertEquals(TeclaHid.DejarPasar, fin)
    }

    @Test
    fun `el Enter de otro aparato no cierra la rafaga`() {
        val lector = LectorHid()

        lector.rafaga("7501055310838", deviceId = 7)
        val fin = lector.procesar(caracter = null, esTerminador = true, esFisico = true, deviceId = 9, ahoraMs = 1_130)

        assertEquals(TeclaHid.DejarPasar, fin)
    }

    @Test
    fun `una rafaga demasiado corta no se toma por codigo`() {
        // Dos teclas rápidas seguidas de Enter pueden ser un atajo del cajero, no un
        // escaneo. Un código de verdad nunca mide 2 caracteres.
        val lector = LectorHid()

        lector.rafaga("ab")
        val fin = lector.procesar(caracter = null, esTerminador = true, esFisico = true, deviceId = 7, ahoraMs = 1_030)

        assertEquals(TeclaHid.DejarPasar, fin)
    }

    @Test
    fun `una pausa a media rafaga la parte en dos`() {
        // 🔴 Sin esto, lo que quedó de un escaneo a medias se pegaría al siguiente y el
        // código emitido sería basura que no existe en ningún catálogo.
        val lector = LectorHid()

        lector.rafaga("111", desdeMs = 1_000)
        lector.rafaga("7501055310838", desdeMs = 9_000) // mucho después
        val fin = lector.procesar(caracter = null, esTerminador = true, esFisico = true, deviceId = 7, ahoraMs = 9_200)

        assertEquals(TeclaHid.Codigo("7501055310838"), fin)
    }

    @Test
    fun `una tecla que no escribe nada parte la rafaga`() {
        // Flechas, F1, volumen: si se ignoraran, el código quedaría cortado a la mitad
        // y se emitiría incompleto.
        val lector = LectorHid()

        lector.rafaga("123", desdeMs = 1_000)
        val flecha = lector.procesar(caracter = null, esTerminador = false, esFisico = true, deviceId = 7, ahoraMs = 1_035)
        lector.rafaga("7501055310838", desdeMs = 1_040)
        val fin = lector.procesar(caracter = null, esTerminador = true, esFisico = true, deviceId = 7, ahoraMs = 1_200)

        assertEquals(TeclaHid.DejarPasar, flecha)
        assertEquals(TeclaHid.Codigo("7501055310838"), fin)
    }

    @Test
    fun `el codigo entregado no arrastra el terminador ni espacios`() {
        val lector = LectorHid()
        lector.rafaga(" 7501055310838 ")
        val fin = lector.procesar(caracter = null, esTerminador = true, esFisico = true, deviceId = 7, ahoraMs = 1_200)
        assertEquals(TeclaHid.Codigo("7501055310838"), fin)
    }

    @Test
    fun `tras entregar un codigo el buffer queda limpio`() {
        val lector = LectorHid()
        lector.rafaga("7501055310838")
        lector.procesar(caracter = null, esTerminador = true, esFisico = true, deviceId = 7, ahoraMs = 1_200)

        // Un Enter inmediato no puede volver a entregar el mismo código.
        val repetido = lector.procesar(caracter = null, esTerminador = true, esFisico = true, deviceId = 7, ahoraMs = 1_210)

        assertEquals(TeclaHid.DejarPasar, repetido)
    }
}
