package com.avoqado.pos.escritorio.base

import com.avoqado.escritorio.ContextoDeEscritorio
import com.avoqado.pos.cashdrawer.data.model.CashDrawerEventEntity
import com.avoqado.pos.cashdrawer.data.model.CashDrawerSessionEntity
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.PendingPaymentEntity
import com.avoqado.pos.core.di.DatabaseModule
import com.avoqado.pos.payment.data.CashPaymentRepository
import com.avoqado.pos.payment.data.ConciliacionDelCobro
import com.avoqado.pos.payment.data.OrderRepository
import com.avoqado.pos.payment.data.SalidaDelApartado
import com.avoqado.pos.payment.data.model.CreateOrderRequest
import com.avoqado.pos.payment.data.model.OrderItemRequest
import com.avoqado.pos.payment.domain.ManualPaymentMethod
import com.avoqado.pos.sync.data.SalesNoticeStore
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import kotlinx.serialization.json.*
import kotlin.test.*

/** Ejecuta los productores reales de reserva/cola, con Room y salida de caja reales; sólo el transporte HTTP falla. */
class EfectivoVentasCortesiaRetryRoomTest {
    @Test fun cortesiaOrderCeroTrasFalloDeRedPasaALaColaConMismaFuenteCajaYReconocimiento() = runBlocking {
        Fixture().use { f ->
            f.reservarOriginal()
            val llamadas = AtomicInteger()
            val transporte = OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor {
                llamadas.incrementAndGet()
                throw IOException("Sin internet en la prueba")
            }.build()
            val ordenes = OrderRepository(f.storage, transporte, f.pagos, f.salida)
            val resultado = ordenes.recordCashPayment(id(80), 0, id(91), idempotencyKey = id(1), capturedVenueId = id(90))
            assertTrue(resultado.isFailure)
            assertEquals(1, llamadas.get(), "Se alcanzó el transporte real de OrderRepository sin conectar con un servidor")
            assertTrue(resultado.exceptionOrNull() is IOException)
            val original = f.fuente()
            f.reconocerOriginal()
            val reconocimientos = f.reconocimientos()
            val eventos = f.cajon.getSessionEvents(id(70))

            val fallo = runCatching { f.encolar(f.pedido) }.exceptionOrNull()
            if (fallo != null) assertEquals(original, f.fuente(), "Una validación fallida no toca la evidencia")
            assertNull(fallo, "La misma cortesía0 debe encolarse tras fallar la red: $fallo")
            assertEquals(original.copy(syncStatus = "PENDING"), f.fuente())
            assertEquals(reconocimientos, f.reconocimientos())
            assertEquals(eventos, f.cajon.getSessionEvents(id(70)), "Una cortesía0 no añade efectivo a la caja")
            f.assertOriginalVisible(original)
        }
    }

    @Test fun cortesiaOrderCeroReintentaVeinteMinutosDespuesConMismoPedidoCapturaAutorCajaYAck() = runBlocking {
        Fixture().use { f ->
            f.reservarOriginal()
            f.reconocerOriginal()
            val original = f.fuente()
            val reconocimientos = f.reconocimientos()
            val eventos = f.cajon.getSessionEvents(id(70))
            val segundoIntento = f.primera + 20 * 60000L
            val fallo = runCatching { f.reservar(f.pedido, hora = segundoIntento) }.exceptionOrNull()
            if (fallo != null) assertEquals(original, f.fuente())
            assertNull(fallo, "Un nuevo intento real de la misma cortesía0 no modifica su captura: $fallo")
            assertEquals(original.copy(createdAt = segundoIntento), f.fuente())
            assertEquals(reconocimientos, f.reconocimientos())
            assertEquals(eventos, f.cajon.getSessionEvents(id(70)))
            f.assertOriginalVisible(original)
            assertEquals(listOf(id(71)), ConciliacionDelCobro.leerCaptura(f.fuente().q15CaptureJson, id(90), id(1))?.ventasYaEnLaCaja)
        }
    }

    @Test fun cortesiaCeroNoAdmiteImportePropinaMetodoPedidoOrdenORecibidoDiferentesNiAlReservarNiAlEncolar() = runBlocking {
        val variantes: List<Pair<String, (CreateOrderRequest) -> CreateOrderRequest>> = listOf(
            "importe0a110" to { it.copy(subtotal = 11000, total = 11000) },
            "propina" to { it.copy(tip = 100, total = 100) },
            "producto" to { it.copy(items = listOf(it.items.single().copy(productId = id(82)))) },
            "motivo de cortesía" to { it.copy(items = listOf(it.items.single().copy(cortesiaReason = "Motivo distinto"))) },
            "nota del pedido" to { it.copy(note = "Pedido diferente") },
        )
        for (encolar in listOf(false, true)) {
            for ((nombre, cambiar) in variantes) Fixture().use { f ->
                f.reservarOriginal(); f.reconocerOriginal()
                val original = f.fuente(); val ack = f.reconocimientos()
                val fallo = runCatching {
                    if (encolar) f.encolar(cambiar(f.pedido)) else f.reservar(cambiar(f.pedido), hora = f.primera + 20 * 60000L)
                }.exceptionOrNull()
                assertTrue(fallo is IllegalArgumentException, "$nombre, encolar=$encolar debe rechazarse: $fallo")
                assertEquals(original, f.fuente(), nombre)
                assertEquals(ack, f.reconocimientos(), nombre)
                assertEquals(1, f.cajon.getSessionEvents(id(70)).size)
            }
            for (nombre in listOf("método", "orden", "recibido", "cliente")) Fixture().use { f ->
                f.reservarOriginal(); f.reconocerOriginal()
                val original = f.fuente(); val ack = f.reconocimientos()
                val metodo = if (nombre == "método") ManualPaymentMethod.CARD_EXTERNAL else null
                val orden = if (nombre == "orden") id(83) else id(80)
                val recibido = if (nombre == "recibido") 100 else 0
                val cliente = if (nombre == "cliente") id(96) else id(95)
                val fallo = runCatching {
                    if (encolar) f.encolar(f.pedido, metodo, orden, recibido, cliente)
                    else f.reservar(f.pedido, f.primera + 20 * 60000L, metodo, orden, recibido, cliente)
                }.exceptionOrNull()
                assertTrue(fallo is IllegalArgumentException, "$nombre, encolar=$encolar debe rechazarse: $fallo")
                assertEquals(original, f.fuente(), nombre)
                assertEquals(ack, f.reconocimientos(), nombre)
                assertEquals(1, f.cajon.getSessionEvents(id(70)).size)
            }
        }
    }

    @Test fun normalizarMetadatosNoPermiteFechaDeOtroTipoOIntentoKeysNuevasNiCapturaAjenaAlCuerpoNuevo() = runBlocking {
        for (caso in listOf("fecha texto", "fecha fraccionaria", "fecha de otro intento", "key nueva", "captura y cuerpo distintos")) Fixture().use { f ->
            f.reservarOriginal(); f.reconocerOriginal()
            val original = f.fuente(); val ack = f.reconocimientos()
            val segunda = f.primera + 20 * 60000L
            val cuerpo = Json.parseToJsonElement(assertNotNull(original.orderRequestJson)).jsonObject.toMutableMap()
            cuerpo["createdAtLocal"] = when (caso) {
                "fecha texto" -> JsonPrimitive(segunda.toString())
                "fecha fraccionaria" -> JsonPrimitive(segunda + 0.5)
                "fecha de otro intento" -> JsonPrimitive(segunda + 60000L)
                else -> JsonPrimitive(segunda)
            }
            if (caso == "key nueva") cuerpo["datoNoSolicitado"] = JsonPrimitive("otro valor")
            val cuerpoNuevo = JsonObject(cuerpo).toString()
            val capturaOriginal = assertNotNull(ConciliacionDelCobro.leerCaptura(original.q15CaptureJson, original.venueId, original.id))
            val propuesta = original.copy(createdAt = segunda, orderRequestJson = cuerpoNuevo,
                q15CaptureJson = ConciliacionDelCobro.guardarCaptura(capturaOriginal.copy(primeraCapturaEn = segunda,
                    orderRequestJson = if (caso == "captura y cuerpo distintos") original.orderRequestJson else cuerpoNuevo)))
            val fallo = runCatching { f.pagos.insert(propuesta) }.exceptionOrNull()
            assertTrue(fallo is IllegalArgumentException, "$caso debe rechazarse antes del reemplazo: $fallo")
            assertEquals(original, f.fuente(), caso)
            assertEquals(ack, f.reconocimientos(), caso)
            assertEquals(1, f.cajon.getSessionEvents(id(70)).size)
        }
    }

    private class Fixture : AutoCloseable {
        val carpeta = Files.createTempDirectory("cortesia-retry-room-")
        val contexto = ContextoDeEscritorio(carpeta)
        val db = DatabaseModule.provideDatabase(contexto)
        val storage = SecureStorage(contexto).apply { venueId = id(90); userId = id(91) }
        val pagos = db.pendingPaymentDao()
        val cajon = db.cashDrawerDao()
        val salida = SalidaDelApartado(pagos, cajon, storage, db)
        val cash = CashPaymentRepository(storage, pagos, salida)
        val avisos = SalesNoticeStore(db.salesNoticeDao(), cash)
        val primera = System.currentTimeMillis() - 60 * 60000L
        val pedido = CreateOrderRequest(
            items = listOf(OrderItemRequest(productId = id(81), name = "Camisa", quantity = 1, unitPrice = 11000,
                isCortesia = true, cortesiaReason = "Cortesía autorizada", externalId = id(84))),
            subtotal = 0, total = 0, paymentMethod = "CASH", note = "Pedido original")

        suspend fun reservarOriginal() {
            cajon.insertSession(CashDrawerSessionEntity(id(70), id(90), "Caja original", id(91), "Ana", primera - 60000, 10000))
            cajon.insertEvent(CashDrawerEventEntity(id(71), id(70), id(90), "CASH_SALE", 11000, "Venta anterior", id(91), "Ana",
                createdAt = primera - 1000))
            reservar(pedido, primera)
            assertEquals("ORDER", fuente().paymentType)
            assertEquals(0, fuente().amountCents)
            assertEquals(0, fuente().tipCents)
            assertNotNull(fuente().orderRequestJson)
            assertEquals(id(70), fuente().cashDrawerSessionId)
            val captura = assertNotNull(ConciliacionDelCobro.leerCaptura(fuente().q15CaptureJson, id(90), id(1)))
            assertEquals(true, captura.daPorCobradaAlAbandonar)
            assertEquals(primera, captura.primeraCapturaEn)
            assertEquals(listOf(id(71)), captura.ventasYaEnLaCaja)
        }

        suspend fun reservar(request: CreateOrderRequest, hora: Long, metodo: ManualPaymentMethod? = null,
            orden: String = id(80), recibido: Int = 0, cliente: String = id(95)) = cash.reservarCobro(
            request, id(91), recibido, 0, 5, orderId = orden, orderExternalId = id(85), customerId = cliente,
            idempotencyKey = id(1), manualMethod = metodo, cajaDelCobro = id(70), capturadaEn = hora)

        suspend fun encolar(request: CreateOrderRequest, metodo: ManualPaymentMethod? = null,
            orden: String = id(80), recibido: Int = 0, cliente: String = id(95)) = cash.queueCashPayment(
            request, id(91), recibido, 0, 5, orderId = orden, orderExternalId = id(85), customerId = cliente,
            idempotencyKey = id(1), manualMethod = metodo, cajaDelCobro = id(70))

        suspend fun fuente(): PendingPaymentEntity = assertNotNull(pagos.findByIdQ15(id(1)))
        suspend fun reconocerOriginal() {
            cash.olvidarReservaEnMemoria(id(1))
            assertTrue(avisos.acknowledge(avisos.page(id(90)).rows.single(), id(92), primera + 1))
        }
        fun reconocimientos(): List<List<String>> = androidx.sqlite.driver.bundled.BundledSQLiteDriver()
            .open(carpeta.resolve("databases/avoqado_db").toString()).use { it.volcar("sales_notice_acknowledgements") }

        suspend fun assertOriginalVisible(original: PendingPaymentEntity) {
            val aviso = db.salesNoticeDao().page(id(90), "", null, emptySet()).rows.single()
            assertEquals(primera, aviso.originalCreatedAt)
            assertEquals(id(92), aviso.acknowledgement?.actor)
            assertEquals(original.q15CaptureJson, fuente().q15CaptureJson)
            assertEquals(original.orderRequestJson, fuente().orderRequestJson)
            assertEquals(original.staffId, fuente().staffId)
            assertEquals(original.cashDrawerSessionId, fuente().cashDrawerSessionId)
        }
        override fun close() { db.close(); carpeta.toFile().deleteRecursively() }
    }

    private companion object { fun id(n: Int) = "c" + n.toString().padStart(24, '0') }
}
