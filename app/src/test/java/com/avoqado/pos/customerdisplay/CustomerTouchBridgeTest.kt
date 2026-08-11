package com.avoqado.pos.customerdisplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El escenario real que estos tests protegen (Sunmi T3 Pro, medido 2026-08-10):
 * el panel del CLIENTE trae digitalizador multitáctil (`SUNMI NP511`) pero
 * Android no lo asocia a esa pantalla, así que sus toques aterrizan en la
 * ventana del CAJERO con las coordenadas del panel grande (1920x1080) en vez de
 * las del chico (1280x800).
 *
 * Aquí vive lo único que se puede comprobar sin el aparato enfrente: a quién
 * puenteamos y con qué escala. Los dos errores posibles tienen precios muy
 * distintos —no puentear deja todo como está; puentear de más deja al cajero sin
 * poder tocar su propia pantalla— y por eso hay más casos de "no se toca" que de
 * "sí se puentea".
 */
class CustomerTouchBridgeTest {

    private fun tactil(
        id: Int,
        name: String = "táctil",
        external: Boolean? = null,
        associatedDisplayId: Int? = null,
    ) = TouchDeviceInfo(
        deviceId = id,
        name = name,
        isTouchscreen = true,
        external = external,
        associatedDisplayId = associatedDisplayId,
    )

    // MARK: - A quién se puentea

    @Test
    fun `T3 Pro - el panel huerfano se puentea y el del cajero no`() {
        val ids = resolveBridgedTouchDeviceIds(
            listOf(
                tactil(3, "goodix-ts", external = false),
                tactil(7, "SUNMI NP511", external = true, associatedDisplayId = NO_ASSOCIATED_DISPLAY),
            ),
        )
        assertEquals(setOf(7), ids)
    }

    @Test
    fun `sin dispositivos de entrada no se puentea nada`() {
        assertEquals(emptySet<Int>(), resolveBridgedTouchDeviceIds(emptyList()))
    }

    @Test
    fun `un equipo normal de una sola pantalla no se toca`() {
        // Teléfono o tablet: un solo táctil integrado. Aunque el resto de señales
        // faltara, aquí no hay nada que puentear.
        assertEquals(
            emptySet<Int>(),
            resolveBridgedTouchDeviceIds(listOf(tactil(1, "main-touch", external = false))),
        )
    }

    @Test
    fun `si no se puede saber si es externo NO se puentea`() {
        // `isExternal` es público desde API 34; abajo es reflexión y el fabricante
        // puede bloquearla. Quedarse sin puente es inofensivo; tragarse los toques
        // del cajero por adivinar, no.
        val ids = resolveBridgedTouchDeviceIds(
            listOf(
                tactil(3, "goodix-ts", external = false),
                tactil(7, "SUNMI NP511", external = null),
            ),
        )
        assertEquals(emptySet<Int>(), ids)
    }

    @Test
    fun `un tactil externo YA ruteado a su pantalla no se toca`() {
        // Un monitor táctil USB en regla: Android le entrega sus eventos a la
        // ventana de SU pantalla. Robárselos sería romper algo que funciona.
        val ids = resolveBridgedTouchDeviceIds(
            listOf(
                tactil(3, "goodix-ts", external = false),
                tactil(9, "Elo Touch", external = true, associatedDisplayId = 2),
            ),
        )
        assertEquals(emptySet<Int>(), ids)
    }

    @Test
    fun `nunca se reclaman TODOS los tactiles`() {
        // Un OEM que marque su panel integrado como externo nos dejaría sin
        // ningún táctil para el cajero. Ante eso: no se puentea nada.
        val ids = resolveBridgedTouchDeviceIds(
            listOf(
                tactil(3, "panel-interno", external = true),
                tactil(7, "SUNMI NP511", external = true),
            ),
        )
        assertEquals(emptySet<Int>(), ids)
    }

    @Test
    fun `el unico tactil del equipo nunca se puentea aunque diga que es externo`() {
        assertEquals(
            emptySet<Int>(),
            resolveBridgedTouchDeviceIds(listOf(tactil(7, "raro", external = true))),
        )
    }

    @Test
    fun `un mouse o un teclado externo no cuentan como pantalla tactil`() {
        val ids = resolveBridgedTouchDeviceIds(
            listOf(
                tactil(3, "goodix-ts", external = false),
                tactil(7, "SUNMI NP511", external = true),
                TouchDeviceInfo(11, "USB Keyboard", isTouchscreen = false, external = true, associatedDisplayId = null),
                TouchDeviceInfo(12, "USB Mouse", isTouchscreen = false, external = true, associatedDisplayId = null),
            ),
        )
        // El teclado y el mouse no entran ni como candidatos ni en la invariante:
        // el puente sigue viendo dos táctiles y se queda con el huérfano.
        assertEquals(setOf(7), ids)
    }

    @Test
    fun `dos paneles de cliente huerfanos se puentean los dos`() {
        val ids = resolveBridgedTouchDeviceIds(
            listOf(
                tactil(3, "goodix-ts", external = false),
                tactil(7, "panel A", external = true),
                tactil(8, "panel B", external = true, associatedDisplayId = NO_ASSOCIATED_DISPLAY),
            ),
        )
        assertEquals(setOf(7, 8), ids)
    }

    // MARK: - La traducción de coordenadas

    @Test
    fun `T3 Pro - de 1920x1080 a 1280x800`() {
        val scale = computeTouchScale(1920f, 1080f, 1280f, 800f)!!
        assertEquals(0.6667f, scale.x, 0.0001f)
        assertEquals(0.7407f, scale.y, 0.0001f)
    }

    @Test
    fun `la esquina de arriba a la izquierda se queda en su sitio`() {
        val scale = computeTouchScale(1920f, 1080f, 1280f, 800f)!!
        assertEquals(0f, mapTouchCoordinate(0f, scale.x), 0.0001f)
        assertEquals(0f, mapTouchCoordinate(0f, scale.y), 0.0001f)
    }

    @Test
    fun `el ultimo pixel cae DENTRO de la pantalla del cliente`() {
        // Es el borde que se equivoca solo: el rango del digitalizador es
        // inclusivo (0..1919), así que el ancho son 1920 y no 1919. Con 1919 el
        // último pixel se mapearía a 1280.0 — justo AFUERA — y la fila de botones
        // pegada al borde derecho dejaría de responder.
        val scale = computeTouchScale(1920f, 1080f, 1280f, 800f)!!
        val x = mapTouchCoordinate(1919f, scale.x)
        val y = mapTouchCoordinate(1079f, scale.y)
        assertTrue("x=$x se salió de 1280", x < 1280f)
        assertTrue("y=$y se salió de 800", y < 800f)
        assertEquals(1279.33f, x, 0.01f)
        assertEquals(799.26f, y, 0.01f)
    }

    @Test
    fun `el centro sigue siendo el centro`() {
        val scale = computeTouchScale(1920f, 1080f, 1280f, 800f)!!
        assertEquals(640f, mapTouchCoordinate(960f, scale.x), 0.0001f)
        assertEquals(400f, mapTouchCoordinate(540f, scale.y), 0.0001f)
    }

    @Test
    fun `las dos escalas son independientes cuando cambia la proporcion`() {
        // 16:9 → 16:10 no es una escala uniforme: usar una sola desplazaría todo
        // en vertical, y el dedo apuntaría sistemáticamente más arriba o más abajo.
        val scale = computeTouchScale(1920f, 1080f, 1280f, 800f)!!
        assertTrue("las escalas x e y no pueden ser la misma", scale.x != scale.y)
    }

    @Test
    fun `sin dimensiones utilizables no hay escala`() {
        // Una ventana recién creada mide 0: sin escala no se reenvía (y el toque
        // se consume igual, que es lo que evita que apriete algo en la caja).
        assertNull(computeTouchScale(1920f, 1080f, 0f, 800f))
        assertNull(computeTouchScale(1920f, 1080f, 1280f, 0f))
        assertNull(computeTouchScale(0f, 1080f, 1280f, 800f))
        assertNull(computeTouchScale(1920f, 0f, 1280f, 800f))
        assertNull(computeTouchScale(-1f, -1f, 1280f, 800f))
    }

    @Test
    fun `si cambian las dimensiones cambia la escala`() {
        // Nada de números fijos: el mismo panel con otra pantalla de cliente (o el
        // mismo equipo con otra densidad) da otra escala.
        val chica = computeTouchScale(1920f, 1080f, 1280f, 800f)!!
        val grande = computeTouchScale(1920f, 1080f, 1920f, 1080f)!!
        assertEquals(1f, grande.x, 0.0001f)
        assertEquals(1f, grande.y, 0.0001f)
        assertTrue(chica.x < grande.x)
    }

    @Test
    fun `una pantalla de cliente mas grande que el tactil amplia en vez de reducir`() {
        val scale = computeTouchScale(1280f, 800f, 1920f, 1080f)!!
        assertEquals(1.5f, scale.x, 0.0001f)
        assertEquals(1.35f, scale.y, 0.0001f)
        assertEquals(1920f, mapTouchCoordinate(1280f, scale.x), 0.001f)
    }
}
