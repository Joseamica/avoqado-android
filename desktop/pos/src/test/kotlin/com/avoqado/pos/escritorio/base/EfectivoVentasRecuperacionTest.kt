package com.avoqado.pos.escritorio.base

import com.avoqado.escritorio.ContextoDeEscritorio
import com.avoqado.pos.cashdrawer.data.model.CashDrawerEventEntity
import com.avoqado.pos.cashdrawer.data.model.CashDrawerSessionEntity
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.PendingPaymentEntity
import com.avoqado.pos.core.di.DatabaseModule
import com.avoqado.pos.payment.data.CashPaymentRepository
import com.avoqado.pos.payment.data.CapturaDelCobro
import com.avoqado.pos.payment.data.ConciliacionDelCobro
import com.avoqado.pos.payment.data.SalidaDelApartado
import com.avoqado.pos.payment.data.model.CreateOrderRequest
import com.avoqado.pos.sync.data.SalesNoticeStore
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class EfectivoVentasRecuperacionTest {
    @Test fun `recuperar un apartado reconocido conserva D15 caja autor y fuentes y anota una sola venta`() = runBlocking {
        val carpeta = Files.createTempDirectory("efectivo-ventas-recuperacion-")
        val contexto = ContextoDeEscritorio(carpeta)
        var db = DatabaseModule.provideDatabase(contexto)
        try {
            val storage = SecureStorage(contexto).apply { venueId = id(90); userId = id(91) }
            val pagos = db.pendingPaymentDao()
            val cajon = db.cashDrawerDao()
            val cash = CashPaymentRepository(storage, pagos, SalidaDelApartado(pagos, cajon, storage, db))
            val avisos = SalesNoticeStore(db.salesNoticeDao(), cash)
            val ahora = System.currentTimeMillis()
            cajon.insertSession(CashDrawerSessionEntity(id(70), id(90), "Caja A", id(91), "Ana", ahora - 60000, 50000))
            val previa = CashDrawerEventEntity(id(71), id(70), id(90), "CASH_SALE", 11000, "otra venta ya conocida", id(91), "Ana", createdAt = ahora - 10000)
            cajon.insertEvent(previa)
            cash.reservarCobro(CreateOrderRequest(emptyList(), subtotal = 11000, total = 11000, paymentMethod = "CASH"),
                id(91), 12000, 1000, null, idempotencyKey = id(1), cajaDelCobro = id(70), capturadaEn = ahora)
            val original = assertNotNull(pagos.findByIdQ15(id(1)))
            val captura = assertNotNull(ConciliacionDelCobro.leerCaptura(original.q15CaptureJson, id(90), id(1)))
            assertEquals(listOf(id(71)), captura.ventasYaEnLaCaja)
            assertEquals(ahora, captura.primeraCapturaEn)
            assertEquals(true, captura.daPorCobradaAlAbandonar)
            assertEquals(0, cash.darPorCobradasLasAbandonadas(), "La reserva viva no es del barrido")
            cash.olvidarReservaEnMemoria(id(1))
            val aviso = avisos.page(id(90)).rows.single()
            assertTrue(avisos.acknowledge(aviso, id(92), ahora + 1))
            assertEquals(original, pagos.findByIdQ15(id(1)))
            assertEquals(listOf(previa), cajon.getSessionEvents(id(70)), "Entendido no postea caja")

            storage.userId = id(93)
            cajon.insertSession(CashDrawerSessionEntity(id(72), id(90), "Caja B", id(93), "Bea", ahora + 1, 0))
            assertEquals(1, cash.darPorCobradasLasAbandonadas())
            assertEquals(0, cash.darPorCobradasLasAbandonadas())
            assertEquals(original.copy(syncStatus = "PENDING"), pagos.findByIdQ15(id(1)))
            val ventas = cajon.getSessionEvents(id(70))
            assertEquals(2, ventas.size)
            assertTrue(previa in ventas)
            val recuperada = ventas.single { it.id != previa.id }
            assertEquals(11000, recuperada.amountCents)
            assertEquals(id(91), recuperada.staffId)
            assertEquals(ahora, recuperada.createdAt)
            assertEquals(id(70), recuperada.sessionId)
            assertTrue(cajon.getSessionEvents(id(72)).isEmpty())
            assertEquals(id(92), avisos.page(id(90)).rows.single().acknowledgement?.actor)
            db.close()
            db = DatabaseModule.provideDatabase(contexto)
            assertEquals(original.copy(syncStatus = "PENDING"), db.pendingPaymentDao().findByIdQ15(id(1)))
            assertEquals(ventas, db.cashDrawerDao().getSessionEvents(id(70)))
            assertEquals(id(92), db.salesNoticeDao().page(id(90), "", null, emptySet()).rows.single().acknowledgement?.actor)
        } finally { db.close(); carpeta.toFile().deleteRecursively() }
    }

    /** Ruling ROOT: la fila registra el último intento; Ventas muestra la primera captura y no duplica la gemela tardía. */
    @Test fun reintentoTardioConGemelaDelSegundoIntentoConservaFuenteYNoDuplicaCaja() = runBlocking {
        val carpeta = Files.createTempDirectory("efectivo-ventas-reintento-")
        val contexto = ContextoDeEscritorio(carpeta)
        val db = DatabaseModule.provideDatabase(contexto)
        try {
            val storage = SecureStorage(contexto).apply { venueId = id(90); userId = id(91) }
            val pagos = db.pendingPaymentDao()
            val cajon = db.cashDrawerDao()
            val cash = CashPaymentRepository(storage, pagos, SalidaDelApartado(pagos, cajon, storage, db))
            val avisos = SalesNoticeStore(db.salesNoticeDao(), cash)
            val primera = System.currentTimeMillis() - 60 * 60000
            val segundoIntento = primera + 20 * 60000
            cajon.insertSession(CashDrawerSessionEntity(id(70), id(90), "Caja A", id(91), "Ana", primera - 60000, 0))
            val pedido = CreateOrderRequest(emptyList(), subtotal = 11000, total = 11000, paymentMethod = "CASH")
            cash.reservarCobro(pedido, id(91), 12000, 1000, null, idempotencyKey = id(1), cajaDelCobro = id(70), capturadaEn = primera)
            cash.olvidarReservaEnMemoria(id(1))
            assertTrue(avisos.acknowledge(avisos.page(id(90)).rows.single(), id(92), primera + 1))
            val original = assertNotNull(pagos.findByIdQ15(id(1)))
            cash.reservarCobro(pedido, id(91), 12000, 1000, null, idempotencyKey = id(1), cajaDelCobro = id(70), capturadaEn = segundoIntento)
            assertEquals(segundoIntento, pagos.findByIdQ15(id(1))?.createdAt)
            assertEquals(original.q15CaptureJson, pagos.findByIdQ15(id(1))?.q15CaptureJson)
            assertEquals(original.orderRequestJson, pagos.findByIdQ15(id(1))?.orderRequestJson)
            assertEquals(original.staffId, pagos.findByIdQ15(id(1))?.staffId)
            assertEquals(primera, db.salesNoticeDao().page(id(90), "", null, emptySet()).rows.single().originalCreatedAt)
            val gemela = CashDrawerEventEntity(id(73), id(70), id(90), "CASH_SALE", 11000, null, id(91), "Ana", createdAt = segundoIntento + 60000)
            cajon.insertEvent(gemela)
            cash.olvidarReservaEnMemoria(id(1))
            assertEquals(1, cash.darPorCobradasLasAbandonadas())
            assertEquals(listOf(gemela), cajon.getSessionEvents(id(70)), "La misma llave ya está contada por el servidor")
            assertEquals(id(92), avisos.page(id(90)).rows.single().acknowledgement?.actor)
        } finally { db.close(); carpeta.toFile().deleteRecursively() }
    }

    @Test fun `Ventas usa createdAt legado si no hay primera captura valida de ese mismo folio y negocio`() = runBlocking {
        val carpeta = Files.createTempDirectory("efectivo-ventas-fecha-legada-")
        val db = DatabaseModule.provideDatabase(ContextoDeEscritorio(carpeta))
        try {
            val fecha = 1700000000000L
            val capturas = listOf(null, "{}", ConciliacionDelCobro.guardarCaptura(
                CapturaDelCobro(id(94), id(3), amountCents = 11000, tipCents = 0, method = "CASH", primeraCapturaEn = fecha - 1000)))
            capturas.forEachIndexed { n, captura ->
                db.pendingPaymentDao().insertRaw(PendingPaymentEntity(id(n + 1), id(90), id(91), 11000, 0, "CASH", "FAST",
                    syncStatus = "FAILED", createdAt = fecha, q15CaptureJson = captura))
            }
            val filas = db.salesNoticeDao().page(id(90), "", null, emptySet()).rows
            assertEquals(3, filas.size)
            assertEquals(listOf(fecha, fecha, fecha), filas.map { it.originalCreatedAt })
            assertEquals(listOf(fecha, fecha, fecha), filas.map { it.createdAt })
        } finally { db.close(); carpeta.toFile().deleteRecursively() }
    }

    private fun id(n: Int) = "c" + n.toString().padStart(24, '0')
}
