package com.avoqado.pos.payment

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.PendingPaymentDao
import com.avoqado.pos.core.data.local.database.PendingPaymentEntity
import com.avoqado.pos.payment.data.CashPaymentRepository
import com.avoqado.pos.payment.data.CobrosParaSoporte
import com.avoqado.pos.payment.data.CobrosParaSoporte.Declaracion
import com.avoqado.pos.payment.data.CobrosParaSoporte.Resultado
import com.avoqado.pos.payment.data.ConciliacionDelCobro
import com.avoqado.pos.payment.data.PaymentSyncService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D63 (founder 16-nov, «A»): un cobro retenido sin otra salida («conservado para revisión», heredado sin captura, o fallido que el
 * servidor ya no acepta) frenaba «Cerrar sesión» para siempre. Un GERENTE lo deja para soporte con rastro; la fila no se borra ni se
 * sube, y desde ahí ya no frena. Promesa: `Avoqado/docs/audit/2026-11-16-salida-de-gerente-cobros-retenidos/promesa.md`.
 */
class CobrosParaSoporteTest {
    private val aqui = "venue-atole"

    private fun fila(
        id: String = "cobro-1",
        estado: String = "EN_VUELO",
        motivo: String? = ConciliacionDelCobro.RESULTADO_NO_COMPROBADO,
        venue: String = aqui,
        revisadaEn: Long? = null,
    ) = PendingPaymentEntity(id, venue, "cajera", 12_900, 0, "CASH", "FAST", syncStatus = estado, q15Reason = motivo, q15ReviewedAt = revisadaEn)

    // ── p1 · quién califica ──────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `P1 califica el conservado para revision, el retenido en la cola y todo fallido de esta sucursal`() {
        assertTrue(CobrosParaSoporte.aplica(fila(estado = "EN_VUELO", motivo = ConciliacionDelCobro.RESULTADO_NO_COMPROBADO), aqui))
        assertTrue(CobrosParaSoporte.aplica(fila(estado = "EN_VUELO", motivo = ConciliacionDelCobro.CAPTURA_SIN_EVIDENCIA), aqui))
        assertTrue(CobrosParaSoporte.aplica(fila(estado = "PENDING", motivo = ConciliacionDelCobro.PRICE_PROOF_PENDING), aqui))
        assertTrue(CobrosParaSoporte.aplica(fila(estado = "FAILED", motivo = null), aqui))
        assertTrue(CobrosParaSoporte.aplica(fila(estado = "FAILED", motivo = ConciliacionDelCobro.CAPTURA_SIN_EVIDENCIA), aqui))
    }

    @Test fun `P1 Codex r1 1 califica la fila vieja sin sucursal, que el freno cuenta como de la abierta`() {
        assertTrue(CobrosParaSoporte.aplica(fila(estado = "FAILED", motivo = null, venue = ""), aqui))
        assertTrue(CobrosParaSoporte.aplica(fila(estado = "FAILED", motivo = ConciliacionDelCobro.CAPTURA_SIN_EVIDENCIA, venue = ""), aqui))
    }

    @Test fun `P1 Codex r1 1 la fila vieja sin sucursal se ve, se deja para soporte y deja de frenar`() = runTest {
        val vieja = fila(id = "sin-sucursal", estado = "FAILED", motivo = ConciliacionDelCobro.CAPTURA_SIN_EVIDENCIA, venue = "")
        val dao = daoCon(fallidos = listOf(vieja)).also {
            coEvery { it.findForVenue(aqui, "sin-sucursal") } returns null
            coEvery { it.findForVenue("", "sin-sucursal") } returns vieja
        }
        val sesion = mockk<SecureStorage>(relaxed = true) {
            every { venueId } returns aqui
            every { userId } returns "u-gerente"
            every { userRole } returns "MANAGER"
        }
        val notas = CobrosParaSoporte(PrefsEnMemoria().context)
        val pagos = servicio(dao, notas, sesion)
        assertEquals(listOf("sin-sucursal"), pagos.retenidasSinSucursal().map { it.id })
        assertEquals(1, pagos.frenoPorSucursal(aqui).deAqui)

        assertEquals(Resultado.GUARDADA, pagos.dejarParaSoporte("sin-sucursal", Declaracion.NO_SE))

        assertEquals(0, pagos.frenoPorSucursal(aqui).deAqui)
        // Sigue a la vista, con su nota.
        assertEquals(listOf("sin-sucursal"), pagos.retenidasSinSucursal().map { it.id })
        assertEquals("", notas.nota("sin-sucursal")?.venueId)
    }

    @Test fun `P1 no califica lo que sale solo, lo subido, lo revisado ni lo de otra sucursal`() {
        // Un EN_VUELO sin motivo es un cobro vivo o un «Cobro interrumpido» con «Sí se cobró» / «Conservar».
        assertFalse(CobrosParaSoporte.aplica(fila(estado = "EN_VUELO", motivo = null), aqui))
        assertFalse(CobrosParaSoporte.aplica(fila(estado = "PENDING", motivo = null), aqui))
        assertFalse(CobrosParaSoporte.aplica(fila(estado = "SYNCING", motivo = null), aqui))
        assertFalse(CobrosParaSoporte.aplica(fila(estado = "SYNCED", motivo = ConciliacionDelCobro.RESULTADO_NO_COMPROBADO), aqui))
        assertFalse(CobrosParaSoporte.aplica(fila(estado = "FAILED", revisadaEn = 1L), aqui))
        assertFalse(CobrosParaSoporte.aplica(fila(estado = "FAILED", venue = "venue-centro"), aqui))
        assertFalse(CobrosParaSoporte.aplica(fila(estado = "FAILED"), ""))
    }

    // ── p2 · p3 · p4 · p8 · la nota ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `P1 un cajero no puede dejarlo para soporte y no se escribe nada`() = runTest {
        val disco = PrefsEnMemoria()
        val notas = CobrosParaSoporte(disco.context)

        assertEquals(Resultado.SIN_PERMISO, notas.dejar(fila(), aqui, Declaracion.NO_SE, "u-cajera", "Ana", "CASHIER"))
        assertEquals(Resultado.SIN_PERMISO, notas.dejar(fila(), aqui, Declaracion.NO_SE, null, "Ana", "MANAGER"))
        assertTrue(disco.guardado.isEmpty())
    }

    @Test fun `P1 el gerente lo deja para soporte con quien, cuando, que cobro y que dijo`() = runTest {
        val disco = PrefsEnMemoria()
        val notas = CobrosParaSoporte(disco.context)

        assertEquals(Resultado.GUARDADA, notas.dejar(fila(), aqui, Declaracion.SI_PAGO, "u-gerente", "Luis Pérez", "MANAGER", ahora = 1_700L))

        val nota = notas.nota("cobro-1")
        assertNotNull(nota)
        assertEquals(Declaracion.SI_PAGO, nota!!.declaracion)
        assertEquals("u-gerente", nota.actorId)
        assertEquals("Luis Pérez", nota.actorNombre)
        assertEquals("MANAGER", nota.actorRol)
        assertEquals(1_700L, nota.marcadoEn)
        assertEquals("EN_VUELO", nota.estado)
        assertEquals(ConciliacionDelCobro.RESULTADO_NO_COMPROBADO, nota.motivo)
        assertEquals(12_900, nota.importeCents)
        assertTrue(notas.vigente(fila()))
    }

    @Test fun `P2 la primera nota gana, marcar otra vez no la cambia`() = runTest {
        val notas = CobrosParaSoporte(PrefsEnMemoria().context)
        notas.dejar(fila(), aqui, Declaracion.NO_PAGO, "u-gerente", "Luis", "MANAGER", ahora = 1L)

        assertEquals(Resultado.YA_ESTABA, notas.dejar(fila(), aqui, Declaracion.SI_PAGO, "u-duena", "Rosa", "OWNER", ahora = 2L))
        assertEquals(Declaracion.NO_PAGO, notas.nota("cobro-1")!!.declaracion)
        assertEquals("u-gerente", notas.nota("cobro-1")!!.actorId)
    }

    @Test fun `P1 si el disco no guarda, no se marca y no frena distinto`() = runTest {
        val disco = PrefsEnMemoria().apply { discoSano = false }
        val notas = CobrosParaSoporte(disco.context)

        assertEquals(Resultado.NO_SE_PUDO, notas.dejar(fila(), aqui, Declaracion.NO_SE, "u-gerente", "Luis", "MANAGER"))
        assertNull(notas.nota("cobro-1"))
        assertFalse(notas.vigente(fila()))
        assertTrue(disco.enDisco.isEmpty())
    }

    @Test fun `P1 una fila que ya no califica no se marca`() = runTest {
        val notas = CobrosParaSoporte(PrefsEnMemoria().context)
        assertEquals(Resultado.NO_APLICA, notas.dejar(fila(estado = "EN_VUELO", motivo = null), aqui, Declaracion.NO_SE, "u-gerente", "Luis", "MANAGER"))
        assertEquals(Resultado.NO_APLICA, notas.dejar(fila(estado = "FAILED", venue = "venue-centro"), aqui, Declaracion.NO_SE, "u-gerente", "Luis", "MANAGER"))
        assertNull(notas.nota("cobro-1"))
    }

    @Test fun `P1 la nota sobrevive a reiniciar la app`() = runTest {
        val disco = PrefsEnMemoria()
        CobrosParaSoporte(disco.context).dejar(fila(), aqui, Declaracion.NO_SE, "u-gerente", "Luis", "MANAGER")

        val despues = CobrosParaSoporte(disco.context)
        assertEquals(Declaracion.NO_SE, despues.nota("cobro-1")?.declaracion)
    }

    @Test fun `P2 si la fila deja de calificar la nota ya no vale y si vuelve a fallar vale otra vez`() = runTest {
        val notas = CobrosParaSoporte(PrefsEnMemoria().context)
        notas.dejar(fila(estado = "FAILED", motivo = null), aqui, Declaracion.NO_SE, "u-gerente", "Luis", "MANAGER")

        assertFalse(notas.vigente(fila(estado = "PENDING", motivo = null)))
        assertTrue(notas.vigente(fila(estado = "FAILED", motivo = null)))
    }

    // ── p5 · p6 · el freno de cerrar sesión ──────────────────────────────────────────────────────────────────────────────────────

    private fun servicio(dao: PendingPaymentDao, notas: CobrosParaSoporte, sesion: SecureStorage = mockk(relaxed = true)) =
        PaymentSyncService(dao, sesion, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk<CashPaymentRepository>(relaxed = true), cobrosParaSoporte = notas)

    private fun daoCon(enVuelo: List<PendingPaymentEntity> = emptyList(), fallidos: List<PendingPaymentEntity> = emptyList(),
        cola: List<PendingPaymentEntity> = emptyList()) = mockk<PendingPaymentDao>(relaxed = true) {
        coEvery { getUnsyncedPayments() } returns cola
        coEvery { enVueloDeTodas() } returns enVuelo
        coEvery { getFailedPayments() } returns fallidos
    }

    @Test fun `P1 el conservado para revision deja de frenar cerrar sesion al dejarlo para soporte`() = runTest {
        val conservado = fila()
        val notas = CobrosParaSoporte(PrefsEnMemoria().context)
        val pagos = servicio(daoCon(enVuelo = listOf(conservado)), notas)
        assertEquals(1, pagos.frenoPorSucursal(aqui).deAqui)

        notas.dejar(conservado, aqui, Declaracion.NO_SE, "u-gerente", "Luis", "MANAGER")

        assertEquals(0, pagos.frenoPorSucursal(aqui).deAqui)
        assertEquals(0, pagos.bloqueantesDeLaSucursal(aqui))
    }

    @Test fun `P1 el fallido dejado para soporte no cuenta como rechazado por revisar`() = runTest {
        val fallido = fila(estado = "FAILED", motivo = null)
        val notas = CobrosParaSoporte(PrefsEnMemoria().context)
        val pagos = servicio(daoCon(fallidos = listOf(fallido)), notas)
        assertEquals(1, pagos.porRevisarDeLaSucursal(aqui))

        notas.dejar(fallido, aqui, Declaracion.NO_PAGO, "u-gerente", "Luis", "MANAGER")

        assertEquals(0, pagos.porRevisarDeLaSucursal(aqui))
        assertEquals(0, pagos.frenoPorSucursal(aqui).deAqui)
    }

    @Test fun `P1 lo demas sigue frenando igual`() = runTest {
        val conservado = fila(id = "conservado")
        val otro = fila(id = "otro-conservado")
        val enCola = fila(id = "en-cola", estado = "PENDING", motivo = null)
        val notas = CobrosParaSoporte(PrefsEnMemoria().context)
        notas.dejar(conservado, aqui, Declaracion.NO_SE, "u-gerente", "Luis", "MANAGER")
        val pagos = servicio(daoCon(enVuelo = listOf(conservado, otro), cola = listOf(enCola)), notas)

        assertEquals(2, pagos.frenoPorSucursal(aqui).deAqui)
    }

    @Test fun `P1 dejarParaSoporte relee la fila de la base, usa la sesion y no toca la fila`() = runTest {
        val conservado = fila()
        val dao = daoCon(enVuelo = listOf(conservado)).also { coEvery { it.findForVenue(aqui, "cobro-1") } returns conservado }
        val sesion = mockk<SecureStorage>(relaxed = true) {
            every { venueId } returns aqui
            every { userId } returns "u-gerente"
            every { userRole } returns "ADMIN"
            every { userFirstName } returns "Luis"
            every { userLastName } returns "Pérez"
        }
        val notas = CobrosParaSoporte(PrefsEnMemoria().context)
        val pagos = servicio(dao, notas, sesion)

        assertEquals(Resultado.GUARDADA, pagos.dejarParaSoporte("cobro-1", Declaracion.NO_SE))

        assertEquals("Luis Pérez", notas.nota("cobro-1")?.actorNombre)
        assertEquals("ADMIN", notas.nota("cobro-1")?.actorRol)
        // La fila no se borra ni se cambia: sólo se leyó.
        io.mockk.coVerify(exactly = 0) { dao.deleteFailed(any()) }
        io.mockk.coVerify(exactly = 0) { dao.updateStatus(any(), any()) }
        io.mockk.coVerify(exactly = 0) { dao.retenerPagoQ15(any(), any(), any(), any()) }
        io.mockk.coVerify(exactly = 0) { dao.enVueloAPendiente(any()) }
    }

    @Test fun `P1 dejarParaSoporte de un cobro que ya no esta no escribe`() = runTest {
        val dao = daoCon().also { coEvery { it.findForVenue(aqui, "cobro-1") } returns null }
        val sesion = mockk<SecureStorage>(relaxed = true) {
            every { venueId } returns aqui
            every { userId } returns "u-gerente"
            every { userRole } returns "MANAGER"
        }
        val disco = PrefsEnMemoria()
        val pagos = servicio(dao, CobrosParaSoporte(disco.context), sesion)

        assertEquals(Resultado.NO_APLICA, pagos.dejarParaSoporte("cobro-1", Declaracion.NO_SE))
        assertTrue(disco.guardado.isEmpty())
    }
}
