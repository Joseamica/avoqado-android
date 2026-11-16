package com.avoqado.pos.kiosk

import com.avoqado.pos.kiosk.data.KioskCheckInApi
import com.avoqado.pos.kiosk.data.KioskPhone
import com.avoqado.pos.kiosk.data.KioskQr
import com.avoqado.pos.kiosk.data.KioskTap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Asistencia verificada (16-nov-2026): el QR que firma el kiosco y el teléfono guardado sin red.
 *
 * P1: la firma debe ser EXACTAMENTE la del SERVIDOR (`signKioskQr`). El vector se calculó allá con Node:
 * HMAC-SHA256(0011…eeff, "v2|cvenue…a|ckey…a|1763316000") en base64url.
 */
class KioskQrTest {
    private val secreto = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
    private val llave = KioskQr.Llave(
        venueId = "cvenue000000000000000000a",
        keyId = "ckey00000000000000000000a",
        secret = secreto,
        checkInUrl = "https://book.avoqado.io/mindform/checkin/q",
    )

    @Test
    fun `P1 la firma coincide con la del servidor`() {
        assertEquals(
            "Vnm1jZmDRn0zA55N2RCIQVLevJCsC7y8jPtvQc7tiG8",
            KioskQr.firma(secreto, llave.venueId, llave.keyId, 1763316000L),
        )
    }

    @Test
    fun `la liga lleva todo en el fragmento, nunca en la ruta`() {
        val url = KioskQr.url(llave, 1763316000L)
        assertEquals(
            "https://book.avoqado.io/mindform/checkin/q#v=2&k=ckey00000000000000000000a&t=1763316000&s=Vnm1jZmDRn0zA55N2RCIQVLevJCsC7y8jPtvQc7tiG8",
            url,
        )
    }

    @Test
    fun `el QR cambia sólo al empezar el siguiente paso de 30 s`() {
        assertEquals(1763316000L, KioskQr.paso(1763316000_000L, 30))
        assertEquals(1763316000L, KioskQr.paso(1763316029_999L, 30))
        assertEquals(1763316030L, KioskQr.paso(1763316030_000L, 30))
    }

    @Test
    fun `una llave guardada se lee igual y una rota no sirve`() {
        assertEquals(llave, KioskQr.leer(KioskQr.guardar(llave)))
        assertNull(KioskQr.leer("{basura"))
        assertNull(KioskQr.leer(KioskQr.guardar(llave.copy(checkInUrl = "javascript:x"))))
    }

    @Test
    fun `teléfono guardado - se lee igual y sin opId no sirve`() {
        val s = KioskPhone.Saved("v1", "+525512345678", "2026-11-16T18:00:00Z", "op-12345678")
        assertEquals(s, KioskPhone.parse(KioskPhone.payload(s)))
        assertNull(KioskPhone.parse(KioskPhone.payload(s.copy(opId = "x"))))
    }

    @Test
    fun `teléfono - sin red o con demasiados intentos se queda, sin reserva va a cuarentena, bien se borra`() {
        assertEquals(KioskTap.Outcome.Sent, KioskPhone.outcome(Result.success(Unit)))
        assertEquals(KioskTap.Outcome.Kept, KioskPhone.outcome(Result.failure<Unit>(IOException("sin red"))))
        assertEquals(KioskTap.Outcome.Kept, KioskPhone.outcome(Result.failure<Unit>(KioskCheckInApi.TooManyAttempts())))
        val no = KioskPhone.outcome(Result.failure<Unit>(KioskCheckInApi.NotFound()))
        assertTrue(no is KioskTap.Outcome.Refused)
        assertEquals(KioskTap.Outcome.Kept, KioskPhone.outcome(Result.failure<Unit>(KioskCheckInApi.Rejected(503, "caído"))))
        assertTrue(KioskPhone.outcome(Result.failure<Unit>(KioskCheckInApi.Rejected(422, "muy vieja", "CHECK_IN_TOO_OLD"))) is KioskTap.Outcome.Refused)
    }
}
