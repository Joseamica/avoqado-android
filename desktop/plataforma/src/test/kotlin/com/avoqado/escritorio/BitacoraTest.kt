package com.avoqado.escritorio

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BitacoraTest {
    @Test fun `Log de Android escribe en la bitacora de la carpeta de datos`() {
        val carpeta = Files.createTempDirectory("bitácora")
        Bitacora.iniciar(carpeta)
        android.util.Log.w("📡", "Network lost", RuntimeException("sin cable"))
        val texto = Files.list(carpeta.resolve("logs")).use { it.toList() }.single().readText()
        assertTrue("W/📡: Network lost" in texto, texto)
        assertTrue("sin cable" in texto, texto)
    }

    @Test fun `con soloInformativo no se escribe D y si I W y E`() {
        val carpeta = Files.createTempDirectory("bitácora-prod")
        Bitacora.iniciar(carpeta)
        Bitacora.soloInformativo = true
        try {
            android.util.Log.d("T", "detalle-secreto-d")
            android.util.Log.i("T", "informativo-i")
            android.util.Log.w("T", "aviso-w")
            android.util.Log.e("T", "error-e")
        } finally { Bitacora.soloInformativo = false }
        val texto = Files.list(carpeta.resolve("logs")).use { it.toList() }.single().readText()
        assertTrue("detalle-secreto-d" !in texto, texto)
        assertTrue("informativo-i" in texto && "aviso-w" in texto && "error-e" in texto, texto)
    }

    @Test fun `sin soloInformativo D si se escribe`() {
        val carpeta = Files.createTempDirectory("bitácora-prueba")
        Bitacora.iniciar(carpeta)
        android.util.Log.d("T", "detalle-d")
        assertTrue("detalle-d" in Files.list(carpeta.resolve("logs")).use { it.toList() }.single().readText())
    }

    // --- Secretos: nada que la app mande por Log.x deja un token en la bitácora (prueba y producción) ---

    @Test fun `un JWT suelto se tapa`() {
        assertEquals("Bearer de prueba: <jwt> y sigue", Bitacora.sanear("Bearer de prueba: $JWT y sigue"))
    }

    @Test fun `las llaves secretas de un JSON se tapan sin importar mayusculas ni espacios`() {
        val texto = """{"accessToken":"a1b2c3","REFRESHTOKEN" : "opaco-sin-puntos","token": "t","Password":"clave","pin":"1234","deviceId":"AVQD-1"}"""
        assertEquals(
            """{"accessToken":"<omitido>","REFRESHTOKEN" : "<omitido>","token": "<omitido>","Password":"<omitido>","pin":"<omitido>","deviceId":"AVQD-1"}""",
            Bitacora.sanear(texto),
        )
    }

    @Test fun `lo que sigue a JSON input se tapa hasta el fin de esa linea`() {
        val texto = "Unexpected JSON token at offset 15: Expected string literal\nJSON input: {\"accessToken\":null,\"x\":\"opaco\"}\nsiguiente"
        assertEquals("Unexpected JSON token at offset 15: Expected string literal\nJSON input: <omitido>\nsiguiente", Bitacora.sanear(texto))
    }

    @Test fun `Authorization Bearer se tapa`() {
        assertEquals("--> Authorization: <omitido>", Bitacora.sanear("--> Authorization: Bearer opaco-123"))
    }

    @Test fun `un mensaje sin secretos sale identico`() {
        listOf(
            "Token refresh network error: failed to connect to /127.0.0.1 (port 9) from /127.0.0.1 after 10000ms",
            """{"tokenExpiresAt":1790000000000,"deviceId":"AVQD-escritorio-7f3a","pinRequired":true}""",
            "Puente táctil: instalado (WM_POINTER) en C:\\Users\\Caja\\AppData\\Local\\Temp",
            "eyJ sin puntos no es un token",
        ).forEach { assertEquals(it, Bitacora.sanear(it)) }
    }

    @Test fun `la bitacora no escribe el token aunque venga en el mensaje o en la CAUSA de la pila, en prueba y en produccion`() {
        for (produccion in listOf(false, true)) {
            val carpeta = Files.createTempDirectory("bitácora secretos")
            Bitacora.iniciar(carpeta)
            Bitacora.soloInformativo = produccion
            try {
                val causa = IllegalArgumentException("JSON input: {\"refreshToken\":\"$REFRESH_OPACO\"}")
                val error = IllegalStateException("falló el refresco con $JWT", causa).apply {
                    addSuppressed(RuntimeException("""cuerpo {"accessToken":"$ACCESS_OPACO"}"""))
                }
                android.util.Log.e("🔐", "Token refresh unexpected error: ${error.message} (Authorization: Bearer $ACCESS_OPACO)", error)
            } finally { Bitacora.soloInformativo = false }
            val texto = log(carpeta)
            for (secreto in listOf(JWT, FIRMA, REFRESH_OPACO, ACCESS_OPACO)) assertFalse(secreto in texto, "modo produccion=$produccion: $texto")
            assertTrue("Token refresh unexpected error: falló el refresco con <jwt>" in texto, texto)
            assertTrue("Caused by: java.lang.IllegalArgumentException: JSON input: <omitido>" in texto, texto)
            assertTrue("Suppressed: java.lang.RuntimeException: cuerpo {\"accessToken\":\"<omitido>\"}" in texto, texto)
        }
    }

    @Test fun `en la bitacora un mensaje sin secretos queda tal cual`() {
        val carpeta = Files.createTempDirectory("bitácora limpia")
        Bitacora.iniciar(carpeta)
        val mensaje = """Sync OK: {"orderId":"ord-9","amountCents":15000,"tokenExpiresAt":1790000000000}"""
        android.util.Log.i("Sync", mensaje)
        assertTrue("I/Sync: $mensaje\n" in log(carpeta), log(carpeta))
    }

    private fun log(carpeta: Path): String = Files.list(carpeta.resolve("logs")).use { it.toList() }.single().readText()

    private companion object {
        const val FIRMA = "firma-falsa_9Xq"
        const val JWT = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJjYWplcm8ifQ.$FIRMA"
        const val REFRESH_OPACO = "refresh-opaco-77"
        const val ACCESS_OPACO = "access-opaco-55"
    }
}
