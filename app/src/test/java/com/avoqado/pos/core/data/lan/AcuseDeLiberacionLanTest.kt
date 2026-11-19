package com.avoqado.pos.core.data.lan

import com.avoqado.pos.core.data.local.PayloadCache
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.PermissionOverrideRepository
import com.avoqado.pos.orders.data.CuentasAbiertasAqui
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

/**
 * F1 · bloque 6 (Codex android r2 #9): la caja dueña libera en Mostrador entre cajas con el PIN de un gerente y el SERVIDOR deja UN asiento
 * `POS_LAN_RELEASE_SIGNED` por sucursal + folio + intento. La liberación y el acuse pendiente (`operationKey → token`) van en UNA escritura;
 * el acuse se manda después y se reenvía (reinicio, sin red, 5xx) hasta que el servidor contesta algo que no cambia al reintentar.
 *
 * El servidor es de mentira pero la cadena es la de producción: [DuenoDeCuentas] → [PermissionOverrideRepository.acusarAplicada] → HTTP.
 */
class AcuseDeLiberacionLanTest {
    private val v = "v1"
    private val disco = mutableMapOf<String, PayloadCache.Cached>()
    private var falloAlGuardar = false
    private val cache = mockk<PayloadCache> {
        coEvery { loadDurable(any(), any()) } answers { disco["${firstArg<String>()}|${secondArg<String>()}"] }
        coEvery { saveDurable(any(), any(), any()) } answers {
            if (falloAlGuardar) throw IllegalStateException("disco lleno")
            disco["${firstArg<String>()}|${secondArg<String>()}"] = PayloadCache.Cached(thirdArg(), 0)
        }
    }
    private val llaveDelRegistro = "${PayloadCache.TYPE_REGISTRO_DE_CUENTAS}|v1"
    private val abiertas = mockk<CuentasAbiertasAqui> { coEvery { todas(any()) } returns setOf("o1", "o2") }
    private var ms = 1_000L
    private var hora = 1_700_000_000_000L

    // MARK: - El SERVIDOR de mentira (contrato §4): una autorización atada por token, UN asiento por llave.

    /** token → la `operationKey` a la que el PIN lo ató. */
    private val autorizaciones = mutableMapOf<String, String>()
    /** Asientos `POS_LAN_RELEASE_SIGNED` por llave. */
    private val asientos = mutableMapOf<String, Int>()
    private var acusesRecibidos = 0
    private var rutas = mutableListOf<String>()
    private var sinRed = false
    private var perderLaRespuesta = false
    private var codigoForzado: Int? = null

    /** El SERVIDOR de PRODUCCIÓN de hoy: su esquema rechaza con 400 de validación la `operationKey` `liberacion-lan:…`. */
    private var servidorViejo = false
    /** La `operationKey` de cada PIN pedido (null = sin llave). */
    private val pinesPedidos = mutableListOf<String?>()
    private var codigoDelPin: Int? = null

    private val servidor = Interceptor { chain ->
        if (sinRed) throw UnknownHostException("sin red")
        val req = chain.request()
        if (req.url.encodedPath.endsWith("/permission-overrides")) {
            val pedido = Json.parseToJsonElement(Buffer().also { req.body!!.writeTo(it) }.readUtf8()).jsonObject
            val llave = pedido["operationKey"]?.jsonPrimitive?.content
            pinesPedidos += llave
            codigoDelPin?.let { return@Interceptor responder(chain, it, """{"success":false,"code":"SUPERVISOR_AUTHORIZATION_REQUIRED","message":"Ese PIN no es de un gerente con permiso"}""") }
            if (servidorViejo && llave != null) {
                return@Interceptor responder(chain, 400, """{"message":"Error de validación: Formato de operación inválido."}""")
            }
            val token = "tok-pin-${pinesPedidos.size}"
            llave?.let { autorizaciones[token] = it }
            return@Interceptor responder(chain, 201,
                """{"success":true,"data":{"token":"$token","authorizedBy":{"id":"g1","name":"Gerente"}}}""")
        }
        rutas += "${req.method} ${req.url.encodedPath} ${req.header("Authorization")}"
        acusesRecibidos++
        codigoForzado?.let { return@Interceptor responder(chain, it, """{"success":false}""") }
        val cuerpo = Json.parseToJsonElement(Buffer().also { req.body!!.writeTo(it) }.readUtf8()).jsonObject
        val token = cuerpo["token"]!!.jsonPrimitive.content
        val llave = cuerpo["operationKey"]!!.jsonPrimitive.content
        val atada = autorizaciones[token]
            ?: return@Interceptor responder(chain, 404, """{"success":false,"code":"AUTHORIZATION_NOT_FOUND"}""")
        if (atada != llave) return@Interceptor responder(chain, 409, """{"success":false,"code":"OPERATION_MISMATCH"}""")
        val nuevo = llave !in asientos
        asientos[llave] = (asientos[llave] ?: 0) + if (nuevo) 1 else 0
        if (perderLaRespuesta) throw IOException("se perdió la respuesta")
        responder(chain, 200, """{"success":true,"recorded":$nuevo}""")
    }

    private fun responder(chain: Interceptor.Chain, codigo: Int, cuerpo: String): Response = Response.Builder()
        .request(chain.request()).protocol(Protocol.HTTP_1_1).code(codigo).message("x").body(cuerpo.toResponseBody()).build()

    private val storage = mockk<SecureStorage> { every { selloYAccess() } returns (1L to "jwt-caja") }
    private val permisos = PermissionOverrideRepository(storage, OkHttpClient.Builder().addInterceptor(servidor).build())

    /** La caja 1 (dueña). Una instancia nueva = la app se reinició (mismo disco). */
    private fun cajaUno() = DuenoDeCuentas(cache, abiertas, { _, _, _ -> false }, { _, _ -> false }, { null }, { "caja-1" }, { "Caja 1" },
        acusar = { venue, llave, token -> permisos.acusarAplicada(venue, llave, token) })
        .also { it.reloj = { Instante("boot-1", ms) }; it.horaDePared = { hora } }

    private val ana = Solicitud("caja-2", "Caja 2", "Ana", "i-ana")

    /** Ana compromete [orderId] en la Caja 2 y pasan los 2 min: el gerente de la Caja 1 firma con PIN y la caja libera. */
    private suspend fun firmaElGerente(a: DuenoDeCuentas, orderId: String = "o1", token: String = "tok-1", intento: String = "i-ana"): String {
        assertEquals(Respuesta.Concedida, a.comprometer(v, orderId, ana.copy(intento = intento)))
        ms += RegistroDeCuentas.NO_CONFIRMO_MS
        val llave = DuenoDeCuentas.operacionDeLiberacion(orderId, "caja-1", intento)!!
        autorizaciones[token] = llave // el PIN ya se validó: el servidor ató el token a esa llave
        assertTrue(a.noSeCobro(v, orderId, "Gerente", intento, acuse = llave to token))
        return llave
    }

    private fun pendientesEnDisco(): Map<String, String> = RegistroDeCuentas.leer(disco[llaveDelRegistro]!!.json)!!.acusesPendientes

    @Test fun `liberacionLAN_caidaYAcuseRepetido_unAsientoDelGerente`() = runTest {
        val llave = firmaElGerente(cajaUno())
        // La liberación y su acuse pendiente quedaron en la MISMA escritura, antes de cualquier POST.
        assertEquals(mapOf(llave to "tok-1"), pendientesEnDisco())
        assertNull(cajaUno().estadoDe(v, "o1"))
        assertEquals(0, acusesRecibidos)

        // La app murió antes del acuse. Al abrir, se reenvía; el servidor lo asienta pero la respuesta se pierde.
        perderLaRespuesta = true
        cajaUno().enviarAcuses(v)
        assertEquals(mapOf(llave to "tok-1"), pendientesEnDisco())

        // Otra vuelta (o otro reinicio, ya vencido el PIN): «ya estaba» también quita el pendiente.
        perderLaRespuesta = false
        cajaUno().enviarAcuses(v)
        assertEquals(emptyMap<String, String>(), pendientesEnDisco())
        cajaUno().enviarAcuses(v)

        assertEquals("UN asiento del gerente para esa liberación", mapOf(llave to 1), asientos)
        assertEquals(2, acusesRecibidos)
        assertTrue(rutas.first(), rutas.all { it.startsWith("POST ") && it.endsWith("/mobile/venues/v1/permission-overrides/applied Bearer jwt-caja") })
        assertEquals("liberacion-lan:o1:caja-1:i-ana", llave)
    }

    @Test fun `liberacionLAN_sinRed_elAcuseQuedaPendienteYSaleAlVolver`() = runTest {
        val a = cajaUno()
        sinRed = true
        val llave = firmaElGerente(a)
        repeat(3) { a.enviarAcuses(v) }
        assertEquals(mapOf(llave to "tok-1"), pendientesEnDisco())
        // Un 5xx, un 408, un 429 o un 404 sin el código del contrato (un servidor que aún no trae la ruta) tampoco lo quitan.
        sinRed = false
        for (c in listOf(500, 503, 408, 429, 404, 409)) {
            codigoForzado = c
            a.enviarAcuses(v)
            assertEquals("$c se reintenta", mapOf(llave to "tok-1"), pendientesEnDisco())
        }
        codigoForzado = null
        a.enviarAcuses(v)
        assertEquals(emptyMap<String, String>(), pendientesEnDisco())
        assertEquals(mapOf(llave to 1), asientos)
    }

    @Test fun `liberacionLAN_404o409_quitaElPendienteSinReintentarParaSiempre`() = runTest {
        val a = cajaUno()
        val l1 = firmaElGerente(a, "o1", "tok-1")
        val l2 = firmaElGerente(a, "o2", "tok-2", intento = "i-beto") // un intento cancelado no se vuelve a comprometer
        autorizaciones.remove("tok-1") // 404: el servidor no conoce esa autorización
        autorizaciones["tok-2"] = "liberacion-lan:o2:caja-1:otro" // 409: era para otro intento
        assertEquals(setOf(l1, l2), pendientesEnDisco().keys)
        a.enviarAcuses(v)
        assertEquals(emptyMap<String, String>(), pendientesEnDisco())
        assertEquals(2, acusesRecibidos)
        repeat(3) { cajaUno().enviarAcuses(v) }
        assertEquals("nunca se vuelve a mandar", 2, acusesRecibidos)
        assertEquals(emptyMap<String, Int>(), asientos)
        // Un 400 (forma rechazada) también es final.
        firmaElGerente(a, "o1", "tok-3", intento = "i-b")
        codigoForzado = 400
        a.enviarAcuses(v)
        assertEquals(emptyMap<String, String>(), pendientesEnDisco())
        a.enviarAcuses(v)
        assertEquals(3, acusesRecibidos)
    }

    @Test fun `firmaLAN_intentoReemplazado_noLiberaElNuevo - ni liberacion ni acuse`() = runTest {
        val a = cajaUno()
        a.comprometer(v, "o1", ana)
        ms += RegistroDeCuentas.NO_CONFIRMO_MS
        val vieja = DuenoDeCuentas.operacionDeLiberacion("o1", "caja-1", "i-viejo")!!
        assertFalse(a.noSeCobro(v, "o1", "Gerente", "i-viejo", acuse = vieja to "tok-1"))
        assertEquals(EstadoDeCuenta.COMPROMETIDA, a.estadoDe(v, "o1")?.estado)
        assertEquals(emptyMap<String, String>(), pendientesEnDisco())
        a.enviarAcuses(v)
        assertEquals(0, acusesRecibidos)
    }

    @Test fun `la escritura que falla - ni liberacion ni acuse`() = runTest {
        val a = cajaUno()
        a.comprometer(v, "o1", ana)
        ms += RegistroDeCuentas.NO_CONFIRMO_MS
        falloAlGuardar = true
        val llave = DuenoDeCuentas.operacionDeLiberacion("o1", "caja-1", "i-ana")!!
        assertFalse(a.noSeCobro(v, "o1", "Gerente", "i-ana", acuse = llave to "tok-1"))
        falloAlGuardar = false
        assertEquals(EstadoDeCuenta.COMPROMETIDA, cajaUno().estadoDe(v, "o1")?.estado)
        assertEquals(emptyMap<String, String>(), pendientesEnDisco())
    }

    @Test fun `un registro viejo sin el mapa se lee sin romper, y normalizar y No se cobro lo conservan`() {
        val viejo = RegistroDeCuentas.leer("""{"cuentas":{},"cancelados":{"i-x":1}}""")!!
        assertEquals(emptyMap<String, String>(), viejo.acusesPendientes)
        assertEquals(mapOf("i-x" to 1L), viejo.cancelados)
        val con = viejo.copy(acusesPendientes = mapOf("k" to "t"))
        assertEquals(con, RegistroDeCuentas.leer(RegistroDeCuentas.escribir(con)))
        // Normalizar con la poda de cancelados (7 días) no tira los acuses.
        assertEquals(mapOf("k" to "t"), RegistroDeCuentas.normalizar(con, Instante("b", 0), 8 * 24 * 60 * 60 * 1000L).acusesPendientes)
        val c = CuentaEnElRegistro(EstadoDeCuenta.COMPROMETIDA, "caja-2", intento = "i-ana", desde = Instante("b", 0))
        val d = RegistroDeCuentas.noSeCobro(con.copy(cuentas = mapOf("o1" to c)), "o1", Instante("b", RegistroDeCuentas.NO_CONFIRMO_MS), 5, "i-ana",
            acuse = "k2" to "t2")
        assertTrue(d.hecho)
        assertEquals(mapOf("k" to "t", "k2" to "t2"), d.registro.acusesPendientes)
    }

    /**
     * F1 (pendiente conocido de `decisiones-codigo-r1.md`): el SERVIDOR de PRODUCCIÓN no conoce la llave `liberacion-lan:` y contesta
     * 400 de validación. La caja repite el PIN UNA vez sin la llave y firma como hoy: libera, sin asiento ni acuse pendiente.
     */
    @Test fun `mostrador_servidorViejo400_firmaSinLlaveYSinAcuse`() = runTest {
        val a = cajaUno()
        assertEquals(Respuesta.Concedida, a.comprometer(v, "o1", ana))
        ms += RegistroDeCuentas.NO_CONFIRMO_MS
        val entre = mockk<CuentasEntreCajas>(relaxed = true)
        val pantalla = com.avoqado.pos.payment.presentation.CobroDeCuentaViewModel(
            mockk(), mockk<SecureStorage>(relaxed = true) { every { venueId } returns v }, mockk(), mockk(relaxed = true), mockk(relaxed = true),
            cuentasEntreCajas = entre, duenoDeCuentas = a, permisos = permisos,
        )
        servidorViejo = true

        val r = pantalla.firmarNoSeCobro("o1", "4321", "i-ana")

        assertTrue("firmó", r is com.avoqado.pos.core.data.network.OverrideResult.Granted)
        assertEquals("con la llave y, ante el 400, UNA vez sin ella", listOf("liberacion-lan:o1:caja-1:i-ana", null), pinesPedidos)
        assertNull("la cuenta quedó liberada", cajaUno().estadoDe(v, "o1"))
        assertEquals("sin acuse pendiente", emptyMap<String, String>(), pendientesEnDisco())
        io.mockk.verify(exactly = 0) { entre.empujarAcuses(any()) }
        a.enviarAcuses(v)
        assertEquals(0, acusesRecibidos)

        // Otro error no se repite: un PIN que no es de gerente (403) se pide una sola vez, como hoy.
        pinesPedidos.clear()
        assertEquals(Respuesta.Concedida, a.comprometer(v, "o2", ana.copy(intento = "i-o2")))
        ms += RegistroDeCuentas.NO_CONFIRMO_MS
        codigoDelPin = 403
        assertTrue(pantalla.firmarNoSeCobro("o2", "0000", "i-o2") is com.avoqado.pos.core.data.network.OverrideResult.Failed)
        assertEquals(listOf<String?>("liberacion-lan:o2:caja-1:i-o2"), pinesPedidos)
    }

    @Test fun `la llave sólo sale con la forma que acepta el servidor`() {
        assertEquals("liberacion-lan:o1:caja-1:a.b:c-1", DuenoDeCuentas.operacionDeLiberacion("o1", "caja-1", "a.b:c-1"))
        assertNull(DuenoDeCuentas.operacionDeLiberacion("o:1", "caja-1", "i"))
        assertNull(DuenoDeCuentas.operacionDeLiberacion("o1", "caja 1", "i"))
        assertNull(DuenoDeCuentas.operacionDeLiberacion("o1", "caja-1", "i".repeat(65)))
        assertNull(DuenoDeCuentas.operacionDeLiberacion("o1", "caja-1", ""))
    }
}
