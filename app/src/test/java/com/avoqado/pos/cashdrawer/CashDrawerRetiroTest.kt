package com.avoqado.pos.cashdrawer

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.cashdrawer.data.ComprobanteDeRetiro
import com.avoqado.pos.cashdrawer.data.ComprobantesDeRetiroPorImprimir
import com.avoqado.pos.cashdrawer.data.PendingDrawerOp
import com.avoqado.pos.cashdrawer.data.ResultadoDelRetiro
import com.avoqado.pos.cashdrawer.data.llaveDelRetiro
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ForbiddenInterceptor
import com.avoqado.pos.core.data.network.OverrideResult
import com.avoqado.pos.core.data.network.PermissionOverrideRepository
import com.avoqado.pos.core.di.registroDeRed
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/**
 * Task 9 del retiro con autorización de gerente: el retiro (con o sin autorización) va por el camino de siempre — fila, cola
 * durable CON su autorización, comprobante en disco y POST — y el replay lo sube con el mismo token. Un rechazo al momento lo
 * deshace todo; un 409 de cierre en proceso no.
 */
class CashDrawerRetiroTest {

    @get:Rule val temporal = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    /** La escritura de la cola truena (disco lleno, la app muere a media escritura). */
    private var fallarAlEscribirLaCola = false
    /** La escritura de la cola «sale bien» pero no queda nada: `encolar` lo detecta al releer. */
    private var ignorarEscriturasDeLaCola = false
    /** Se llama con cada escritura de la cola, ANTES de guardarla: para ver qué había en disco en ese instante. */
    private var alEscribirLaCola: (String?) -> Unit = {}
    private val dao = FakeCashDrawerDao().apply { runBlocking { insertSession(sesionLocal("srv-1", openedAt = haceMinutos(60))) } }
    private val storage = almacenConCola()
    private val store by lazy {
        ComprobantesDeRetiroPorImprimir(PreferenceDataStoreFactory.create(scope = scope, produceFile = { temporal.root.resolve("retiro_comprobantes.preferences_pb") }))
    }
    private val enElCable: MutableList<Request> = java.util.Collections.synchronizedList(mutableListOf())

    @After fun cerrar() { scope.cancel() }

    // MARK: - Andamio

    private fun almacenConCola(colaInicial: String? = null) = mockk<SecureStorage>(relaxed = true).also { st ->
        var cola: String? = colaInicial
        every { st.venueId } returns VENUE_ID
        every { st.venueName } returns "Testarudo Cafe"
        every { st.userId } returns "staff-1"
        every { st.userFirstName } returns "Ana"
        every { st.userLastName } returns "Ruiz"
        every { st.pendingDrawerOpsJson(any()) } answers { cola }
        every { st.setPendingDrawerOpsJson(any(), any()) } answers {
            alEscribirLaCola(secondArg())
            if (fallarAlEscribirLaCola) throw IllegalStateException("no se pudo escribir la cola")
            if (!ignorarEscriturasDeLaCola) cola = secondArg()
        }
    }

    private fun cola(): List<PendingDrawerOp> =
        storage.pendingDrawerOpsJson(VENUE_ID)
            ?.let { Json { ignoreUnknownKeys = true }.decodeFromString(ListSerializer(PendingDrawerOp.serializer()), it) }
            ?: emptyList()

    /** `responder` null = sin red. Guarda cada petición (con sus headers) ANTES de contestar. */
    private fun servidor(responder: (Request) -> Pair<Int, String>?): OkHttpClient = mockk {
        every { newCall(any()) } answers {
            val request = firstArg<Request>()
            enElCable += request
            val call = mockk<Call>()
            every { call.execute() } answers {
                val r = responder(request) ?: throw IOException("sin red: ${request.url.encodedPath}")
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(r.first).message("x")
                    .body(r.second.toResponseBody("application/json".toMediaType())).build()
            }
            call
        }
    }

    private fun repo(client: OkHttpClient, comprobantes: ComprobantesDeRetiroPorImprimir = store) = CashDrawerRepository(
        dao, storage, client, sinCobrosEnCola(), conectividadDePrueba(), database = null, comprobantes = comprobantes,
    )

    private fun retiroDelServidor(authorizedBy: String = "null") =
        """{"success":true,"data":${eventoJson("srv-ev-1", "PAY_OUT", "50.00", note = "Proveedor", localId = "loc-1")},"authorizedBy":$authorizedBy}"""

    private fun comprobante(localId: String = "loc-1") = ComprobanteDeRetiro(
        venueId = VENUE_ID, localId = localId, venueName = "Testarudo Cafe", nombreDelAparato = "Caja 1",
        fechaTexto = "10/11/2026", horaTexto = "13:05", concepto = "Proveedor", amountCents = 5_000,
        hizo = "Ana Ruiz", rolDeQuienHizo = "Cajero", autorizadoPor = "Daniel Pérez",
    )

    private fun retiros() = dao.events.values.filter { it.type == "PAY_OUT" }
    private fun pagoAlRetiro(r: Request) = r.url.encodedPath.endsWith("/pay-out")

    // MARK: - El retiro al momento

    @Test fun `201 confirma, quita la cola y trae el autorizador del servidor`() = runBlocking {
        val r = repo(servidor { 201 to retiroDelServidor("""{"id":"sv-9","name":"Daniel"}""") })
            .addPayOut(5_000, "Proveedor", localId = "loc-1", autorizacion = "tok-1")

        val hecho = r as ResultadoDelRetiro.Hecho
        assertTrue(hecho.confirmado)
        assertEquals("Daniel", hecho.autorizadoPor)
        assertEquals("srv-ev-1", hecho.evento.id)
        assertEquals("un solo retiro", 1, retiros().size)
        assertTrue("la cola debía quedar vacía: ${cola()}", cola().isEmpty())
    }

    @Test fun `con autorizacion, la peticion lleva X-Permission-Override y la marca de error local`() = runBlocking {
        repo(servidor { 201 to retiroDelServidor() }).addPayOut(5_000, "Proveedor", localId = "loc-1", autorizacion = "tok-1")

        val peticion = enElCable.single(::pagoAlRetiro)
        assertEquals("tok-1", peticion.header(ForbiddenInterceptor.PERMISSION_OVERRIDE_HEADER))
        assertNotNull(peticion.header(ForbiddenInterceptor.LOCAL_ERROR_HEADER))
        assertTrue(peticion.body!!.let { b -> okio.Buffer().also { b.writeTo(it) }.readUtf8() }.contains("\"localId\":\"loc-1\""))
    }

    @Test fun `sin red se guarda y se encola CON su autorizacion, y el replay la manda`() = runBlocking {
        val r = repo(servidor { null }).addPayOut(5_000, "Proveedor", localId = "loc-1", autorizacion = "tok-1")

        assertEquals(ResultadoDelRetiro.Hecho(retiros().single(), autorizadoPor = null, confirmado = false), r)
        assertEquals("tok-1", cola().single().autorizacion)

        enElCable.clear()
        repo(servidor { 200 to retiroDelServidor() }).reproducirPendientes()

        val reenvio = enElCable.single(::pagoAlRetiro)
        assertEquals("tok-1", reenvio.header(ForbiddenInterceptor.PERMISSION_OVERRIDE_HEADER))
        assertNotNull(reenvio.header(ForbiddenInterceptor.LOCAL_ERROR_HEADER))
        assertTrue(cola().isEmpty())
        assertEquals(1, retiros().size)
    }

    @Test fun `el reintento de un PAY_IN no cambia`() = runBlocking {
        repo(servidor { null }).addPayIn(PAY_IN_CENTS, "Cambio")
        enElCable.clear()

        repo(servidor { 200 to """{"success":true,"data":${eventoJson("srv-in", "PAY_IN", "100.00")}}""" }).reproducirPendientes()

        val reenvio = enElCable.single { it.url.encodedPath.endsWith("/pay-in") }
        assertNull(reenvio.header(ForbiddenInterceptor.PERMISSION_OVERRIDE_HEADER))
        assertNull(reenvio.header(ForbiddenInterceptor.LOCAL_ERROR_HEADER))
    }

    /** Fallo de revisión #2: el teclado global sólo daría un token suelto, que `/pay-out` nunca acepta. */
    @Test fun `un retiro sin autorizacion se reproduce sin token, con marca local`() = runBlocking {
        repo(servidor { null }).addPayOut(5_000, "Proveedor", localId = "loc-1")
        assertNull(cola().single().autorizacion)
        enElCable.clear()

        repo(servidor { 200 to retiroDelServidor() }).reproducirPendientes()

        val reenvio = enElCable.single(::pagoAlRetiro)
        assertNull(reenvio.header(ForbiddenInterceptor.PERMISSION_OVERRIDE_HEADER))
        assertNotNull(reenvio.header(ForbiddenInterceptor.LOCAL_ERROR_HEADER))
    }

    @Test fun `el primer POST sin token lleva la marca local`() = runBlocking {
        repo(servidor { 201 to retiroDelServidor() }).addPayOut(5_000, "Proveedor", localId = "loc-1")

        val peticion = enElCable.single(::pagoAlRetiro)
        assertNull(peticion.header(ForbiddenInterceptor.PERMISSION_OVERRIDE_HEADER))
        assertNotNull(peticion.header(ForbiddenInterceptor.LOCAL_ERROR_HEADER))
    }

    /** Fallo de revisión #4: nunca un `Hecho(confirmado = false)` sin una operación durable que lo vaya a mandar. */
    @Test fun `si la cola no se puede guardar, se deshace y no se manda nada`() = runBlocking {
        ignorarEscriturasDeLaCola = true
        val r = repo(servidor { 201 to retiroDelServidor() })
            .addPayOut(5_000, "Proveedor", localId = "loc-1", autorizacion = "tok-1", comprobante = comprobante())

        assertEquals(ResultadoDelRetiro.Rechazado("No se pudo guardar el retiro en el aparato. Intenta de nuevo."), r)
        assertTrue("quedó la fila", retiros().isEmpty())
        assertTrue("salió el POST", enElCable.none(::pagoAlRetiro))
        assertTrue("quedó el comprobante", store.pendientes(VENUE_ID).isEmpty())
    }

    @Test fun `si falla anotar el comprobante, Rechazado y no queda nada`() = runBlocking {
        val roto = mockk<ComprobantesDeRetiroPorImprimir> {
            coEvery { anotar(any()) } throws IOException("disco lleno")
            coEvery { quitar(any(), any()) } just Runs
        }
        val r = repo(servidor { 201 to retiroDelServidor() }, comprobantes = roto)
            .addPayOut(5_000, "Proveedor", localId = "loc-1", autorizacion = "tok-1", comprobante = comprobante())

        assertEquals(ResultadoDelRetiro.Rechazado("No se pudo guardar el retiro en el aparato. Intenta de nuevo."), r)
        assertTrue("quedó la fila", retiros().isEmpty())
        assertTrue("quedó en la cola: ${cola()}", cola().isEmpty())
        assertTrue("salió el POST", enElCable.none(::pagoAlRetiro))
    }

    @Test fun `403 al momento se deshace - ni fila ni cola`() = runBlocking {
        val r = repo(servidor { 403 to """{"error":"Forbidden","message":"Ese código no autoriza este retiro"}""" })
            .addPayOut(5_000, "Proveedor", localId = "loc-1", autorizacion = "tok-1")

        assertEquals(ResultadoDelRetiro.Rechazado("Ese código no autoriza este retiro"), r)
        assertTrue("quedó la fila del retiro rechazado", retiros().isEmpty())
        assertTrue("quedó en la cola: ${cola()}", cola().isEmpty())
    }

    @Test fun `400, 422 y un 409 cualquiera al momento tambien se deshacen`() = runBlocking {
        for (code in listOf(400, 422, 409)) {
            val r = repo(servidor { code to """{"message":"no ($code)"}""" }).addPayOut(5_000, "Proveedor", localId = "loc-$code")
            assertEquals(ResultadoDelRetiro.Rechazado("no ($code)"), r)
        }
        assertTrue(retiros().isEmpty())
        assertTrue(cola().isEmpty())
    }

    @Test fun `409 de cierre en proceso no - sigue en cola`() = runBlocking {
        val r = repo(servidor { 409 to """{"code":"SHIFT_CLOSE_IN_PROGRESS","message":"Cerrando turno"}""" })
            .addPayOut(5_000, "Proveedor", localId = "loc-1", autorizacion = "tok-1")

        assertEquals(ResultadoDelRetiro.Hecho(retiros().single(), autorizadoPor = null, confirmado = false), r)
        assertEquals("loc-1", cola().single().localId)
        assertEquals("tok-1", cola().single().autorizacion)
    }

    @Test fun `una cola guardada antes de este cambio se sigue leyendo`() {
        val guardadoAntes = """[{"kind":"PAY_OUT","sessionId":"srv-1","amountCents":5000,"note":"pago","localId":"loc-1","at":1756400000000}]"""
        val op = Json { ignoreUnknownKeys = true }.decodeFromString(ListSerializer(PendingDrawerOp.serializer()), guardadoAntes).single()
        assertNull(op.autorizacion)
        assertEquals(5000, op.amountCents)
    }

    @Test fun `la llave del retiro es la misma que la del servidor`() {
        assertEquals("pay-out:loc-1:500000", llaveDelRetiro("loc-1", 500_000))
    }

    // MARK: - El comprobante (Codex r3 #7)

    @Test fun `al llegar el POST ya estan la fila, la cola y el comprobante`() = runBlocking {
        var alLlegar: Triple<Boolean, Boolean, Boolean>? = null
        repo(servidor { peticion ->
            if (pagoAlRetiro(peticion)) {
                alLlegar = Triple(
                    dao.events.containsKey("loc-1"),
                    cola().any { it.localId == "loc-1" },
                    runBlocking { store.pendientes(VENUE_ID) }.any { it.localId == "loc-1" },
                )
            }
            201 to retiroDelServidor()
        }).addPayOut(5_000, "Proveedor", localId = "loc-1", autorizacion = "tok-1", comprobante = comprobante())

        assertEquals("al llegar el POST: (fila, cola, comprobante)", Triple(true, true, true), alLlegar)
        assertEquals("el papel sigue pendiente hasta imprimirse", listOf(comprobante()), store.pendientes(VENUE_ID))
    }

    @Test fun `un rechazo al momento tambien quita el comprobante`() = runBlocking {
        repo(servidor { 403 to """{"message":"no"}""" })
            .addPayOut(5_000, "Proveedor", localId = "loc-1", autorizacion = "tok-1", comprobante = comprobante())

        assertTrue(store.pendientes(VENUE_ID).isEmpty())
    }

    /**
     * Fallo de revisión #1: el papel se quita PRIMERO. Si deshacer se interrumpe después (aquí: la cola no se deja escribir), no queda
     * un comprobante que la recuperación imprimiría como reimpresión de un retiro que el servidor rechazó.
     */
    @Test fun `si deshacer el rechazo se interrumpe al quitar la cola, no queda comprobante`() = runBlocking {
        val r = repo(servidor { fallarAlEscribirLaCola = true; 403 to """{"message":"no"}""" })
        runCatching { r.addPayOut(5_000, "Proveedor", localId = "loc-1", autorizacion = "tok-1", comprobante = comprobante()) }

        assertTrue("quedó el comprobante de un retiro rechazado", store.pendientes(VENUE_ID).isEmpty())
        assertTrue("la fila se borra antes que la operación", retiros().isEmpty())
        assertEquals("la operación sigue (su replay recibirá el rechazo y lo marcará)", "loc-1", cola().single().localId)
    }

    /** Fallo de revisión #1: cerrar la pantalla (cancelar la corrutina) con la respuesta en camino no deja el rechazo a medias. */
    @Test fun `si cancelan la corrutina con la respuesta en camino, el rechazo igual se deshace`() = runBlocking {
        lateinit var trabajo: Job
        val r = repo(servidor { trabajo.cancel(); 403 to """{"message":"no"}""" })
        trabajo = launch { r.addPayOut(5_000, "Proveedor", localId = "loc-1", autorizacion = "tok-1", comprobante = comprobante()) }
        trabajo.join()

        assertTrue("quedó el comprobante", store.pendientes(VENUE_ID).isEmpty())
        assertTrue("quedó la fila", retiros().isEmpty())
        assertTrue("quedó en la cola: ${cola()}", cola().isEmpty())
    }

    @Test fun `el comprobante de otro negocio no sale en este`() = runBlocking {
        store.anotar(comprobante().copy(venueId = "otro"))
        store.anotar(comprobante("loc-2"))
        assertEquals(listOf("loc-2"), store.pendientes(VENUE_ID).map { it.localId })
        store.quitar(VENUE_ID, "loc-2")
        assertTrue(store.pendientes(VENUE_ID).isEmpty())
    }

    // MARK: - Auditoría final (H6–H11)

    /** H7: el papel va ANTES de publicar la operación. Si anotarlo falla, la operación nunca llegó a la cola: el replay no pudo verla. */
    @Test fun `si falla anotar el comprobante, la operacion nunca llega a la cola`() = runBlocking {
        var colaAlAnotar: List<PendingDrawerOp>? = null
        val roto = mockk<ComprobantesDeRetiroPorImprimir> {
            coEvery { anotar(any()) } answers { colaAlAnotar = cola(); throw IOException("disco lleno") }
            coEvery { quitar(any(), any()) } just Runs
        }
        val r = repo(servidor { 201 to retiroDelServidor() }, comprobantes = roto)
            .addPayOut(5_000, "Proveedor", localId = "loc-1", autorizacion = "tok-1", comprobante = comprobante())

        assertEquals(ResultadoDelRetiro.Rechazado("No se pudo guardar el retiro en el aparato. Intenta de nuevo."), r)
        assertEquals("al anotar el papel la operación ya estaba publicada", emptyList<PendingDrawerOp>(), colaAlAnotar)
        assertTrue(cola().isEmpty())
        assertTrue(retiros().isEmpty())
        assertTrue(enElCable.none(::pagoAlRetiro))
    }

    /** H7: si la app muere justo después de publicar la operación (antes del POST), su comprobante ya está en disco. */
    @Test fun `cuando la operacion llega a la cola su comprobante ya esta guardado`() = runBlocking {
        var papelAlPublicar: Boolean? = null
        alEscribirLaCola = { json ->
            if (papelAlPublicar == null && json?.contains("loc-1") == true) {
                papelAlPublicar = runBlocking { store.pendientes(VENUE_ID) }.any { it.localId == "loc-1" }
            }
        }
        repo(servidor { null }).addPayOut(5_000, "Proveedor", localId = "loc-1", autorizacion = "tok-1", comprobante = comprobante())

        assertEquals("al publicar la operación, ¿ya estaba el papel?", true, papelAlPublicar)
        assertEquals("loc-1", cola().single().localId)
    }

    /** H8: el primer envío toma el candado del replay: el replay no manda (ni confirma) un retiro que addPayOut todavía puede deshacer. */
    @Test fun `el replay no manda el retiro mientras addPayOut lo esta registrando`() = runBlocking {
        val enElPost = CompletableDeferred<Unit>()
        val soltar = java.util.concurrent.CountDownLatch(1)
        val r = repo(servidor { peticion ->
            if (pagoAlRetiro(peticion)) { enElPost.complete(Unit); soltar.await(10, java.util.concurrent.TimeUnit.SECONDS) }
            201 to retiroDelServidor()
        })

        val retiro = async(Dispatchers.IO) { r.addPayOut(5_000, "Proveedor", localId = "loc-1", autorizacion = "tok-1") }
        enElPost.await()
        val replay = async(Dispatchers.IO) { r.reproducirPendientes() }
        delay(300) // le da tiempo al replay de colarse, si pudiera
        soltar.countDown()
        retiro.await()
        replay.await()

        assertEquals("el retiro salió dos veces", 1, synchronized(enElCable) { enElCable.count(::pagoAlRetiro) })
        assertTrue(cola().isEmpty())
        assertEquals(1, retiros().size)
    }

    /** H9: el rechazo deja PRIMERO su marca durable; si después no se puede quitar el papel, sigue `Rechazado` y la cola lo dice. */
    @Test fun `si quitar el comprobante falla al deshacer un rechazo, sigue Rechazado y la cola lo marca`() = runBlocking {
        val roto = mockk<ComprobantesDeRetiroPorImprimir> {
            coEvery { anotar(any()) } just Runs
            coEvery { quitar(any(), any()) } throws IOException("disco lleno")
        }
        val r = repo(servidor { 403 to """{"message":"Ese código no autoriza este retiro"}""" }, comprobantes = roto)

        val resultado = r.addPayOut(5_000, "Proveedor", localId = "loc-1", autorizacion = "tok-1", comprobante = comprobante())

        assertEquals(ResultadoDelRetiro.Rechazado("Ese código no autoriza este retiro"), resultado)
        assertNotNull("sin la marca del rechazo: ${cola()}", cola().single().rechazadaEn)
        assertEquals(setOf("loc-1"), r.retirosRechazados(VENUE_ID))
    }

    /** H11: tras un 2xx, un error al limpiar la cola no cambia el resultado: el retiro ya está registrado. */
    @Test fun `si quitar la operacion falla tras el 2xx, sigue Hecho y confirmado`() = runBlocking {
        val r = repo(servidor { fallarAlEscribirLaCola = true; 201 to retiroDelServidor("""{"id":"sv-9","name":"Daniel"}""") })
            .addPayOut(5_000, "Proveedor", localId = "loc-1", autorizacion = "tok-1")

        val hecho = r as? ResultadoDelRetiro.Hecho ?: throw AssertionError("esperaba Hecho: $r")
        assertTrue(hecho.confirmado)
        assertEquals("srv-ev-1", hecho.evento.id)
        assertEquals("Daniel", hecho.autorizadoPor)
    }

    /** H11: con la misma llave el servidor devuelve el retiro que YA tiene; el aparato se queda con lo que el servidor registró. */
    @Test fun `si el servidor devuelve otro importe para la misma llave, la fila guarda el del servidor`() = runBlocking {
        val yaRegistrado = """{"success":true,"data":${eventoJson("srv-ev-1", "PAY_OUT", "80.00", note = "Bajada", localId = "loc-1")},"authorizedBy":null}"""

        val r = repo(servidor { 200 to yaRegistrado }).addPayOut(5_000, "Proveedor", localId = "loc-1")

        val fila = retiros().single()
        assertEquals("srv-ev-1", fila.id)
        assertEquals(8_000, fila.amountCents)
        assertEquals("Bajada", fila.note)
        assertEquals(fila, (r as ResultadoDelRetiro.Hecho).evento)
    }

    @Test fun `el replay tambien adopta lo que registro el servidor`() = runBlocking {
        repo(servidor { null }).addPayOut(5_000, "Proveedor", localId = "loc-1")
        val yaRegistrado = """{"success":true,"data":${eventoJson("srv-ev-1", "PAY_OUT", "80.00", note = "Bajada", localId = "loc-1")},"authorizedBy":null}"""

        repo(servidor { 200 to yaRegistrado }).reproducirPendientes()

        assertEquals(listOf(Triple("srv-ev-1", 8_000, "Bajada")), retiros().map { Triple(it.id, it.amountCents, it.note) })
    }

    /**
     * Re-revisión (Importante 1): el papel que se quedó porque la impresora falló (H10) no sobrevive a un rechazo del REPLAY. El replay
     * marca el rechazo y quita el papel (mismo orden que H9); «Ya lo vi» también lo quita.
     */
    @Test fun `un rechazo del replay quita el comprobante pendiente, y Ya lo vi tambien`() = runBlocking {
        repo(servidor { null }).addPayOut(5_000, "Proveedor", localId = "loc-1", autorizacion = "tok-1", comprobante = comprobante())
        assertEquals("el papel sigue pendiente (no se pudo imprimir)", listOf("loc-1"), store.pendientes(VENUE_ID).map { it.localId })

        val r = repo(servidor { 403 to """{"message":"La autorización ya no es válida."}""" })
        r.reproducirPendientes()

        assertNotNull("el rechazo se marca", cola().single().rechazadaEn)
        assertTrue("quedó el papel de un retiro rechazado", store.pendientes(VENUE_ID).isEmpty())

        // Un papel que sobrevivió (otra versión, o un fallo al quitarlo) tampoco sobrevive a «Ya lo vi».
        store.anotar(comprobante())
        r.descartarRechazada(r.operacionesRechazadas().single().localKey)
        assertTrue(cola().isEmpty())
        assertTrue("«Ya lo vi» dejó el papel", store.pendientes(VENUE_ID).isEmpty())
    }

    /** H6: la sucursal y su URL se fijan al empezar. Si a media operación cambia la sucursal, el retiro sigue siendo de la de antes. */
    @Test fun `el POST y la cola son de la sucursal donde empezo el retiro aunque cambie a mitad`() = runBlocking {
        var sucursal = VENUE_ID
        val colas = mutableMapOf<String, String?>()
        val st = mockk<SecureStorage>(relaxed = true).also { s ->
            every { s.venueId } answers { sucursal }
            every { s.userId } returns "staff-1"
            every { s.userFirstName } returns "Ana"
            every { s.userLastName } returns "Ruiz"
            every { s.pendingDrawerOpsJson(any()) } answers { colas[firstArg()] }
            every { s.setPendingDrawerOpsJson(any(), any()) } answers { colas[firstArg()] = secondArg() }
        }
        // La persona cambia de sucursal mientras el retiro se guarda (el primer punto en que addPayOut suspende).
        val cambiaDeSucursal = mockk<ComprobantesDeRetiroPorImprimir> {
            coEvery { anotar(any()) } answers { sucursal = "venue-2" }
            coEvery { quitar(any(), any()) } just Runs
        }
        val r = CashDrawerRepository(
            dao, st, servidor { 201 to retiroDelServidor() }, sinCobrosEnCola(), conectividadDePrueba(), database = null, comprobantes = cambiaDeSucursal,
        ).addPayOut(5_000, "Proveedor", localId = "loc-1", autorizacion = "tok-1", comprobante = comprobante())

        assertTrue("esperaba Hecho confirmado: $r", (r as? ResultadoDelRetiro.Hecho)?.confirmado == true)
        val post = enElCable.single(::pagoAlRetiro).url.encodedPath
        assertTrue("el POST salió a otra sucursal: $post", post.contains("/venues/$VENUE_ID/"))
        assertTrue("quedó en la cola de la sucursal de origen: ${colas[VENUE_ID]}", colas[VENUE_ID].isNullOrEmpty() || colas[VENUE_ID] == "[]")
        assertNull("tocó la cola de la otra sucursal", colas["venue-2"])
        assertEquals(VENUE_ID, retiros().single().venueId)
    }

    // MARK: - Lo que cuentan las guardas

    @Test fun `movimientosSinSubir cuenta lo encolado y no lo rechazado`() = runBlocking {
        repo(servidor { null }).addPayOut(5_000, "Proveedor", localId = "loc-1", autorizacion = "tok-1")
        assertEquals(1, repo(servidor { null }).movimientosSinSubir(VENUE_ID))

        repo(servidor { 403 to """{"message":"no"}""" }).reproducirPendientes() // el replay lo marca, no lo borra
        assertNotNull(cola().single().rechazadaEn)
        assertEquals(0, repo(servidor { null }).movimientosSinSubir(VENUE_ID))
        assertEquals(0, repo(servidor { null }).movimientosSinSubir("otro-negocio"))
    }

    /**
     * Fallo de revisión #3: lo que está detrás de una apertura RECHAZADA no lo puede mandar la cola (se ve en Caja con «Reintentar»);
     * contarlo bloquearía cerrar sesión para siempre. Detrás de una apertura sólo pendiente, sí cuenta.
     */
    @Test fun `movimientosSinSubir no cuenta lo que espera a una apertura rechazada`() {
        val cola = """[
            {"kind":"OPEN","sessionId":"s-1","amountCents":50000,"localId":"ev-open-1","at":1,"rechazadaEn":2,"motivoDelRechazo":"no"},
            {"kind":"PAY_OUT","sessionId":"s-1","amountCents":5000,"localId":"loc-1","at":3},
            {"kind":"OPEN","sessionId":"s-2","amountCents":50000,"localId":"ev-open-2","at":4},
            {"kind":"PAY_OUT","sessionId":"s-2","amountCents":5000,"localId":"loc-2","at":5}]"""
        storage.setPendingDrawerOpsJson(VENUE_ID, cola)

        assertEquals("la apertura pendiente y su retiro", 2, repo(servidor { null }).movimientosSinSubir(VENUE_ID))
    }

    // MARK: - «N por sincronizar» (hz:8024bc1c:D40)

    /**
     * D40 (ensayo general, 12-nov): sin internet, la cajera registra un ingreso, un retiro y el cierre, y la banda «N por sincronizar»
     * seguía en 13: el cajón nunca entraba en la cuenta. [CashDrawerRepository.movimientosPorSubir] cuenta lo mismo que las guardas
     * ([CashDrawerRepository.movimientosSinSubir]) y sube y baja con cada escritura de la cola.
     */
    @Test fun `P1 movimientosPorSubir sube con el ingreso, el retiro y el cierre sin red y baja al subir`() = runBlocking {
        var conRed = false
        val r = repo(servidor { req ->
            if (!conRed) null
            else when {
                req.url.encodedPath.endsWith("/pay-in") -> 200 to """{"success":true,"data":${eventoJson("srv-in", "PAY_IN", "100.00")}}"""
                req.url.encodedPath.endsWith("/pay-out") -> 200 to retiroDelServidor()
                req.url.encodedPath.endsWith("/close") -> 200 to sesionJson("srv-1")
                else -> 200 to """{"success":true,"data":{}}"""
            }
        })
        assertEquals(0, r.movimientosPorSubir.value)

        r.addPayIn(PAY_IN_CENTS, "Cambio")
        assertEquals("el ingreso", 1, r.movimientosPorSubir.value)
        r.addPayOut(5_000, "Proveedor", localId = "loc-1")
        assertEquals("el ingreso y el retiro", 2, r.movimientosPorSubir.value)
        r.closeSession(FONDO_CENTS, null)
        assertEquals("el ingreso, el retiro y el cierre", 3, r.movimientosPorSubir.value)
        assertEquals("lo mismo que cuentan las guardas", r.movimientosSinSubir(VENUE_ID), r.movimientosPorSubir.value)

        conRed = true
        r.reproducirPendientes()
        assertEquals("todo subió: ${cola()}", 0, r.movimientosSinSubir(VENUE_ID))
        assertEquals("y la banda lo dice", 0, r.movimientosPorSubir.value)
    }

    /** Al arrancar la app o cambiar de sesión, lo que ya estaba en la cola de ESA sucursal se cuenta; lo rechazado no. */
    @Test fun `P1 recontar cuenta la cola guardada de la sucursal de la sesion, sin lo rechazado`() {
        storage.setPendingDrawerOpsJson(VENUE_ID, """[
            {"kind":"PAY_IN","sessionId":"srv-1","amountCents":10000,"localId":"in-1","at":1},
            {"kind":"PAY_OUT","sessionId":"srv-1","amountCents":5000,"localId":"loc-1","at":2,"rechazadaEn":3,"motivoDelRechazo":"no"},
            {"kind":"CLOSE","sessionId":"srv-1","amountCents":500000,"at":4}]""")
        val r = repo(servidor { null })

        r.recontarMovimientosPorSubir()

        assertEquals("el ingreso y el cierre", 2, r.movimientosPorSubir.value)
    }

    /** Codex D40 r1 #2: una cola que no se puede leer no es una cola vacía: la banda se queda con el número que tenía. */
    @Test fun `P1 si la cola no se puede leer, la banda no se va a cero`() {
        storage.setPendingDrawerOpsJson(VENUE_ID, """[{"kind":"PAY_IN","sessionId":"srv-1","amountCents":10000,"localId":"in-1","at":1}]""")
        val r = repo(servidor { null })
        r.recontarMovimientosPorSubir()
        assertEquals(1, r.movimientosPorSubir.value)

        storage.setPendingDrawerOpsJson(VENUE_ID, "{ esto no es una cola")
        r.recontarMovimientosPorSubir()

        assertEquals(1, r.movimientosPorSubir.value)
    }

    // MARK: - El log de depuración (Codex r3 #11)

    @Test fun `el log de depuracion tapa X-Permission-Override`() {
        val lineas = mutableListOf<String>()
        val cliente = OkHttpClient.Builder()
            .addInterceptor(registroDeRed(depuracion = true) { lineas += it })
            .addInterceptor { cadena ->
                Response.Builder().request(cadena.request()).protocol(Protocol.HTTP_1_1).code(201).message("OK")
                    .body("{}".toResponseBody("application/json".toMediaType())).build()
            }
            .build()
        cliente.newCall(
            Request.Builder().url("https://api.test/pay-out")
                .header(ForbiddenInterceptor.PERMISSION_OVERRIDE_HEADER, "tok-secreto")
                .header("Authorization", "Bearer sesion-secreta")
                .post("{}".toRequestBody("application/json".toMediaType())).build(),
        ).execute().close()

        assertTrue("no se registró nada", lineas.any { it.startsWith(ForbiddenInterceptor.PERMISSION_OVERRIDE_HEADER) })
        assertFalse(lineas.joinToString("\n"), lineas.any { "tok-secreto" in it || "sesion-secreta" in it })
    }

    // MARK: - El PIN pide la autorización atada a ESE retiro

    @Test fun `con llave el cuerpo la lleva, sin llave no`() = runBlocking {
        val sesion = mockk<SecureStorage>(relaxed = true) { every { accessToken } returns "sesion" }
        val pin = PermissionOverrideRepository(
            sesion,
            servidor { 201 to """{"success":true,"data":{"token":"tok-1","authorizedBy":{"id":"sv-9","name":"Daniel"}}}""" },
        )
        fun cuerpo(r: Request) = Json.parseToJsonElement(okio.Buffer().also { r.body!!.writeTo(it) }.readUtf8()).jsonObject

        assertEquals(OverrideResult.Granted("tok-1", "Daniel"), pin.requestToken(VENUE_ID, "1234", "cash-drawer:pay-out", operationKey = "pay-out:loc-1:5000"))
        assertEquals("pay-out:loc-1:5000", cuerpo(enElCable.last())["operationKey"]?.jsonPrimitive?.content)

        pin.requestToken(VENUE_ID, "1234", "payments:refund")
        assertFalse(cuerpo(enElCable.last()).containsKey("operationKey"))
    }
}
