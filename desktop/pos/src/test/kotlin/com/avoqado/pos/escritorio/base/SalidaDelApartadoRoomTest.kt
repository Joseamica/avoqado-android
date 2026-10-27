package com.avoqado.pos.escritorio.base

import com.avoqado.escritorio.ActividadDeEscritorio
import com.avoqado.pos.cashdrawer.data.CashDrawerDao
import com.avoqado.pos.cashdrawer.data.model.CashDrawerEventEntity
import com.avoqado.pos.cashdrawer.data.model.CashDrawerSessionEntity
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.AvoqadoDatabase
import com.avoqado.pos.core.data.local.database.PendingPaymentDao
import com.avoqado.pos.escritorio.Inyector
import com.avoqado.pos.payment.data.CashPaymentRepository
import com.avoqado.pos.payment.data.ConciliacionDelCobro
import com.avoqado.pos.payment.data.OrderRepository
import com.avoqado.pos.payment.data.SalidaDelApartado
import com.avoqado.pos.payment.data.model.CreateOrderRequest
import java.nio.file.Files
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * 🔴 D2, rediseño r9 (24-oct-2026), con la base REAL de WINDOWS: el único dueño de la anotación ([SalidaDelApartado]) frente al 2xx,
 * a la carrera, al disco que falla y al importe corregido. El 2xx pasa por el `OrderRepository` de verdad con un HTTP de mentira
 * (el cuerpo de NUESTRA API que confirma el cobro).
 */
class SalidaDelApartadoRoomTest {
    private val carpeta = Files.createTempDirectory("fulltest-salida-del-apartado")
    private val inyector = Inyector.crear(ActividadDeEscritorio(carpeta))
    private val storage = inyector.getInstance(SecureStorage::class.java).apply {
        venueId = VENUE; userId = "staff-1"; userFirstName = "Ana"; userLastName = "Ruiz"
    }
    private val db = inyector.getInstance(AvoqadoDatabase::class.java)
    private val pagos = inyector.getInstance(PendingPaymentDao::class.java)
    private val cajon = inyector.getInstance(CashDrawerDao::class.java)
    private val caja = inyector.getInstance(CashPaymentRepository::class.java)
    private val salida = inyector.getInstance(SalidaDelApartado::class.java)

    @AfterTest fun cerrar() {
        runCatching { db.close() }
        carpeta.toFile().deleteRecursively()
    }

    private suspend fun abrir(id: String, status: String = "OPEN", haceMin: Long = 60) = cajon.insertSession(
        CashDrawerSessionEntity(id, VENUE, "PC", "staff-1", "Ana Ruiz", System.currentTimeMillis() - haceMin * 60_000, 50_000, status = status),
    )

    private suspend fun apartar(repo: CashPaymentRepository, llave: String, enLaCaja: String?, importe: Int = 10_000, vendedor: String = "staff-1") =
        repo.reservarCobro(
            CreateOrderRequest(items = emptyList(), subtotal = importe, total = importe, paymentMethod = "CASH"), vendedor, importe, 0, null,
            idempotencyKey = llave, venueId = VENUE, cajaDelCobro = enLaCaja,
        )

    /** El `/fast` de nuestra API contestando 2xx que CONFIRMA el cobro, con lo que el servidor registró. */
    private fun ordenes(pesos: String) = ordenesQueContestan(
        """{"success":true,"data":{"id":"pago-srv","orderId":"orden-srv","amount":"$pesos","tipAmount":"0.00","status":"COMPLETED"}}""",
    )

    /** Un 2xx con el cuerpo que se le dé (p. ej. uno que NO confirma el cobro). */
    private fun ordenesQueContestan(cuerpo: String) = OrderRepository(
        storage,
        OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(cuerpo.toResponseBody("application/json".toMediaType())).build()
        }.build(),
        pagos,
        salida,
    )

    private suspend fun dosXx(llave: String, importe: Int = 10_000) =
        ordenes("%d.%02d".format(importe / 100, importe % 100)).recordFastCashPayment(importe, "staff-1", idempotencyKey = llave, capturedVenueId = VENUE)

    private suspend fun ventasEn(vararg cajas: String) = cajas.flatMap { cajon.getSessionEvents(it) }.filter { it.type == "CASH_SALE" }
    private suspend fun estado(llave: String) = pagos.findForVenue(VENUE, llave)?.syncStatus
    private fun det(llave: String) = UUID.nameUUIDFromBytes("CASH_SALE:$llave".toByteArray(Charsets.UTF_8)).toString()

    /** Prueba 2: escribir la venta falla (disco). La transacción entera se deshace: la fila sigue `EN_VUELO`; la siguiente vuelta la anota. */
    @Test fun `si escribir la venta falla la fila sigue EN_VUELO y la siguiente vuelta la anota y la pasa`() = runBlocking {
        abrir("caja-1")
        var falla = true
        val cajonQueFalla = object : CashDrawerDao by cajon {
            override suspend fun insertarEventoSiNoExiste(event: CashDrawerEventEntity): Long =
                if (falla) throw java.io.IOException("disco lleno") else cajon.insertarEventoSiNoExiste(event)
        }
        val repo = CashPaymentRepository(storage, pagos, SalidaDelApartado(pagos, cajonQueFalla, storage, db))
        apartar(repo, "k1", enLaCaja = "caja-1")
        repo.olvidarReservaEnMemoria("k1")

        assertEquals(0, repo.darPorCobradasLasAbandonadas())
        assertEquals("EN_VUELO", estado("k1"), "el UPDATE a PENDING se deshizo con la venta")
        assertTrue(ventasEn("caja-1").isEmpty())

        falla = false
        assertEquals(1, repo.darPorCobradasLasAbandonadas())
        assertEquals("PENDING", estado("k1"))
        assertEquals(listOf(det("k1")), ventasEn("caja-1").map { it.id })
    }

    /** Prueba 4: el barrido y el 2xx tardío de la MISMA venta, en los dos órdenes: una sola anotación. */
    @Test fun `el barrido y el 2xx tardio en cualquier orden anotan una vez`() = runBlocking {
        abrir("caja-1")
        // Barrido primero, luego el 2xx de la respuesta que tardó.
        apartar(caja, "barrido-primero", "caja-1"); caja.olvidarReservaEnMemoria("barrido-primero")
        assertEquals(1, caja.darPorCobradasLasAbandonadas())
        assertTrue(dosXx("barrido-primero").isSuccess)
        // El 2xx primero (con la venta todavía en pantalla), luego el barrido.
        apartar(caja, "dos-xx-primero", "caja-1")
        assertTrue(dosXx("dos-xx-primero").isSuccess)
        caja.olvidarReservaEnMemoria("dos-xx-primero")
        assertEquals(0, caja.darPorCobradasLasAbandonadas())

        assertEquals("SYNCED", estado("barrido-primero"))
        assertEquals("SYNCED", estado("dos-xx-primero"))
        val ids = ventasEn("caja-1").map { it.id }
        assertEquals(listOf(det("barrido-primero"), det("dos-xx-primero")).sorted(), ids.sorted(), "una por venta")
    }

    /** Prueba 6: con el 2xx en línea y OTRA caja abierta después, la venta va a la que capturó el cobro; cerrada, a ninguna. */
    @Test fun `el 2xx anota en la caja capturada nunca en la abierta ahora`() = runBlocking {
        abrir("caja-1", haceMin = 120)
        apartar(caja, "k1", "caja-1")
        apartar(caja, "k2", "caja-1")
        abrir("caja-2", haceMin = 1) // la más reciente: es la que `getOpenSession` devuelve ahora
        assertEquals("caja-2", cajon.getOpenSession(VENUE)?.id)

        assertTrue(dosXx("k1").isSuccess)
        cajon.insertSession(cajon.getSession("caja-1")!!.copy(status = "CLOSED", closedAt = System.currentTimeMillis()))
        assertTrue(dosXx("k2").isSuccess)

        assertEquals(listOf(det("k1")), ventasEn("caja-1").map { it.id }, "en la capturada")
        assertTrue(ventasEn("caja-2").isEmpty(), "nunca en la abierta ahora")
        assertEquals("SYNCED", estado("k2"), "cerrada la capturada: se cobra igual, sin anotarse en otra caja")
    }

    /**
     * Prueba 7: la carrera de verdad, en hilos, entre el barrido y el 2xx tardío de la misma venta (y el respaldo del 2xx). La base
     * los pone en fila: una sola anotación, y conserva la caja del primero aunque después la caja cambie (alias a otra abierta).
     */
    @Test fun `la carrera del barrido contra el 2xx tardio anota una vez y conserva la caja del primero`() = runBlocking {
        abrir("caja-1")
        repeat(15) { i ->
            val llave = "carrera-$i"
            apartar(caja, llave, "caja-1"); caja.olvidarReservaEnMemoria(llave)
            val largada = CompletableDeferred<Unit>()
            listOf(
                async(Dispatchers.Default) { largada.await(); caja.darPorCobradasLasAbandonadas() },
                async(Dispatchers.Default) { largada.await(); dosXx(llave) },
                async(Dispatchers.Default) { largada.await(); caja.darPorCobrado(llave) },
            ).also { largada.complete(Unit) }.awaitAll()
            assertEquals(listOf(det(llave)), ventasEn("caja-1").map { it.id }.filter { it == det(llave) }, "vuelta $i")
        }
        // La caja cambia después: un escritor tardío no la muda a la otra.
        cajon.insertSession(cajon.getSession("caja-1")!!.copy(status = "CLOSED"))
        abrir("caja-2", haceMin = 0)
        storage.setDrawerSessionAliasesJson(VENUE, """[{"local":"caja-1","servidor":"caja-2","en":1}]""")
        repeat(15) { i -> caja.darPorCobrado("carrera-$i"); dosXx("carrera-$i") }
        assertEquals(15, ventasEn("caja-1").size)
        assertTrue(ventasEn("caja-2").isEmpty())
        // Codex r9 #4: el insert es IGNORE de verdad en SQLite. Un segundo escritor del MISMO id (p. ej. el cajón del ViewModel en un
        // camino sin apartado con esa llave) no la muda de caja ni le cambia el importe.
        val primera = ventasEn("caja-1").first()
        assertEquals(-1L, cajon.insertarEventoSiNoExiste(primera.copy(sessionId = "caja-2", amountCents = 1)))
        assertEquals(primera, cajon.getEvent(primera.id))
    }

    /**
     * Codex r10 #1: la cajera aceptó un total más bajo (precio corregido o total adoptado) y la venta se soltó antes del 2xx. El apartado
     * ya lleva el importe aceptado: el barrido anota ESE importe y la cola sube ESE importe (lee `amountCents` de la fila). La captura
     * Q15 original no se toca.
     */
    @Test fun `el apartado con el importe aceptado se da por cobrado y sube con ese importe`() = runBlocking {
        abrir("caja-1")
        apartar(caja, "k1", "caja-1", importe = 10_000)
        caja.actualizarApartado("k1", VENUE, 8_000, "orden-k1")
        caja.olvidarReservaEnMemoria("k1")

        assertEquals(1, caja.darPorCobradasLasAbandonadas())

        val fila = pagos.findForVenue(VENUE, "k1")!!
        assertEquals("PENDING", fila.syncStatus)
        assertEquals(8_000, fila.amountCents, "lo que la cola manda al reproducirla")
        assertEquals("orden-k1", fila.orderId, "agregado 1: y su orden, en la misma escritura")
        assertEquals(10_000, ConciliacionDelCobro.leerCaptura(fila.q15CaptureJson, VENUE, "k1")?.amountCents, "la captura original")
        assertEquals(listOf(8_000), ventasEn("caja-1").map { it.amountCents })
        assertEquals(listOf<String?>("orden-k1"), ventasEn("caja-1").map { it.orderId }, "la venta del cajón lleva su orden")
    }

    /** Con el 2xx comprobado la venta se anota AUNQUE la caja ya traiga la gemela del servidor (la fusión con red retira la copia). */
    /** Codex r14 A5: el respaldo del 2xx comprobado (lo local falló tras comprobarlo) anota lo que el servidor CONFIRMÓ, no lo apartado. */
    @Test fun `el respaldo del 2xx comprobado anota el importe que confirmo el servidor`() = runBlocking {
        abrir("caja-1")
        apartar(caja, "k-respaldo", "caja-1") // 100.00 apartados

        assertTrue(caja.darPorCobrado("k-respaldo", importeCents = 8_000))

        assertEquals("PENDING", pagos.findForVenue(VENUE, "k-respaldo")?.syncStatus)
        assertEquals(8_000, cajon.getEvent(com.avoqado.pos.cashdrawer.data.CashDrawerRepository.idDeLaVentaEnEfectivo("k-respaldo"))?.amountCents)
    }

    @Test fun `con 2xx comprobado se anota aunque la gemela del servidor ya este en la caja`() = runBlocking {
        abrir("caja-1")
        apartar(caja, "k1", "caja-1", importe = 10_000)
        cajon.insertEvent(
            CashDrawerEventEntity(
                id = "cmh0servidorgemelaconelxx1", sessionId = "caja-1", venueId = VENUE, type = "CASH_SALE", amountCents = 10_000,
                note = null, staffId = "staff-1", staffName = "Ana Ruiz", orderId = "orden-srv", createdAt = System.currentTimeMillis(),
            ),
        )

        assertTrue(dosXx("k1").isSuccess)

        assertEquals("SYNCED", estado("k1"))
        assertTrue(ventasEn("caja-1").any { it.id == det("k1") })
    }

    /** Agregado 8: un 2xx que NO comprueba el cobro (portal cautivo, intermediario) queda por revisar y no entra al cajón, ni barriendo. */
    @Test fun `un 2xx sin comprobar no anota la venta ni la deja al barrido`() = runBlocking {
        abrir("caja-1")
        apartar(caja, "k1", "caja-1")

        val cobro = ordenesQueContestan("""{"success":true}""").recordFastCashPayment(10_000, "staff-1", idempotencyKey = "k1", capturedVenueId = VENUE)
        caja.olvidarReservaEnMemoria("k1")
        assertEquals(0, caja.darPorCobradasLasAbandonadas())

        assertTrue(cobro.exceptionOrNull() is OrderRepository.ResultadoNoComprobado)
        // Como hoy (Q15/H2): queda en la cola con su motivo, por revisar; su escritura no pasa por la salida que anota el cajón.
        assertEquals("PENDING", estado("k1"))
        assertEquals(ConciliacionDelCobro.RESULTADO_NO_COMPROBADO, pagos.findForVenue(VENUE, "k1")?.q15Reason)
        assertTrue(ventasEn("caja-1").isEmpty())
    }

    /** Agregado 8: «No se cobró» (una persona) guarda la evidencia y no anota la venta, ni barriendo. */
    @Test fun `No se cobro no anota la venta ni la deja al barrido`() = runBlocking {
        abrir("caja-1")
        apartar(caja, "k1", "caja-1")
        caja.olvidarReservaEnMemoria("k1")

        caja.descartarPorqueNoSeCobro("k1")
        assertEquals(0, caja.darPorCobradasLasAbandonadas())

        assertEquals("EN_VUELO", estado("k1"))
        assertTrue(ventasEn("caja-1").isEmpty())
    }

    /**
     * Agregado 2: quién cobró. La salida DEL FLUJO (encolar, 2xx) anota a la cajera que el flujo capturó, aunque la venta la haya hecho
     * otro vendedor. El barrido (y «Sí se cobró») usan el `staffId` de la fila y su nombre sólo si es la sesión de ahora.
     */
    @Test fun `la salida del flujo anota a la cajera capturada y el barrido al de la fila`() = runBlocking {
        abrir("caja-1")
        apartar(caja, "k-encolado", "caja-1", importe = 10_000, vendedor = "vendedor-1")
        caja.recordarCajero("k-encolado", "cajera-1", "Bea Caja")
        caja.queueCashPayment(
            CreateOrderRequest(items = emptyList(), subtotal = 10_000, total = 10_000, paymentMethod = "CASH"), "vendedor-1", 10_000, 0, null,
            idempotencyKey = "k-encolado", venueId = VENUE,
        )
        apartar(caja, "k-2xx", "caja-1", importe = 20_000, vendedor = "vendedor-1")
        caja.recordarCajero("k-2xx", "cajera-1", "Bea Caja")
        assertTrue(dosXx("k-2xx", 20_000).isSuccess)
        apartar(caja, "k-barrido", "caja-1", importe = 30_000, vendedor = "vendedor-1")
        caja.recordarCajero("k-barrido", "cajera-1", "Bea Caja")
        caja.olvidarReservaEnMemoria("k-barrido")
        assertEquals(1, caja.darPorCobradasLasAbandonadas())

        val porLlave = ventasEn("caja-1").associateBy { it.id }
        for (llave in listOf("k-encolado", "k-2xx")) {
            assertEquals("cajera-1", porLlave.getValue(det(llave)).staffId, llave)
            assertEquals("Bea Caja", porLlave.getValue(det(llave)).staffName, llave)
        }
        assertEquals("vendedor-1", porLlave.getValue(det("k-barrido")).staffId)
        assertEquals("", porLlave.getValue(det("k-barrido")).staffName, "no es la sesión de ahora")
    }

    /**
     * Agregado 5 y Codex r11 #2: la lectura de la pareja exacta va acotada en el SQL y por tandas. Sólo efectivo PURO que todavía puede
     * subir (PENDING/SYNCING sin motivo o esperando precio, EN_VUELO sin motivo), de ESE negocio y de ESAS cajas. Lo SYNCED con captura
     * (que no se purga nunca), lo FAILED, lo retenido, lo manual, lo de otra caja y lo de otro negocio no se leen. Las tandas siguen
     * desde la última fila, sin saltarse ni repetir ninguna.
     */
    @Test fun `los cobros de efectivo vivos se leen acotados en el SQL y por tandas`() = runBlocking {
        val venta = CreateOrderRequest(items = emptyList(), subtotal = 10_000, total = 10_000, paymentMethod = "CASH")
        suspend fun fila(llave: String, venue: String = VENUE, manual: com.avoqado.pos.payment.domain.ManualPaymentMethod? = null, enLaCaja: String = "caja-1") =
            caja.reservarCobro(venta, "staff-1", 10_000, 0, null, idempotencyKey = llave, venueId = venue, manualMethod = manual, cajaDelCobro = enLaCaja)
        fila("en-vuelo")
        fila("pendiente"); pagos.enVueloAPendiente("pendiente")
        fila("esperando-precio"); pagos.enVueloAPendiente("esperando-precio")
        pagos.retenerPagoQ15(VENUE, "esperando-precio", ConciliacionDelCobro.PRICE_PROOF_PENDING, 1L)
        fila("retenida"); pagos.retenerPagoQ15(VENUE, "retenida", ConciliacionDelCobro.RESULTADO_NO_COMPROBADO, 1L)
        fila("subida"); pagos.registrarResultadoQ15(VENUE, "subida", true, null, null, 1L)
        fila("fallida"); pagos.enVueloAPendiente("fallida"); pagos.updateStatus("fallida", "FAILED")
        fila("manual", manual = com.avoqado.pos.payment.domain.ManualPaymentMethod.CARD_EXTERNAL)
        fila("de-otra-caja", enLaCaja = "caja-2")
        fila("de-otro-negocio", venue = "OTRO-VENUE")

        val vivos = pagos.cobrosDeEfectivoVivos(VENUE, listOf("caja-1"), Long.MIN_VALUE, "", 500).map { it.id }.toSet()
        assertEquals(setOf("en-vuelo", "pendiente", "esperando-precio"), vivos)

        val porTandas = mutableListOf<String>()
        var despuesDe = Long.MIN_VALUE
        var despuesDeId = ""
        while (true) {
            val tanda = pagos.cobrosDeEfectivoVivos(VENUE, listOf("caja-1"), despuesDe, despuesDeId, 1)
            if (tanda.isEmpty()) break
            assertEquals(1, tanda.size, "cada tanda respeta su tope")
            porTandas += tanda.single().id
            despuesDe = tanda.single().createdAt; despuesDeId = tanda.single().id
        }
        assertEquals(vivos, porTandas.toSet(), "de una en una salen todas, sin repetir")
        assertEquals(porTandas.size, porTandas.toSet().size)
    }

    private companion object { const val VENUE = "FULLTEST-SALIDA-APARTADO" }
}
