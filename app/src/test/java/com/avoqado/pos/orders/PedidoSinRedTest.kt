package com.avoqado.pos.orders

import com.avoqado.pos.core.data.local.PayloadCache
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.orders.data.CopiaDeCuentas
import com.avoqado.pos.orders.data.Lectura
import com.avoqado.pos.orders.data.OrdersRepository
import com.avoqado.pos.orders.data.RazonSinRed
import com.avoqado.pos.orders.data.TEXTO_PEDIDO_SIN_RED
import com.avoqado.pos.orders.data.horaDeLaCopia
import com.avoqado.pos.orders.data.lecturaDe
import com.avoqado.pos.orders.data.razonParaNoCobrar
import com.avoqado.pos.orders.presentation.OPEN_ORDERS_STATUS_FILTER
import com.avoqado.pos.payment.domain.TextosDeCuentaSinRed
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.net.UnknownHostException
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * D22 (9-nov-2026, SAMSUNG del Alien): sin red, abrir otra venta en «Pedidos» decía «No se encontró la orden». La venta
 * existe; lo que falta es red. Sólo un 404 dice que no existe.
 */
class PedidoSinRedTest {
    // D12 (Tarea 5): el repo lleva la copia sobre el «disco» de memoria de la Tarea 4 (CopiaDeCuentasTest). Vacía, todo sigue como D22.
    private val disco = mutableMapOf<String, PayloadCache.Cached>()
    private val cache = mockk<PayloadCache> {
        coEvery { load(any(), any()) } answers { disco["${firstArg<String>()}|${secondArg<String>()}"] }
        coEvery { loadDurable(any(), any()) } answers { disco["${firstArg<String>()}|${secondArg<String>()}"] }
        coEvery { saveDurable(any(), any(), any()) } answers {
            disco["${firstArg<String>()}|${secondArg<String>()}"] = PayloadCache.Cached(thirdArg(), 0)
        }
    }
    private var reloj = 1_000_000_000L
    private var copia = CopiaDeCuentas(cache).also { it.ahora = { reloj } }
    private var venue = "v1"
    private val storage = mockk<SecureStorage>(relaxed = true).also { every { it.venueId } answers { venue } }

    private fun repo(respuesta: (Interceptor.Chain) -> Response): OrdersRepository =
        OrdersRepository(storage, OkHttpClient.Builder().addInterceptor(Interceptor { respuesta(it) }).build(), copia)

    /** La lista (`…/orders?…`) contesta [lista]; el detalle (`…/orders/<id>`), [detalle]. */
    private var lista: (Interceptor.Chain) -> Response = { throw UnknownHostException("sin red") }
    private var detalle: (Interceptor.Chain) -> Response = { throw UnknownHostException("sin red") }
    private val peticiones = AtomicInteger()
    private fun repoConRutas() = repo { chain ->
        peticiones.incrementAndGet()
        if (chain.request().url.encodedPath.endsWith("/orders")) lista(chain) else detalle(chain)
    }

    private fun fila(id: String, saldo: String? = "6000", creada: String = "2026-11-10T18:00:00Z", extra: String = "") =
        """{"id":"$id","status":"PENDING","paymentStatus":"PENDING","source":"AVOQADO_ANDROID","esDeMesa":false,""" +
            """"createdAt":"$creada","pagosCompletados":0${saldo?.let { ",\"saldoPendienteCents\":$it" }.orEmpty()}$extra}"""
    private fun pagina(vararg filas: String) =
        """{"data":[${filas.joinToString(",")}],"meta":{"total":${filas.size},"page":1,"pageCount":1}}"""
    private fun ids(r: OrdersRepository) = r.orders.value.map { it.id }
    private fun sinRed(): (Interceptor.Chain) -> Response = { throw UnknownHostException("sin red") }

    private fun http(chain: Interceptor.Chain, codigo: Int, cuerpo: String) = Response.Builder()
        .request(chain.request()).protocol(Protocol.HTTP_1_1).code(codigo).message("x")
        .body(cuerpo.toResponseBody()).build()

    @Test fun `sin red no dice que el pedido no existe`() = runTest {
        val r = repo { throw UnknownHostException("sin red") }
        r.loadOrderDetail("B")
        assertEquals("Sin conexión: para ver este pedido necesitas internet. Se abre solo al volver la conexión.", r.errorMessage.value)
        assertNull(r.selectedOrder.value)
    }

    @Test fun `un 404 sigue diciendo que no se encontro`() = runTest {
        val r = repo { http(it, 404, """{"success":false,"message":"Order not found"}""") }
        r.loadOrderDetail("B")
        assertEquals("No se encontró la orden", r.errorMessage.value)
    }

    @Test fun `un 500 no dice que el pedido no existe`() = runTest {
        val r = repo { http(it, 500, """{"message":"boom"}""") }
        r.loadOrderDetail("B")
        assertEquals("Error al cargar detalle de orden", r.errorMessage.value)
    }

    @Test fun `un 200 sin pedido no dice que no existe`() = runTest {
        val r = repo { http(it, 200, """{"success":true}""") }
        r.loadOrderDetail("B")
        assertEquals("Error al cargar detalle de orden", r.errorMessage.value)
    }

    /** Codex r1 #1: la conexión se corta al leer el cuerpo. */
    private val cuerpoQueSeCorta = object : okhttp3.ResponseBody() {
        override fun contentType(): okhttp3.MediaType? = null
        override fun contentLength() = -1L
        override fun source(): okio.BufferedSource = object : okio.Source {
            override fun read(sink: okio.Buffer, byteCount: Long): Long = throw java.io.IOException("se cortó al leer")
            override fun timeout() = okio.Timeout.NONE
            override fun close() {}
        }.buffer()
    }

    @Test fun `un 500 que se corta al leer no dice Sin conexion`() = runTest {
        val r = repo { http(it, 500, "").newBuilder().body(cuerpoQueSeCorta).build() }
        r.loadOrderDetail("B")
        assertEquals("Error al cargar detalle de orden", r.errorMessage.value)
    }

    @Test fun `un 200 que se corta al leer es sin red`() = runTest {
        val r = repo { http(it, 200, "").newBuilder().body(cuerpoQueSeCorta).build() }
        r.loadOrderDetail("B")
        assertEquals("B", r.detalleSinRed.value)
        assertEquals("Sin conexión: para ver este pedido necesitas internet. Se abre solo al volver la conexión.", r.errorMessage.value)
    }

    /** Codex r2 #3: cerrar «Pedidos» corta la consulta, y con ella la llamada (su 403 no sale encima de otra pantalla). */
    @Test fun `cancelar la consulta cancela la llamada`() = runTest {
        val entro = java.util.concurrent.CountDownLatch(1)
        val sigue = java.util.concurrent.CountDownLatch(1)
        val vista = java.util.concurrent.CountDownLatch(1)
        val cancelada = java.util.concurrent.atomic.AtomicBoolean(false)
        val r = repo { chain ->
            entro.countDown()
            sigue.await(5, java.util.concurrent.TimeUnit.SECONDS)
            cancelada.set(chain.call().isCanceled())
            vista.countDown()
            http(chain, 200, """{"success":true,"order":{"id":"B"}}""")
        }
        val consulta = launch { r.loadOrderDetail("B") }
        runCurrent()
        assertEquals(true, entro.await(5, java.util.concurrent.TimeUnit.SECONDS))
        consulta.cancel()
        sigue.countDown()
        assertEquals(true, vista.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals("la llamada se canceló", true, cancelada.get())
    }

    @Test fun `con red abre el pedido`() = runTest {
        val r = repo { http(it, 200, """{"success":true,"order":{"id":"B"}}""") }
        r.loadOrderDetail("B")
        assertEquals("B", r.selectedOrder.value?.id)
        assertNull(r.errorMessage.value)
    }

    // MARK: - D12 (Tarea 5): «Pedidos» alimenta la copia y la enseña sin red

    @Test fun `lecturaDe decide que tanto prueba una lectura`() {
        assertEquals(Lectura.LISTA_COMPLETA, lecturaDe(1, false, null, null, false))
        assertEquals(Lectura.PRIMERA_PAGINA, lecturaDe(1, false, null, null, true))
        assertEquals(Lectura.SUELTA, lecturaDe(1, false, "7", null, false))
        assertEquals(Lectura.SUELTA, lecturaDe(1, false, null, "PENDING", false))
        assertEquals(Lectura.SUELTA, lecturaDe(1, true, null, null, false))
        assertEquals(Lectura.SUELTA, lecturaDe(2, false, null, null, false))
        assertEquals(Lectura.LISTA_COMPLETA, lecturaDe(1, false, null, OPEN_ORDERS_STATUS_FILTER, false))
        assertEquals(Lectura.PRIMERA_PAGINA, lecturaDe(1, false, null, OPEN_ORDERS_STATUS_FILTER, true))
    }

    /** Codex r2 #5: del JSON real al disco y, tras reiniciar sin red, de vuelta a la lista con la razón de cada gris. */
    @Test fun `recorrido real JSON a disco a reinicio conserva las cobrables y las grises con su razon`() = runTest {
        lista = { http(it, 200, pagina(
            fila("A", creada = "2026-11-10T18:02:00Z"),
            fila("R", creada = "2026-11-10T18:01:00Z", extra = ",\"cobrosConSaldo\":1"),
            fila("V", saldo = null, creada = "2026-11-10T18:00:00Z"),
        )) }
        assertEquals(true, repoConRutas().loadOrders(1, null, null).isSuccess)

        copia = CopiaDeCuentas(cache).also { it.ahora = { reloj } } // el proceso se reinició: misma copia en disco
        lista = sinRed()
        val r = repoConRutas()
        r.loadOrders(1, null, null)
        assertEquals(listOf("A", "R", "V"), ids(r))
        val porId = r.orders.value.associateBy { it.id }
        assertEquals(RazonSinRed.TARJETAS_DE_REGALO, razonParaNoCobrar(porId.getValue("R")))
        assertEquals(RazonSinRed.SIN_SALDO_DEL_SERVIDOR, razonParaNoCobrar(porId.getValue("V")))
        assertNull(razonParaNoCobrar(porId.getValue("A")))
    }

    /** Review Focus 3. */
    @Test fun `una fila con saldo raro no tumba la pagina`() = runTest {
        lista = { http(it, 200, pagina(fila("A", saldo = "60.5"), fila("B"))) }
        val r = repoConRutas()
        assertEquals(true, r.loadOrders(1, null, null).isSuccess)
        assertEquals(listOf("A", "B"), ids(r))
        assertNull(r.orders.value.first().saldoPendienteCents)
    }

    /** Codex r2 #4. */
    @Test fun `una lista completa vacia saca de la copia la cuenta que ya se cobro`() = runTest {
        lista = { http(it, 200, pagina(fila("A"))) }
        val r = repoConRutas()
        r.loadOrders(1, null, null)
        assertEquals("A", copia.cuenta("v1", "A")?.cuenta?.id)
        lista = { http(it, 200, """{"data":[],"meta":{"total":0,"page":1,"pageCount":1}}""") }
        r.loadOrders(1, null, null)
        assertNull(copia.cuenta("v1", "A"))
    }

    @Test fun `sin red el detalle de una cuenta de la copia se ve con su hora y sigue la reapertura`() = runTest {
        lista = { http(it, 200, pagina(fila("A"))) }
        val r = repoConRutas()
        r.loadOrders(1, null, null)
        val vistaEn = reloj
        reloj += 60_000
        r.loadOrderDetail("A") // el detalle no contesta: sin red
        assertEquals("A", r.selectedOrder.value?.id)
        assertEquals("A", r.detalleDeLaCopia.value?.cuenta?.id)
        assertEquals(TextosDeCuentaSinRed.copiaDeLas(horaDeLaCopia(vistaEn, null)), r.errorMessage.value)
        assertEquals("A", r.detalleSinRed.value)
    }

    @Test fun `sin red y sin copia todo sigue como D22`() = runTest {
        val r = repoConRutas()
        r.loadOrderDetail("B")
        assertNull(r.selectedOrder.value)
        assertNull(r.detalleDeLaCopia.value)
        assertEquals(TEXTO_PEDIDO_SIN_RED, r.errorMessage.value)
        assertEquals("B", r.detalleSinRed.value)
    }

    @Test fun `tras reiniciar sin red la lista se llena con la copia y dice su hora`() = runTest {
        lista = { http(it, 200, pagina(fila("A"))) }
        repoConRutas().loadOrders(1, null, null)
        val vistaEn = reloj
        reloj += 3_600_000

        lista = sinRed()
        val r = repoConRutas()
        r.loadOrders(1, null, null)
        assertEquals(listOf("A"), ids(r))
        assertEquals(vistaEn, r.copiaVistaA.value)

        lista = { http(it, 200, pagina(fila("A"))) }
        r.loadOrders(1, null, null)
        assertNull(r.copiaVistaA.value)
    }

    /** Codex r2: la petición salió en v1 y la sesión ya es v2 cuando contesta. */
    @Test fun `una respuesta atrasada de otra sucursal no entra a la lista ni a la copia de la nueva`() = runTest {
        var url = ""
        lista = { url = it.request().url.toString(); venue = "v2"; http(it, 200, pagina(fila("A"))) }
        val r = repoConRutas()
        r.loadOrders(1, null, null)
        assertEquals(true, url.contains("/venues/v1/"))
        assertEquals(listOf("A"), copia.cuentas("v1").map { it.cuenta.id })
        assertEquals(emptyList<String>(), copia.cuentas("v2").map { it.cuenta.id })
        assertEquals(emptyList<String>(), ids(r))
    }

    @Test fun `salir y entrar a otra sucursal con la lista poblada no deja ver la de antes sin red`() = runTest {
        lista = { http(it, 200, pagina(fila("A"))) }
        val r = repoConRutas()
        r.loadOrders(1, null, null)
        assertEquals(listOf("A"), ids(r))

        venue = "v2"
        lista = sinRed()
        r.loadOrders(1, null, null)
        assertEquals(emptyList<String>(), ids(r))
        r.loadOrderDetail("A")
        assertNull(r.detalleDeLaCopia.value)
        assertNull(r.selectedOrder.value)
    }

    /** Review Focus 1: la del negocio, no la del aparato. */
    @Test fun `la hora de la copia es la del negocio`() {
        val vistaEn = Instant.parse("2026-11-10T18:05:00Z").toEpochMilli()
        assertEquals("12:05", horaDeLaCopia(vistaEn, "America/Mexico_City"))
        assertEquals("10:05", horaDeLaCopia(vistaEn, "America/Tijuana"))
    }

    /** Codex r3 H1: lo que un detalle bueno (o su 404) sabe también pone la copia al día. */
    @Test fun `un detalle bueno con abono, regalo, cierre o 404 deja la copia al dia y sin red no reaparece cobrable`() = runTest {
        lista = { http(it, 200, pagina(fila("A"), fila("B"), fila("C"), fila("D"))) }
        val r = repoConRutas()
        r.loadOrders(1, null, null)
        assertNull(razonParaNoCobrar(copia.cuenta("v1", "A")!!.cuenta))

        val saldo = ""","preBillBalance":{"orderTotalCents":10000,"totalPaidCents":4000,"remainingBalanceCents":6000}"""
        detalle = { chain ->
            when (chain.request().url.pathSegments.last()) {
                "A" -> http(chain, 200, """{"success":true,"order":{"id":"A","status":"PENDING","paymentStatus":"PARTIAL",""" +
                    """"payments":[{"id":"p1","status":"COMPLETED","method":"CASH","amount":40.0}]$saldo}}""")
                "B" -> http(chain, 200, """{"success":true,"order":{"id":"B","status":"PENDING","paymentStatus":"PENDING","cobrosConSaldo":1$saldo}}""")
                "C" -> http(chain, 200, """{"success":true,"order":{"id":"C","status":"PENDING","paymentStatus":"PAID"$saldo}}""")
                else -> http(chain, 404, """{"success":false,"message":"Order not found"}""")
            }
        }
        for (id in listOf("A", "B", "C", "D")) r.loadOrderDetail(id)

        detalle = sinRed()
        r.loadOrderDetail("A")
        assertEquals(RazonSinRed.CON_ABONO, razonParaNoCobrar(r.detalleDeLaCopia.value!!.cuenta))
        r.loadOrderDetail("B")
        assertEquals(RazonSinRed.TARJETAS_DE_REGALO, razonParaNoCobrar(r.detalleDeLaCopia.value!!.cuenta))
        assertNull("pagada: fuera de la copia", copia.cuenta("v1", "C"))
        assertNull("404: fuera de la copia", copia.cuenta("v1", "D"))
        r.loadOrderDetail("C")
        assertNull(r.detalleDeLaCopia.value)
        assertEquals(TEXTO_PEDIDO_SIN_RED, r.errorMessage.value)
    }

    /** Codex r3 H1: la primera respuesta se retiene; la segunda (lista completa vacía) llega antes. */
    @Test fun `una lista vieja retenida no resucita la cuenta que una lista nueva vacia retiro`() = runTest {
        val llamadas = AtomicInteger()
        val entro = CompletableDeferred<Unit>()
        val suelta = CompletableDeferred<Unit>()
        lista = { chain ->
            if (llamadas.incrementAndGet() == 1) {
                entro.complete(Unit)
                runBlocking { suelta.await() }
                http(chain, 200, pagina(fila("A")))
            } else http(chain, 200, """{"data":[],"meta":{"total":0,"page":1,"pageCount":1}}""")
        }
        val r = repoConRutas()
        val vieja = launch { r.loadOrders(1, null, null) }
        entro.await()
        r.loadOrders(1, null, null)
        suelta.complete(Unit)
        vieja.join()
        assertNull(copia.cuenta("v1", "A"))
    }

    /** Codex r3 H4: al perder la red con «Pedidos» a la vista, la copia vigente sin otra consulta y sin desmontar nada. */
    @Test fun `cortar la red con la lista y el detalle ya en pantalla activa la copia sin otra consulta`() = runTest {
        val (r, t) = listaYDetalleConRed()
        val visto = r.selectedOrder.value
        reloj += 60_000
        val antes = peticiones.get()
        r.alPerderLaRed()
        assertEquals("A", r.detalleDeLaCopia.value?.cuenta?.id)
        assertEquals(TextosDeCuentaSinRed.copiaDeLas(horaDeLaCopia(t, null)), r.errorMessage.value)
        assertEquals(t, r.copiaVistaA.value)
        assertSame("sin desmontar el detalle", visto, r.selectedOrder.value)
        assertEquals("ninguna petición nueva", antes, peticiones.get())
    }

    @Test fun `al cortar la red una copia vencida o de otra sucursal no se reutiliza`() = runTest {
        val (vencida, t) = listaYDetalleConRed()
        reloj = t + 24 * 60 * 60 * 1000L + 1
        vencida.alPerderLaRed()
        assertNull(vencida.detalleDeLaCopia.value)

        reloj = t
        val (deOtra, _) = listaYDetalleConRed()
        venue = "v2"
        deOtra.alPerderLaRed()
        assertNull(deOtra.detalleDeLaCopia.value)
    }

    // MARK: - D12 (Tarea 5, revisión r1): la copia sólo se enseña cuando de verdad es «sin red» y sin búsqueda ni filtro

    /** Siembra la copia de `v1` con `A` desde una lectura buena y regresa un repo NUEVO (lista vacía) con la lista caída. */
    private suspend fun copiaConAyListaQue(contesta: (Interceptor.Chain) -> Response): OrdersRepository {
        lista = { http(it, 200, pagina(fila("A"))) }
        repoConRutas().loadOrders(1, null, null)
        lista = contesta
        return repoConRutas()
    }

    /** Revisión r1 #1: un 5xx no es «sin conexión»: la UI no lo dice ni enseña la copia. */
    @Test fun `un 500 con la lista vacia no ensena la copia ni dice Sin conexion`() = runTest {
        val r = copiaConAyListaQue { http(it, 500, """{"message":"boom"}""") }
        r.loadOrders(1, null, null)
        assertEquals(emptyList<String>(), ids(r))
        assertNull(r.copiaVistaA.value)
    }

    /** Revisión r1 #1: un 403 no enseña cuentas que el servidor acaba de negar. */
    @Test fun `un 403 con la lista vacia no ensena la copia`() = runTest {
        val r = copiaConAyListaQue { http(it, 403, """{"message":"sin permiso"}""") }
        r.loadOrders(1, null, null)
        assertEquals(emptyList<String>(), ids(r))
        assertNull(r.copiaVistaA.value)
    }

    /** Revisión r1 #1: con otro fallo y otra sucursal, no se quedan a la vista las filas de la anterior. */
    @Test fun `un error del servidor tras cambiar de sucursal vacia la lista de la anterior`() = runTest {
        lista = { http(it, 200, pagina(fila("A"))) }
        val r = repoConRutas()
        r.loadOrders(1, null, null)
        assertEquals(listOf("A"), ids(r))
        venue = "v2"
        lista = { http(it, 500, """{"message":"boom"}""") }
        r.loadOrders(1, null, null)
        assertEquals(emptyList<String>(), ids(r))
        assertNull(r.copiaVistaA.value)
    }

    /** Revisión r1 #2: la copia completa no se hace pasar por el resultado de una búsqueda o un filtro. */
    @Test fun `con busqueda o filtro sin red la lista no se llena con toda la copia`() = runTest {
        val r = copiaConAyListaQue(sinRed())
        r.loadOrders(1, "7", null)
        assertEquals(emptyList<String>(), ids(r))
        assertNull(r.copiaVistaA.value)
        r.loadOrders(1, null, "PENDING")
        assertEquals(emptyList<String>(), ids(r))
        assertNull(r.copiaVistaA.value)
        r.loadOrders(1, null, OPEN_ORDERS_STATUS_FILTER) // «Abiertas» sí: la copia es justo eso
        assertEquals(listOf("A"), ids(r))
    }

    /** Revisión r1 menor #1: un saldo raro en el detalle no lo tumba (con red abre) y sin red queda gris, nunca cobrable. */
    @Test fun `un saldo raro en el detalle no lo tumba y la copia queda sin saldo`() = runTest {
        for ((i, raro) in listOf("60.5", "-5", "\"6000x\"").withIndex()) {
            venue = "v$i"
            lista = { http(it, 200, pagina(fila("A"))) }
            detalle = { http(it, 200, """{"success":true,"order":{"id":"A","status":"PENDING","paymentStatus":"PENDING",""" +
                """"preBillBalance":{"orderTotalCents":10000,"totalPaidCents":0,"remainingBalanceCents":$raro}}}""") }
            val r = repoConRutas()
            r.loadOrders(1, null, null)
            r.loadOrderDetail("A")
            assertEquals("con red el detalle abre ($raro)", "A", r.selectedOrder.value?.id)
            assertNull(r.errorMessage.value)

            detalle = sinRed()
            r.loadOrderDetail("A")
            assertEquals("sin red, gris ($raro)", RazonSinRed.SIN_SALDO_DEL_SERVIDOR, razonParaNoCobrar(r.detalleDeLaCopia.value!!.cuenta))
        }
    }

    // MARK: - T12b: lo que la Tarea 5 dejó para después y el cobro abierto que una recarga no desmonta

    /** Punto 1: la copia con A y B a la vista sin red; una búsqueda o un filtro nunca la enseña entera como su resultado. */
    @Test fun `sin red una busqueda con la copia a la vista nunca ensena las cuentas que no coinciden`() = runTest {
        lista = { http(it, 200, pagina(fila("A"), fila("B"))) }
        repoConRutas().loadOrders(1, null, null)
        lista = sinRed()
        val r = repoConRutas()
        r.loadOrders(1, null, null)
        assertEquals(listOf("A", "B"), ids(r).sorted())

        r.loadOrders(1, "A", null)
        assertEquals("nunca se ve B (ni la copia entera)", emptyList<String>(), ids(r))
        assertNull(r.copiaVistaA.value)
        assertEquals(TextosDeCuentaSinRed.BUSQUEDA_SIN_RED, r.avisoDeLaLista.value)
        assertEquals(true, r.listaEsperaLaRed)

        r.loadOrders(1, null, null) // borró la búsqueda: vuelve la copia con su hora
        assertEquals(listOf("A", "B"), ids(r).sorted())
        r.loadOrders(1, null, "COMPLETED")
        assertEquals("un filtro tampoco", emptyList<String>(), ids(r))
        assertEquals(TextosDeCuentaSinRed.BUSQUEDA_SIN_RED, r.avisoDeLaLista.value)

        lista = { http(it, 500, """{"message":"boom"}""") } // el servidor sí contestó: el «Sin conexión» de antes no se queda
        r.loadOrders(1, null, null)
        assertNull(r.avisoDeLaLista.value)
        assertEquals(false, r.listaEsperaLaRed)
    }

    /** Punto 1: también sobre la lista del servidor que quedó «como estaba» al irse la red. */
    @Test fun `sin red una busqueda sobre la lista que ya se veia tampoco ensena lo que no coincide`() = runTest {
        lista = { http(it, 200, pagina(fila("A"), fila("B"))) }
        val r = repoConRutas()
        r.loadOrders(1, null, null)
        r.alPerderLaRed()
        lista = sinRed()
        r.loadOrders(1, "A", null)
        assertEquals(emptyList<String>(), ids(r))
        assertEquals(TextosDeCuentaSinRed.BUSQUEDA_SIN_RED, r.avisoDeLaLista.value)
    }

    /** Punto 2: con la copia a la vista, el servidor SÍ contestó (500, 403): la lista se queda, el aviso dice la razón real. */
    @Test fun `un 500 o un 403 tras la copia no dicen Sin conexion`() = runTest {
        for (codigo in listOf(500, 403)) {
            disco.clear()
            val r = copiaConAyListaQue(sinRed())
            r.loadOrders(1, null, null)
            val vistaEn = r.copiaVistaA.value!!
            assertEquals(TextosDeCuentaSinRed.copiaDeLas(horaDeLaCopia(vistaEn, null)), r.avisoDeLaLista.value)

            lista = { http(it, codigo, """{"message":"boom"}""") }
            r.loadOrders(1, null, null)
            assertEquals("$codigo: la lista se queda", listOf("A"), ids(r))
            assertEquals(TextosDeCuentaSinRed.noSeCargaronDeLas(horaDeLaCopia(vistaEn, null)), r.avisoDeLaLista.value)
            assertEquals(false, r.avisoDeLaLista.value!!.contains("Sin conexión"))
            assertEquals("un servidor que contestó mal no se repite solo", false, r.listaEsperaLaRed)

            lista = sinRed() // se fue la red de verdad: vuelve a decir «Sin conexión»
            r.loadOrders(1, null, null)
            assertEquals(TextosDeCuentaSinRed.copiaDeLas(horaDeLaCopia(vistaEn, null)), r.avisoDeLaLista.value)
        }
    }

    /** Punto 2: el 5xx apaga la bandera del servidor y `alPerderLaRed` llega DESPUÉS de la lectura: no lo vuelve «Sin conexión». */
    @Test fun `un 500 sobre la lista en vivo y luego la bandera caida no dice Sin conexion`() = runTest {
        lista = { http(it, 200, pagina(fila("A"))) }
        val r = repoConRutas()
        r.loadOrders(1, null, null)
        val t = reloj
        lista = { http(it, 500, """{"message":"boom"}""") }
        r.loadOrders(1, null, null)
        assertNull("en vivo, un 500 no pinta nada (como hoy)", r.avisoDeLaLista.value)
        r.alPerderLaRed()
        assertEquals(TextosDeCuentaSinRed.noSeCargaronDeLas(horaDeLaCopia(t, null)), r.avisoDeLaLista.value)
        lista = { http(it, 500, """{"message":"boom"}""") }
        r.loadOrders(1, null, null)
        r.alPerderLaRed() // en el otro orden también: el que ya hay no se pisa
        assertEquals(TextosDeCuentaSinRed.noSeCargaronDeLas(horaDeLaCopia(t, null)), r.avisoDeLaLista.value)
    }

    /** Punto 4: con un cobro abierto, una recarga con 500 o 404 no deja la pantalla sin el detalle (ni el cobro). */
    @Test fun `con un cobro abierto una recarga con 500 o 404 no quita el detalle de la pantalla`() = runTest {
        for ((codigo, razon) in listOf(500 to "Error al cargar detalle de orden", 404 to "No se encontró la orden")) {
            val (r, _) = listaYDetalleConRed()
            val visto = r.selectedOrder.value!!
            r.alPerderLaRed()
            detalle = { http(it, codigo, """{"message":"x"}""") }
            r.loadOrderDetail("A", reintento = true) // la reapertura al volver la red
            assertNull("el repositorio no cambia", r.selectedOrder.value)
            fun estado(cobrando: Boolean) = com.avoqado.pos.orders.presentation.estadoDelDetalle(
                com.avoqado.pos.orders.presentation.HostDelDetalle.TABLETA, "A", r.isLoadingDetail.value, r.selectedOrder.value,
                r.errorMessage.value, cobrando = cobrando, ultimo = visto)
            assertEquals("$codigo con cobro", com.avoqado.pos.orders.presentation.EstadoDelDetalle.Detalle(visto), estado(true))
            assertEquals("$codigo sin cobro, como hoy", com.avoqado.pos.orders.presentation.EstadoDelDetalle.Fallo(razon), estado(false))
            assertEquals("el aviso dice la razón real", razon, r.errorMessage.value)
        }
    }

    /** Punto 4: «Cargando» (un toque que vuelve a pedirla) tampoco desmonta el cobro; un último de OTRA cuenta no cuenta. */
    @Test fun `con un cobro abierto Cargando deja el ultimo detalle y otra cuenta no`() {
        val a = com.avoqado.pos.orders.data.model.OrderDetail("A")
        val tableta = com.avoqado.pos.orders.presentation.HostDelDetalle.TABLETA
        assertEquals(com.avoqado.pos.orders.presentation.EstadoDelDetalle.Detalle(a),
            com.avoqado.pos.orders.presentation.estadoDelDetalle(tableta, "A", true, null, null, cobrando = true, ultimo = a))
        assertEquals(com.avoqado.pos.orders.presentation.EstadoDelDetalle.Cargando,
            com.avoqado.pos.orders.presentation.estadoDelDetalle(tableta, "B", true, null, null, cobrando = true, ultimo = a))
        assertEquals(com.avoqado.pos.orders.presentation.EstadoDelDetalle.Cargando,
            com.avoqado.pos.orders.presentation.estadoDelDetalle(tableta, "A", true, null, null, cobrando = false, ultimo = a))
    }

    /** O1: la cuenta de tarjetas que la recarga trae PAGADA no cambia a su pantalla de resultado a media cobranza. */
    @Test fun `la cuenta de tarjetas PAGADA espera a que cierre el cobro para ir a su resultado`() {
        val pagada = com.avoqado.pos.orders.data.model.OrderDetail("A", esCuentaDeTarjetas = true, paymentStatus = "PAID")
        assertEquals(false, com.avoqado.pos.orders.presentation.vaACuentaDeTarjetas(pagada, cobrando = true))
        assertEquals("sin cobro abierto, como hoy", true, com.avoqado.pos.orders.presentation.vaACuentaDeTarjetas(pagada, cobrando = false))
        assertEquals(false, com.avoqado.pos.orders.presentation.vaACuentaDeTarjetas(pagada.copy(paymentStatus = "PENDING"), cobrando = false))
    }

    /** Con red: la lista con `A` (lectura buena a la hora `t`) y el detalle de `A` del servidor. */
    private suspend fun listaYDetalleConRed(): Pair<OrdersRepository, Long> {
        lista = { http(it, 200, pagina(fila("A"))) }
        detalle = { http(it, 200, """{"success":true,"order":{"id":"A","status":"PENDING","paymentStatus":"PENDING","items":[]}}""") }
        val r = repoConRutas()
        r.loadOrders(1, null, null)
        r.loadOrderDetail("A")
        assertEquals("A", r.selectedOrder.value?.id)
        assertNull(r.detalleDeLaCopia.value)
        return r to reloj
    }
}

/** D22: mientras «Pedidos» se ve, el pedido que no abrió por falta de red se vuelve a pedir solo cuando el aparato tiene red. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PedidoSeAbreAlVolverLaRedTest {
    @get:org.junit.Rule val main = com.avoqado.pos.MainDispatcherRule()
    private val storage = mockk<SecureStorage>(relaxed = true)
    private val repository = io.mockk.spyk(OrdersRepository(storage, OkHttpClient()))
    private val conectado = kotlinx.coroutines.flow.MutableStateFlow(true)
    private val alcanzable = kotlinx.coroutines.flow.MutableStateFlow(true)
    private val red = mockk<com.avoqado.pos.core.util.ConnectivityMonitor> {
        every { isConnected } returns conectado
        every { isServerReachable } returns alcanzable
    }
    private lateinit var vm: com.avoqado.pos.orders.presentation.OrdersViewModel
    private val sinRed = "Sin conexión: para ver este pedido necesitas internet. Se abre solo al volver la conexión."
    private var consultas = 0

    @org.junit.Before fun armar() {
        val factory = mockk<com.avoqado.pos.core.domain.refresh.RefreshGateFactory>()
        every { factory.create(any(), any()) } returns com.avoqado.pos.core.domain.refresh.RefreshGate(clock = { kotlin.time.Duration.ZERO })
        vm = com.avoqado.pos.orders.presentation.OrdersViewModel(repository, factory, mockk(relaxed = true), storage, mockk(relaxed = true), red)
    }

    @org.junit.After fun cerrar() { vm.viewModelScope.cancel() }

    private fun responde(id: String, que: () -> com.avoqado.pos.orders.data.model.OrderDetail?) {
        io.mockk.coEvery { repository.fetchOrderDetail(id) } answers { consultas++; que() }
    }

    @Test fun `sin red no abre y al volver la red se abre solo`() = runTest {
        backgroundScope.launch { vm.reabrirAlVolverLaRed() }
        conectado.value = false
        responde("B") { throw UnknownHostException("sin red") }
        vm.selectOrder("B"); runCurrent()
        assertEquals("sin red", sinRed, vm.errorMessage.value)
        advanceTimeBy(120_000); runCurrent()
        assertEquals("sin red del aparato no insiste", 1, consultas)

        responde("B") { com.avoqado.pos.orders.data.model.OrderDetail("B") }
        conectado.value = true; runCurrent()
        assertEquals("al volver la red se abre solo", "B", vm.selectedOrder.value?.id)
        assertNull(vm.errorMessage.value)
    }

    @Test fun `con el pedido a la vista volver la red no lo vuelve a pedir`() = runTest {
        backgroundScope.launch { vm.reabrirAlVolverLaRed() }
        responde("A") { com.avoqado.pos.orders.data.model.OrderDetail("A") }
        vm.selectOrder("A"); runCurrent()
        conectado.value = false; runCurrent(); conectado.value = true; runCurrent()
        advanceTimeBy(120_000); runCurrent()
        assertEquals(1, consultas)
    }

    /** Codex r1 #1 y #2: el fallo de red llega con el aparato «con red» (se cortó al leer, o el «volvió» llegó a media carga). */
    @Test fun `con red del aparato reintenta en escalera sin bucle y abre cuando contesta`() = runTest {
        backgroundScope.launch { vm.reabrirAlVolverLaRed() }
        responde("B") { throw java.io.IOException("se cortó al leer") }
        vm.selectOrder("B"); runCurrent()
        assertEquals("el toque y el reintento inmediato", 2, consultas)
        advanceTimeBy(4_999); runCurrent()
        assertEquals("nada antes de 5 s", 2, consultas)
        advanceTimeBy(1); runCurrent()
        assertEquals(3, consultas)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(4, consultas)
        assertEquals(sinRed, vm.errorMessage.value)

        responde("B") { com.avoqado.pos.orders.data.model.OrderDetail("B") }
        advanceTimeBy(20_000); runCurrent()
        assertEquals("B", vm.selectedOrder.value?.id)
        val alAbrir = consultas
        advanceTimeBy(300_000); runCurrent()
        assertEquals("abierto ya no se pide", alAbrir, consultas)
    }

    @Test fun `un 404 o un servidor que contesto mal no se repiten solos`() = runTest {
        backgroundScope.launch { vm.reabrirAlVolverLaRed() }
        responde("X") { null }
        vm.selectOrder("X"); runCurrent()
        assertEquals("No se encontró la orden", vm.errorMessage.value)
        responde("Y") { throw com.avoqado.pos.core.data.network.LecturaFallida(com.avoqado.pos.core.data.network.FalloDeLectura.SERVIDOR) }
        vm.selectOrder("Y"); runCurrent()
        assertEquals("Error al cargar detalle de orden", vm.errorMessage.value)
        advanceTimeBy(300_000); runCurrent()
        assertEquals(2, consultas)
    }

    @Test fun `con Pedidos cerrado no pide nada`() = runTest {
        val viendo = backgroundScope.launch { vm.reabrirAlVolverLaRed() }
        conectado.value = false
        responde("B") { throw UnknownHostException("sin red") }
        vm.selectOrder("B"); runCurrent()
        viendo.cancel(); runCurrent()
        conectado.value = true; runCurrent()
        advanceTimeBy(120_000); runCurrent()
        assertEquals(1, consultas)
        assertEquals(sinRed, vm.errorMessage.value)
    }

    /** Codex r2 #3: la consulta automática que ya salió se corta al cerrar «Pedidos». */
    @Test fun `cerrar Pedidos corta la consulta automatica en vuelo`() = runTest {
        val viendo = backgroundScope.launch { vm.reabrirAlVolverLaRed() }
        conectado.value = false
        responde("B") { throw UnknownHostException("sin red") }
        vm.selectOrder("B"); runCurrent()
        var cortada = false
        io.mockk.coEvery { repository.fetchOrderDetail("B") } coAnswers {
            try { kotlinx.coroutines.awaitCancellation() } finally { cortada = true }
        }
        conectado.value = true; runCurrent()
        assertEquals(false, cortada)
        assertEquals("mientras reintenta se queda el aviso, sin círculo", sinRed, vm.errorMessage.value)
        assertEquals(false, vm.isLoadingDetail.value)
        viendo.cancel(); runCurrent()
        assertEquals("se cortó con la pantalla", true, cortada)
        assertEquals("Codex r3 #3: cortada, se queda el aviso", sinRed, vm.errorMessage.value)
        assertEquals(false, vm.isLoadingDetail.value)
    }

    /** Codex r3 #2: la red sube y baja a media reapertura; nunca hay dos consultas a la vez y el aviso no se pierde. */
    @Test fun `la red que va y viene no encima consultas`() = runTest {
        backgroundScope.launch { vm.reabrirAlVolverLaRed() }
        conectado.value = false
        responde("B") { throw UnknownHostException("sin red") }
        vm.selectOrder("B"); runCurrent()
        var enVuelo = 0
        var maximo = 0
        var cortadas = 0
        io.mockk.coEvery { repository.fetchOrderDetail("B") } coAnswers {
            enVuelo++; maximo = maxOf(maximo, enVuelo)
            try { kotlinx.coroutines.awaitCancellation() } finally { enVuelo--; cortadas++ }
        }
        repeat(3) {
            conectado.value = true; runCurrent()
            conectado.value = false; runCurrent()
        }
        assertEquals("una a la vez", 1, maximo)
        assertEquals("cada bajada corta la suya", 3, cortadas)
        assertEquals(sinRed, vm.errorMessage.value)
    }

    /** Codex r4 #1: «Reintentar» (o tocar el mismo pedido) a media reapertura la corta: nunca dos consultas a la vez. */
    @Test fun `Reintentar a media reapertura la corta y manda el toque`() = runTest {
        backgroundScope.launch { vm.reabrirAlVolverLaRed() }
        conectado.value = false
        responde("B") { throw UnknownHostException("sin red") }
        vm.selectOrder("B"); runCurrent()
        var cortada = false
        io.mockk.coEvery { repository.fetchOrderDetail("B") } coAnswers {
            try { kotlinx.coroutines.awaitCancellation() } finally { cortada = true }
        }
        conectado.value = true; runCurrent()
        val respuesta = kotlinx.coroutines.CompletableDeferred<com.avoqado.pos.orders.data.model.OrderDetail?>()
        io.mockk.coEvery { repository.fetchOrderDetail("B") } coAnswers { respuesta.await() }
        vm.selectOrder("B"); runCurrent()
        assertEquals("la automática se cortó", true, cortada)
        advanceTimeBy(60_000); runCurrent()
        respuesta.complete(com.avoqado.pos.orders.data.model.OrderDetail("B")); runCurrent()
        assertEquals("el toque abrió", "B", vm.selectedOrder.value?.id)
    }

    /** Codex r4 #3: refrescar la lista (volver a la pantalla tras 30 s) no borra el «Sin conexión…» del pedido. */
    @Test fun `refrescar la lista no borra el aviso del pedido`() = runTest {
        conectado.value = false
        responde("B") { throw UnknownHostException("sin red") }
        vm.selectOrder("B"); runCurrent()
        io.mockk.coEvery { repository.fetchOrders(any(), any(), any(), any()) } returns Result.failure(UnknownHostException("sin red"))
        vm.refreshNow(); runCurrent()
        assertEquals(sinRed, vm.errorMessage.value)
        io.mockk.coEvery { repository.fetchOrders(any(), any(), any(), any()) } returns
            Result.success(com.avoqado.pos.orders.data.model.OrderPage(orders = emptyList(), total = 0, page = 1, pageCount = 1))
        vm.refreshNow(); runCurrent()
        assertEquals(sinRed, vm.errorMessage.value)
    }

    /** Codex r5: volver a pedir el pedido que ya se ve y recibir un 5xx enseña el fallo, no la foto anterior. */
    @Test fun `volver a pedir el pedido abierto y fallar ensena el fallo`() = runTest {
        responde("A") { com.avoqado.pos.orders.data.model.OrderDetail("A") }
        vm.selectOrder("A"); runCurrent()
        responde("A") { throw com.avoqado.pos.core.data.network.LecturaFallida(com.avoqado.pos.core.data.network.FalloDeLectura.SERVIDOR) }
        vm.selectOrder("A"); runCurrent()
        assertEquals(com.avoqado.pos.orders.presentation.EstadoDelDetalle.Fallo("Error al cargar detalle de orden"),
            com.avoqado.pos.orders.presentation.estadoDelDetalle(com.avoqado.pos.orders.presentation.HostDelDetalle.TABLETA,
                vm.selectedOrderId.value, vm.isLoadingDetail.value, vm.selectedOrder.value, vm.errorMessage.value))
    }

    /** T12b punto 3: con la lista «como estaba» sin red, al volver la red se pide sola y el aviso se va. Sin parpadeo, nada nuevo. */
    @Test fun `al volver la red la lista se pide sola y se quita el aviso`() = runTest {
        var pedidas = 0
        fun pagina(id: String) = Result.success(com.avoqado.pos.orders.data.model.OrderPage(
            orders = listOf(com.avoqado.pos.orders.data.model.OrderSummary(id)), total = 1, page = 1, pageCount = 1))
        io.mockk.coEvery { repository.fetchOrders(any(), any(), any(), any()) } answers { pedidas++; pagina("A") }
        vm.refreshNow(); runCurrent()
        backgroundScope.launch { vm.copiaAlPerderLaRed() }; runCurrent()
        assertEquals("sin parpadeo no vuelve a pedir", 1, pedidas)

        alcanzable.value = false; runCurrent() // se fue la red: la lista dice de qué hora es
        org.junit.Assert.assertNotNull(vm.copiaVistaA.value)
        org.junit.Assert.assertNotNull(vm.avisoDeLaLista.value)

        io.mockk.coEvery { repository.fetchOrders(any(), any(), any(), any()) } answers { pedidas++; pagina("B") }
        alcanzable.value = true; runCurrent()
        assertEquals("al volver la red se pide sola", 2, pedidas)
        assertNull(vm.copiaVistaA.value)
        assertNull(vm.avisoDeLaLista.value)
        assertEquals(listOf("B"), vm.orders.value.map { it.id })
    }

    @Test fun `tocar otro pedido corta la reapertura del anterior`() = runTest {
        backgroundScope.launch { vm.reabrirAlVolverLaRed() }
        conectado.value = false
        responde("A") { throw UnknownHostException("sin red") }
        vm.selectOrder("A"); runCurrent()
        var cortada = false
        io.mockk.coEvery { repository.fetchOrderDetail("A") } coAnswers {
            try { kotlinx.coroutines.awaitCancellation() } finally { cortada = true }
        }
        conectado.value = true; runCurrent()
        responde("B") { com.avoqado.pos.orders.data.model.OrderDetail("B") }
        vm.selectOrder("B"); runCurrent()
        assertEquals(true, cortada)
        assertEquals("B", vm.selectedOrder.value?.id)
    }
}
