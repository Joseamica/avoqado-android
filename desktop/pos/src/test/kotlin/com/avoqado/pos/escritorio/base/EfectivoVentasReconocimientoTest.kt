package com.avoqado.pos.escritorio.base

import com.avoqado.escritorio.ContextoDeEscritorio
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.di.DatabaseModule
import com.avoqado.pos.payment.data.CanalDelCobro
import com.avoqado.pos.payment.data.CashPaymentRepository
import com.avoqado.pos.payment.data.ConciliacionDelCobro
import com.avoqado.pos.payment.data.model.CreateOrderRequest
import com.avoqado.pos.sync.data.SalesNoticeStore
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class EfectivoVentasReconocimientoTest {
    @Test fun `Entendido conserva reserva abandonada y captura al promover y cambiar cajera y causa`() = runBlocking {
        val carpeta = Files.createTempDirectory("efectivo-ventas-ack-")
        val contexto = ContextoDeEscritorio(carpeta)
        val db = DatabaseModule.provideDatabase(contexto)
        try {
            val storage = SecureStorage(contexto).apply { venueId = id(90); userId = id(91) }
            val pagos = db.pendingPaymentDao()
            val cash = CashPaymentRepository(storage, pagos)
            val avisos = SalesNoticeStore(db.salesNoticeDao(), cash)
            cash.reservarCobro(CreateOrderRequest(emptyList(), subtotal = 11000, total = 11000, paymentMethod = "CASH"),
                id(91), 12000, 1000, null, orderId = id(80), idempotencyKey = id(1))
            val original = assertNotNull(pagos.findByIdQ15(id(1)))
            val avisoVivo = db.salesNoticeDao().page(id(90), "", null, emptySet()).rows.single()
            assertTrue(avisos.page(id(90)).rows.isEmpty())
            assertFalse(avisos.acknowledge(avisoVivo, id(92), 100))
            assertEquals(original, pagos.findByIdQ15(id(1)))

            cash.olvidarReservaEnMemoria(id(1))
            val abandonado = avisos.page(id(90)).rows.single()
            assertTrue(avisos.acknowledge(abandonado, id(92), 101))
            cash.soltarReserva(id(1))
            assertEquals(original, pagos.findByIdQ15(id(1)), "Entendido y soltar nunca borran la captura abandonada")
            assertEquals(1, pagos.enVueloAPendiente(id(1)))
            assertEquals(original.copy(syncStatus = "PENDING"), pagos.findByIdQ15(id(1)))
            storage.userId = id(93)
            val reconocida = avisos.page(id(90)).rows.single()
            assertEquals(id(92), reconocida.acknowledgement?.actor)
            assertFalse(avisos.acknowledge(reconocida, id(93), 102))
            assertEquals(id(92), avisos.page(id(90)).rows.single().acknowledgement?.actor)

            val resultado = ConciliacionDelCobro.evaluar(original.q15CaptureJson, id(90), id(1), CanalDelCobro.ORDER,
                """{"success":true,"payment":{"paymentId":"${id(50)}","orderId":"${id(80)}","status":"COMPLETED","amount":109,"tipAmount":0,"method":"CASH"}}""")
            assertNotNull(resultado.motivo)
            pagos.registrarResultadoQ15(id(90), id(1), resultado.aplicado, resultado.motivo, resultado.resultadoJson, 103)
            val nuevo = avisos.page(id(90)).rows.single()
            assertNull(nuevo.acknowledgement, "Una causa nueva vuelve a avisar a la nueva cajera")
            assertFalse(avisos.acknowledge(reconocida, id(93), 104), "El aviso anterior no reconoce la causa nueva")
            val actual = assertNotNull(pagos.findByIdQ15(id(1)))
            assertEquals(original.q15CaptureJson, actual.q15CaptureJson)
            assertEquals(original.orderRequestJson, actual.orderRequestJson)
            assertEquals(original.amountCents, actual.amountCents)
            assertEquals(original.staffId, actual.staffId)
            assertEquals(original.createdAt, actual.createdAt)
        } finally { db.close(); carpeta.toFile().deleteRecursively() }
    }

    private fun id(n: Int) = "c" + n.toString().padStart(24, '0')
}
