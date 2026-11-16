package com.avoqado.pos.kiosk.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Asistencia verificada (Mindform, 16-nov-2026): el QR de la llegada lo FIRMA este aparato.
 *
 * El aparato pide una vez, con red, su llave al SERVIDOR (`POST /mobile/venues/:venueId/kiosk/qr-key`) y la guarda. Con
 * ella pinta cada [Llave.rotateSeconds] un QR nuevo sin hablar con nadie: así sale aunque se caiga el internet del local,
 * y le sirve a toda la fila (lo que dice QUIÉN llegó es la sesión del celular de cada alumna, no el QR).
 *
 *   firma = HMAC-SHA256(secreto, "v2|<venueId>|<keyId>|<ts>")  en base64url sin relleno
 *
 * 🔴 Debe dar EXACTAMENTE lo que calcula el SERVIDOR (`signKioskQr`, `kioskQr.service.ts`): la prueba
 * `KioskQrTest` fija un vector calculado allá.
 */
object KioskQr {
    private val json = Json { ignoreUnknownKeys = true }

    /** La llave de ESTE aparato para UNA sucursal. [secret] en hex, tal cual la dio el servidor. */
    @Serializable
    data class Llave(
        val venueId: String,
        val keyId: String,
        val secret: String,
        /** La página del celular, sin fragmento: «https://book.avoqado.io/mindform/checkin/q». */
        val checkInUrl: String,
        val rotateSeconds: Int = 30,
    )

    fun guardar(llave: Llave): String = json.encodeToString(Llave.serializer(), llave)

    fun leer(texto: String?): Llave? =
        texto?.let { runCatching { json.decodeFromString(Llave.serializer(), it) }.getOrNull() }
            ?.takeIf { it.keyId.isNotBlank() && it.secret.length >= 32 && it.checkInUrl.startsWith("http") }

    fun firma(secretHex: String, venueId: String, keyId: String, ts: Long): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(hex(secretHex), "HmacSHA256"))
        val bytes = mac.doFinal("v2|$venueId|$keyId|$ts".toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * La hora del QR en segundos, redondeada al paso de rotación: el QR no cambia a cada segundo (no parpadea) y cambia
     * solo al empezar el siguiente paso. [ahoraMs] es la hora del SERVIDOR estimada por el aparato, o la suya si no hay.
     */
    fun paso(ahoraMs: Long, rotateSeconds: Int): Long {
        val s = ahoraMs / 1000
        val r = rotateSeconds.coerceAtLeast(1)
        return s - (s % r)
    }

    /** Lo que se pinta: la página del celular con el QR en el FRAGMENTO (no viaja al servidor de la página). */
    fun url(llave: Llave, ts: Long): String =
        "${llave.checkInUrl}#v=2&k=${llave.keyId}&t=$ts&s=${firma(llave.secret, llave.venueId, llave.keyId, ts)}"

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
