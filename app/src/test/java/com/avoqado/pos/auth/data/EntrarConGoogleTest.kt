package com.avoqado.pos.auth.data

import android.app.ActivityManager
import android.net.Uri
import androidx.browser.auth.AuthTabIntent
import androidx.fragment.app.FragmentActivity
import com.avoqado.pos.auth.data.model.GoogleConfigResponse
import com.avoqado.pos.auth.data.model.PaseDeGoogle
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Entrar con Google en ANDROID por una pestaña de incógnito (Auth Tab efímera) que vuelve por el SERVIDOR (hz:6bfcd3a1:D1). */
class EntrarConGoogleTest {
    private val textoReintenta = "No pudimos confirmar tu cuenta de Google. Vuelve a intentarlo."
    private val textoAparato = "Entrar con Google no está disponible en este aparato. Entra con tu correo y contraseña."

    /** `avoqado-pos://google?…` con estos parámetros (el `Uri` del android.jar de las pruebas es un cascarón). */
    private fun vuelta(vararg pares: Pair<String, String>): Uri {
        val parametros = pares.toMap()
        return mockk { every { getQueryParameter(any()) } answers { parametros[firstArg<String>()] } }
    }

    // --- La vuelta del SERVIDOR ---

    @Test
    fun `vuelta buena con nuestro state es Vuelta con el state y el verificador`() {
        assertEquals(
            PaseDeGoogle.Vuelta("s1", "verificador"),
            leerVueltaDeAuthTab(vuelta("state" to "s1"), "s1", "verificador"),
        )
    }

    @Test
    fun `access_denied es Cancelado`() {
        assertEquals(PaseDeGoogle.Cancelado, leerVueltaDeAuthTab(vuelta("state" to "s1", "error" to "access_denied"), "s1", "v"))
    }

    @Test
    fun `otro error de Google o del SERVIDOR es Fallo con el texto de reintentar`() {
        for (error in listOf("server_error", "sin_codigo")) {
            val r = leerVueltaDeAuthTab(vuelta("state" to "s1", "error" to error), "s1", "v") as PaseDeGoogle.Fallo
            assertEquals(textoReintenta, r.texto)
        }
    }

    @Test
    fun `state ajeno, sin state o sin uri es Fallo con el texto de reintentar`() {
        for (uri in listOf(vuelta("state" to "otro"), vuelta("state" to "otro", "error" to "access_denied"), vuelta(), null)) {
            val r = leerVueltaDeAuthTab(uri, "s1", "v") as PaseDeGoogle.Fallo
            assertEquals(textoReintenta, r.texto)
        }
    }

    @Test
    fun `resultado de la pestaña - cerrar es Cancelado, OK lee la vuelta, lo demás es Fallo del aparato`() {
        assertEquals(PaseDeGoogle.Cancelado, paseDeLaAuthTab(AuthTabIntent.RESULT_CANCELED, null, "s1", "v"))
        assertEquals(PaseDeGoogle.Vuelta("s1", "v"), paseDeLaAuthTab(AuthTabIntent.RESULT_OK, vuelta("state" to "s1"), "s1", "v"))
        for (codigo in listOf(AuthTabIntent.RESULT_VERIFICATION_FAILED, AuthTabIntent.RESULT_UNKNOWN_CODE)) {
            assertEquals(textoAparato, (paseDeLaAuthTab(codigo, null, "s1", "v") as PaseDeGoogle.Fallo).texto)
        }
    }

    // --- El navegador (Codex r4 #2) ---

    private val ambas = setOf("com.android.chrome", "com.sec.android.app.sbrowser")

    @Test
    fun `Chrome va primero si cumple las dos`() {
        assertEquals(
            "com.android.chrome",
            elegirNavegador(listOf("com.sec.android.app.sbrowser", "com.android.chrome"), { it in ambas }, { it in ambas }),
        )
    }

    @Test
    fun `sin Chrome que cumpla, el primer candidato que cumpla las dos`() {
        val soloAuthTab = setOf("com.android.chrome", "org.mozilla.firefox", "com.sec.android.app.sbrowser")
        assertEquals(
            "com.sec.android.app.sbrowser",
            elegirNavegador(
                listOf("org.mozilla.firefox", "com.sec.android.app.sbrowser"),
                { it in soloAuthTab },
                { it == "com.sec.android.app.sbrowser" },
            ),
        )
    }

    @Test
    fun `ninguno con Auth Tab e incógnito - nulo`() {
        assertNull(elegirNavegador(listOf("org.mozilla.firefox"), { true }, { false }))
        assertNull(elegirNavegador(emptyList(), { false }, { true }))
    }

    // --- La caja bloqueada (Codex r4 #4) ---

    @Test
    fun `sólo el bloqueo de administrador frena, el fijado normal del POS se intenta`() {
        assertTrue(cajaBloqueada(ActivityManager.LOCK_TASK_MODE_LOCKED))
        assertFalse(cajaBloqueada(ActivityManager.LOCK_TASK_MODE_PINNED))
        assertFalse(cajaBloqueada(ActivityManager.LOCK_TASK_MODE_NONE))
    }

    // --- La config (Codex r4 #7) ---

    @Test
    fun `sin la parte de ANDROID en la config - Fallo del servidor sin tocar el aparato`() = runTest {
        val buena = GoogleConfigResponse(success = true, android = true, webClientId = "web", redirectUri = "https://api/vuelta")
        val entrar = EntrarConGoogle()
        val aparato = mockk<FragmentActivity>()   // estricto: cualquier llamada truena
        for (config in listOf(
            buena.copy(android = false, windows = true),
            buena.copy(webClientId = null),
            buena.copy(redirectUri = " "),
        )) {
            val r = entrar.pedirPase(aparato, config) as PaseDeGoogle.Fallo
            assertEquals("Entrar con Google no está disponible por ahora. Entra con tu correo y contraseña.", r.texto)
        }
    }
}
