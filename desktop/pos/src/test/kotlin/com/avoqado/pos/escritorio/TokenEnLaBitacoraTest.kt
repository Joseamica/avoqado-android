package com.avoqado.pos.escritorio

import com.avoqado.escritorio.ActividadDeEscritorio
import com.avoqado.escritorio.Bitacora
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.TokenRefreshAuthenticator
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * El camino que encontró Codex: el refresco contesta 200 con un `accessToken: null` y otro token válido;
 * kotlinx.serialization mete el cuerpo en el mensaje («JSON input: …») y `TokenRefreshAuthenticator` lo registra con
 * `Log.e(e.message)`. En escritorio ese Log va al archivo, también en producción: la bitácora tiene que taparlo.
 */
class TokenEnLaBitacoraTest {
    @Serializable private data class RespuestaDeRefresco(val accessToken: String, val refreshToken: String)

    @Test fun `el mensaje REAL de kotlinx con el token en el JSON input no llega a la bitacora`() {
        val carpeta = Files.createTempDirectory("Avoqado POS bitácora kotlinx")
        Bitacora.iniciar(carpeta)
        val error = try {
            Json { ignoreUnknownKeys = true }.decodeFromString<RespuestaDeRefresco>(CUERPO)
            fail("el cuerpo con accessToken null debía tronar")
        } catch (e: SerializationException) { e }
        assertTrue(JWT in error.message.orEmpty() && "JSON input:" in error.message.orEmpty(), "kotlinx cambió su mensaje: ${error.message}")

        android.util.Log.e("🔐", "Token refresh unexpected error: ${error.message}")   // la línea exacta de la app (:305)
        android.util.Log.e("🔐", "con pila", error)

        val texto = log(carpeta)
        assertFalse(FIRMA in texto || JWT in texto, texto)
        assertTrue("JSON input: <omitido>" in texto, texto)
        assertTrue("Expected string literal but 'null' literal was found" in texto, "se perdió el motivo: $texto")
    }

    @Test fun `punta a punta - el TokenRefreshAuthenticator real con un 200 invalido no deja el token en la bitacora de produccion`() {
        val carpeta = Files.createTempDirectory("Avoqado POS bitácora refresco")
        Bitacora.iniciar(carpeta)
        val inyector = Inyector.crear(ActividadDeEscritorio(carpeta))
        inyector.getInstance(SecureStorage::class.java).updateTokens(accessToken = "access-viejo", refreshToken = "refresh-viejo")
        val autenticador = inyector.getInstance(TokenRefreshAuthenticator::class.java)
        // El «servidor» del refresco: contesta 200 con el cuerpo que rompe la deserialización.
        autenticador.refreshHttpClient = OkHttpClient.Builder().addInterceptor { cadena ->
            Response.Builder().request(cadena.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(CUERPO.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val pedido = Request.Builder().url("http://127.0.0.1:9/api/v1/mobile/venues/v1/orders").build()
        val respuesta401 = Response.Builder().request(pedido).protocol(Protocol.HTTP_1_1).code(401).message("Unauthorized").build()

        Bitacora.soloInformativo = true
        try {
            assertNull(autenticador.authenticate(null, respuesta401), "un 200 inválido no reintenta ni cierra sesión")
        } finally { Bitacora.soloInformativo = false }

        val texto = log(carpeta)
        assertTrue("Token refresh unexpected error" in texto, "no pasó por la línea de la app: $texto")
        assertFalse(FIRMA in texto || JWT in texto, texto)
    }

    private fun log(carpeta: Path): String = Files.list(carpeta.resolve("logs")).use { it.toList() }.single().readText()

    private companion object {
        const val FIRMA = "firma-falsa_9Xq"
        const val JWT = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJjYWplcm8ifQ.$FIRMA"
        const val CUERPO = """{"accessToken":null,"refreshToken":"$JWT"}"""
    }
}
