package com.avoqado.pos.loyalty

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiService
import com.avoqado.pos.core.data.network.SesionSinRenovar
import com.avoqado.pos.loyalty.data.LecturaDeTarjeta
import com.avoqado.pos.loyalty.data.ScannedCustomer
import com.avoqado.pos.loyalty.data.TarjetaSinLeer
import com.avoqado.pos.loyalty.data.WalletScanRepository
import com.avoqado.pos.loyalty.data.WalletScanResponse
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.net.UnknownHostException

/**
 * D53 (hz:b37dd7ea:D53): una tarjeta digital que no se pudo leer dice POR QUÉ, y nunca termina en «Producto no encontrado ·
 * Crear nuevo». Sin red lo decide la falla de ESTA petición (nadie contestó), no la bandera global del aparato.
 */
class WalletScanRepositoryTest {

    private val api = mockk<ApiService>()
    private val secureStorage = mockk<SecureStorage>(relaxed = true)
    private val repo = WalletScanRepository(api, secureStorage)
    private val pase = "a1".repeat(24)

    private fun http(codigo: Int) = HttpException(Response.error<Any>(codigo, "".toResponseBody()))
    private val encontrada = WalletScanResponse(
        found = true,
        customer = ScannedCustomer(id = "c1", firstName = "Ana"),
        stampsEarned = 3,
        stampsRequired = 7,
    )

    @Before
    fun setUp() {
        every { secureStorage.venueId } returns "venue-1"
    }

    @Test
    fun `P1 sin red dice sin conexion, no producto no encontrado`() = runBlocking {
        coEvery { api.scanWalletPass(any(), any()) } throws UnknownHostException("api.avoqado.io")
        assertEquals(LecturaDeTarjeta.SinLeer(TarjetaSinLeer.SIN_RED), repo.escanear(pase))
    }

    @Test
    fun `una sesion que no se pudo renovar sin red tambien es sin conexion`() = runBlocking {
        coEvery { api.scanWalletPass(any(), any()) } throws SesionSinRenovar("red:UnknownHostException")
        assertEquals(LecturaDeTarjeta.SinLeer(TarjetaSinLeer.SIN_RED), repo.escanear(pase))
    }

    @Test
    fun `la renovacion que el servidor contesto con 5xx o 429 no es sin conexion`() = runBlocking {
        // Codex D53 r1 #5: el servidor contestó dos veces (401 y luego 500/429 al renovar).
        coEvery { api.scanWalletPass(any(), any()) } throws SesionSinRenovar("servidor:500")
        assertEquals(LecturaDeTarjeta.SinLeer(TarjetaSinLeer.FALLO), repo.escanear(pase))
    }

    @Test
    fun `una respuesta sin el veredicto found no se lee como no encontrada`() {
        // Codex D53 r1 #5: sin valor por omisión, `{}` truena al leerse y el repositorio dice «No se pudo leer».
        val json = com.avoqado.pos.core.di.NetworkModule.provideJson()
        assertTrue(runCatching { json.decodeFromString(WalletScanResponse.serializer(), "{}") }.isFailure)
        assertEquals(false, json.decodeFromString(WalletScanResponse.serializer(), """{"found":false}""").found)
    }

    @Test
    fun `el escaneo no lleva la marca de rendirse rapido`() {
        // Codex D53 r1 #1: con la marca, un escaneo lento dejaba «nadie contesta» y el siguiente cobro en efectivo se iba a la
        // cola sin intentarlo, con el servidor vivo.
        val metodo = ApiService::class.java.methods.first { it.name == "scanWalletPass" }
        assertEquals(null, metodo.getAnnotation(retrofit2.http.Headers::class.java))
    }

    @Test
    fun `502, 503 y 504 son el servidor que no esta, como en Mesas`() = runBlocking {
        for (codigo in listOf(502, 503, 504)) {
            coEvery { api.scanWalletPass(any(), any()) } throws http(codigo)
            assertEquals("$codigo", LecturaDeTarjeta.SinLeer(TarjetaSinLeer.SIN_RED), repo.escanear(pase))
        }
    }

    @Test
    fun `un rechazo del servidor no se disfraza de sin conexion`() = runBlocking {
        for (codigo in listOf(401, 403, 500)) {
            coEvery { api.scanWalletPass(any(), any()) } throws http(codigo)
            assertEquals("$codigo", LecturaDeTarjeta.SinLeer(TarjetaSinLeer.FALLO), repo.escanear(pase))
        }
    }

    @Test
    fun `P1 con red y la tarjeta que el servidor no encuentra dice no encontrada`() = runBlocking {
        coEvery { api.scanWalletPass(any(), any()) } returns WalletScanResponse(found = false)
        assertEquals(LecturaDeTarjeta.SinLeer(TarjetaSinLeer.NO_ENCONTRADA), repo.escanear(pase))
    }

    @Test
    fun `sin negocio en la sesion no pregunta y no se pudo leer`() = runBlocking {
        every { secureStorage.venueId } returns null
        assertEquals(LecturaDeTarjeta.SinLeer(TarjetaSinLeer.FALLO), repo.escanear(pase))
    }

    @Test
    fun `la tarjeta encontrada se devuelve tal cual`() = runBlocking {
        coEvery { api.scanWalletPass("venue-1", any()) } returns encontrada
        assertEquals(LecturaDeTarjeta.Leida(encontrada), repo.escanear(pase))
    }

    @Test(expected = CancellationException::class)
    fun `la pantalla que se va cancela, no es una tarjeta sin leer`(): Unit = runBlocking {
        coEvery { api.scanWalletPass(any(), any()) } throws CancellationException("se fue la pantalla")
        repo.escanear(pase)
    }

    @Test
    fun `ningun aviso ofrece crear producto y todos mandan a elegir al cliente desde el carrito`() {
        // Codex D53 r2 #4: «Agregar cliente» no siempre se ve (teléfono con el carrito vacío, o «Cambiar cliente» si ya hay uno).
        for (motivo in TarjetaSinLeer.entries) {
            assertFalse(motivo.name, "crear" in motivo.aviso.lowercase())
            assertTrue(motivo.name, "al cliente a mano desde el carrito" in motivo.aviso)
        }
        assertTrue("cuando vuelva la red" in TarjetaSinLeer.SIN_RED.aviso)
    }
}
