package com.avoqado.pos.inventory.waste

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.inventory.data.RespuestaHttp
import com.avoqado.pos.inventory.waste.data.BloqueoDeMermaPorPlan
import com.avoqado.pos.inventory.waste.data.EstadoMerma
import com.avoqado.pos.inventory.waste.data.PendingWasteEntity
import com.avoqado.pos.inventory.waste.data.TransporteDeMerma
import com.avoqado.pos.inventory.waste.data.WasteSyncCoordinator
import com.avoqado.pos.inventory.waste.presentation.PendingWasteViewModel
import com.avoqado.pos.inventory.waste.presentation.TonoDeAviso
import com.avoqado.pos.inventory.waste.presentation.mermasVisibles
import com.avoqado.pos.inventory.waste.presentation.porSubir
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * «Mermas por subir» y descartar. La cola corre el SQL real; el servidor es un transporte que
 * contesta lo que se le diga y apunta cuántas veces se le pidió anular. Espejo exacto de
 * `PendingWasteViewModelTests.swift` de avoqado-ios.
 */
class PendingWasteViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private companion object {
        const val FOLIO = "3f9c2c1e-5b7a-4c1d-9e8f-0a1b2c3d4e5f"
        const val VENUE = "venue-centro"
        const val YO = "yo"
    }

    private class ServidorDeAnulacion : TransporteDeMerma {
        var respuestaDeAnulacion =
            RespuestaHttp(200, """{"outcome":"VOIDED","voidedByStaffId":"gerente-1","voidedAt":"2026-09-22T18:00:00.000Z"}""")
        var anulaciones = 0
        val anuladasPor = mutableListOf<String>()
        override suspend fun enviar(fila: PendingWasteEntity) = RespuestaHttp(201, "{}")
        override suspend fun anular(fila: PendingWasteEntity, porStaffId: String): RespuestaHttp {
            anulaciones++
            anuladasPor += porStaffId
            return respuestaDeAnulacion
        }
    }

    private val cola = ColaDeMermaSobreSqlite()
    private val servidor = ServidorDeAnulacion()
    private val conexion = MutableStateFlow(true)
    private val red = mockk<ConnectivityMonitor> {
        every { isConnected } returns conexion
        every { isServerReachable } returns MutableStateFlow(true)
    }

    private fun vmDe(staffId: String = YO, esGerente: Boolean = false): PendingWasteViewModel {
        val almacen = mockk<SecureStorage> {
            every { venueId } returns VENUE
            every { userId } returns staffId
            every { venuesConMermaBloqueada } returns emptySet()
            every { venuesConMermaBloqueada = any() } just Runs
        }
        val motor = WasteSyncCoordinator(cola, servidor, almacen, red, BloqueoDeMermaPorPlan(almacen, red, CatalogoDeMermaFalso()))
        val roles = mockk<RoleManager> { every { veMermasDeTodos } returns esGerente }
        return PendingWasteViewModel(cola, motor, almacen, roles)
    }

    private fun fila(
        folio: String = FOLIO,
        staffId: String = YO,
        estado: String = EstadoMerma.PENDING,
        ultimoCodigo: String? = null,
        creadaEn: Long = 0L,
    ) = PendingWasteEntity(
        idempotencyKey = folio, venueId = VENUE, staffId = staffId, itemType = "RAW_MATERIAL", itemId = "rm-1",
        itemName = "Aguacate", unit = "KILOGRAM", quantity = "3", reasonCode = "SPOILED", note = null,
        clientOccurredAt = "2026-09-22T12:00:00-06:00", estado = estado, ultimoCodigo = ultimoCodigo,
        creadaEn = creadaEn,
    )

    /** Cada quien ve lo suyo; MANAGER+ ve lo de todos (spec §5). */
    @Test
    fun `un cajero ve solo lo suyo, un gerente ve todo`() = runTest {
        cola.encolar(fila(folio = "a", staffId = YO, creadaEn = 1))
        cola.encolar(fila(folio = "b", staffId = "otra-persona", creadaEn = 2))

        val cajero = vmDe(esGerente = false).apply { cargar() }
        val gerente = vmDe(esGerente = true).apply { cargar() }

        assertEquals(listOf("a"), cajero.filas.value.map { it.folio })
        assertEquals(listOf("a", "b"), gerente.filas.value.map { it.folio })
    }

    /**
     * 🔴 Codex r2: con la lista abierta, una fila que estaba saliendo y el servidor mandó a revisión seguía
     * diciendo «Se está subiendo», con «Descartar» apagado, hasta salir y volver a entrar. La lista se
     * entera sola de cada cambio de la cola.
     */
    @Test
    fun `la lista se actualiza sola mientras esta abierta`() = runTest {
        cola.encolar(fila(estado = EstadoMerma.SENDING))
        val vm = vmDe().apply { cargar() }
        assertFalse(vm.filas.value.single().sePuedeDescartar)

        cola.marcar(FOLIO, EstadoMerma.NEEDS_REVIEW, "ITEM_NOT_FOUND", 0L)

        assertTrue(vm.filas.value.single().sePuedeDescartar)
    }

    /** El contador de «Más» cuenta con la MISMA regla que la lista, y lo cerrado ya no está por subir. */
    @Test
    fun `el contador de Mas cuenta lo que la persona ve y no cuenta lo cerrado`() = runTest {
        cola.encolar(fila(folio = "a", staffId = YO, creadaEn = 1))
        cola.encolar(fila(folio = "b", staffId = YO, estado = EstadoMerma.NEEDS_REVIEW, creadaEn = 2))
        cola.encolar(fila(folio = "c", staffId = "otra-persona", creadaEn = 3))
        cola.cerrar("a", EstadoMerma.VOIDED, porStaffId = YO, cuando = 9)

        assertEquals(1, porSubir(mermasVisibles(cola.todas(), YO, todas = false)))
        assertEquals(2, porSubir(mermasVisibles(cola.todas(), YO, todas = true)))
    }

    /**
     * 🔴 Descartar EXIGE red (spec §4.3): sin ella no se puede saber si el servidor la aplicó.
     * Borrarla aquí dejaría registrada una merma que el negocio cree descartada.
     */
    @Test
    fun `sin red no se puede descartar, y se dice por que`() = runTest {
        cola.encolar(fila())
        conexion.value = false
        val vm = vmDe()

        vm.descartar(FOLIO)

        assertEquals(EstadoMerma.PENDING, cola.todas().single().estado)
        assertEquals(0, servidor.anulaciones)
        assertEquals("Necesitas conexión para descartar una merma.", vm.aviso.value?.texto)
        assertEquals(TonoDeAviso.ADVERTENCIA, vm.aviso.value?.tono)
    }

    /**
     * 🔴 Spec §4.3: la fila anulada NO se borra, se CIERRA con autor y fecha. Sin esa marca, una
     * merma que alguien descartó a propósito es indistinguible de una que nunca existió.
     */
    @Test
    fun `descartar con red cierra el folio y deja constancia de quien lo anulo`() = runTest {
        cola.encolar(fila())
        val vm = vmDe()

        vm.descartar(FOLIO)

        val f = cola.todas().single()
        assertEquals(EstadoMerma.VOIDED, f.estado)
        assertEquals("gerente-1", f.cerradaPorStaffId)
        assertEquals("Merma descartada", vm.aviso.value?.texto)
    }

    /**
     * 🔴 Codex r1: la lápida guardaba la hora del APARATO. La que cuenta es la del servidor
     * (`voidedAt`), la misma que verá el dashboard. Y el `void` sale con la credencial de quien lo
     * pidió: es su nombre el que queda en la lápida.
     */
    @Test
    fun `descartar guarda la hora del servidor y sale con la credencial de quien lo pide`() = runTest {
        cola.encolar(fila())
        val vm = vmDe(staffId = "gerente-1", esGerente = true)

        vm.descartar(FOLIO)

        assertEquals(java.time.Instant.parse("2026-09-22T18:00:00.000Z").toEpochMilli(), cola.todas().single().cerradaEn)
        assertEquals(listOf("gerente-1"), servidor.anuladasPor)
    }

    /**
     * 🔴 `ALREADY_APPLIED` significa que SÍ se había registrado. La fila se cierra como APLICADA y se
     * DICE — nunca desaparece en silencio, porque el cajero volvería a capturarla (spec §7 L5).
     */
    @Test
    fun `si ya estaba aplicada se dice, y la fila queda como aplicada`() = runTest {
        cola.encolar(fila())
        servidor.respuestaDeAnulacion = RespuestaHttp(200, """{"outcome":"ALREADY_APPLIED"}""")
        val vm = vmDe()

        vm.descartar(FOLIO)

        assertEquals("Esta merma sí se había registrado. No la vuelvas a capturar.", vm.aviso.value?.texto)
        assertEquals(EstadoMerma.APPLIED, cola.todas().single().estado)
    }

    /**
     * Envío y descarte se excluyen por el ESTADO de la fila (spec §5): la que va en camino no se toca.
     * Lo decide el reclamo atómico de la cola, el mismo del drenado.
     */
    @Test
    fun `no se puede descartar una fila que esta saliendo`() = runTest {
        cola.encolar(fila(estado = EstadoMerma.SENDING))
        val vm = vmDe()

        vm.descartar(FOLIO)

        assertEquals(0, servidor.anulaciones)
        assertEquals(EstadoMerma.SENDING, cola.todas().single().estado)
        assertEquals("Esta merma se está subiendo. Espera un momento.", vm.aviso.value?.texto)
    }

    /** El drenado nunca toma una fila en revisión: se descarta sin pasar por el reclamo. */
    @Test
    fun `una fila en revision se puede descartar`() = runTest {
        cola.encolar(fila(estado = EstadoMerma.NEEDS_REVIEW, ultimoCodigo = "UNIT_MISMATCH"))
        val vm = vmDe()

        vm.descartar(FOLIO)

        assertEquals(1, servidor.anulaciones)
        assertEquals(EstadoMerma.VOIDED, cola.todas().single().estado)
    }

    /**
     * Si el servidor no pudo anular (red a medias, 5xx), la fila vuelve a la cola TAL CUAL: ni se
     * pierde ni se queda trabada en «subiendo».
     */
    @Test
    fun `si la anulacion falla la fila vuelve a la cola`() = runTest {
        cola.encolar(fila())
        servidor.respuestaDeAnulacion = RespuestaHttp(503, "")
        val vm = vmDe()

        vm.descartar(FOLIO)

        assertEquals(EstadoMerma.PENDING, cola.todas().single().estado)
        assertEquals("No se pudo descartar. Intenta de nuevo.", vm.aviso.value?.texto)
        assertEquals(TonoDeAviso.ERROR, vm.aviso.value?.tono)
    }

    /** Sin el permiso, se DICE a quién pedírselo, y la merma sigue en la cola. */
    @Test
    fun `sin permiso para descartar se dice, y la fila sigue en la cola`() = runTest {
        cola.encolar(fila())
        servidor.respuestaDeAnulacion =
            RespuestaHttp(403, """{"error":"No tienes permiso para anular este folio.","code":"WASTE_PERMISSION_DENIED"}""")
        val vm = vmDe()

        vm.descartar(FOLIO)

        assertEquals(EstadoMerma.PENDING, cola.todas().single().estado)
        assertEquals("No tienes permiso para descartar mermas. Pídeselo a tu gerente.", vm.aviso.value?.texto)
    }

    /** La fila dice por qué está detenida, en palabras del cajero, nunca el código crudo. */
    @Test
    fun `una fila en revision explica el motivo`() = runTest {
        cola.encolar(fila(estado = EstadoMerma.NEEDS_REVIEW, ultimoCodigo = "UNIT_MISMATCH"))
        val vm = vmDe().apply { cargar() }

        assertEquals(
            "La unidad del artículo cambió. Descártala y captúrala de nuevo.",
            vm.filas.value.single().explicacion,
        )
    }
}
