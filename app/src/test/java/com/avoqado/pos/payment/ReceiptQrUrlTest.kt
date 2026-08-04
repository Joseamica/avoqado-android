package com.avoqado.pos.payment

import com.avoqado.pos.core.data.network.ApiConstants
import com.avoqado.pos.core.data.network.resolveReceiptUrl
import com.avoqado.pos.payment.data.OrderRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QR del recibo digital impreso y de la pantalla del cliente.
 *
 * Bug que estos tests congelan (agosto 2026): la URL se armaba concatenando la base del
 * API (`ApiConstants.BASE_URL + "/public/receipt/" + key`), o sea que TODOS los tickets de
 * Android llevaban a la página vieja del backend — la que NO tiene autofactura. El backend
 * ya mandaba la buena en `digitalReceipt.receiptUrl` y el parser la tiraba a la basura.
 */
class ReceiptQrUrlTest {

    private val legacyMarker = "/public/receipt/"

    // MARK: - resolveReceiptUrl

    @Test
    fun `prefiere la URL que manda el backend`() {
        val fromServer = "https://dashboard.avoqado.io/receipts/public/abc123"
        assertEquals(fromServer, resolveReceiptUrl(fromServer, "otra-llave"))
    }

    @Test
    fun `sin URL del backend la arma contra el dashboard`() {
        val url = resolveReceiptUrl(null, "abc123")
        assertEquals(ApiConstants.DASHBOARD_URL.trimEnd('/') + "/receipts/public/abc123", url)
    }

    @Test
    fun `sin llave y sin URL no hay QR`() {
        assertNull(resolveReceiptUrl(null, null))
    }

    @Test
    fun `strings en blanco cuentan como ausentes`() {
        assertNull(resolveReceiptUrl("", ""))
        assertNull(resolveReceiptUrl("   ", "  "))
        // URL en blanco pero con llave → cae al respaldo, no devuelve la cadena vacía.
        assertTrue(resolveReceiptUrl("", "abc123")!!.endsWith("/receipts/public/abc123"))
    }

    // MARK: - REGRESIÓN: nunca volver a la página sin facturación

    @Test
    fun `el respaldo NUNCA arma la URL legacy del API`() {
        val url = resolveReceiptUrl(null, "abc123")!!
        assertFalse("El QR volvió a apuntar al API en vez del dashboard: $url", url.contains(legacyMarker))
        assertFalse("El QR apunta al host del API: $url", url.startsWith(ApiConstants.BASE_URL))
        assertTrue(url.contains("/receipts/public/"))
    }

    @Test
    fun `el respaldo no duplica la diagonal si la base trae una al final`() {
        // Blindaje por si alguien edita DASHBOARD_URL y le deja "/" al final.
        assertFalse(resolveReceiptUrl(null, "abc123")!!.contains("//receipts"))
    }

    // MARK: - extractReceiptUrlFromResponse

    @Test
    fun `saca la receiptUrl del pago de una orden`() {
        val body = """{"payment":{"digitalReceipt":{"accessKey":"k1","receiptUrl":"https://dashboard.avoqado.io/receipts/public/k1"}}}"""
        assertEquals("https://dashboard.avoqado.io/receipts/public/k1", OrderRepository.extractReceiptUrlFromResponse(body))
    }

    @Test
    fun `saca la receiptUrl del cobro fast`() {
        val body = """{"data":{"digitalReceipt":{"accessKey":"k2","receiptUrl":"https://dashboard.avoqado.io/receipts/public/k2"}}}"""
        assertEquals("https://dashboard.avoqado.io/receipts/public/k2", OrderRepository.extractReceiptUrlFromResponse(body))
    }

    @Test
    fun `saca la receiptUrl cuando el recibo viene en la raiz`() {
        val body = """{"digitalReceipt":{"accessKey":"k3","receiptUrl":"https://dashboard.avoqado.io/receipts/public/k3"}}"""
        assertEquals("https://dashboard.avoqado.io/receipts/public/k3", OrderRepository.extractReceiptUrlFromResponse(body))
    }

    @Test
    fun `sin receiptUrl devuelve null y no truena`() {
        // Backend viejo: manda la llave pero no la URL. Debe degradar al respaldo, no romper.
        val body = """{"data":{"digitalReceipt":{"accessKey":"k4"}}}"""
        assertNull(OrderRepository.extractReceiptUrlFromResponse(body))
        assertEquals("k4", OrderRepository.extractReceiptAccessKeyFromResponse(body))
        assertTrue(
            resolveReceiptUrl(
                OrderRepository.extractReceiptUrlFromResponse(body),
                OrderRepository.extractReceiptAccessKeyFromResponse(body),
            )!!.endsWith("/receipts/public/k4"),
        )
    }

    @Test
    fun `un cuerpo que no es JSON no truena`() {
        assertNull(OrderRepository.extractReceiptUrlFromResponse("no soy json"))
        assertNull(OrderRepository.extractReceiptUrlFromResponse(""))
    }

    // MARK: - REGRESIÓN: no rompí la extracción del accessKey que ya existía

    @Test
    fun `el accessKey se sigue extrayendo de las tres formas`() {
        assertEquals("k1", OrderRepository.extractReceiptAccessKeyFromResponse("""{"payment":{"digitalReceipt":{"accessKey":"k1"}}}"""))
        assertEquals("k2", OrderRepository.extractReceiptAccessKeyFromResponse("""{"data":{"digitalReceipt":{"accessKey":"k2"}}}"""))
        assertEquals("k3", OrderRepository.extractReceiptAccessKeyFromResponse("""{"digitalReceipt":{"accessKey":"k3"}}"""))
    }
}
