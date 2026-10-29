package com.avoqado.pos.giftcards
import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.pos.data.LectorHidBus
import com.avoqado.pos.giftcards.data.*
import com.avoqado.pos.giftcards.domain.*
import com.avoqado.pos.giftcards.presentation.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.TimeUnit
@OptIn(ExperimentalCoroutinesApi::class)
class IdentificarTarjetaViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private lateinit var server: MockWebServer
    private lateinit var prefs: SolicitudesDeTarjetaTest.Preferencias
    private lateinit var storage: SecureStorage
    private lateinit var repo: GiftCardsRepository
    private lateinit var vm: IdentificarTarjetaViewModel
    private val online = MutableStateFlow(true)
    private val reachable = MutableStateFlow(true)
    private val lector = LectorHidBus()
    private val settings = AjustesDeTarjetas(false, emptyList(), 100.0, 10000.0, sale = VentaPermitida(false), capacidades = setOf("PAGE_TOKEN", "TELEFONO"), tieneTarjetas = true)
    private val card = """{"giftCardId":"c123456789012345678901234","last4":"1234","balance":300.5,"status":"ACTIVE","usable":true}"""
    @Before fun setup() {
        server = MockWebServer().also { it.start() }
        System.setProperty("avoqado.test.baseUrl", server.url("/api/v1").toString())
        prefs = SolicitudesDeTarjetaTest.Preferencias(); prefs.values["venueId"] = "a"; prefs.values["userId"] = "u"
        storage = prefs.almacen()
        val red = mockk<ConnectivityMonitor> { every { isConnected } returns online; every { isServerReachable } returns reachable }
        val roles = mockk<RoleManager> { every { canReadGiftCards } returns true }
        SecureStorage::class.java.getDeclaredField("plainPrefs").apply { isAccessible = true }.set(storage,
            SecureStorage::class.java.getDeclaredField("prefs").apply { isAccessible = true }.get(storage))
        prefs.values["giftCardSettings.a"] = GiftCardsApi.json.encodeToString(AjustesDeTarjetas.serializer(), settings)
        repo = GiftCardsRepository(OkHttpClient(), storage)
        vm = IdentificarTarjetaViewModel(repo, storage, red, lector, roles)
        vm.ahora = { 1000L }; vm.abrir("a", settings)
    }
    @After fun end() { vm.cerrar(); System.clearProperty("avoqado.test.baseUrl"); server.shutdown() }
    private fun enqueue(body: String, status: Int = 200) { server.enqueue(MockResponse().setResponseCode(status).setBody(if (status in 200..299) "{\"success\":true,\"data\":$body}" else body)) }
    private fun request() = server.takeRequest(1, TimeUnit.SECONDS).also { assertNotNull("debe enviar una solicitud", it) }!!
    @Test fun `escanear consulta pageToken exacto sin crear pago`() = runBlocking {
        enqueue("""{"usableBalance":300.5,"cards":[$card]}""")
        vm.porEscaneo("  HTTPS://PAY.AVOQADO.IO/tarjeta/Xk3pQ9vLabcdefghij12/\n")
        val req = request(); assertEquals("/api/v1/mobile/venues/a/gift-cards/lookup", req.path)
        assertEquals("""{"pageToken":"Xk3pQ9vLabcdefghij12"}""", req.body.readUtf8())
        assertTrue(vm.estado.value is EstadoDeIdentificacion.Saldo)
        assertFalse(vm.puedeCobrar(true, false)); assertEquals(1, server.requestCount)
    }
    @Test fun `manual 4 a 40 caracteres recorta y deja validar al servidor`() = runBlocking {
        enqueue("""{"code":"GIFT_CARD_NOT_FOUND","message":"No encontramos esa tarjeta"}""",404)
        vm.porCodigo("  abcd  "); assertEquals("""{"code":"abcd"}""",request().body.readUtf8())
        assertEquals(EstadoDeIdentificacion.Error("No encontramos esa tarjeta"), vm.estado.value)
        vm.porCodigo("abc"); vm.porCodigo("a".repeat(41)); assertEquals(1,server.requestCount)
    }
    @Test fun `sin capacidad pageToken desconocido cero solicitudes`() = runBlocking {
        vm.abrir("a",settings.copy(capacidades=emptySet()))
        vm.porEscaneo("https://pay.avoqado.io/tarjeta/Xk3pQ9vLabcdefghij12")
        assertEquals(EstadoDeIdentificacion.Desconocido,vm.estado.value); assertEquals(0,server.requestCount)
    }
    @Test fun `telefono E164 y confirmacion sin enumerar`() = runBlocking {
        enqueue("""{"enviado":true}""")
        vm.pedirCodigo("5512345678"); assertEquals("""{"phone":"+525512345678"}""",request().body.readUtf8())
        assertEquals(EstadoDeIdentificacion.CodigoPedido,vm.estado.value)
        assertEquals("Si ese teléfono tiene tarjetas de regalo, le llegó un código por WhatsApp.", TextosDeTarjetas.CODIGO_ENVIADO)
    }
    @Test fun `dos tarjetas requieren eleccion proof solo en memoria y caduca`() = runBlocking {
        enqueue("""{"proofToken":"private-proof","expiresAt":"2099-01-01T00:00:00Z","usableBalance":601,"cards":[$card,${card.replace("1234", "5678")}],"hayMas":true}""")
        vm.verificar("5512345678","123456"); request()
        assertTrue(vm.estado.value is EstadoDeIdentificacion.Eligiendo)
        val result = vm.estado.value as EstadoDeIdentificacion.Eligiendo
        assertEquals(2,result.resultado.cards.size); assertTrue(result.resultado.hayMas); assertNull(vm.identificada.value)
        vm.elegir(result.resultado.cards[1].giftCardId)
        assertEquals(PruebaDeTarjeta.Telefono("private-proof",result.resultado.cards[1].giftCardId), vm.identificada.value?.prueba)
        assertFalse(prefs.values.toString().contains("private-proof")); assertFalse(vm.estado.value.toString().contains("private-proof"))
        vm.ahora = { 901001L }; vm.elegir(result.resultado.cards[0].giftCardId); assertNull(vm.identificada.value)
    }
    @Test fun `sin tarjetas y codigo invalido muestran mensajes sin cobro`() = runBlocking {
        enqueue("""{"proofToken":"pt","expiresAt":"2099-01-01T00:00:00Z","usableBalance":0,"cards":[]}""")
        vm.verificar("5512345678","123456"); request(); assertEquals(EstadoDeIdentificacion.Error(TextosDeTarjetas.TELEFONO_SIN_TARJETAS),vm.estado.value)
        enqueue("""{"code":"GIFT_CARD_PHONE_CODE_INVALID","message":"El código no es correcto o ya venció. Pide otro."}""",409)
        vm.verificar("5512345678","123456"); request(); assertEquals(EstadoDeIdentificacion.Error("El código no es correcto o ya venció. Pide otro."),vm.estado.value)
        assertNull(vm.identificada.value)
    }
    @Test fun `429 propio bloquea hasta retryAfter y se rehabilita solo`() = runTest {
        enqueue("""{"code":"GIFT_CARD_PHONE_RATE_LIMITED","message":"Espera un minuto","retryAfter":60}""",429)
        vm.pedirCodigo("5512345678"); request(); assertEquals(61000L,vm.bloqueadoHasta.value)
        assertEquals(EstadoDeIdentificacion.Error("Espera un minuto"),vm.estado.value)
        vm.pedirCodigo("5512345678"); assertEquals(1,server.requestCount)
        vm.ahora={61001}; advanceTimeBy(60001); runCurrent(); assertNull(vm.bloqueadoHasta.value)
    }
    @Test fun `sin conexion o servidor alcanzable no consulta y transporte deja reintentar`() = runBlocking {
        for (r in listOf(online, reachable)) { r.value=false; vm.porCodigo("abcd"); assertEquals(EstadoDeIdentificacion.Error(TextosDeTarjetas.SIN_RED),vm.estado.value); r.value=true }
        assertEquals(0,server.requestCount)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)); vm.porCodigo("abcd")
        assertEquals(EstadoDeIdentificacion.Error(TextosDeTarjetas.SIN_RED),vm.estado.value)
        enqueue("""{"usableBalance":300.5,"cards":[$card]}"""); vm.porCodigo("abcd"); assertTrue(vm.estado.value is EstadoDeIdentificacion.Saldo)
    }
    @Test fun `HID solo durante apertura cerrar borra y devuelve propietario`() = runTest {
        runCurrent(); assertTrue(LectorParaTarjetas.abierta.value)
        enqueue("""{"usableBalance":300.5,"cards":[$card]}""")
        lector.recibirDelLectorIntegrado("K7QZABCDEFGHJKMNP"); runCurrent()
        withContext(Dispatchers.IO) {
            request()
            withTimeout(5000) { vm.estado.first { it is EstadoDeIdentificacion.Saldo || it is EstadoDeIdentificacion.Error } }
        }
        assertTrue(vm.estado.value is EstadoDeIdentificacion.Saldo)
        vm.cerrar(); assertFalse(LectorParaTarjetas.abierta.value); assertNull(vm.identificada.value)
        lector.recibirDelLectorIntegrado("K7QZABCDEFGHJKMNP"); runCurrent(); assertEquals(1,server.requestCount)
    }
    @Test fun `respuesta vieja tras cierre o cambio de usuario no reaparece`() = runBlocking {
        server.dispatcher = object: Dispatcher() { override fun dispatch(r: RecordedRequest): MockResponse { vm.cerrar(); return MockResponse().setBody("""{"success":true,"data":{"usableBalance":300.5,"cards":[$card]}}""") } }
        vm.porCodigo("abcd"); assertEquals(EstadoDeIdentificacion.Capturando,vm.estado.value); assertNull(vm.identificada.value)
        vm.abrir("a",settings); storage.userId="otra"; vm.porCodigo("abcd"); assertEquals(1,server.requestCount)
    }
    @Test fun `vencimiento zona de negocio razones y conteo mostrado`() {
        val zonaAnterior = com.avoqado.pos.core.util.VenueTimeZone.current
        try {
        com.avoqado.pos.core.util.VenueTimeZone.set("America/Mexico_City")
        assertEquals("Sin vencimiento",venceTarjeta(null))
        assertEquals("Vence el 30/01/2027",venceTarjeta("2027-01-31T01:00:00Z"))
        val razones=listOf("PENDING","BLOCKED","EXPIRED","DEACTIVATED","EMPTY")
        assertEquals(listOf("Todavía no se cobra","Bloqueada","Vencida","Cancelada","Sin saldo"),razones.map(TextosDeTarjetas::razon))
        assertEquals("Hay más tarjetas. Se muestran 2; consulta otra con su código o QR.",TextosDeTarjetas.hayMasTarjetas(2))
        assertNull(TextosDeTarjetas.razon(null))
        } finally { com.avoqado.pos.core.util.VenueTimeZone.set(zonaAnterior) }
    }

    @Test fun `403 sin code conserva el permiso propio aunque el servidor diga Forbidden`() = runBlocking {
        enqueue("""{"message":"Forbidden"}""",403)
        vm.porCodigo("abcd"); request()
        assertEquals(EstadoDeIdentificacion.Error(TextosDeTarjetas.SIN_PERMISO_CONSULTAR),vm.estado.value)
        assertNull(vm.identificada.value)
    }
    @Test fun `401 es sesion sin respuesta y nunca muestra un mensaje tecnico`() = runBlocking {
        enqueue("""{"message":"Unauthorized"}""",401)
        vm.porCodigo("abcd"); request()
        assertEquals(EstadoDeIdentificacion.Error(TextosDeTarjetas.SIN_RED),vm.estado.value)
        assertNull(vm.identificada.value)
    }
    @Test fun `403 con code respeta el message de negocio`() = runBlocking {
        enqueue("""{"code":"GIFT_CARDS_NOT_IN_PLAN","message":"Texto del plan"}""",403)
        vm.porCodigo("abcd"); request()
        assertEquals(EstadoDeIdentificacion.Error("Texto del plan"),vm.estado.value)
    }

    @Test fun `r2 editar telefono A B A invalida reto lista y seleccion hasta verificar otra vez`() = runBlocking {
        val a = "5512345678"
        val id = "c123456789012345678901234"
        val listado = """{"proofToken":"prueba-r2","expiresAt":"2099-01-01T00:00:00Z","usableBalance":300.5,"cards":[$card]}"""
        enqueue(listado); vm.verificar(a,"123456"); request(); vm.elegir(id)
        assertNotNull(vm.identificada.value)
        vm.cambiarTelefono("5598765432"); vm.cambiarTelefono(a)
        assertEquals(EstadoDeIdentificacion.Capturando,vm.estado.value)
        assertNull(vm.identificada.value)
        vm.elegir(id); assertNull(vm.identificada.value); assertEquals(1,server.requestCount)
        enqueue(listado); vm.verificar(a,"654321"); request(); vm.elegir(id)
        assertNotNull(vm.identificada.value)
    }
    @Test fun `r2 escaner manual conserva entrada despues de timeout sin escrituras`() = runBlocking {
        val crudo = "K7QZABCDEFGHJKMNP"
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        vm.porEscaneo(crudo); request()
        assertEquals(EstadoDeIdentificacion.Error(TextosDeTarjetas.SIN_RED),vm.estado.value)
        assertEquals(crudo,vm.codigo.value)
        val disco = SecureStorage::class.java.getDeclaredField("prefs").apply { isAccessible=true }.get(storage) as android.content.SharedPreferences
        verify(exactly=0) { disco.edit() }
    }
    @Test fun `r2 reintentar tras reconexion usa mismo cuerpo sin volver a pasar literal`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        vm.porEscaneo("  K7QZ-ABCD-EFGH-JKMNP  ")
        val primero = request().body.readUtf8()
        assertEquals(EstadoDeIdentificacion.Error(TextosDeTarjetas.SIN_RED),vm.estado.value)
        online.value=false; vm.reintentar(); assertEquals(1,server.requestCount)
        online.value=true; enqueue("""{"usableBalance":300.5,"cards":[$card]}""")
        vm.reintentar()
        assertEquals(primero,request().body.readUtf8())
        assertTrue(vm.estado.value is EstadoDeIdentificacion.Saldo)
        val disco = SecureStorage::class.java.getDeclaredField("prefs").apply { isAccessible=true }.get(storage) as android.content.SharedPreferences
        verify(exactly=0) { disco.edit() }
        vm.cerrar(); assertEquals("",vm.codigo.value)
    }
    @Test fun `r2 enviar codigo usa campo actual sin argumento del llamador`() = runBlocking {
        vm.cambiarCodigo("  abcd  ")
        enqueue("""{"usableBalance":300.5,"cards":[$card]}""")
        vm.enviarCodigo()
        assertEquals("""{"code":"abcd"}""",request().body.readUtf8())
        assertEquals(1,server.requestCount)
    }
    private suspend fun retirarCapacidades() {
        enqueue("""{"enabled":true,"suggestedAmounts":[],"minAmount":100,"maxAmount":10000,"sale":{"sellable":true}}""")
        assertEquals(EstadoDeAjustes.NoDisponible, repo.refrescarAjustes("a")); request()
    }
    private suspend fun entradaRetirada(prueba: PruebaDeTarjeta? = null) {
        retirarCapacidades()
        enqueue("""{"usableBalance":300.5,"cards":[$card]}""")
        if (prueba == null) vm.porCodigo("abcd") else vm.porPrueba(prueba)
        assertEquals("Una entrada abierta no consulta después de retirar capacidades", 1, server.requestCount)
        assertNull(vm.identificada.value)
    }
    @Test fun `union E consulta del mosaico abierta no usa ajustes retirados`() = runBlocking { entradaRetirada() }
    @Test fun `union E consulta del escaner abierta no usa ajustes retirados`() = runBlocking { entradaRetirada(PruebaDeTarjeta.PageToken("privado")) }
    @Test fun `union E identificar del cobro no manda OTP ni consulta al retirar capacidades`() = runBlocking {
        retirarCapacidades()
        enqueue("""{"enviado":true}"""); vm.pedirCodigo("5512345678")
        vm.verificar("5512345678", "123456"); vm.porEscaneo("abcd"); vm.reintentar()
        assertEquals(1, server.requestCount); assertNull(vm.identificada.value)
    }
    @Test fun `union E entrada abierta queda inerte al perder ajustes del negocio`() = runBlocking {
        prefs.values.remove("giftCardSettings.a")
        enqueue("""{"usableBalance":300.5,"cards":[$card]}"""); vm.porCodigo("abcd")
        assertEquals(0, server.requestCount); assertNull(vm.identificada.value)
    }

}
