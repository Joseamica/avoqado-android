package com.avoqado.pos.pos.data

import com.avoqado.pos.core.data.local.PayloadCache
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.pos.data.model.Promotion
import com.avoqado.pos.pos.data.model.PromotionGroup
import com.avoqado.pos.pos.data.model.PromotionOption
import com.avoqado.pos.pos.data.model.PromotionsPayload
import com.avoqado.pos.pos.data.model.PromotionsResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Catálogo de promociones — cache-first, calcado de UpsellRepository.
 *
 * 🔴 La propiedad que más importa: fail-safe = poder vender. Un refresh
 * fallido NUNCA borra el catálogo bueno. El único caso que limpia es un 403
 * con `featureCode` (el candado de plan real) — cualquier otro rechazo
 * (permisos, proxy, 500) conserva lo que ya había.
 */
class PromotionsRepositoryTest {

    private fun secureStorage(token: String? = "access-token"): SecureStorage =
        mockk(relaxed = true) { every { accessToken } returns token }

    /** Fake respaldado por mapa — misma llave `"type:venueId"` que el PayloadCache real. */
    private fun fakePayloadCache(backing: MutableMap<String, PayloadCache.Cached> = mutableMapOf()): PayloadCache =
        mockk(relaxed = true) {
            coEvery { load(any(), any()) } answers { backing["${firstArg<String>()}:${secondArg<String>()}"] }
            coEvery { save(any(), any(), any()) } answers {
                backing["${firstArg<String>()}:${secondArg<String>()}"] =
                    PayloadCache.Cached(thirdArg(), System.currentTimeMillis())
            }
            coEvery { clear(any(), any()) } answers { backing.remove("${firstArg<String>()}:${secondArg<String>()}") }
        }

    private fun callReturning(code: Int, body: String): Call {
        val call = mockk<Call>()
        every { call.execute() } returns Response.Builder()
            .request(Request.Builder().url("https://example.test/promotions").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "Error")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
        return call
    }

    private fun clientReturning(code: Int, body: String): OkHttpClient {
        val call = callReturning(code, body)
        return mockk { every { newCall(any()) } returns call }
    }

    /** Varias llamadas sucesivas sobre EL MISMO cliente — para simular 1er refresh OK, 2do fallido. */
    private fun sequencedClient(vararg responses: Pair<Int, String>): OkHttpClient {
        val calls = responses.map { (code, body) -> callReturning(code, body) }
        return mockk { every { newCall(any()) } returnsMany calls }
    }

    private fun clientThrowing(error: IOException): OkHttpClient {
        val call = mockk<Call>()
        every { call.execute() } throws error
        return mockk { every { newCall(any()) } returns call }
    }

    private fun promotion(id: String = "promo-1", name: String = "2x1 cervezas") = Promotion(
        id = id,
        name = name,
        pricingMode = "FIXED_TOTAL",
        priceCents = 5000,
        groups = listOf(
            PromotionGroup(
                id = "g1",
                name = "Elige tu cerveza",
                options = listOf(
                    PromotionOption(
                        id = "opt-1",
                        productId = "prod-1",
                        productName = "Corona",
                        productPriceCents = 3500,
                        quantity = 2,
                        chargedQuantity = 1,
                    ),
                ),
            ),
        ),
    )

    private fun successBody(active: List<Promotion>): String {
        val json = Json { encodeDefaults = true }
        val payload = PromotionsResponse(success = true, data = PromotionsPayload(active = active))
        return json.encodeToString(PromotionsResponse.serializer(), payload)
    }

    private fun planLockBody(): String = """{"featureCode":"PROMOTIONS","message":"El local no tiene el plan PRO"}"""

    // ── arranque seguro ──────────────────────────────────────────────────────

    @Test
    fun `arranca con el payload vacio, seguro por defecto`() {
        val repo = PromotionsRepository(secureStorage(), clientThrowing(IOException("no debería llamarse")), fakePayloadCache())

        assertTrue(repo.promotions.value.active.isEmpty())
        assertTrue(repo.promotions.value.upcoming.isEmpty())
    }

    // ── fail-safe: un fallo NO borra el catálogo bueno ────────────────────────

    @Test
    fun `un fallo de red NO borra el catalogo bueno`() = runTest {
        val client = sequencedClient(
            200 to successBody(listOf(promotion())),
            500 to "{}",
        )
        val repo = PromotionsRepository(secureStorage(), client, fakePayloadCache())

        repo.refresh("venue-1")
        assertEquals(1, repo.promotions.value.active.size)

        // Bache del servidor a media operación.
        repo.refresh("venue-1")

        assertEquals(1, repo.promotions.value.active.size)
        assertEquals("promo-1", repo.promotions.value.active.first().id)
    }

    @Test
    fun `sin red tambien conserva el catalogo — se hidrata del disco`() = runTest {
        val cache = fakePayloadCache()
        val onlineRepo = PromotionsRepository(secureStorage(), clientReturning(200, successBody(listOf(promotion()))), cache)
        onlineRepo.refresh("venue-1")
        assertEquals(1, onlineRepo.promotions.value.active.size)

        // Dispositivo/proceso nuevo (memoria vacía), mismo disco, sin red.
        val offlineRepo = PromotionsRepository(secureStorage(), clientThrowing(IOException("sin red")), cache)
        offlineRepo.refresh("venue-1")

        assertEquals(1, offlineRepo.promotions.value.active.size)
        assertEquals("promo-1", offlineRepo.promotions.value.active.first().id)
    }

    // ── el único caso que SÍ limpia ────────────────────────────────────────

    @Test
    fun `un 403 con featureCode SI limpia el cache`() = runTest {
        val cache = fakePayloadCache()
        val client = sequencedClient(
            200 to successBody(listOf(promotion())),
            403 to planLockBody(),
        )
        val repo = PromotionsRepository(secureStorage(), client, cache)

        repo.refresh("venue-1")
        assertEquals(1, repo.promotions.value.active.size)

        repo.refresh("venue-1")

        assertTrue(repo.promotions.value.active.isEmpty())
        coVerify { cache.clear(PromotionsRepository.TYPE, "venue-1") }
    }

    @Test
    fun `un 403 SIN featureCode NO limpia el cache — es candado de permiso, no de plan`() = runTest {
        val client = sequencedClient(
            200 to successBody(listOf(promotion())),
            403 to """{"message":"Sin permiso"}""",
        )
        val repo = PromotionsRepository(secureStorage(), client, fakePayloadCache())

        repo.refresh("venue-1")
        repo.refresh("venue-1")

        assertEquals(1, repo.promotions.value.active.size)
    }

    // ── clearCache (se llama al cambiar de venue, ver AuthRepository.switchVenue) ──

    @Test
    fun `clearCache deja el payload vacio de inmediato`() = runTest {
        val repo = PromotionsRepository(secureStorage(), clientReturning(200, successBody(listOf(promotion()))), fakePayloadCache())
        repo.refresh("venue-1")
        assertEquals(1, repo.promotions.value.active.size)

        repo.clearCache()

        assertTrue(repo.promotions.value.active.isEmpty())
        assertTrue(repo.promotions.value.upcoming.isEmpty())
    }

    // ── aislamiento por venue ──────────────────────────────────────────────

    @Test
    fun `el cache se guarda con la llave del venue — dos venues no se pisan`() = runTest {
        val sharedCache = fakePayloadCache()

        val repoVenueA = PromotionsRepository(secureStorage(), clientReturning(200, successBody(listOf(promotion(id = "promo-a")))), sharedCache)
        repoVenueA.refresh("venue-a")
        assertEquals("promo-a", repoVenueA.promotions.value.active.first().id)

        // Dispositivo nuevo para venue-b, sin red, comparte el mismo disco que venue-a.
        val repoVenueB = PromotionsRepository(secureStorage(), clientThrowing(IOException("sin red")), sharedCache)
        repoVenueB.refresh("venue-b")

        // Si la llave no incluyera el venueId, aquí aparecería promo-a.
        assertTrue(repoVenueB.promotions.value.active.isEmpty())
    }

    @Test
    fun `sin token no se intenta la llamada`() = runTest {
        val client = mockk<OkHttpClient>(relaxed = true)
        val repo = PromotionsRepository(secureStorage(token = null), client, fakePayloadCache())

        repo.refresh("venue-1")

        assertTrue(repo.promotions.value.active.isEmpty())
        verify(exactly = 0) { client.newCall(any()) }
    }

    // ── "no sé" vs "sé que no hay" ───────────────────────────────────────────
    // El panel de cobro escribe "Aún no hay promociones. Créalas desde el
    // dashboard" con esta señal. Si dice CARGADO antes de tiempo, le miente al
    // cajero de un local que sí tiene promociones.

    @Test
    fun `arranca sin saber, no diciendo que no hay`() {
        val repo = PromotionsRepository(secureStorage(), clientThrowing(IOException("no debería llamarse")), fakePayloadCache())

        assertEquals(EstadoCatalogo.SIN_CARGAR, repo.estado.value)
    }

    @Test
    fun `tras un refresh exitoso ya sabemos que hay`() = runTest {
        val repo = PromotionsRepository(secureStorage(), clientReturning(200, successBody(listOf(promotion()))), fakePayloadCache())

        repo.refresh("venue-1")

        assertEquals(EstadoCatalogo.CARGADO, repo.estado.value)
    }

    @Test
    fun `sin red y sin cache NO se afirma que el local no tiene promociones`() = runTest {
        // 🔴 El defecto que cierra este test: decir "Aún no hay promociones,
        // créalas desde el dashboard" cuando la verdad es "no pude preguntarle a
        // nadie". Además de afirmar un negativo sin saberlo, manda a RECREAR
        // promociones que probablemente ya existen.
        val repo = PromotionsRepository(secureStorage(), clientThrowing(IOException("sin red")), fakePayloadCache())

        repo.refresh("venue-1")

        assertEquals(EstadoCatalogo.NO_SE_PUDO, repo.estado.value)
    }

    @Test
    fun `sin red pero con cache en disco, el cache gana y no se habla de error`() = runTest {
        // La ley del repo: el fail-safe nunca puede ser quedarse sin poder
        // vender. Un catálogo un poco viejo es infinitamente mejor que uno vacío.
        val cache = fakePayloadCache()
        PromotionsRepository(secureStorage(), clientReturning(200, successBody(listOf(promotion()))), cache)
            .refresh("venue-1")

        val sinRed = PromotionsRepository(secureStorage(), clientThrowing(IOException("sin red")), cache)
        sinRed.refresh("venue-1")

        assertEquals(EstadoCatalogo.CARGADO, sinRed.estado.value)
        assertEquals(1, sinRed.promotions.value.active.size)
    }

    @Test
    fun `un rechazo del server sin cache tampoco afirma que no hay`() = runTest {
        // 500, proxy corporativo, permisos del mesero: no supimos nada.
        val repo = PromotionsRepository(secureStorage(), clientReturning(500, "boom"), fakePayloadCache())

        repo.refresh("venue-1")

        assertEquals(EstadoCatalogo.NO_SE_PUDO, repo.estado.value)
    }

    @Test
    fun `el candado de plan SI es una respuesta, no una falla`() = runTest {
        // El server contestó: el local perdió el plan. El panel pinta el candado
        // de PRO, no un error de conexión.
        val repo = PromotionsRepository(secureStorage(), clientReturning(403, planLockBody()), fakePayloadCache())

        repo.refresh("venue-1")

        assertEquals(EstadoCatalogo.CARGADO, repo.estado.value)
    }

    @Test
    fun `ni con el disco roto se queda cargando para siempre`() = runTest {
        // Es lo que justifica el `finally` y no una línea al final del try: si el
        // rescate del cache truena (disco lleno, cache corrupto), el estado tiene
        // que salir de CARGANDO igual. Sin red Y sin poder leer el disco es
        // exactamente el momento en que una pantalla girando para siempre sería
        // lo peor.
        val cacheRoto: PayloadCache = mockk(relaxed = true) {
            coEvery { load(any(), any()) } throws IllegalStateException("cache corrupto")
        }
        val repo = PromotionsRepository(secureStorage(), clientThrowing(IOException("sin red")), cacheRoto)

        runCatching { repo.refresh("venue-1") }

        // No se pudo preguntar ni rescatar nada, pero el estado SALIÓ de CARGANDO.
        assertEquals(EstadoCatalogo.NO_SE_PUDO, repo.estado.value)
    }

    @Test
    fun `sin token no se queda cargando para siempre`() = runTest {
        val repo = PromotionsRepository(secureStorage(token = null), mockk(relaxed = true), fakePayloadCache())

        repo.refresh("venue-1")

        assertEquals(EstadoCatalogo.CARGADO, repo.estado.value)
    }

    @Test
    fun `cambiar de local deja de saber — no hereda el CARGADO del anterior`() = runTest {
        // El bug que cierra este test: `switchVenue` llama derecho al repositorio
        // (clearCache + refresh) sin pasar por el ViewModel del panel, y ese
        // ViewModel sobrevive al cambio de pestaña. Con una bandera local, el
        // panel del local NUEVO enseñaba "Aún no hay promociones" usando el "ya
        // cargué" del ANTERIOR, con el catálogo recién vaciado.
        val repo = PromotionsRepository(secureStorage(), clientReturning(200, successBody(listOf(promotion()))), fakePayloadCache())
        repo.refresh("venue-a")
        assertEquals(EstadoCatalogo.CARGADO, repo.estado.value)

        repo.clearCache()

        assertEquals(EstadoCatalogo.SIN_CARGAR, repo.estado.value)
        assertTrue(repo.promotions.value.active.isEmpty())
    }

    @Test
    fun `sin venue activo no se queda girando`() {
        val repo = PromotionsRepository(secureStorage(), clientThrowing(IOException("no debería llamarse")), fakePayloadCache())

        repo.marcarSinVenue()

        assertEquals(EstadoCatalogo.CARGADO, repo.estado.value)
    }
}
