package com.avoqado.pos.payment

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.payment.data.CierreDeLlave
import com.avoqado.pos.payment.data.DesenlacePagarDespues
import com.avoqado.pos.payment.data.OrderRepository
import com.avoqado.pos.payment.data.PayLaterQueue
import com.avoqado.pos.payment.data.PayLaterRow
import com.avoqado.pos.payment.data.PayLaterRow.Estado
import com.avoqado.pos.pos.data.SavedCartsRepository
import com.avoqado.pos.pos.data.model.SavedCart
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Pagar después» sin red (27-oct, promesa en `docs/audit/2026-10-27-pagar-despues-sin-red/`): la fila propia. Se escribe ANTES
 * del POST, se muta por id y bajo candado (Codex r1 #6), una EN_VUELO de este proceso no se toca (R7), y al reenviar un 2xx sólo
 * cuenta si trae `order.id` (R2-1) y se compara su total con lo que vio el cliente (R1).
 */
class PagarDespuesFilaTest {

    private val disco = PrefsEnMemoria()
    private val copias = PrefsEnMemoria()
    private val storage = mockk<SecureStorage>(relaxed = true).also { every { it.venueId } returns "venue-A" }
    private val ordenes = mockk<OrderRepository>()

    private fun fila(
        id: String = "pay-later:1",
        venue: String = "venue-A",
        estado: Estado = Estado.PENDIENTE,
        total: Int = 10000,
        creada: Long = 1L,
        copia: String? = null,
    ) = PayLaterRow(
        id = id, venueId = venue, staffId = "staff-1", customerId = "cli-1", customerName = "Ana",
        totalEsperadoCents = total, cuerpo = """{"externalId":"$id","isOfflineReplay":true}""", carritoGuardadoId = copia,
        creadaEn = creada, estado = estado,
    )

    private fun fila(q: PayLaterQueue, id: String) = q.filas.value.firstOrNull { it.id == id }

    private fun cola() = PayLaterQueue(disco.context, ordenes, storage, SavedCartsRepository(copias.context), mockk(relaxed = true))
        .apply { io = Dispatchers.Unconfined }

    private fun responde(code: Int, body: String, contentType: String = "application/json") {
        coEvery { ordenes.enviarPagarDespues(any(), any()) } returns OrderRepository.RespuestaCruda(code, contentType, null, body)
    }

    private fun creada(total: String? = "100.0") =
        """{"success":true,"order":{"id":"ord-1"${total?.let { ""","total":$it""" } ?: ""}}}"""

    // MARK: - Cómo se lee la respuesta

    private fun lee(code: Int, body: String, ct: String = "application/json") = OrderRepository.leerPagarDespues(code, ct, null, body)

    @Test
    fun `un 2xx sin order id no es una cuenta creada - sigue pendiente`() {
        assertTrue(lee(200, "<html>portal</html>", "text/html") is DesenlacePagarDespues.Pendiente)
        assertTrue(lee(201, """{"success":true}""") is DesenlacePagarDespues.Pendiente)
    }

    @Test
    fun `un 2xx con order id es la cuenta, con su total en centavos o sin total`() {
        assertEquals(DesenlacePagarDespues.Creada("ord-1", 10000), lee(201, creada("100.0")).sinOrden())
        assertEquals(DesenlacePagarDespues.Creada("ord-1", 11410), lee(201, creada("114.10000000000001")).sinOrden())
        assertEquals(DesenlacePagarDespues.Creada("ord-1", null), lee(201, creada(null)).sinOrden())
        // El servidor también puede contestar con `data` (como lee `createOrder`).
        assertEquals(DesenlacePagarDespues.Creada("ord-2", 5000), lee(201, """{"data":{"id":"ord-2","total":50}}""").sinOrden())
    }

    private fun DesenlacePagarDespues.sinOrden() = (this as? DesenlacePagarDespues.Creada)?.copy(orden = null) ?: this

    @Test
    fun `5xx, 408, 429 y el 4xx de un intermediario siguen pendientes`() {
        assertTrue(lee(503, "") is DesenlacePagarDespues.Pendiente)
        assertTrue(lee(408, """{"message":"x"}""") is DesenlacePagarDespues.Pendiente)
        assertTrue(lee(429, """{"message":"x"}""") is DesenlacePagarDespues.Pendiente)
        assertTrue(lee(404, "<html>ngrok</html>", "text/html") is DesenlacePagarDespues.Pendiente)
    }

    @Test
    fun `401 es la sesion, y el resto de los 4xx de la API son un rechazo con su motivo`() {
        assertTrue(lee(401, """{"message":"Token has expired"}""") is DesenlacePagarDespues.SesionVencida)
        val rechazo = lee(404, """{"message":"Cliente no encontrado"}""") as DesenlacePagarDespues.Rechazada
        assertEquals(404, rechazo.code)
        assertEquals("Cliente no encontrado", rechazo.mensaje)
        assertEquals("Error al crear orden (400)", (lee(400, "{}") as DesenlacePagarDespues.Rechazada).mensaje)
    }

    @Test
    fun `el 409 de precio trae sus cambios`() {
        val body = """{"code":"ORDER_PRICE_CHANGED","message":"El precio cambió.","details":{"priceChanges":[
            {"externalId":"e1","productId":"p1","productName":"Café","displayedUnitPriceCents":10000,"unitPriceCents":12500}]}}"""
        val rechazo = lee(409, body) as DesenlacePagarDespues.Rechazada
        assertEquals(1, rechazo.cambios.size)
    }

    @Test
    fun `una respuesta perdida no se reenvia sola - un 4xx despues probaria lo que no es (R2-2)`() = runTest {
        val server = okhttp3.mockwebserver.MockWebServer().apply { start() }
        try {
            // Una cuenta que sale bien deja la conexión abierta; la siguiente se corta tras llegar al servidor (el caso en que
            // OkHttp reenviaría solo por una conexión reusada) y un tercer envío se toparía con un 403.
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(201).setBody(creada()))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(403).setBody("""{"message":"Sin permiso"}"""))
            every { storage.accessToken } returns "token-QA"
            val repositorio = OrderRepository(storage, okhttp3.OkHttpClient()).apply {
                baseUrl = server.url("/api/v1").toString().trimEnd('/')
            }
            assertEquals(201, repositorio.enviarPagarDespues("venue-A", """{"externalId":"pay-later:0"}""").code)

            val error = runCatching { repositorio.enviarPagarDespues("venue-A", """{"externalId":"pay-later:1"}""") }.exceptionOrNull()

            assertTrue("la petición se cortó: sin respuesta, no un 403", error is java.io.IOException)
            assertEquals(2, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    // MARK: - El almacén

    @Test
    fun `mutaciones concurrentes no se pisan - cada fila es suya`() = runTest {
        val q = cola()
        q.guardar(fila("pay-later:fija", total = 1))
        withContext(Dispatchers.Default) {
            (1..40).map { n -> async { q.guardar(fila("pay-later:$n")) } }.awaitAll()
            (1..40).map { async { q.cambiar("pay-later:fija") { it.copy(intentos = it.intentos + 1) } } }.awaitAll()
        }

        assertEquals(41, q.filas.value.size)
        assertEquals(40, fila(q, "pay-later:fija")?.intentos)
        // Sobrevive a reabrir la app: está en disco, no sólo en memoria.
        assertEquals(41, cola().filas.value.size)
    }

    @Test
    fun `un exito gana a un fallo tardio - lo borrado no revive`() = runTest {
        val q = cola()
        q.guardar(fila())
        q.borrar("pay-later:1")

        assertFalse(q.cambiar("pay-later:1") { it.copy(estado = Estado.RECHAZADA) })
        assertTrue(q.filas.value.isEmpty())
    }

    @Test
    fun `sin disco no se promete nada`() = runTest {
        val q = cola()
        disco.discoSano = false
        assertFalse(q.guardar(fila()))
        assertTrue(q.filas.value.isEmpty())
    }

    @Test
    fun `A-2 un commit fallido no deja la fila en la memoria de Android ni se reenvia`() = runTest {
        val q = cola()
        q.guardar(fila("pay-later:vieja", estado = Estado.RECHAZADA))
        disco.discoSano = false

        assertFalse(q.guardar(fila("pay-later:nueva")))
        assertFalse(q.cambiar("pay-later:vieja") { it.copy(estado = Estado.PENDIENTE) })
        disco.discoSano = true

        assertEquals("la memoria vuelve a lo que hay en disco", disco.enDisco, HashMap(disco.guardado))
        assertEquals(listOf(Estado.RECHAZADA), cola().filas.value.map { it.estado })
        responde(201, creada())
        q.drenar()
        coVerify(exactly = 0) { ordenes.enviarPagarDespues(any(), any()) }
    }

    @Test
    fun `A-1 el drenado relee la fila - la que se rechazo en linea mientras esperaba no se manda`() = runTest {
        val q = cola()
        q.guardar(fila("pay-later:a", creada = 1))
        q.guardar(fila("pay-later:b", estado = Estado.EN_VUELO, creada = 2))
        // B en línea: su marca hace que la pasada se la salte... hasta que el rechazo la suelta.
        assertTrue(q.marcarEnCurso("pay-later:b"))
        val respuestaDeA = kotlinx.coroutines.CompletableDeferred<OrderRepository.RespuestaCruda>()
        coEvery { ordenes.enviarPagarDespues(any(), match { "pay-later:a" in it }) } coAnswers { respuestaDeA.await() }
        coEvery { ordenes.enviarPagarDespues(any(), match { "pay-later:b" in it }) } returns
            OrderRepository.RespuestaCruda(201, "application/json", null, creada())

        val pasada = async { q.drenar() }
        testScheduler.runCurrent()
        // Ya con A en vuelo, B recibe un 409 en línea: su fila se va y su marca se suelta.
        q.cambiar("pay-later:b") { it.copy(estado = Estado.RECUPERAR) }
        q.soltar("pay-later:b")
        respuestaDeA.complete(OrderRepository.RespuestaCruda(201, "application/json", null, creada()))
        pasada.await()

        coVerify(exactly = 0) { ordenes.enviarPagarDespues(any(), match { "pay-later:b" in it }) }
        assertEquals(Estado.RECUPERAR, fila(q, "pay-later:b")?.estado)
    }

    @Test
    fun `A-4 una fila por recuperar nunca se manda, ni con Reintentar`() = runTest {
        val q = cola()
        q.guardar(fila(estado = Estado.RECUPERAR))
        responde(201, creada())

        q.drenar()
        assertNull(q.reintentar("pay-later:1"))

        coVerify(exactly = 0) { ordenes.enviarPagarDespues(any(), any()) }
        assertTrue(q.descartar("pay-later:1"))
    }

    @Test
    fun `A-8 los contadores siguen a la sucursal de la sesion, y las acciones no tocan otra sucursal`() = runTest {
        val q = cola()
        q.guardar(fila("pay-later:a", estado = Estado.PENDIENTE))
        q.guardar(fila("pay-later:r", estado = Estado.RECHAZADA))
        assertEquals(1, q.porSubir.first())
        assertEquals(1, q.porRevisar.first())

        every { storage.venueId } returns "venue-B"
        q.alCambiarLaSesion()

        assertEquals(0, q.porSubir.first())
        assertEquals(0, q.porRevisar.first())
        assertTrue(q.porRevisarEnLaSucursal.first().isEmpty())
        responde(201, creada())
        assertNull(q.reintentar("pay-later:r"))
        assertFalse(q.descartar("pay-later:r"))
        coVerify(exactly = 0) { ordenes.enviarPagarDespues(any(), any()) }
    }

    @Test
    fun `A-5 el reenvio no borra la fila mientras su copia guardada no se retire, y la cuenta no se vuelve a mandar`() = runTest {
        val guardados = SavedCartsRepository(copias.context)
        guardados.saveCart(SavedCart(id = "copia-1", name = "Ana", items = emptyList()))
        val q = PayLaterQueue(disco.context, ordenes, storage, guardados, mockk(relaxed = true)).apply { io = Dispatchers.Unconfined }
        q.guardar(fila(estado = Estado.PENDIENTE, copia = "copia-1"))
        assertTrue(q.copiaLigada("copia-1"))
        responde(201, creada())
        copias.discoSano = false

        q.drenar()

        assertEquals(Estado.RETIRO_PENDIENTE, fila(q, "pay-later:1")?.estado)
        assertTrue("sigue ligada: no se restaura", q.copiaLigada("copia-1"))
        assertEquals(0, q.porSubir.first())
        assertEquals(0, q.porRevisar.first())
        assertNull(q.reintentar("pay-later:1"))
        assertFalse(q.descartar("pay-later:1"))

        copias.discoSano = true
        q.drenar()

        assertTrue(q.filas.value.isEmpty())
        assertTrue(guardados.savedCarts.value.isEmpty())
        coVerify(exactly = 1) { ordenes.enviarPagarDespues(any(), any()) }
    }

    @Test
    fun `A-5 descartar una REVISAR cuya copia no se pudo retirar la deja esperando el retiro, no la suelta`() = runTest {
        val guardados = SavedCartsRepository(copias.context)
        guardados.saveCart(SavedCart(id = "copia-1", name = "Ana", items = emptyList()))
        val q = PayLaterQueue(disco.context, ordenes, storage, guardados, mockk(relaxed = true)).apply { io = Dispatchers.Unconfined }
        q.guardar(fila(estado = Estado.REVISAR, copia = "copia-1"))
        copias.discoSano = false

        assertTrue(q.descartar("pay-later:1"))

        assertEquals(Estado.RETIRO_PENDIENTE, fila(q, "pay-later:1")?.estado)
        assertTrue(q.porRevisarEnLaSucursal.first().isEmpty())
        assertTrue(q.copiaLigada("copia-1"))
    }

    @Test
    fun `una RECHAZADA bloquea restaurar su copia, sin retirarla, hasta que se descarta`() = runTest {
        val guardados = SavedCartsRepository(copias.context)
        guardados.saveCart(SavedCart(id = "copia-1", name = "Ana", items = emptyList()))
        val q = PayLaterQueue(disco.context, ordenes, storage, guardados, mockk(relaxed = true)).apply { io = Dispatchers.Unconfined }
        q.guardar(fila(estado = Estado.RECHAZADA, copia = "copia-1"))

        q.mantenimientoLocal()
        assertTrue("Reintentar podría crear la cuenta: su copia no se vende otra vez", q.copiaLigada("copia-1"))
        assertEquals("y no se retira: si se descarta, la venta sigue ahí", 1, guardados.savedCarts.value.size)

        assertTrue(q.descartar("pay-later:1"))
        assertFalse(q.copiaLigada("copia-1"))
    }

    @Test
    fun `r2-2 la venta por recuperar se guarda aunque su copia original ya no este (cambio de sucursal), y mientras tanto la bloquea`() = runTest {
        val guardados = SavedCartsRepository(copias.context)
        val q = PayLaterQueue(disco.context, ordenes, storage, guardados, mockk(relaxed = true)).apply { io = Dispatchers.Unconfined }
        val venta = SavedCart(id = "copia-1", name = "Ana", items = emptyList(), llavePagarDespues = "pay-later:1")
        q.guardar(fila(estado = Estado.RECUPERAR, copia = "copia-1").copy(venta = venta))
        assertTrue("mientras se recupera, la copia original no se vende", q.copiaLigada("copia-1"))

        assertEquals(true, q.pasarACarritosGuardados("pay-later:1"))

        assertEquals("D8: con la sucursal de su fila", listOf(venta.copy(venueId = "venue-A")), guardados.savedCarts.value)
        assertTrue(q.filas.value.isEmpty())
        assertFalse(q.copiaLigada("copia-1"))
    }

    @Test
    fun `A-4 Pasar a carritos guardados saca la venta por recuperar, y sin disco se queda`() = runTest {
        val guardados = SavedCartsRepository(copias.context)
        val q = PayLaterQueue(disco.context, ordenes, storage, guardados, mockk(relaxed = true)).apply { io = Dispatchers.Unconfined }
        val venta = SavedCart(id = "venta-ana", name = "Ana", items = emptyList(), attachedCustomerId = "cli-1", llavePagarDespues = "pay-later:1")
        q.guardar(fila(estado = Estado.RECUPERAR).copy(venta = venta))
        copias.discoSano = false

        assertEquals(false, q.pasarACarritosGuardados("pay-later:1"))
        assertEquals(Estado.RECUPERAR, fila(q, "pay-later:1")?.estado)
        copias.discoSano = true
        assertEquals(true, q.pasarACarritosGuardados("pay-later:1"))

        assertTrue(q.filas.value.isEmpty())
        assertEquals("D8: con la sucursal de su fila", listOf(venta.copy(venueId = "venue-A")), guardados.savedCarts.value)
        coVerify(exactly = 0) { ordenes.enviarPagarDespues(any(), any()) }
    }

    @Test
    fun `A-6 guardar un carrito mientras el reenvio retira otra copia no lo pierde`() = runTest {
        val guardados = SavedCartsRepository(copias.context)
        (1..60).forEach { guardados.saveCart(SavedCart(id = "a$it", name = "a$it", items = emptyList())) }
        withContext(Dispatchers.Default) {
            val retiros = (1..60).map { async { guardados.deleteCart("a$it") } }
            val nuevos = (1..60).map { async { guardados.saveCart(SavedCart(id = "b$it", name = "b$it", items = emptyList())) } }
            (retiros + nuevos).awaitAll()
        }

        assertEquals((1..60).map { "b$it" }.toSet(), guardados.savedCarts.value.map { it.id }.toSet())
        assertEquals("y lo mismo quedó escrito", 60, SavedCartsRepository(copias.context).savedCarts.value.size)
    }

    // MARK: - El reenvío

    @Test
    fun `la EN_VUELO de un intento vivo no se toca, la de un proceso muerto se reenvia con su cuerpo de reenvio`() = runTest {
        val q = cola()
        q.guardar(fila("pay-later:viva", estado = Estado.EN_VUELO))
        q.guardar(fila("pay-later:huerfana", estado = Estado.EN_VUELO))
        assertTrue(q.marcarEnCurso("pay-later:viva"))
        responde(201, creada())

        q.drenar()

        coVerify(exactly = 0) { ordenes.enviarPagarDespues(any(), match { "pay-later:viva" in it }) }
        coVerify(exactly = 1) { ordenes.enviarPagarDespues("venue-A", fila(id = "pay-later:huerfana").cuerpo) }
        assertNull(fila(q, "pay-later:huerfana"))
        assertEquals(Estado.EN_VUELO, fila(q, "pay-later:viva")?.estado)
    }

    @Test
    fun `la cuenta creada por otro importe queda por revisar con los dos importes`() = runTest {
        val q = cola()
        q.guardar(fila(total = 10000))
        responde(201, creada("112.5"))

        q.drenar()

        val revisar = fila(q, "pay-later:1")!!
        assertEquals(Estado.REVISAR, revisar.estado)
        assertEquals(11250, revisar.totalRegistradoCents)
        assertEquals("ord-1", revisar.orderId)
    }

    @Test
    fun `la cuenta creada sin importe legible queda por revisar, nunca se vuelve a crear`() = runTest {
        val q = cola()
        q.guardar(fila())
        responde(201, creada(null))

        q.drenar()

        assertEquals(Estado.REVISAR, fila(q, "pay-later:1")?.estado)
        assertNull(fila(q, "pay-later:1")?.totalRegistradoCents)
    }

    @Test
    fun `un rechazo de la API queda visible con su motivo y no se borra`() = runTest {
        val q = cola()
        q.guardar(fila())
        responde(404, """{"message":"Cliente no encontrado"}""")

        q.drenar()

        val rechazada = fila(q, "pay-later:1")!!
        assertEquals(Estado.RECHAZADA, rechazada.estado)
        assertEquals("Cliente no encontrado", rechazada.motivo)
    }

    @Test
    fun `sin respuesta corta la pasada, un 5xx sigue con la siguiente`() = runTest {
        val q = cola()
        q.guardar(fila("pay-later:a", creada = 1))
        q.guardar(fila("pay-later:b", creada = 2))
        coEvery { ordenes.enviarPagarDespues(any(), any()) } throws java.io.IOException("sin red")

        q.drenar()

        coVerify(exactly = 1) { ordenes.enviarPagarDespues(any(), any()) }
        assertEquals(Estado.PENDIENTE, fila(q, "pay-later:a")?.estado)

        responde(503, "")
        q.drenar()

        coVerify(exactly = 3) { ordenes.enviarPagarDespues(any(), any()) }
        assertEquals(2, fila(q, "pay-later:a")?.intentos)
        assertEquals(Estado.PENDIENTE, fila(q, "pay-later:b")?.estado)
    }

    @Test
    fun `un 401 corta la pasada sin gastar intento`() = runTest {
        val q = cola()
        q.guardar(fila("pay-later:a", creada = 1))
        q.guardar(fila("pay-later:b", creada = 2))
        responde(401, """{"message":"Token has expired"}""")

        q.drenar()

        coVerify(exactly = 1) { ordenes.enviarPagarDespues(any(), any()) }
        assertEquals(0, fila(q, "pay-later:a")?.intentos)
        assertEquals(Estado.PENDIENTE, fila(q, "pay-later:a")?.estado)
    }

    @Test
    fun `solo se suben las filas de la sucursal de la sesion`() = runTest {
        val q = cola()
        q.guardar(fila("pay-later:otra", venue = "venue-B"))
        responde(201, creada())

        q.drenar()

        coVerify(exactly = 0) { ordenes.enviarPagarDespues(any(), any()) }
        assertEquals(Estado.PENDIENTE, fila(q, "pay-later:otra")?.estado)
    }

    @Test
    fun `al arrancar se retiran las copias guardadas de las filas que van a existir, no las de una rechazada`() = runTest {
        val guardados = SavedCartsRepository(copias.context)
        listOf("copia-vuelo", "copia-pendiente", "copia-rechazada").forEach {
            guardados.saveCart(SavedCart(id = it, name = it, items = emptyList()))
        }
        val anterior = cola()
        anterior.guardar(fila("pay-later:1", estado = Estado.EN_VUELO, copia = "copia-vuelo"))
        anterior.guardar(fila("pay-later:2", estado = Estado.PENDIENTE, copia = "copia-pendiente"))
        anterior.guardar(fila("pay-later:3", estado = Estado.RECHAZADA, copia = "copia-rechazada"))

        cola() // la app vuelve a abrir

        assertEquals(listOf("copia-rechazada"), SavedCartsRepository(copias.context).savedCarts.value.map { it.id })
    }

    @Test
    fun `reintentar una rechazada la manda con la misma llave y descartar la quita`() = runTest {
        val q = cola()
        q.guardar(fila(estado = Estado.RECHAZADA))
        responde(201, creada())

        assertTrue(q.reintentar("pay-later:1") is DesenlacePagarDespues.Creada)
        assertNull(fila(q, "pay-later:1"))

        q.guardar(fila(estado = Estado.REVISAR))
        assertTrue(q.descartar("pay-later:1"))
        assertTrue(q.filas.value.isEmpty())
    }

    // MARK: - D9: el rechazo del REENVÍO sólo devuelve la venta si el SERVIDOR cierra su llave

    private val ventaDeAna = SavedCart(id = "copia-1", name = "Ana", items = emptyList(), attachedCustomerId = "cli-1",
        llavePagarDespues = "pay-later:1", cobroIniciadoEn = 5L, venueId = "venue-A")

    private fun cierra(code: Int, body: String, contentType: String = "application/json") {
        coEvery { ordenes.cerrarLlaveDePagarDespues(any(), any()) } returns OrderRepository.RespuestaCruda(code, contentType, null, body)
    }

    private fun colaConGuardados(): Pair<PayLaterQueue, SavedCartsRepository> {
        val guardados = SavedCartsRepository(copias.context)
        return PayLaterQueue(disco.context, ordenes, storage, guardados, mockk(relaxed = true)).apply { io = Dispatchers.Unconfined } to guardados
    }

    @Test
    fun `D9 el reenvio se rechaza y el SERVIDOR cierra la llave - la venta vuelve a Carritos guardados sin su llave vieja`() = runTest {
        val (q, guardados) = colaConGuardados()
        q.guardar(fila(estado = Estado.PENDIENTE, copia = "copia-1").copy(venta = ventaDeAna))
        responde(400, """{"message":"Producto inválido"}""")
        cierra(200, """{"success":true,"released":true}""")

        q.drenar()
        assertEquals(Estado.RECUPERAR, fila(q, "pay-later:1")?.estado)
        assertEquals("Producto inválido", fila(q, "pay-later:1")?.motivo)
        q.mantenimientoLocal()

        assertTrue(q.filas.value.isEmpty())
        assertEquals(listOf(ventaDeAna.copy(llavePagarDespues = null, cobroIniciadoEn = null)), guardados.savedCarts.value)
        coVerify(exactly = 1) { ordenes.cerrarLlaveDePagarDespues("venue-A", "pay-later:1") }
    }

    @Test
    fun `D9 Codex r4 - la cuenta se creo y perdio la respuesta, el reenvio da 403 - se da por creada y no vuelve`() = runTest {
        val (q, guardados) = colaConGuardados()
        q.guardar(fila(estado = Estado.PENDIENTE, copia = "copia-1").copy(venta = ventaDeAna))
        responde(403, """{"message":"No tienes permiso para crear cuentas"}""")
        cierra(200, """{"success":true,"released":false,"order":{"id":"ord-1","total":100,"status":"CONFIRMED","paymentStatus":"PENDING"}}""")

        q.drenar()
        q.mantenimientoLocal()

        assertTrue(q.filas.value.isEmpty())
        assertTrue("nada vendible de más", guardados.savedCarts.value.isEmpty())
    }

    @Test
    fun `D9 la cuenta existe con otro importe - queda por revisar con su folio`() = runTest {
        val (q, guardados) = colaConGuardados()
        q.guardar(fila(estado = Estado.PENDIENTE, copia = "copia-1").copy(venta = ventaDeAna))
        responde(409, """{"message":"Cambió el precio"}""")
        cierra(200, """{"success":true,"released":false,"order":{"id":"ord-1","total":90}}""")

        q.drenar()

        val revisar = fila(q, "pay-later:1")!!
        assertEquals(Estado.REVISAR, revisar.estado)
        assertEquals("ord-1", revisar.orderId)
        assertEquals(9000, revisar.totalRegistradoCents)
        assertTrue(guardados.savedCarts.value.isEmpty())
    }

    @Test
    fun `D9 si no se puede cerrar la llave (sin permiso, sin red o un portal) queda RECHAZADA como antes y nada vuelve`() = runTest {
        for (cerrar in listOf<() -> Unit>(
            { cierra(403, """{"message":"Sin permiso"}""") },
            { coEvery { ordenes.cerrarLlaveDePagarDespues(any(), any()) } throws java.io.IOException("sin red") },
            { cierra(200, "<html>portal</html>", contentType = "text/html") },
        )) {
            val (q, guardados) = colaConGuardados()
            q.guardar(fila(estado = Estado.PENDIENTE, copia = "copia-1").copy(venta = ventaDeAna))
            responde(400, """{"message":"Producto inválido"}""")
            cerrar()

            q.drenar()
            q.mantenimientoLocal()

            assertEquals(Estado.RECHAZADA, fila(q, "pay-later:1")?.estado)
            assertTrue(guardados.savedCarts.value.isEmpty())
            q.descartar("pay-later:1")
        }
    }

    @Test
    fun `D9 una fila de version anterior (sin venta) queda RECHAZADA y no se pide cerrar la llave`() = runTest {
        val (q, _) = colaConGuardados()
        q.guardar(fila(estado = Estado.PENDIENTE))
        responde(400, """{"message":"Producto inválido"}""")

        q.drenar()

        assertEquals(Estado.RECHAZADA, fila(q, "pay-later:1")?.estado)
        coVerify(exactly = 0) { ordenes.cerrarLlaveDePagarDespues(any(), any()) }
    }

    @Test
    fun `D9 lectura de cerrar la llave - solo un 200 JSON de la API cuenta`() {
        val leer = OrderRepository::leerCierreDeLlave
        assertEquals(CierreDeLlave.Cerrada, leer(200, "application/json; charset=utf-8", null, """{"success":true,"released":true}"""))
        assertEquals(CierreDeLlave.Existe("ord-1", 15050), leer(200, "application/json", null, """{"released":false,"order":{"id":"ord-1","total":"150.50"}}"""))
        assertEquals(CierreDeLlave.Existe("ord-1", null), leer(200, "application/json", null, """{"released":false,"order":{"id":"ord-1"}}"""))
        assertEquals(CierreDeLlave.NoSeSabe, leer(200, "application/json", null, """{"released":"true"}"""))
        assertEquals(CierreDeLlave.NoSeSabe, leer(200, "application/json", null, """{"released":false}"""))
        assertEquals(CierreDeLlave.NoSeSabe, leer(200, "application/json", "ERR_NGROK_3200", """{"released":true}"""))
        assertEquals(CierreDeLlave.NoSeSabe, leer(200, "text/html", null, """{"released":true}"""))
        assertEquals(CierreDeLlave.NoSeSabe, leer(404, "application/json", null, """{"released":true}"""))
    }

    @Test
    fun `D9 Codex codigo r1 #2 - Reintentar que encuentra la cuenta dice que quedo registrada, no el rechazo`() = runTest {
        val (q, _) = colaConGuardados()
        q.guardar(fila(estado = Estado.RECHAZADA, copia = "copia-1").copy(venta = ventaDeAna, motivo = "No tienes permiso"))
        responde(403, """{"message":"No tienes permiso para crear cuentas"}""")
        cierra(200, """{"success":true,"released":false,"order":{"id":"ord-1","total":100}}""")

        val desenlace = q.reintentar("pay-later:1")

        assertEquals(DesenlacePagarDespues.Creada("ord-1", 10000), desenlace)
        assertTrue(q.filas.value.isEmpty())
    }
}
