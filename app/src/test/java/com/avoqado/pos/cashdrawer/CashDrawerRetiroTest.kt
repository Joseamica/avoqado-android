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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    private val dao = FakeCashDrawerDao().apply { runBlocking { insertSession(sesionLocal("srv-1", openedAt = haceMinutos(60))) } }
    private val storage = almacenConCola()
    private val store by lazy {
        ComprobantesDeRetiroPorImprimir(PreferenceDataStoreFactory.create(scope = scope, produceFile = { temporal.root.resolve("retiro_comprobantes.preferences_pb") }))
    }
    private val enElCable = mutableListOf<Request>()

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
        every { st.setPendingDrawerOpsJson(any(), any()) } answers { cola = secondArg() }
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

    private fun repo(client: OkHttpClient) = CashDrawerRepository(
        dao, storage, client, sinCobrosEnCola(), conectividadDePrueba(), database = null, comprobantes = store,
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

    @Test fun `un retiro sin autorizacion se reproduce como siempre, sin headers nuevos`() = runBlocking {
        repo(servidor { null }).addPayOut(5_000, "Proveedor", localId = "loc-1")
        assertNull(cola().single().autorizacion)
        enElCable.clear()

        repo(servidor { 200 to retiroDelServidor() }).reproducirPendientes()

        val reenvio = enElCable.single(::pagoAlRetiro)
        assertNull(reenvio.header(ForbiddenInterceptor.PERMISSION_OVERRIDE_HEADER))
        assertNull(reenvio.header(ForbiddenInterceptor.LOCAL_ERROR_HEADER))
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

    @Test fun `el comprobante se anota despues de la fila y la cola y antes del POST`() = runBlocking {
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

    @Test fun `el comprobante de otro negocio no sale en este`() = runBlocking {
        store.anotar(comprobante().copy(venueId = "otro"))
        store.anotar(comprobante("loc-2"))
        assertEquals(listOf("loc-2"), store.pendientes(VENUE_ID).map { it.localId })
        store.quitar(VENUE_ID, "loc-2")
        assertTrue(store.pendientes(VENUE_ID).isEmpty())
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
