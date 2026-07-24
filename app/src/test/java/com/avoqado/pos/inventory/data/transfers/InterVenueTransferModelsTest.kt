package com.avoqado.pos.inventory.data.transfers

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Traslados entre sucursales (CEDIS) — la máquina de estados espeja EXACTAMENTE
 * ALLOWED_ACTIONS de avoqado-server/src/services/dashboard/interVenueTransfer.domain.ts,
 * más el lado (origen/destino) que puede ejecutar cada acción.
 */
class InterVenueTransferModelsTest {

    // MARK: - Máquina de estados: origen

    @Test
    fun `REQUESTED como origen ofrece aprobar, rechazar y cancelar`() {
        val actions = availableTransferActions(TransferStatus.REQUESTED, isSource = true, isDestination = false)
        assertEquals(setOf(TransferAction.APPROVE, TransferAction.REJECT, TransferAction.CANCEL), actions)
    }

    @Test
    fun `APPROVED como origen ofrece despachar y cancelar`() {
        val actions = availableTransferActions(TransferStatus.APPROVED, isSource = true, isDestination = false)
        assertEquals(setOf(TransferAction.DISPATCH, TransferAction.CANCEL), actions)
    }

    @Test
    fun `IN_TRANSIT como origen no ofrece nada — recibir es del destino`() {
        val actions = availableTransferActions(TransferStatus.IN_TRANSIT, isSource = true, isDestination = false)
        assertTrue(actions.isEmpty())
    }

    // MARK: - Máquina de estados: destino

    @Test
    fun `REQUESTED como destino solo puede cancelar su solicitud`() {
        val actions = availableTransferActions(TransferStatus.REQUESTED, isSource = false, isDestination = true)
        assertEquals(setOf(TransferAction.CANCEL), actions)
    }

    @Test
    fun `IN_TRANSIT y PARTIALLY_RECEIVED como destino ofrecen recibir`() {
        for (status in listOf(TransferStatus.IN_TRANSIT, TransferStatus.PARTIALLY_RECEIVED)) {
            val actions = availableTransferActions(status, isSource = false, isDestination = true)
            assertEquals("status=$status", setOf(TransferAction.RECEIVE), actions)
        }
    }

    // MARK: - Estados terminales y terceros

    @Test
    fun `estados terminales no ofrecen acciones para nadie`() {
        val terminal = listOf(
            TransferStatus.COMPLETED,
            TransferStatus.COMPLETED_WITH_VARIANCE,
            TransferStatus.REJECTED,
            TransferStatus.CANCELLED,
        )
        for (status in terminal) {
            assertTrue("origen/$status", availableTransferActions(status, true, false).isEmpty())
            assertTrue("destino/$status", availableTransferActions(status, false, true).isEmpty())
        }
    }

    @Test
    fun `un venue que no es origen ni destino nunca ve acciones`() {
        for (status in listOf(TransferStatus.REQUESTED, TransferStatus.APPROVED, TransferStatus.IN_TRANSIT)) {
            assertTrue(availableTransferActions(status, isSource = false, isDestination = false).isEmpty())
        }
    }

    @Test
    fun `un estado futuro desconocido degrada a cero acciones, nunca revienta`() {
        assertTrue(availableTransferActions("ESTADO_NUEVO_V2", isSource = true, isDestination = true).isEmpty())
        // Y la etiqueta lo muestra tal cual en lugar de tronar:
        assertEquals("ESTADO_NUEVO_V2", TransferStatus.label("ESTADO_NUEVO_V2"))
    }

    // MARK: - Parsing del wire (cantidades como String, envelope tolerante)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `el detalle parsea el wire real con cantidades string y campos extra`() {
        val raw = """
            {
              "id": "t1", "number": "TR-20260723-ABC", "mode": "PULL", "status": "IN_TRANSIT",
              "sourceVenueId": "vA", "destinationVenueId": "vB",
              "sourceVenue": {"id":"vA","name":"El Atole","operationalRole":"HYBRID","salesEnabled":true},
              "destinationVenue": {"id":"vB","name":"La Ribera","operationalRole":"STORE"},
              "campoFuturo": {"x": 1},
              "items": [{
                "id":"i1","unit":"KILOGRAM","quantityRequested":"2","quantityDispatched":"2.000",
                "quantityReceived":"0","quantityVarianceResolved":"0",
                "sourceRawMaterial":{"id":"rmA","name":"Piña","sku":"PIN-01","unit":"KILOGRAM"},
                "destinationRawMaterial":{"id":"rmB","name":"Piña","sku":"PIN-02","unit":"KILOGRAM"},
                "allocations":[{"ignorado":true}]
              }]
            }
        """.trimIndent()
        val detail = json.decodeFromString(InterVenueTransferDetail.serializer(), raw)
        assertEquals("TR-20260723-ABC", detail.number)
        assertEquals(1, detail.items.size)
        assertEquals("2.000", detail.items.first().quantityDispatched)
        assertEquals("HYBRID", detail.sourceVenue.operationalRole)
    }

    @Test
    fun `la pagina de lista parsea con defaults cuando faltan campos opcionales`() {
        val raw = """
            {"items":[{"id":"t1","number":"TR-1","mode":"PULL","status":"REQUESTED",
              "sourceVenueId":"vA","destinationVenueId":"vB",
              "sourceVenue":{"id":"vA","name":"A"},"destinationVenue":{"id":"vB","name":"B"}}],
             "total":1,"page":1,"pageSize":100,"totalPages":1}
        """.trimIndent()
        val page = json.decodeFromString(TransferListPage.serializer(), raw)
        assertEquals(1, page.items.size)
        assertEquals(0, page.items.first()._count.items)
    }

    @Test
    fun `las etiquetas de estado estan en espanol`() {
        assertEquals("Solicitado", TransferStatus.label(TransferStatus.REQUESTED))
        assertEquals("En tránsito", TransferStatus.label(TransferStatus.IN_TRANSIT))
        assertEquals("Recibido", TransferStatus.label(TransferStatus.COMPLETED))
        assertEquals("Recibido con diferencias", TransferStatus.label(TransferStatus.COMPLETED_WITH_VARIANCE))
    }
}
