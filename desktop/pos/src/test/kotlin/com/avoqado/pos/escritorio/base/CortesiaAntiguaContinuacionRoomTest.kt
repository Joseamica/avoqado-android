package com.avoqado.pos.escritorio.base

import androidx.room.withTransaction
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.avoqado.escritorio.ContextoDeEscritorio
import com.avoqado.pos.cashdrawer.data.model.CashDrawerEventEntity
import com.avoqado.pos.cashdrawer.data.model.CashDrawerSessionEntity
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.PendingPaymentEntity
import com.avoqado.pos.core.di.DatabaseModule
import com.avoqado.pos.payment.data.CashPaymentRepository
import com.avoqado.pos.payment.data.ConciliacionDelCobro
import com.avoqado.pos.payment.data.SalidaDelApartado
import com.avoqado.pos.payment.data.model.CreateOrderRequest
import com.avoqado.pos.payment.data.model.OrderItemRequest
import com.avoqado.pos.sync.data.SalesNoticeStore
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * BORRADOR: promover como CortesiaAntiguaContinuacionRoomTest.kt, en ESTE paquete de desktop/pos.
 * Usa DatabaseModule/Room/KSP y los productores existentes. No implementa ni copia el CAS.
 * No acredita confirmación HTTP, permisos del servicio, SQLITE_BUSY entre handles ni aparatos.
 */
class CortesiaAntiguaContinuacionRoomTest {
    @Test fun fuenteExactaSoloReiniciaRetryYConservaLos33CamposCajaYAckAlReabrirRoom() = runBlocking {
        Fixture().use { f ->
            val original = f.sembrar()
            val ack = f.reconocimientos()
            val eventos = f.db.cashDrawerDao().getSessionEvents(id(70))
            val sesiones = f.filas("cash_drawer_sessions")

            assertEquals(1, f.pagos.reanudarCortesiaAntiguaQ4(original) { true })
            val esperado = original.copy(syncStatus = "PENDING", retryCount = 0, lastError = null, lastRetryAt = null)
            assertEquals(esperado, f.fuente(), "La igualdad de la entidad cubre sus 33 columnas, incluidos los JSON y NULL")
            assertEquals(ack, f.reconocimientos(), "El ACK físico completo permanece, aunque su versión sea histórica")
            assertEquals(eventos, f.db.cashDrawerDao().getSessionEvents(id(70)))
            assertEquals(sesiones, f.filas("cash_drawer_sessions"))
            assertEquals(0, f.pagos.reanudarCortesiaAntiguaQ4(original) { true }, "La foto FAILED ya fue consumida")
            assertEquals(esperado, f.fuente())

            f.reabrir()
            assertEquals(esperado, f.fuente(), "La continuación es durable; no depende de un objeto o mapa de la fixture")
            assertEquals(ack, f.reconocimientos())
            assertEquals(eventos, f.db.cashDrawerDao().getSessionEvents(id(70)))
            assertEquals(sesiones, f.filas("cash_drawer_sessions"))
        }
    }

    @Test fun otraInstanciaRoomCambiaFuenteYElCasViejoNoBorraSuDiagnosticoNiSuAck() = runBlocking {
        Fixture().use { f ->
            val original = f.sembrar()
            val ack = f.reconocimientos()
            val eventos = f.filas("cash_drawer_events")
            val cambiado = original.copy(lastError = "Diagnóstico posterior de la misma cortesía", retryCount = 9,
                lastRetryAt = f.primera + 9000)
            val otra = DatabaseModule.provideDatabase(f.contexto)
            try {
                assertEquals(original, otra.pendingPaymentDao().findForVenue(original.venueId, original.id))
                // Escritura real con el DAO generado; es preparación de una fuente posterior, no un CAS copiado.
                otra.pendingPaymentDao().insertRaw(cambiado)
                assertEquals(cambiado, f.fuente())
                assertEquals(0, f.pagos.reanudarCortesiaAntiguaQ4(original) { true },
                    "Mismo venue/K y finanzas no autorizan pisar una fuente completa que ya cambió")
                assertEquals(cambiado, f.fuente())
                assertEquals(ack, f.reconocimientos())
                assertEquals(eventos, f.filas("cash_drawer_events"))

                assertEquals(0, f.pagos.reanudarCortesiaAntiguaQ4(cambiado.copy(venueId = id(93))) { true })
                assertEquals(cambiado, f.fuente(), "La misma K de otro venue no se adopta")
                assertEquals(1, f.pagos.reanudarCortesiaAntiguaQ4(cambiado) { true },
                    "La fuente posterior sigue siendo elegible si se usa su foto exacta")
                assertEquals(cambiado.copy(syncStatus = "PENDING", retryCount = 0, lastError = null, lastRetryAt = null),
                    otra.pendingPaymentDao().findForVenue(original.venueId, original.id))
                assertEquals(ack, f.reconocimientos())
                assertEquals(eventos, f.filas("cash_drawer_events"))
            } finally {
                otra.close()
            }
        }
    }

    @Test fun contextoRevocadoDuranteEsperaDelWriterSeConsultaDentroDeLaTxGeneradaYSinMutarFuente() = runBlocking {
        withContext(Dispatchers.IO) {
            Fixture().use { f ->
                val original = f.sembrar()
                val ack = f.reconocimientos()
                val eventos = f.filas("cash_drawer_events")
                val entro = CompletableDeferred<Unit>()
                val liberar = CompletableDeferred<Unit>()
                val contextoVigente = AtomicBoolean(true)
                val consultas = ConcurrentLinkedQueue<Boolean>()
                // Patrón Q16: el helper real desktop retiene useWriterConnection + IMMEDIATE.
                val holder = launch {
                    f.db.withTransaction {
                        entro.complete(Unit)
                        liberar.await()
                    }
                }
                var operacion: kotlinx.coroutines.Deferred<Int>? = null
                try {
                    withTimeout(10000) { entro.await() }
                    assertTrue(contextoVigente.get())
                    // Entrada directa @Transaction para probar el wrapper GENERADO, sin mocking ni función sustituta.
                    val pendiente = async(start = CoroutineStart.UNDISPATCHED) {
                        f.pagos.reanudarCortesiaAntiguaTxQ4(original) {
                            contextoVigente.get().also { consultas.add(it) }
                        }
                    }
                    operacion = pendiente
                    assertFalse(pendiente.isCompleted, "La transacción de cortesía no debe saltarse el escritor retenido")
                    assertTrue(consultas.isEmpty(), "La evaluación dentro de la tx ocurre después de obtener el writer")
                    contextoVigente.set(false)
                    liberar.complete(Unit)
                    withTimeout(10000) {
                        holder.join()
                        assertEquals(0, pendiente.await())
                    }
                    assertTrue(consultas.isNotEmpty(), "Retornar0 sin consultar contexto no demuestra la guardia postespera")
                    assertTrue(consultas.all { !it }, "El callback real de la tx observa la revocación, no el valor anterior")
                    assertEquals(original, f.fuente())
                    assertEquals(ack, f.reconocimientos())
                    assertEquals(eventos, f.filas("cash_drawer_events"))
                } finally {
                    // Ninguna barrera/Job/tx queda viva si falla una aserción o el seam devuelve antes de esperar.
                    liberar.complete(Unit)
                    withContext(NonCancellable) {
                        operacion?.cancelAndJoin()
                        holder.cancelAndJoin()
                    }
                }
            }
        }
    }

    /** Misma configuración y productores que EfectivoVentasCortesiaRetryRoomTest; sin modificar su fixture privada. */
    private class Fixture : AutoCloseable {
        val carpeta = Files.createTempDirectory("cortesia-antigua-room-")
        val contexto = ContextoDeEscritorio(carpeta)
        var db = DatabaseModule.provideDatabase(contexto)
            private set
        val pagos get() = db.pendingPaymentDao()
        private val storage = SecureStorage(contexto).apply { venueId = id(90); userId = id(91) }
        private val salida = SalidaDelApartado(pagos, db.cashDrawerDao(), storage, db)
        private val cash = CashPaymentRepository(storage, pagos, salida)
        private val avisos = SalesNoticeStore(db.salesNoticeDao(), cash)
        val primera = System.currentTimeMillis() - 60 * 60000L

        suspend fun sembrar(): PendingPaymentEntity {
            db.cashDrawerDao().insertSession(CashDrawerSessionEntity(id(70), id(90), "Caja original", id(91), "Ana",
                primera - 60000, 10000))
            db.cashDrawerDao().insertEvent(CashDrawerEventEntity(id(71), id(70), id(90), "CASH_SALE", 11000,
                "Venta anterior", id(91), "Ana", createdAt = primera - 1000))
            val pedido = CreateOrderRequest(items = listOf(OrderItemRequest(productId = id(81), name = "Camisa",
                quantity = 1, unitPrice = 11000, isCortesia = true, cortesiaReason = "Cortesía autorizada", externalId = id(84))),
                subtotal = 0, total = 0, paymentMethod = "CASH", note = "Pedido original")
            cash.reservarCobro(pedido, id(91), 0, 0, 5, orderId = id(80), orderExternalId = id(85),
                customerId = id(95), idempotencyKey = id(1), cajaDelCobro = id(70), capturadaEn = primera)
            val reservada = fuente()
            assertEquals("EN_VUELO", reservada.syncStatus)
            assertEquals(0, reservada.amountCents)
            assertEquals(0, reservada.tipCents)
            assertEquals("ORDER", reservada.paymentType)
            assertEquals("CASH", reservada.method)
            assertNull(reservada.tenderTypeId)
            assertNull(reservada.tenderRevision)
            assertNull(reservada.q15Reason)
            val captura = assertNotNull(ConciliacionDelCobro.leerCaptura(reservada.q15CaptureJson, id(90), id(1)))
            assertEquals(assertNotNull(reservada.orderRequestJson), captura.orderRequestJson)
            assertEquals(primera, captura.primeraCapturaEn)
            assertEquals(true, captura.daPorCobradaAlAbandonar)
            assertEquals(listOf(id(71)), captura.ventasYaEnLaCaja)
            assertEquals(id(91), reservada.staffId)
            assertEquals(id(70), reservada.cashDrawerSessionId)
            cash.olvidarReservaEnMemoria(id(1))
            assertTrue(avisos.acknowledge(avisos.page(id(90)).rows.single(), id(92), primera + 1))
            assertEquals(1, reconocimientos().size, "El ACK real existe antes de probar cualquier CAS")

            // Historia propia igual a la familia Task21; no inventa datos monetarios ni toca los JSON originales.
            val original = reservada.copy(syncStatus = "FAILED", retryCount = 4, lastRetryAt = primera + 2,
                lastError = "Cortesía no confirmada", orderNumber = "ORIGINAL-110",
                q15InitialResultJson = "{\"error\":\"initial-failure\"}", q15ResultJson = "{\"error\":\"courtesy-not-confirmed\"}",
                q15DetectedAt = primera + 3, q15ReviewedAt = primera + 4, q15ReviewedBy = id(92), q15ReviewRole = "MANAGER")
            pagos.insertRaw(original)
            assertEquals(original, fuente())
            BundledSQLiteDriver().open(carpeta.resolve("databases/avoqado_db").toString()).use {
                assertEquals(18L, it.leerLargo("PRAGMA user_version"))
            }
            return original
        }

        suspend fun fuente(): PendingPaymentEntity = assertNotNull(pagos.findForVenue(id(90), id(1)))
        fun filas(tabla: String): List<List<String>> = BundledSQLiteDriver()
            .open(carpeta.resolve("databases/avoqado_db").toString()).use { it.volcar(tabla) }
        fun reconocimientos(): List<List<String>> = filas("sales_notice_acknowledgements")
        fun reabrir() {
            db.close()
            db = DatabaseModule.provideDatabase(contexto)
        }
        override fun close() {
            db.close()
            carpeta.toFile().deleteRecursively()
        }
    }

    private companion object { fun id(n: Int) = "c" + n.toString().padStart(24, '0') }
}
