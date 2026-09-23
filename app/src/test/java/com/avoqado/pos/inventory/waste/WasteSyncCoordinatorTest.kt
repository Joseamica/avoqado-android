package com.avoqado.pos.inventory.waste

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.inventory.data.RespuestaHttp
import com.avoqado.pos.inventory.waste.data.BloqueoDeMermaPorPlan
import com.avoqado.pos.inventory.waste.data.EstadoMerma
import com.avoqado.pos.inventory.waste.data.PendingWasteEntity
import com.avoqado.pos.inventory.waste.data.TransporteDeMerma
import com.avoqado.pos.inventory.waste.data.WasteCatalogItem
import com.avoqado.pos.inventory.waste.data.WasteSyncCoordinator
import com.avoqado.pos.inventory.waste.data.esperaAntesDeReintentar
import com.avoqado.pos.inventory.waste.domain.WasteReason
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * El motor de la merma: registrar (siempre en disco ANTES de la red) y drenar (el desenlace lo
 * decide el CÓDIGO del servidor, con la sesión de quien la capturó y al venue donde se capturó).
 *
 * La cola corre el SQL real ([ColaDeMermaSobreSqlite]); la red es un transporte falso que apunta
 * qué se le mandó. Espejo exacto de `WasteSyncCoordinatorTests.swift` de avoqado-ios.
 */
class WasteSyncCoordinatorTest {

    private companion object {
        const val FOLIO = "3f9c2c1e-5b7a-4c1d-9e8f-0a1b2c3d4e5f"
        const val VENUE = "venue-centro"
        const val YO = "yo"
        val ITEM = WasteCatalogItem("RAW_MATERIAL", "rm-1", "Aguacate", "AGU-01", "kg")
        val CREADO = RespuestaHttp(201, """{"reportId":"r1","declared":"3","deducted":"3","unrecorded":"0"}""")
    }

    /** Apunta lo que se le mandó y contesta lo que se le diga. */
    private class TransporteFalso(
        var respuesta: (PendingWasteEntity) -> RespuestaHttp = { CREADO },
        var retrasoMs: Long = 0L,
        var alEnviar: (PendingWasteEntity) -> Unit = {},
    ) : TransporteDeMerma {
        val foliosEnviados = mutableListOf<String>()
        val venuesEnviados = mutableListOf<String>()
        private var enVuelo = 0
        var maxEnVuelo = 0
        override suspend fun enviar(fila: PendingWasteEntity): RespuestaHttp {
            foliosEnviados += fila.idempotencyKey
            venuesEnviados += fila.venueId
            alEnviar(fila)
            enVuelo++
            maxEnVuelo = maxOf(maxEnVuelo, enVuelo)
            try {
                if (retrasoMs > 0) delay(retrasoMs)
                return respuesta(fila)
            } finally {
                enVuelo--
            }
        }

        override suspend fun anular(fila: PendingWasteEntity) = RespuestaHttp(0, "")
    }

    private val cola = ColaDeMermaSobreSqlite()
    private var sesion: String? = YO
    private var bloqueadas: Set<String> = emptySet()
    private val bloqueo = BloqueoDeMermaPorPlan(
        mockk {
            every { venuesConMermaBloqueada } answers { bloqueadas }
            every { venuesConMermaBloqueada = any() } answers { bloqueadas = firstArg() }
        },
        mockk(),
        CatalogoDeMermaFalso(),
    )

    private fun TestScope.motor(transporte: TransporteDeMerma = TransporteFalso()): WasteSyncCoordinator {
        val almacen = mockk<SecureStorage> { every { userId } answers { sesion } }
        val red = mockk<ConnectivityMonitor> {
            every { isConnected } returns MutableStateFlow(true)
            every { isServerReachable } returns MutableStateFlow(true)
        }
        return WasteSyncCoordinator(cola, transporte, almacen, red, bloqueo).apply {
            reloj = { currentTime }
            zona = ZoneId.of("America/Mexico_City")
        }
    }

    private fun fila(folio: String = FOLIO, staffId: String = YO, venueId: String = VENUE, creadaEn: Long = 0L) =
        PendingWasteEntity(
            idempotencyKey = folio, venueId = venueId, staffId = staffId, itemType = "RAW_MATERIAL",
            itemId = "rm-1", itemName = "Aguacate", unit = "kg", quantity = "3", reasonCode = "SPOILED",
            note = null, clientOccurredAt = "2026-09-22T12:00:00-06:00", estado = EstadoMerma.PENDING,
            creadaEn = creadaEn,
        )

    // MARK: - Registrar

    /**
     * 🔴 La pregunta 2 de `todo-funciona-sin-red.md`: si el proceso muere entre el toque y el POST,
     * ¿se pierde algo? Registrar escribe la fila y NO toca la red: encolar en el `catch` del POST
     * sería tarde. La cantidad tecleada con coma se guarda ya normalizada, con su folio.
     */
    @Test
    fun `registrar escribe la fila en disco y no toca la red`() = runTest {
        val transporte = TransporteFalso()
        val folio = motor(transporte).registrar(VENUE, YO, ITEM, "3,5", WasteReason.SPOILED, null).getOrThrow()

        assertEquals(0, transporte.foliosEnviados.size)
        val f = cola.todas().single()
        assertEquals(folio, f.idempotencyKey)
        assertEquals(EstadoMerma.PENDING, f.estado)
        assertEquals(VENUE, f.venueId)
        assertEquals(YO, f.staffId)
        assertEquals("3.5", f.quantity)
        assertEquals("kg", f.unit)
        assertEquals("SPOILED", f.reasonCode)
        assertTrue("sin zona: ${f.clientOccurredAt}", f.clientOccurredAt.endsWith("-06:00"))
    }

    /** Una cantidad que el servidor rechazaría (422 permanente) no llega a la cola. */
    @Test
    fun `una cantidad invalida se rechaza antes de escribir la fila`() = runTest {
        assertTrue(motor().registrar(VENUE, YO, ITEM, "1,234.5", WasteReason.SPOILED, null).isFailure)
        assertEquals(0, cola.todas().size)
    }

    /** OTHER sin nota tampoco: el servidor lo rechaza con 422 permanente. */
    @Test
    fun `OTHER sin nota se rechaza antes de escribir la fila`() = runTest {
        assertTrue(motor().registrar(VENUE, YO, ITEM, "3", WasteReason.OTHER, "   ").isFailure)
        assertEquals(0, cola.todas().size)
    }

    // MARK: - Drenar

    @Test
    fun `un 201 cierra la fila`() = runTest {
        cola.encolar(fila())
        motor().drenarAhora()
        assertEquals(0, cola.todas().size)
    }

    /**
     * Sin red la merma se queda, con su folio, y espera su turno: no se reintenta en ráfaga. Cuando
     * pasa la espera sale con el MISMO folio, que el servidor deduplica.
     */
    @Test
    fun `sin red se queda y se reintenta con el mismo folio tras esperar`() = runTest {
        cola.encolar(fila())
        val transporte = TransporteFalso(respuesta = { RespuestaHttp(0, "sin red") })
        val motor = motor(transporte)

        motor.drenarAhora()
        assertEquals(EstadoMerma.PENDING, cola.todas().single().estado)
        motor.drenarAhora()
        assertEquals(listOf(FOLIO), transporte.foliosEnviados)

        transporte.respuesta = { CREADO }
        testScheduler.advanceTimeBy(esperaAntesDeReintentar(1) + 1)
        motor.drenarAhora()
        assertEquals(listOf(FOLIO, FOLIO), transporte.foliosEnviados)
        assertEquals(0, cola.todas().size)
    }

    @Test
    fun `un 409 WASTE_VOIDED deja la fila anulada y no la reenvia`() = runTest {
        cola.encolar(fila())
        val transporte = TransporteFalso(respuesta = { RespuestaHttp(409, """{"code":"WASTE_VOIDED"}""") })
        val motor = motor(transporte)

        motor.drenarAhora()
        assertEquals(EstadoMerma.VOIDED, cola.todas().single().estado)

        testScheduler.advanceTimeBy(3_600_000)
        motor.drenarAhora()
        assertEquals(1, transporte.foliosEnviados.size)
    }

    @Test
    fun `un 422 deja la fila en revision, ni la borra ni la reintenta`() = runTest {
        cola.encolar(fila())
        val transporte = TransporteFalso(respuesta = { RespuestaHttp(422, """{"code":"QUANTITY_TOO_LARGE"}""") })
        val motor = motor(transporte)

        motor.drenarAhora()
        val f = cola.todas().single()
        assertEquals(EstadoMerma.NEEDS_REVIEW, f.estado)
        assertEquals("QUANTITY_TOO_LARGE", f.ultimoCodigo)

        testScheduler.advanceTimeBy(3_600_000)
        motor.drenarAhora()
        assertEquals(1, transporte.foliosEnviados.size)
    }

    /** El bloqueo de plan no pide nada al cajero: se reintenta solo y sube cuando el plan vuelve. */
    @Test
    fun `un 403 de plan queda bloqueado y sube solo cuando el plan vuelve`() = runTest {
        cola.encolar(fila())
        val transporte = TransporteFalso(
            respuesta = { RespuestaHttp(403, """{"error":"Forbidden","featureCode":"INVENTORY_TRACKING"}""") },
        )
        val motor = motor(transporte)

        motor.drenarAhora()
        assertEquals(EstadoMerma.PLAN_BLOCKED, cola.todas().single().estado)

        transporte.respuesta = { CREADO }
        testScheduler.advanceTimeBy(3_600_000)
        motor.drenarAhora()
        assertEquals(0, cola.todas().size)
    }

    /**
     * 🔴 Review Focus 3 — cada fila viaja al venue en el que se CAPTURÓ, no al activo: el motor le da
     * la fila entera al transporte, y el transporte arma la URL con `fila.venueId`
     * (`WasteRepositoryTest` lo fija contra un servidor de verdad).
     */
    @Test
    fun `cada fila se manda al venue en el que se capturo`() = runTest {
        cola.encolar(fila(folio = "a", venueId = "venue-centro", creadaEn = 1L))
        cola.encolar(fila(folio = "b", venueId = "venue-sur", creadaEn = 2L))
        val transporte = TransporteFalso()

        motor(transporte).drenarAhora()

        assertEquals(listOf("venue-centro", "venue-sur"), transporte.venuesEnviados)
    }

    /**
     * 🔴 Spec §5: la merma sube con la sesión de quien la capturó. Si el aparato cambió de usuario,
     * la fila de la persona anterior NO sube con la credencial de la nueva — el servidor toma el
     * autor del token y la merma quedaría a nombre de quien no la registró. Se queda, con dueño.
     */
    @Test
    fun `una fila de otra persona no sube con la sesion actual`() = runTest {
        cola.encolar(fila(staffId = "persona-A"))
        sesion = "persona-B"
        val transporte = TransporteFalso()

        motor(transporte).drenarAhora()

        assertEquals(0, transporte.foliosEnviados.size)
        assertEquals(EstadoMerma.PENDING, cola.todas().single().estado)
    }

    /**
     * 🔴 Spec §5: «cada envío se ata a su sesión EN EL MOMENTO de la petición». Si el usuario cambia
     * A MEDIA VUELTA (el cambio por PIN no espera a que el drenado termine), la fila siguiente —aunque
     * ya estaba reclamada— no puede salir con la credencial nueva: vuelve a la cola, sin contarse como
     * intento, y espera a su dueño.
     */
    @Test
    fun `si la sesion cambia a media vuelta, lo que sigue no sale con la sesion nueva`() = runTest {
        cola.encolar(fila(folio = "a", creadaEn = 1L))
        cola.encolar(fila(folio = "b", creadaEn = 2L))
        val transporte = TransporteFalso(alEnviar = { sesion = "otra-persona" })

        motor(transporte).drenarAhora()

        assertEquals(listOf("a"), transporte.foliosEnviados)
        val b = cola.todas().single()
        assertEquals("b", b.idempotencyKey)
        assertEquals(EstadoMerma.PENDING, b.estado)
        assertEquals(0, b.intentos)
    }

    /** Sin sesión no se manda nada: no hay a nombre de quién. */
    @Test
    fun `sin sesion no se manda nada`() = runTest {
        cola.encolar(fila())
        sesion = null
        val transporte = TransporteFalso()

        motor(transporte).drenarAhora()

        assertEquals(0, transporte.foliosEnviados.size)
    }

    /**
     * Un solo trabajador: dos drenados a la vez nunca tienen dos envíos en el aire, y cada fila sale
     * una sola vez. (El CAS de la cola ya impide mandar la MISMA fila dos veces; lo que el candado del
     * motor agrega es el orden: sin él, dos drenados mandarían dos filas distintas en paralelo.)
     */
    @Test
    fun `dos drenados concurrentes nunca tienen dos envios en el aire`() = runTest {
        cola.encolar(fila(folio = "a", creadaEn = 1L))
        cola.encolar(fila(folio = "b", creadaEn = 2L))
        val transporte = TransporteFalso(retrasoMs = 50)
        val motor = motor(transporte)

        listOf(async { motor.drenarAhora() }, async { motor.drenarAhora() }).awaitAll()

        assertEquals(1, transporte.maxEnVuelo)
        assertEquals(listOf("a", "b"), transporte.foliosEnviados)
    }

    // MARK: - Al cerrar sesión

    /**
     * Al cerrar sesión o cambiar de usuario se intenta subir lo PROPIO, con un plazo corto: salir del
     * turno no debe dejar la merma esperando a que esa persona vuelva a ESE aparato. Lo ajeno no se
     * toca, y lo que no alcance se queda con su dueño.
     */
    @Test
    fun `al cerrar sesion se intenta vaciar lo propio`() = runTest {
        cola.encolar(fila(folio = "mia", staffId = YO, creadaEn = 1L))
        cola.encolar(fila(folio = "ajena", staffId = "otra-persona", creadaEn = 2L))
        val transporte = TransporteFalso()

        motor(transporte).vaciarAntesDeCerrarSesion(staffId = YO, plazoMs = 5_000)

        assertEquals(listOf("mia"), transporte.foliosEnviados)
        assertEquals(listOf("ajena"), cola.todas().map { it.idempotencyKey })
    }

    /**
     * 🔴 El plazo NO puede retener el cierre de sesión: con la red colgada, la persona sale igual.
     * Y la fila que estaba saliendo no se queda atorada en `SENDING` —que sólo se sana al reiniciar
     * la app—: vuelve a la cola, con su dueño y su folio, y el envío abandonado no cuenta como intento.
     */
    @Test
    fun `con la red colgada el cierre de sesion no se cuelga y la fila vuelve a la cola`() = runTest {
        cola.encolar(fila())
        val transporte = TransporteFalso(retrasoMs = Long.MAX_VALUE / 2)
        val inicio = currentTime

        motor(transporte).vaciarAntesDeCerrarSesion(staffId = YO, plazoMs = 5_000)

        assertTrue("tardó ${currentTime - inicio} ms", currentTime - inicio <= 5_000)
        val f = cola.todas().single()
        assertEquals(EstadoMerma.PENDING, f.estado)
        assertEquals(YO, f.staffId)
        assertEquals(FOLIO, f.idempotencyKey)
        assertEquals(0, f.intentos)
    }

    // MARK: - Arranque

    /**
     * 🔴 Review Focus 4 — al arrancar, lo que quedó en `SENDING` (el proceso murió entre el POST y la
     * respuesta) vuelve a la cola y sale con el MISMO folio.
     */
    @Test
    fun `al arrancar, lo que quedo en SENDING vuelve a salir con su folio`() = runTest {
        cola.encolar(fila().copy(estado = EstadoMerma.SENDING))
        val transporte = TransporteFalso()
        val motor = motor(transporte)

        motor.start(backgroundScope)
        runCurrent()

        assertEquals(listOf(FOLIO), transporte.foliosEnviados)
        assertNull(cola.todas().singleOrNull())
        motor.stop()
    }

    // MARK: - Bloqueo de plan

    /**
     * Un 403 de plan marca la sucursal DE LA FILA: es lo que hace que «Más» y la pantalla digan
     * «Incluido en el Plan Premium». No marca la sucursal activa.
     */
    @Test
    fun `un 403 de plan marca como bloqueada la sucursal de la fila`() = runTest {
        cola.encolar(fila(venueId = "venue-sur"))
        val transporte = TransporteFalso(
            respuesta = { RespuestaHttp(403, """{"error":"Forbidden","featureCode":"INVENTORY_TRACKING"}""") },
        )

        motor(transporte).drenarAhora()

        assertTrue(bloqueo.estaBloqueado("venue-sur"))
        assertFalse(bloqueo.estaBloqueado(VENUE))
    }

    /**
     * 🔴 Un 201 NO levanta el bloqueo: el servidor recupera un folio ya aplicado ANTES del candado
     * de plan, así que ese 201 no prueba que el plan esté activo. Sólo lo levanta preguntarle.
     */
    @Test
    fun `un 201 no levanta el bloqueo de plan`() = runTest {
        bloqueo.bloquear(VENUE)
        cola.encolar(fila())

        motor().drenarAhora()

        assertEquals(0, cola.todas().size)
        assertTrue(bloqueo.estaBloqueado(VENUE))
    }

    /** La espera entre reintentos crece y tiene tope: ni ráfaga ni silencio de horas. */
    @Test
    fun `la espera entre reintentos crece y tiene tope`() {
        assertEquals(30_000L, esperaAntesDeReintentar(1))
        assertEquals(60_000L, esperaAntesDeReintentar(2))
        assertEquals(15 * 60_000L, esperaAntesDeReintentar(20))
    }
}
