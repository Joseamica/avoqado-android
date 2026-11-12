package com.avoqado.pos.auth.presentation

import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.customers.data.PendingGrant
import com.avoqado.pos.customers.data.PendingGrantQueue
import com.avoqado.pos.payment.data.PaymentSyncService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * D34 (12-nov): con una operación rechazada, «Cerrar sesión» se frena a propósito y su texto decía «resuélvela», pero un mesero no
 * puede «Marcar resuelta». Opción «a»: el freno se queda y el texto le dice a quien no puede qué hacer (que entre un gerente por
 * «Cambiar usuario», que lo rechazado ya no frena desde D28). A quien sí puede, le dice dónde.
 */
class FrenoConRechazosTest {

    @Test fun `P1 un mesero con 1 rechazada sabe que entra un gerente por Cambiar usuario`() {
        assertEquals(
            "Hay 1 operación rechazada por revisar. Pídele a un gerente que la resuelva: Más › Cambiar usuario.",
            textoDelFrenoConRechazos(total = 1, rechazadas = 1, puedeResolver = false, antesDe = "cerrar sesión"),
        )
    }

    @Test fun `P1 un mesero con 2 rechazadas, en plural`() {
        assertEquals(
            "Hay 2 operaciones rechazadas por revisar. Pídele a un gerente que las resuelva: Más › Cambiar usuario.",
            textoDelFrenoConRechazos(total = 2, rechazadas = 2, puedeResolver = false, antesDe = "cerrar sesión"),
        )
    }

    @Test fun `P1 un gerente con 1 rechazada sabe donde resolverla`() {
        assertEquals(
            "Hay 1 operación rechazada por revisar. Resuélvela en Ventas › Pendientes › «Otras operaciones y revisiones anteriores» antes de cerrar sesión.",
            textoDelFrenoConRechazos(total = 1, rechazadas = 1, puedeResolver = true, antesDe = "cerrar sesión"),
        )
    }

    /** Codex r2 #1: «lo demás» puede no subir solo (un vale por imprimir): no se promete que se sincroniza. */
    @Test fun `P1 con algo mas pendiente, se pide sincronizar o resolverlo y luego el gerente`() {
        assertEquals(
            "Hay 3 operaciones pendientes: 1 operación rechazada por revisar y 2 más. Sincroniza o resuelve lo demás y " +
                "pídele a un gerente que resuelva lo rechazado: Más › Cambiar usuario.",
            textoDelFrenoConRechazos(total = 3, rechazadas = 1, puedeResolver = false, antesDe = "cerrar sesión"),
        )
        assertEquals(
            "Hay 2 operaciones pendientes: 1 operación rechazada por revisar y 1 más. Sincroniza o resuelve lo demás y " +
                "resuelve lo rechazado en Ventas › Pendientes › «Otras operaciones y revisiones anteriores» antes de cambiar de establecimiento.",
            textoDelFrenoConRechazos(total = 2, rechazadas = 1, puedeResolver = true, antesDe = "cambiar de establecimiento"),
        )
    }

    // MARK: - Qué cuenta como rechazado (Codex r1)

    private fun pagos(porRevisar: Int, bloqueantes: Int = porRevisar, sinEnviar: Int = 0) = mockk<PaymentSyncService> {
        coEvery { porRevisarCount("v-1") } returns porRevisar
        coEvery { blockingWorkCount() } returns bloqueantes
        coEvery { sinEnviarCount() } returns sinEnviar
    }

    private fun cola(rechazadas: Int = 0) = mockk<SyncOutbox>(relaxed = true) { coEvery { rechazadasCount("v-1") } returns rechazadas }

    private fun paquete(estado: PendingGrant.Estado, venue: String = "v-1") =
        PendingGrant(id = "p-$estado-$venue", venueId = venue, packId = "pack", customerId = "c", createdAt = 1, estado = estado)

    /** r1 #1: con «bloquea 1» y «sin enviar 0» leídos en momentos distintos (un acuse en medio), la resta inventaba un rechazo. */
    @Test fun `P1 un acuse entre dos lecturas no inventa un rechazo`() = runBlocking {
        assertEquals(0, rechazadasPorRevisar(pagos(porRevisar = 0, bloqueantes = 1, sinEnviar = 0), cola(), null, "v-1"))
    }

    /** r1 #3: un paquete rechazado o en duda también lo resuelve un gerente; esperar la red no lo saca. Uno de otra sucursal, no. */
    @Test fun `P1 los paquetes por revisar de esta sucursal cuentan como rechazados`() = runBlocking {
        val paquetes = mockk<PendingGrantQueue> {
            every { filas } returns MutableStateFlow(listOf(
                paquete(PendingGrant.Estado.RECHAZADA), paquete(PendingGrant.Estado.EN_DUDA),
                paquete(PendingGrant.Estado.POR_ENTREGAR), paquete(PendingGrant.Estado.RECHAZADA, venue = "otra"),
            ))
        }

        assertEquals("1 cobro + 1 ronda + 2 paquetes", 4, rechazadasPorRevisar(pagos(porRevisar = 1), cola(rechazadas = 1), paquetes, "v-1"))
    }
}
