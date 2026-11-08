package com.avoqado.pos.articles

import com.avoqado.pos.articles.data.ArticlesRepository
import com.avoqado.pos.core.data.local.SecureStorage
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * D23: la hoja de cupón o de descuento enseña lo que tenga `errorMessage`. Tiene que ser el motivo real del rechazo del
 * SERVIDOR; sin red, decirlo; y una página que no es nuestra API (502 de Render) nunca llega a la pantalla.
 */
class CuponesYDescuentosMotivoDelRechazoTest {
    private val guardados: List<Pair<String, suspend (ArticlesRepository) -> Boolean>> = listOf(
        "Error al crear cupón" to { it.createCoupon("{}") },
        "Error al actualizar cupón" to { it.updateCoupon("cupon-1", "{}") },
        "Error al crear descuento" to { it.createDiscount("{}") },
        "Error al actualizar descuento" to { it.updateDiscount("descuento-1", "{}") },
    )

    @Test
    fun `un 400 deja el motivo que mando el servidor`() = runTest {
        val motivo = "Los límites de uso van de 1 en adelante; déjalo vacío para que sea ilimitado"
        guardados.forEach { (_, guardar) ->
            val repository = repositorio(clientQueResponde(400, """{"success":false,"message":"$motivo"}"""))

            assertFalse(guardar(repository))
            assertEquals(motivo, repository.errorMessage.value)
        }
    }

    @Test
    fun `sin red dice que no hay conexion`() = runTest {
        guardados.forEach { (_, guardar) ->
            val call = mockk<Call>()
            every { call.execute() } throws java.net.UnknownHostException("Unable to resolve host")
            val repository = repositorio(mockk { every { newCall(any()) } returns call })

            assertFalse(guardar(repository))
            assertEquals("Sin conexión. Revisa la red e inténtalo de nuevo.", repository.errorMessage.value)
        }
    }

    @Test
    fun `una respuesta que no es de la API cae al texto propio`() = runTest {
        guardados.forEach { (respaldo, guardar) ->
            val repository = repositorio(clientQueResponde(502, "<html>Bad Gateway</html>"))

            assertFalse(guardar(repository))
            assertEquals(respaldo, repository.errorMessage.value)
        }
    }

    private fun repositorio(client: OkHttpClient) = ArticlesRepository(
        mockk<SecureStorage> { every { venueId } returns "venue-1" },
        client,
        mockk(relaxed = true),
    )

    private fun clientQueResponde(status: Int, body: String): OkHttpClient {
        val call = mockk<Call>()
        every { call.execute() } returns Response.Builder()
            .request(Request.Builder().url("https://api.avoqado.io/test").build())
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message("Rechazo")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
        return mockk { every { newCall(any()) } returns call }
    }
}
