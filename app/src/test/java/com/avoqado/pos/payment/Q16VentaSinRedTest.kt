package com.avoqado.pos.payment

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.database.Q15_PAYMENT_REVIEW_SQL
import com.avoqado.pos.payment.Q16PrecioCapturadoTest.Companion.VENUE
import com.avoqado.pos.payment.data.*
import io.mockk.coEvery
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import okhttp3.mockwebserver.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * Servidor falso con la idempotencia del de verdad (`order.mobile.service.ts`): UNA orden por `externalId`
 * (`createOrderWithItems`) y UN pago por `idempotencyKey` (`payCashOrder`), aunque lleguen varias peticiones
 * con las mismas identidades. Los cortes simulan la respuesta perdida antes o después de que el servidor guarde.
 */
class ServidorFalso : Dispatcher() {
    enum class Corte { ANTES_DEL_COMMIT, DESPUES_DEL_COMMIT }
    val ordenes = linkedMapOf<String, String>()      // externalId → orderId
    val mercancia = mutableMapOf<String, Int>()      // orderId → total SIN propina, en centavos
    val pagos = linkedMapOf<String, JSONObject>()    // idempotencyKey → pago
    val peticiones = mutableListOf<Pair<String, JSONObject>>()
    var corteOrden: Corte? = null
    var cortePago: Corte? = null
    /** Precio con que el servidor valora la orden al crearla (cambió mientras no había red); null = el del pedido. */
    var precio: Int? = null
    var rechazoDeOrden: Int? = null
    /** Llaves cuyo `/pay` responde 2xx SIN el pago (identidad desconocida ⇒ la fila queda retenida, no aplicada). */
    val sinIdentidad = mutableSetOf<String>()
    /** Código con que responde `/sync/recovery` (500 persistente, sesión…); null = responde bien. */
    var recuperacionCaida: Int? = null
    /** `/sync/recovery` sin respuesta: lee la petición y corta la conexión sin contestar (IOException del lado del POS).
     *  Ojo: `DISCONNECT_AT_START` devuelto por un `Dispatcher` propio NO se aplica (MockWebServer contesta 200 vacío). */
    var recuperacionSinRespuesta = false
    /** Código con que responden `/pay` y `/fast` (servidor caído para cobros); null = responden bien. */
    var cobrosCaidos: Int? = null
    @Synchronized fun de(tipo: String) = peticiones.filter { it.first == tipo }.map { it.second }
    @Synchronized override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path!!
        // clone(): la prueba vuelve a leer el mismo cuerpo con takeRequest().
        val body = request.body.clone().readUtf8().let { if (it.isBlank()) JSONObject() else JSONObject(it) }
        val corte = MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
        return when {
            path.endsWith("/sync/recovery") -> {
                peticiones += "recovery" to body
                if (recuperacionSinRespuesta) return corte
                recuperacionCaida?.let { return MockResponse().setResponseCode(it) }
                val ids = body.getJSONArray("externalOrderIds")
                val found = (0 until ids.length()).mapNotNull { i ->
                    ordenes[ids.getString(i)]?.let { JSONObject().put("externalId", ids.getString(i)).put("orderId", it) }
                }
                ok("""{"data":{"intents":[],"orders":${JSONArray(found)}}}""")
            }
            path.endsWith("/orders") -> {
                peticiones += "orders" to body
                rechazoDeOrden?.let { return MockResponse().setResponseCode(it).setBody("""{"success":false,"message":"Producto no encontrado"}""") }
                if (corteOrden == Corte.ANTES_DEL_COMMIT) return corte
                val id = ordenes.getOrPut(body.getString("externalId")) { "orden-${ordenes.size + 1}" }
                mercancia.getOrPut(id) { precio ?: (body.optInt("total") - body.optInt("tip")) }
                if (corteOrden == Corte.DESPUES_DEL_COMMIT) corte else ok("""{"success":true,"order":{"id":"$id"}}""")
            }
            path.endsWith("/pay") -> {
                peticiones += "pay" to body
                cobrosCaidos?.let { return MockResponse().setResponseCode(it) }
                if (cortePago == Corte.ANTES_DEL_COMMIT) return corte
                if (body.getString("idempotencyKey") in sinIdentidad) return ok("""{"success":true}""")
                val orderId = path.substringBeforeLast("/pay").substringAfterLast("/orders/")
                val pago = pagos.getOrPut(body.getString("idempotencyKey")) {
                    val merch = mercancia.getValue(orderId); val tip = body.getInt("tip")
                    // Como `payCashOrder`: se registra a lo más lo que la cuenta debe; el resto es cambio.
                    val aplicado = minOf(body.getInt("amount"), merch)
                    JSONObject().put("paymentId", "pago-${pagos.size + 1}").put("orderId", orderId).put("status", "COMPLETED")
                        .put("amount", aplicado).put("tipAmount", tip).put("changeCents", body.getInt("amount") - aplicado)
                        .apply {
                            if (body.has("tenderTypeId")) put("tenderTypeId", body.getString("tenderTypeId")).put("tenderRevision", body.getInt("tenderRevision"))
                            else put("method", body.getString("method"))
                        }
                        .put("orderTotalCents", merch + tip).put("remainingBalanceCents", maxOf(0, merch - aplicado))
                        .put("orderPaymentStatus", if (aplicado >= merch) "PAID" else "PARTIAL")
                }
                if (cortePago == Corte.DESPUES_DEL_COMMIT) corte else ok(JSONObject().put("success", true).put("payment", pago).toString())
            }
            path.endsWith("/fast") -> {
                peticiones += "fast" to body
                cobrosCaidos?.let { return MockResponse().setResponseCode(it) }
                // Como `/fast`: un pago por llave; responde en PESOS (no centavos).
                fun pesos(cents: Int) = java.math.BigDecimal(cents).movePointLeft(2).toPlainString()
                val pago = pagos.getOrPut(body.getString("idempotencyKey")) {
                    JSONObject().put("id", "pago-${pagos.size + 1}").put("orderId", "rapida-${pagos.size + 1}").put("status", "COMPLETED")
                        .put("amount", pesos(body.getInt("amount"))).put("tipAmount", pesos(body.getInt("tip"))).put("method", body.getString("method"))
                }
                ok(JSONObject().put("success", true).put("data", pago).toString())
            }
            else -> MockResponse().setResponseCode(404)
        }
    }
    private fun ok(body: String) = MockResponse().setResponseCode(200).setBody(body)
}

/**
 * Despachador que DETIENE el primer arranque hasta que llega el segundo (o 1 s): abre, a propósito, la ventana entre
 * «¿hay un drenado activo?» y «lo asigno» de `syncNow` (N4). Hilos de fondo (daemon) para no retener la JVM de pruebas.
 */
class Portero : CoroutineDispatcher() {
    private val hilos = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
    private val segundo = java.util.concurrent.CountDownLatch(1)
    private val llegadas = java.util.concurrent.atomic.AtomicInteger()
    override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
        when (llegadas.incrementAndGet()) {
            1 -> segundo.await(1, java.util.concurrent.TimeUnit.SECONDS)
            2 -> segundo.countDown()
        }
        hilos.execute(block)
    }
}

/** Q16 (2.24.1): la venta de mostrador cobrada sin red se manda al volver la red. Servicio, HTTP y SQL en disco reales. */
@OptIn(ExperimentalCoroutinesApi::class)
class Q16VentaSinRedTest {
    @get:Rule val main = MainDispatcherRule()
    /** Una prueba de la cola que se cuelga FALLA con la pila del hilo atorado, en vez de colgar la corrida entera. */
    @get:Rule val tope: org.junit.rules.Timeout = org.junit.rules.Timeout.builder()
        .withTimeout(90, java.util.concurrent.TimeUnit.SECONDS).withLookingForStuckThread(true).build()

    private fun Q16PrecioCapturadoTest.Fixture.arrancar(falso: ServidorFalso = ServidorFalso()): ServidorFalso {
        server.dispatcher = falso
        service.usarContextoDeSincronizacion(Dispatchers.Unconfined)
        return falso
    }
    private suspend fun PaymentSyncService.vuelta() { syncNow(); esperarSincronizacionActualQ16() }
    private suspend fun PaymentSyncService.primera() { start(); esperarSincronizacionActualQ16() }

    /** El proceso muere y la app vuelve a abrir: servicio nuevo sobre el MISMO disco; el backoff ya pasó. */
    private suspend fun Q16PrecioCapturadoTest.Fixture.reiniciar(anterior: PaymentSyncService): PaymentSyncService {
        anterior.stop(); reopen(); sql("UPDATE pending_payments SET lastRetryAt=0")
        return PaymentSyncService(dao, storage, client, network, drawer, outbox, io.mockk.mockk<com.avoqado.pos.payment.data.CashPaymentRepository>(relaxed = true)).also {
            it.usarContextoDeSincronizacion(Dispatchers.Unconfined); it.primera()
        }
    }

    /** UNA orden y UN pago en el servidor, con la identidad de la fila en CADA petición, y la captura intacta. */
    private fun Q16PrecioCapturadoTest.Fixture.unaOrdenYUnPago(falso: ServidorFalso, key: String, antes: Map<String, Any?>) {
        assertEquals(listOf(key), falso.ordenes.keys.toList())
        assertEquals(listOf(key), falso.pagos.keys.toList())
        assertTrue(falso.de("orders").all { it.getString("externalId") == key })
        assertTrue(falso.de("pay").all { it.getString("idempotencyKey") == key && it.getInt("amount") == 10000 && it.getInt("tip") == 500 })
        val fila = row(key)
        assertEquals("SYNCED", fila["syncStatus"]); assertNull(fila["q15Reason"]); assertNull(fila["orderId"])
        assertEquals(antes["original"], fila["original"]); assertEquals(antes["q15CaptureJson"], fila["q15CaptureJson"])
    }

    @Test fun `FIFO A y B sin red se mandan en orden con su orden y su pago cada una`() = runTest {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            val falso = f.arrancar(); val a = f.venta("venta-A"); val b = f.venta("venta-B")
            f.service.primera()
            assertEquals(listOf("orders" to a, "pay" to a, "orders" to b, "pay" to b),
                falso.peticiones.map { (tipo, body) -> tipo to (if (tipo == "orders") body.getString("externalId") else body.getString("idempotencyKey")) })
            assertEquals("SYNCED", f.row(a)["syncStatus"]); assertEquals("SYNCED", f.row(b)["syncStatus"])
            assertEquals(2, falso.ordenes.size); assertEquals(2, falso.pagos.size)
        }
    }

    @Test fun `FIFO si A falla por red B no se adelanta`() = runTest {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            val falso = f.arrancar().apply { corteOrden = ServidorFalso.Corte.ANTES_DEL_COMMIT }
            val a = f.venta("venta-A"); val b = f.venta("venta-B"); val antesB = f.row(b)
            f.service.primera()
            assertTrue(falso.peticiones.isNotEmpty())
            assertTrue(falso.peticiones.all { (tipo, body) -> tipo == "orders" && body.getString("externalId") == a })
            assertTrue(falso.ordenes.isEmpty()); assertTrue(falso.pagos.isEmpty())
            assertEquals(1, (f.row(a)["retryCount"] as Number).toInt()); assertEquals("PENDING", f.row(a)["syncStatus"])
            assertEquals(antesB, f.row(b))
        }
    }

    @Test fun `C6 corte despues de guardar la orden y reinicio dan una orden y un pago`() = runTest {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            val falso = f.arrancar().apply { corteOrden = ServidorFalso.Corte.DESPUES_DEL_COMMIT }
            val key = f.venta("venta-A"); val antes = f.row(key)
            f.service.primera()
            assertEquals(1, falso.ordenes.size); assertTrue(falso.pagos.isEmpty()); assertEquals("PENDING", f.row(key)["syncStatus"])
            falso.corteOrden = null
            f.reiniciar(f.service).stop()
            assertTrue(falso.de("orders").size >= 2); assertEquals(1, falso.de("pay").size)
            f.unaOrdenYUnPago(falso, key, antes)
        }
    }

    @Test fun `C6 corte entre crear y pagar y reinicio dan una orden y un pago`() = runTest {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            val falso = f.arrancar().apply { cortePago = ServidorFalso.Corte.ANTES_DEL_COMMIT }
            val key = f.venta("venta-A"); val antes = f.row(key)
            f.service.primera()
            assertEquals(1, falso.ordenes.size); assertTrue(falso.pagos.isEmpty()); assertEquals("PENDING", f.row(key)["syncStatus"])
            falso.cortePago = null
            f.reiniciar(f.service).stop()
            assertEquals(2, falso.de("orders").size)
            f.unaOrdenYUnPago(falso, key, antes)
        }
    }

    @Test fun `C6 corte despues de guardar el pago y reinicio no cobran dos veces`() = runTest {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            val falso = f.arrancar().apply { cortePago = ServidorFalso.Corte.DESPUES_DEL_COMMIT }
            val key = f.venta("venta-A"); val antes = f.row(key)
            f.service.primera()
            assertEquals(1, falso.pagos.size); assertEquals("PENDING", f.row(key)["syncStatus"])
            falso.cortePago = null
            f.reiniciar(f.service).stop()
            assertTrue(falso.de("pay").size >= 2)
            f.unaOrdenYUnPago(falso, key, antes)
        }
    }

    // Ronda 1 (controlador): la fila que se suelta se manda en la MISMA vuelta, y la venta de detrás también.
    @Test fun `retenida por la 2240 con recuperacion vacia se suelta y se manda en la misma vuelta con la venta de detras`() = runTest {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            val falso = f.arrancar(); f.service.primera(); assertTrue(falso.peticiones.isEmpty())
            val a = f.venta("venta-A"); f.retenerComo2240(a); val b = f.venta("venta-B")
            val antesA = f.row(a); val antesB = f.row(b)
            f.service.vuelta()   // UNA sola llamada a syncNow
            assertEquals(listOf("recovery" to a, "orders" to a, "pay" to a, "orders" to b, "pay" to b), falso.peticiones.map { (tipo, body) ->
                tipo to when (tipo) { "recovery" -> body.getJSONArray("externalOrderIds").getString(0); "orders" -> body.getString("externalId"); else -> body.getString("idempotencyKey") }
            })
            assertEquals(listOf(a, b), falso.ordenes.keys.toList()); assertEquals(listOf(a, b), falso.pagos.keys.toList())
            for ((key, antes) in listOf(a to antesA, b to antesB)) {
                val fila = f.row(key)
                assertEquals(key, "SYNCED", fila["syncStatus"]); assertNull(key, fila["q15Reason"]); assertNull(key, fila["orderId"])
                assertEquals(key, antes["original"], fila["original"]); assertEquals(key, antes["q15CaptureJson"], fila["q15CaptureJson"])
                assertEquals(key, 10000, falso.pagos.getValue(key).getInt("amount")); assertEquals(key, 500, falso.pagos.getValue(key).getInt("tipAmount"))
            }
        }
    }

    @Test fun `C7 sin evidencia completa no se crea orden ni se cobra y la recuperacion vacia no la suelta`() = runTest {
        val otro = """{"externalId":"otra-llave","items":[{"productId":"original-product","quantity":1}]}"""
        val casos: List<Pair<String, (Q16PrecioCapturadoTest.Fixture) -> Unit>> = listOf(
            "captura sin pedido" to { f -> f.seed(captured = null) },
            "externalId discordante" to { f -> f.seed(captured = otro) },
            "orderId vacio" to { f -> f.seed(); f.sql("UPDATE pending_payments SET orderId='',capturedOrderRef=''") },
            "alias durable de Mesas" to { f -> coEvery { f.outbox.aliasDurableQ16(VENUE, "local-order") } returns "server-order"; f.seed(order = "local-order") },
            "FAST con articulos" to { f -> f.seed(kind = "FAST", items = """[{"productId":"original-product","quantity":1}]""") },
        )
        for ((caso, siembra) in casos) Q16PrecioCapturadoTest.Fixture().use { f ->
            val falso = f.arrancar(); siembra(f); val antes = f.row()
            f.service.primera(); f.service.vuelta(); f.service.vuelta()
            val fila = f.row()
            assertEquals(caso, ConciliacionDelCobro.PRICE_PROOF_PENDING, fila["q15Reason"]); assertEquals(caso, "PENDING", fila["syncStatus"])
            assertTrue(caso, falso.de("orders").isEmpty()); assertTrue(caso, falso.de("pay").isEmpty())
            assertTrue(caso, falso.ordenes.isEmpty() && falso.pagos.isEmpty())
            for (k in listOf("original", "q15CaptureJson", "orderId", "retryCount", "lastRetryAt")) assertEquals("$caso $k", antes[k], fila[k])
        }
    }

    @Test fun `precio que subio mientras no habia red registra lo cobrado una vez y deja el aviso al gerente`() = runTest {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            val falso = f.arrancar().apply { precio = 12000 }
            val key = f.venta("venta-A"); val antes = f.row(key)
            f.service.primera(); f.service.vuelta()
            val fila = f.row(key)
            assertEquals("SYNCED", fila["syncStatus"]); assertEquals(ConciliacionDelCobro.DIFERENCIA, fila["q15Reason"])
            assertEquals(listOf("orders", "pay"), falso.peticiones.map { it.first })
            assertEquals(1, falso.pagos.size); assertEquals(10000, falso.pagos.getValue(key).getInt("amount"))
            val resultado = JSONObject(fila["q15ResultJson"] as String)
            assertEquals(10000, resultado.getInt("totalAlCobrarCents")); assertEquals(12000, resultado.getInt("totalEnElServidorCents"))
            assertEquals("totalDeLaOrden", resultado.getJSONArray("camposDiferentes").getString(0))
            assertEquals(antes["original"], fila["original"]); assertEquals(antes["q15CaptureJson"], fila["q15CaptureJson"])
            assertEquals(1, f.sql(Q15_PAYMENT_REVIEW_SQL, mapOf("venueId" to VENUE, "id" to key, "actor" to "manager", "rol" to "MANAGER", "ahora" to 9)))
        }
    }

    // Ronda Codex código (P1-4): la 2.24.0 creó la orden a $120 y se perdió la respuesta; la recuperación la ADOPTA (la fila
    // ya trae `orderId`). La regla del total se decide con la CAPTURA (nació sin orden), no con la fila.
    private fun adoptadaConPrecio(precioEnElServidor: Int, revisa: (Map<String, Any?>) -> Unit) = runTest {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            val falso = f.arrancar()
            val key = f.venta("venta-A"); f.retenerComo2240(key); val antes = f.row(key)
            falso.ordenes[key] = "orden-1"; falso.mercancia["orden-1"] = precioEnElServidor
            f.service.primera(); f.service.vuelta(); f.service.vuelta()
            val fila = f.row(key)
            assertEquals(listOf("recovery", "pay"), falso.peticiones.map { it.first })
            assertEquals(listOf(key), falso.pagos.keys.toList()); assertEquals(10000, falso.pagos.getValue(key).getInt("amount"))
            assertEquals("SYNCED", fila["syncStatus"]); assertEquals("orden-1", fila["orderId"])
            assertEquals(antes["original"], fila["original"]); assertEquals(antes["q15CaptureJson"], fila["q15CaptureJson"])
            revisa(fila)
        }
    }
    @Test fun `adoptada por la recuperacion con precio que subio registra lo cobrado una vez y deja el aviso al gerente`() =
        adoptadaConPrecio(12000) { fila ->
            assertEquals(ConciliacionDelCobro.DIFERENCIA, fila["q15Reason"])
            val resultado = JSONObject(fila["q15ResultJson"] as String)
            assertEquals("totalDeLaOrden", resultado.getJSONArray("camposDiferentes").getString(0))
            assertEquals(10000, resultado.getInt("totalAlCobrarCents")); assertEquals(12000, resultado.getInt("totalEnElServidorCents"))
        }
    @Test fun `adoptada por la recuperacion con el mismo precio no deja aviso`() =
        adoptadaConPrecio(10000) { fila ->
            assertNull(fila["q15Reason"]); assertFalse((fila["q15ResultJson"] as String).contains("totalAlCobrarCents"))
        }

    // Ronda Codex código (P1-3): C7 con recuperación NO vacía. La captura dice la venta «otra-llave» y el pedido de la fila
    // «original-key»; aunque el servidor SÍ tenga la orden «original-key», no se adopta ni se cobra.
    @Test fun `C7 externalId discordante con la orden del pedido ya en el servidor no se adopta ni se cobra`() = runTest {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            val falso = f.arrancar().apply { ordenes["original-key"] = "orden-B"; mercancia["orden-B"] = 10005 }
            f.seed(captured = """{"externalId":"otra-llave","items":[{"productId":"original-product","quantity":1}]}""")
            val antes = f.row()
            f.service.primera(); f.service.vuelta(); f.service.vuelta()
            val fila = f.row()
            assertTrue(falso.de("recovery").isNotEmpty())
            assertTrue(falso.de("orders").isEmpty()); assertTrue(falso.de("pay").isEmpty()); assertTrue(falso.pagos.isEmpty())
            assertEquals(ConciliacionDelCobro.PRICE_PROOF_PENDING, fila["q15Reason"]); assertEquals("PENDING", fila["syncStatus"])
            for (k in listOf("original", "q15CaptureJson", "orderId", "retryCount", "lastRetryAt")) assertEquals(k, antes[k], fila[k])
        }
    }

    // Adenda (founder, 16-oct): una venta RETENIDA no tapa la cola; sólo esperan las de su misma orden.
    private fun Q16PrecioCapturadoTest.Fixture.sinEvidencia(id: String) = sql("UPDATE pending_payments SET q15CaptureJson=NULL WHERE id=:id", mapOf("id" to id))
    private fun ServidorFalso.cobrosDe(key: String) = (de("pay") + de("fast")).filter { it.getString("idempotencyKey") == key }

    @Test fun `la del founder venta con productos retenida no detiene la de importe libre de detras`() = runTest {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            val falso = f.arrancar()
            val a = f.venta("venta-A", subtotal = 16000, tip = 0); f.sinEvidencia(a)
            val b = f.importeLibre("venta-B", 4500)
            f.service.primera()
            assertEquals(ConciliacionDelCobro.CAPTURA_SIN_EVIDENCIA, f.row(a)["q15Reason"]); assertEquals("PENDING", f.row(a)["syncStatus"])
            assertTrue(falso.cobrosDe(a).isEmpty()); assertTrue(falso.de("orders").isEmpty())
            assertEquals(listOf("fast" to b), falso.peticiones.map { (tipo, body) -> tipo to body.getString("idempotencyKey") })
            assertEquals(4500, falso.pagos.getValue(b).getString("amount").let { java.math.BigDecimal(it).movePointRight(2).toInt() })
            assertEquals("SYNCED", f.row(b)["syncStatus"]); assertNull(f.row(b)["q15Reason"])
        }
    }

    @Test fun `la retenida detiene sola a las filas de su misma orden`() = runTest {
        val casos: List<Pair<String, suspend (Q16PrecioCapturadoTest.Fixture) -> Pair<String, String>>> = listOf(
            "mismo pedido (externalId)" to { f -> f.venta("abono-1", orden = "venta-A").also { f.sinEvidencia(it) } to f.venta("abono-2", orden = "venta-A") },
            "misma orden del servidor" to { f ->
                f.seed("p1", order = "server-order", request = """{"externalId":"pedido-1","items":[]}"""); f.sinEvidencia("p1")
                f.seed("p2", order = "server-order", request = """{"externalId":"pedido-2","items":[]}"""); "p1" to "p2"
            },
        )
        for ((caso, siembra) in casos) Q16PrecioCapturadoTest.Fixture().use { f ->
            val falso = f.arrancar(); val (a, b) = siembra(f); val antesB = f.row(b)
            f.service.primera(); f.service.vuelta()
            assertEquals(caso, ConciliacionDelCobro.CAPTURA_SIN_EVIDENCIA, f.row(a)["q15Reason"])
            assertTrue(caso, falso.peticiones.isEmpty())
            assertEquals(caso, antesB, f.row(b))
        }
    }

    @Test fun `sin hambre diez retenidas al frente no dejan fuera a la de detras y cada una pide una recuperacion`() = runTest {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            val falso = f.arrancar()
            val retenidas = (0 until 10).map { i -> "r$i".also { id ->
                f.seed(id, request = """{"externalId":"orden-$i","items":[{"productId":"p","quantity":1}]}""",
                    captured = """{"externalId":"otra-$i","items":[{"productId":"p","quantity":1}]}""")
                f.retenerComo2240(id)
            } }
            val z = f.venta("venta-Z")
            f.service.primera()
            assertEquals("SYNCED", f.row(z)["syncStatus"]); assertEquals(1, falso.cobrosDe(z).size)
            assertEquals(listOf("orders", "pay"), falso.peticiones.filter { it.first != "recovery" }.map { it.first })
            val consultas = falso.de("recovery").map { it.getJSONArray("externalOrderIds").getString(0) }
            // Como conjunto (ordenado): las 10 comparten `createdAt` y su desempate no es parte de la regla.
            assertEquals((0 until 10).map { "orden-$it" }, consultas.sorted())
            for (id in retenidas) { assertEquals(id, ConciliacionDelCobro.PRICE_PROOF_PENDING, f.row(id)["q15Reason"]); assertEquals(id, "PENDING", f.row(id)["syncStatus"]) }
        }
    }

    // Ronda de arreglos 1: la venta que el ENVÍO deja retenida (2xx sin identidad ⇒ `!aplicado`) tampoco detiene a la de detrás,
    // y no se vuelve a mandar en la vuelta siguiente.
    @Test fun `2xx sin identidad retiene la venta y la de importe libre de detras se manda`() = runTest {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            val falso = f.arrancar()
            val a = f.venta("venta-A"); falso.sinIdentidad += a
            val b = f.importeLibre("venta-B", 4500)
            f.service.primera()   // UNA vuelta: B sale en la misma en que A quedó retenida
            assertEquals(ConciliacionDelCobro.RESULTADO_NO_COMPROBADO, f.row(a)["q15Reason"]); assertEquals("PENDING", f.row(a)["syncStatus"])
            assertEquals(1, falso.cobrosDe(b).size); assertEquals("SYNCED", f.row(b)["syncStatus"]); assertNull(f.row(b)["q15Reason"])
            f.service.vuelta()
            assertEquals(1, falso.cobrosDe(a).size); assertEquals(ConciliacionDelCobro.RESULTADO_NO_COMPROBADO, f.row(a)["q15Reason"])
        }
    }

    // Ronda de arreglos 1 (MINOR 1): si no se puede leer el alias de una fila, la vuelta se CORTA (como antes de la adenda):
    // seguir con menos identidades dejaría cobrar antes una fila posterior de la misma cuenta de Mesas.
    @Test fun `si falla leer el alias de la retenida se corta la vuelta y no se cobra la de su misma cuenta`() = runTest {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            val falso = f.arrancar().apply { mercancia["server-order"] = 10005 }
            coEvery { f.outbox.aliasDurableQ16(VENUE, "local-order") } throws IllegalStateException("disco de prueba")
            f.seed("p1", order = "local-order", request = """{"externalId":"pedido-1","items":[]}""")
            f.seed("p2", order = "server-order", request = """{"externalId":"pedido-2","items":[]}"""); val antesB = f.row("p2")
            f.service.primera(); f.service.vuelta()
            assertTrue(falso.cobrosDe("p2").isEmpty()); assertTrue(falso.peticiones.isEmpty())
            assertEquals(antesB, f.row("p2"))
        }
    }

    // MARK: - Ronda de arreglos 2 (Codex 2.ª pasada, N1–N6)

    /** La fila de atrás, de la orden `orden`, con su propio pedido y creada DESPUÉS (sin depender de desempates). */
    private fun Q16PrecioCapturadoTest.Fixture.deLaOrden(id: String, orden: String): Map<String, Any?> {
        seed(id, order = orden, request = """{"externalId":"pedido-$id","items":[]}""")
        sql("UPDATE pending_payments SET createdAt=:t WHERE id=:id", mapOf("t" to System.currentTimeMillis() + 60_000, "id" to id))
        return row(id)
    }

    // N3: la retención DENTRO del envío (alias no comprobado) deja la fila PENDIENTE con su marca, en la misma escritura;
    // si quedara SYNCING, la vuelta siguiente no la vería y la de su misma orden se cobraría.
    @Test fun `retenida en el envio queda PENDIENTE con su marca y la de su misma orden sigue esperando en la segunda vuelta`() = runTest {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            val falso = f.arrancar().apply { mercancia["server-1"] = 10005 }
            f.seed("pA", order = "local-1"); f.sql("UPDATE pending_payments SET orderId='server-1' WHERE id='pA'")
            val antesB = f.deLaOrden("pB", "server-1")
            f.service.primera(); f.service.vuelta()
            assertEquals("PENDING", f.row("pA")["syncStatus"]); assertEquals(ConciliacionDelCobro.PRICE_PROOF_PENDING, f.row("pA")["q15Reason"])
            assertTrue(falso.peticiones.isEmpty()); assertEquals(antesB, f.row("pB"))
        }
    }

    // N4: dos `syncNow()` a la vez (reconexión + reintento manual) hacen UN solo drenado: un envío por fila.
    @Test fun `dos syncNow a la vez hacen un solo drenado y un solo envio por fila`() = runTest {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            val falso = f.arrancar(); f.service.primera()
            val key = f.venta("venta-A")
            // Si corren dos drenados, los dos leen la fila PENDIENTE antes de que alguno la marque SYNCING.
            val barrera = java.util.concurrent.CyclicBarrier(2)
            coEvery { f.dao.updateStatus(any(), "SYNCING") } answers {
                runCatching { barrera.await(1, java.util.concurrent.TimeUnit.SECONDS) }
                f.sql("UPDATE pending_payments SET syncStatus=:s WHERE id=:id", mapOf("id" to firstArg<String>(), "s" to secondArg<String>())); Unit
            }
            f.service.usarContextoDeSincronizacion(Portero())
            val salida = java.util.concurrent.CountDownLatch(1)
            val hilos = List(2) { kotlin.concurrent.thread { salida.await(); f.service.syncNow() } }
            salida.countDown(); hilos.forEach { it.join() }
            f.service.esperarSincronizacionActualQ16()
            withContext(Dispatchers.Default) { withTimeoutOrNull(1500) { while (falso.de("pay").size < 2) delay(20) } }
            assertEquals(1, falso.de("orders").size); assertEquals(1, falso.de("pay").size)
            assertEquals("SYNCED", f.row(key)["syncStatus"])
        }
    }

    // N5 (regla final del controlador): un 500 PERSISTENTE de la recuperación es una respuesta del servidor ⇒ A sigue
    // retenida y la independiente B sale; si no, un 500 ligado a un externalId detendría la cola para siempre (H2).
    @Test fun `recuperacion con 500 persistente deja retenida a la venta y la independiente de detras sale`() = runTest {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            val falso = f.arrancar().apply { recuperacionCaida = 500 }
            val a = f.venta("venta-A"); f.retenerComo2240(a); val antesA = f.row(a)
            val b = f.importeLibre("venta-B", 4500)
            f.service.primera()
            assertEquals(listOf("recovery", "fast"), falso.peticiones.map { it.first })
            assertEquals("SYNCED", f.row(b)["syncStatus"]); assertEquals(antesA, f.row(a))
        }
    }

    // N5: la recuperación SIN respuesta (conexión cortada ⇒ IOException) es pausa: corta la vuelta y nadie gasta reintento.
    @Test fun `recuperacion sin respuesta corta la vuelta sin gastar reintentos y la independiente no se adelanta`() = runTest {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            val falso = f.arrancar().apply { recuperacionSinRespuesta = true }
            val a = f.venta("venta-A"); f.retenerComo2240(a); val antesA = f.row(a)
            val b = f.importeLibre("venta-B", 4500); val antesB = f.row(b)
            f.service.primera()
            assertTrue(falso.peticiones.isNotEmpty()); assertTrue(falso.peticiones.all { it.first == "recovery" })
            assertEquals(antesA, f.row(a)); assertEquals(antesB, f.row(b))
        }
    }

    // N5, control C1: con la recuperación en 500 y el cobro de B en 503, B sale, falla por el servidor y queda PENDIENTE
    // con su reintento normal (el transitorio de B corta la vuelta, como siempre); A no gasta reintento.
    @Test fun `recuperacion con 500 y cobro de la independiente en 503 dejan a B pendiente con su reintento normal`() = runTest {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            val falso = f.arrancar().apply { recuperacionCaida = 500; cobrosCaidos = 503 }
            val a = f.venta("venta-A"); f.retenerComo2240(a); val antesA = f.row(a)
            val b = f.importeLibre("venta-B", 4500)
            f.service.primera()
            assertEquals(listOf("recovery", "fast"), falso.peticiones.map { it.first })
            assertEquals("PENDING", f.row(b)["syncStatus"]); assertEquals(1, (f.row(b)["retryCount"] as Number).toInt())
            assertNull(f.row(b)["q15Reason"]); assertEquals(antesA, f.row(a))
        }
    }

    // N1: la recuperación ADOPTA la orden del servidor (S) de A, que sólo se identificaba por su `externalId`; B, de esa
    // misma cuenta y sólo con S, espera en la MISMA vuelta.
    @Test fun `adoptada por la recuperacion bloquea a la fila de su orden del servidor en la misma vuelta`() = runTest {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            val falso = f.arrancar().apply { ordenes["venta-A"] = "orden-S"; mercancia["orden-S"] = 10000 }
            val a = f.venta("venta-A"); f.retenerComo2240(a)
            val antesB = f.deLaOrden("pB", "orden-S")
            f.service.primera()
            assertEquals("orden-S", f.row(a)["orderId"])
            assertEquals(listOf("recovery"), falso.peticiones.map { it.first })
            assertEquals(antesB, f.row("pB"))
        }
    }

    // N1: el ENVÍO crea la orden (S) y termina retenido (2xx sin identidad): S entra a lo bloqueado y B, de S, espera.
    @Test fun `retenida en el envio tras crear su orden bloquea a la fila de esa orden`() = runTest {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            val falso = f.arrancar()
            val a = f.venta("venta-A"); falso.sinIdentidad += a
            val antesB = f.deLaOrden("pB", "orden-1")
            f.service.primera()
            assertEquals(ConciliacionDelCobro.RESULTADO_NO_COMPROBADO, f.row(a)["q15Reason"]); assertEquals("PENDING", f.row(a)["syncStatus"])
            assertEquals(listOf("orders", "pay"), falso.peticiones.map { it.first }); assertEquals(mapOf("venta-A" to "orden-1"), falso.ordenes.toMap())
            assertEquals(antesB, f.row("pB"))
        }
    }

    // N2: C7 (la captura dice el pedido «otra-llave», la fila dice «original-key»): el pedido de la CAPTURA también es su
    // identidad, así que una fila posterior de «otra-llave» espera.
    @Test fun `C7 el pedido de la captura tambien bloquea a la fila posterior de ese pedido`() = runTest {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            val falso = f.arrancar()
            f.seed("p1", captured = """{"externalId":"otra-llave","items":[{"productId":"original-product","quantity":1}]}""")
            val p2 = f.venta("venta-2", orden = "otra-llave"); val antes2 = f.row(p2)
            f.service.primera()
            assertEquals(ConciliacionDelCobro.PRICE_PROOF_PENDING, f.row("p1")["q15Reason"])
            assertTrue(falso.peticiones.isEmpty()); assertEquals(antes2, f.row(p2))
        }
    }

    // N6: el lote ya no se queda sin avanzar. 1,000 retenidas al frente + una independiente ⇒ sale en ESA vuelta; y en la
    // siguiente cada retenida pide `/sync/recovery` otra vez a lo más una vez. Sólo 10 consultan al servidor (las otras 990
    // están retenidas sin evidencia, que no consulta): 2,000 peticiones locales tardan más de un minuto en el Alienware.
    @Test fun `mil retenidas al frente no dejan fuera a la de detras y cada una pide una recuperacion por vuelta`() = runTest {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            val falso = f.arrancar()
            f.db.autoCommit = false
            repeat(1000) { i ->
                f.seed("r$i", request = """{"externalId":"orden-$i","items":[{"productId":"p","quantity":1}]}""",
                    captured = """{"externalId":"otra-$i","items":[{"productId":"p","quantity":1}]}""")
                f.retenerComo2240("r$i")
            }
            f.sql("UPDATE pending_payments SET q15Reason='CAPTURA_SIN_EVIDENCIA' WHERE id LIKE 'r%' AND CAST(substr(id,2) AS INTEGER) >= 10")
            f.db.commit(); f.db.autoCommit = true
            val z = f.venta("venta-Z")
            f.service.primera()
            assertEquals("SYNCED", f.row(z)["syncStatus"]); assertEquals(1, falso.cobrosDe(z).size)
            fun consultas() = falso.de("recovery").groupingBy { it.getJSONArray("externalOrderIds").getString(0) }.eachCount()
            assertEquals((0 until 10).associate { "orden-$it" to 1 }, consultas())
            f.service.vuelta()
            assertEquals((0 until 10).associate { "orden-$it" to 2 }, consultas()); assertEquals(1, falso.cobrosDe(z).size)
            assertEquals(1000, f.query("SELECT id FROM pending_payments WHERE syncStatus='PENDING' AND q15Reason IS NOT NULL").size)
        }
    }

    @Test fun `C11 producto inexistente deja la venta FAILED visible sin pago ni FAST y con captura y llaves intactas`() = runTest {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            val falso = f.arrancar().apply { rechazoDeOrden = 400 }
            val key = f.venta("venta-A"); val antes = f.row(key)
            f.service.primera()
            val fila = f.row(key)
            assertEquals("FAILED", fila["syncStatus"]); assertTrue(fila["lastError"].toString().contains("400"))
            assertNull(fila["q15Reason"]); assertNull(fila["orderId"])
            assertEquals(listOf("orders"), falso.peticiones.map { it.first }); assertTrue(falso.pagos.isEmpty())
            assertEquals(antes["original"], fila["original"]); assertEquals(antes["q15CaptureJson"], fila["q15CaptureJson"])
            assertEquals("ORDER", JSONObject(fila["original"] as String).getString("kind"))
            assertEquals(listOf(key), f.query("SELECT id FROM pending_payments WHERE syncStatus='FAILED' AND q15Reason IS NULL").map { it["id"] })
        }
    }
}
