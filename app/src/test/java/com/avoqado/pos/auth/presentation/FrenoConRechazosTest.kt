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
            "Hay 1 operación por revisar. Pídele a un gerente que la resuelva: Más › Cambiar usuario.",
            textoDelFrenoConRechazos(total = 1, rechazadas = 1, puedeResolver = false, antesDe = "cerrar sesión"),
        )
    }

    @Test fun `P1 un mesero con 2 rechazadas, en plural`() {
        assertEquals(
            "Hay 2 operaciones por revisar. Pídele a un gerente que las resuelva: Más › Cambiar usuario.",
            textoDelFrenoConRechazos(total = 2, rechazadas = 2, puedeResolver = false, antesDe = "cerrar sesión"),
        )
    }

    @Test fun `P1 un gerente con 1 rechazada sabe donde resolverla`() {
        assertEquals(
            "Hay 1 operación por revisar. Resuélvela en Ventas › Pendientes › «Otras operaciones y revisiones anteriores» antes de cerrar sesión.",
            textoDelFrenoConRechazos(total = 1, rechazadas = 1, puedeResolver = true, antesDe = "cerrar sesión"),
        )
    }

    /** Codex r2 #1: «lo demás» puede no subir solo (un vale por imprimir): no se promete que se sincroniza. */
    @Test fun `P1 con algo mas pendiente, se pide sincronizar o resolverlo y luego el gerente`() {
        assertEquals(
            "Hay 3 operaciones pendientes: 1 operación por revisar y 2 más. Sincroniza o resuelve lo demás y " +
                "pídele a un gerente que resuelva lo rechazado: Más › Cambiar usuario.",
            textoDelFrenoConRechazos(total = 3, rechazadas = 1, puedeResolver = false, antesDe = "cerrar sesión"),
        )
        assertEquals(
            "Hay 2 operaciones pendientes: 1 operación por revisar y 1 más. Sincroniza o resuelve lo demás y " +
                "resuelve lo rechazado en Ventas › Pendientes › «Otras operaciones y revisiones anteriores» antes de cambiar de establecimiento.",
            textoDelFrenoConRechazos(total = 2, rechazadas = 1, puedeResolver = true, antesDe = "cambiar de establecimiento"),
        )
    }

    // MARK: - Qué cuenta como rechazado (Codex r1)

    private fun pagos(porRevisar: Int, bloqueantes: Int = porRevisar, sinEnviar: Int = 0, sinConfirmar: Int = 0) = mockk<PaymentSyncService> {
        coEvery { porRevisarDeLaSucursal("v-1") } returns porRevisar
        coEvery { sinConfirmarPorRevisar() } returns sinConfirmar
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

        assertEquals(
            "1 cobro + 1 ronda + 2 paquetes, todo de esta sucursal",
            4,
            rechazadasPorRevisar(pagos(porRevisar = 1), cola(rechazadas = 1), paquetes, "v-1"),
        )
    }

    // MARK: - D52: lo de otras sucursales se dice con su nombre

    /** D52: lo de otra sucursal sólo se ve y se resuelve con ella abierta: el aviso dice cuál y cómo llegar. */
    @Test fun `P1 D52 lo pendiente de otra sucursal dice cual y como llegar`() {
        val nombres = mapOf("v-centro" to "Sucursal Centro", "v-norte" to "Sucursal Norte")
        assertEquals(
            "Hay 1 operación pendiente de Sucursal Centro: cambia a esa sucursal para resolverla (Más › Cambiar de establecimiento).",
            textoDeLoDeOtrasSucursales(mapOf("v-centro" to 1), nombres),
        )
        assertEquals(
            "Hay 3 operaciones pendientes de Sucursal Centro y Sucursal Norte: cambia a cada sucursal para resolverlas " +
                "(Más › Cambiar de establecimiento).",
            textoDeLoDeOtrasSucursales(mapOf("v-centro" to 2, "v-norte" to 1), nombres),
        )
        assertEquals(null, textoDeLoDeOtrasSucursales(emptyMap(), nombres))
    }

    /**
     * D52: una sucursal a la que esta sesión no tiene acceso. Codex r1 #2: «Cambiar usuario» no lleva a otra sucursal (el PIN es de la
     * abierta), así que no se promete ese camino: sólo a quién avisar.
     */
    @Test fun `P1 D52 lo de una sucursal sin acceso no promete un camino que no existe`() {
        assertEquals(
            "Hay 1 operación pendiente de otra sucursal a la que esta sesión no tiene acceso: avisa a un gerente de esa sucursal.",
            textoDeLoDeOtrasSucursales(mapOf("v-ajena" to 1), mapOf("v-centro" to "Sucursal Centro")),
        )
    }

    /** Codex D52 r2: una sucursal sin acceso no le quita a las conocidas su nombre ni su camino. */
    @Test fun `P1 D52 lo de una sucursal conocida y lo de una sin acceso se dicen por separado`() {
        assertEquals(
            "Hay 1 operación pendiente de Sucursal Centro: cambia a esa sucursal para resolverla (Más › Cambiar de establecimiento). " +
                "Hay 1 operación pendiente de otra sucursal a la que esta sesión no tiene acceso: avisa a un gerente de esa sucursal.",
            textoDeLoDeOtrasSucursales(mapOf("v-centro" to 1, "v-ajena" to 1), mapOf("v-centro" to "Sucursal Centro")),
        )
    }

    /**
     * D53: un cobro «sin confirmar» que espera a una persona (un «Ya pagó de otra forma» a medias) también lo resuelve un gerente en la
     * misma hoja: cuenta como «por revisar», y desde D53 tampoco frena «Cambiar usuario».
     */
    @Test fun `P1 D53 un cobro sin confirmar que espera a una persona cuenta como por revisar`() = runBlocking {
        assertEquals(1, rechazadasPorRevisar(pagos(porRevisar = 0, sinConfirmar = 1), cola(), null, "v-1"))
    }
}
