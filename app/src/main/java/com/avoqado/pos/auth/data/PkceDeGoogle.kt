package com.avoqado.pos.auth.data

import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

// Entrar con Google (hz:6bfcd3a1:D1): el PKCE y la URL de Google, iguales en ANDROID y en WINDOWS. Sólo `java.*`:
// WINDOWS compila este mismo archivo.

private const val ALFABETO_PKCE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
private val azar = SecureRandom()

/** El verificador de PKCE (RFC 7636): 64 caracteres del alfabeto permitido. */
fun nuevoVerificador(): String = String(CharArray(64) { ALFABETO_PKCE[azar.nextInt(ALFABETO_PKCE.length)] })

/** El `state`: 32 bytes al azar en base64url sin relleno, 43 caracteres (lo que acepta el SERVIDOR). */
fun nuevoState(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { azar.nextBytes(it) })

/** PKCE (RFC 7636, S256): base64url(SHA-256(verificador)), sin relleno. */
fun retoPkce(verificador: String): String = Base64.getUrlEncoder().withoutPadding()
    .encodeToString(MessageDigest.getInstance("SHA-256").digest(verificador.toByteArray(Charsets.US_ASCII)))

fun urlDeGoogle(clientId: String, redirectUri: String, state: String, reto: String): String =
    "https://accounts.google.com/o/oauth2/v2/auth?" + listOf(
        "client_id" to clientId,
        "redirect_uri" to redirectUri,
        "response_type" to "code",
        "scope" to "openid email profile",
        "code_challenge" to reto,
        "code_challenge_method" to "S256",
        "state" to state,
        "prompt" to "select_account",
        // El nombre del charset, no `Charsets.UTF_8`: esa sobrecarga no existe en Android antes de la API 33.
    ).joinToString("&") { (clave, valor) -> "$clave=${URLEncoder.encode(valor, "UTF-8").replace("+", "%20")}" }
