package com.avoqado.pos.auth.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** El PKCE y la URL de Google, iguales en ANDROID y en WINDOWS (hz:6bfcd3a1:D1). */
class PkceDeGoogleTest {

    @Test
    fun `retoPkce con el vector del RFC 7636`() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", retoPkce("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test
    fun `urlDeGoogle lleva S256, select_account y la dirección codificada`() {
        val url = urlDeGoogle("cliente.apps.googleusercontent.com", "http://127.0.0.1:5123", "estado-1", "reto_2")
        assertTrue(url, url.startsWith("https://accounts.google.com/o/oauth2/v2/auth?"))
        for (parte in listOf(
            "client_id=cliente.apps.googleusercontent.com",
            "redirect_uri=http%3A%2F%2F127.0.0.1%3A5123",
            "response_type=code",
            "scope=openid%20email%20profile",
            "code_challenge=reto_2",
            "code_challenge_method=S256",
            "state=estado-1",
            "prompt=select_account",
        )) assertTrue("$url no trae $parte", url.contains(parte))
    }

    @Test
    fun `el verificador son 64 caracteres del alfabeto de PKCE y cambia cada vez`() {
        val alfabeto = ('A'..'Z') + ('a'..'z') + ('0'..'9') + listOf('-', '.', '_', '~')
        val uno = nuevoVerificador()
        assertEquals(64, uno.length)
        assertTrue(uno, uno.all { it in alfabeto })
        assertNotEquals(uno, nuevoVerificador())
    }

    @Test
    fun `el state son 43 caracteres base64url, lo que acepta el SERVIDOR, y cambia cada vez`() {
        val uno = nuevoState()
        assertTrue(uno, Regex("^[A-Za-z0-9_-]{43}$").matches(uno))
        assertNotEquals(uno, nuevoState())
    }
}
