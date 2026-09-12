package com.avoqado.pos.transactions

import com.avoqado.pos.transactions.data.model.ReceiptLink
import com.avoqado.pos.transactions.data.model.ResultadoLigaRecibo
import com.avoqado.pos.transactions.data.model.TonoDelAviso
import com.avoqado.pos.transactions.data.model.avisoDeReimpresion
import com.avoqado.pos.transactions.data.model.tonoDeReimpresion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lo que se le dice al cajero cuando reimprime un ticket y el QR no pudo salir.
 *
 * 🔴 La regla que protege: **el aviso no miente**. Un 403/404/500 no es «sin conexión»; decírselo
 * manda al cajero a revisar el WiFi por un problema que está en el servidor. Y si la impresión
 * falló, manda su propio error: el QR es lo de menos cuando no salió papel.
 */
class AvisoDeReimpresionTest {

    private val ligaBuena = ResultadoLigaRecibo.Obtenida(
        ReceiptLink(accessKey = "k", receiptUrl = "https://x/receipts/public/k", autofacturaAvailable = true),
    )

    @Test
    fun `con la liga el aviso es el de siempre`() {
        assertEquals("Recibo impreso", avisoDeReimpresion("Recibo impreso", imprimio = true, liga = ligaBuena))
    }

    @Test
    fun `P1 sin red lo dice como falta de conexion`() {
        val aviso = avisoDeReimpresion("Recibo impreso", imprimio = true, liga = ResultadoLigaRecibo.SinRed)

        assertTrue(aviso.startsWith("Recibo impreso"))
        assertTrue(aviso.contains("sin conexión"))
    }

    @Test
    fun `P1 un fallo del servidor NO se llama falta de conexion`() {
        val aviso = avisoDeReimpresion("Recibo impreso", imprimio = true, liga = ResultadoLigaRecibo.FalloDelServidor(500))

        assertTrue(aviso.contains("sin QR de facturación"))
        assertFalse("el WiFi no tiene la culpa de un 500", aviso.contains("sin conexión"))
    }

    @Test
    fun `P1 si no imprimio manda el error de la impresora`() {
        val aviso = avisoDeReimpresion("La impresora no tiene papel", imprimio = false, liga = ResultadoLigaRecibo.SinRed)

        assertEquals("La impresora no tiene papel", aviso)
    }

    // MARK: - El TONO del aviso
    //
    // 🔴 Regresión encontrada en QA de hardware (Sunmi OrderPAD 3, 12-sep-2026): el sheet decidía el
    // color comparando el TEXTO contra "Recibo impreso". El aviso "Recibo impreso — sin QR de
    // facturación (sin conexión)" ya no era igual, así que caía en ROJO de error — sobre un ticket que
    // SÍ salió. La regla del workspace es explícita: sin red es un estado normal, nunca rojo.
    // Ahora el tono sale del RESULTADO (¿imprimió? ¿hubo liga?), no de cómo está escrito el mensaje.

    @Test
    fun `P1 impreso con QR es exito`() {
        assertEquals(TonoDelAviso.EXITO, tonoDeReimpresion(imprimio = true, liga = ligaBuena))
    }

    @Test
    fun `P1 impreso SIN red NO es error`() {
        // El ticket salió. Que falte el QR es un aviso, no una falla.
        assertEquals(TonoDelAviso.AVISO, tonoDeReimpresion(imprimio = true, liga = ResultadoLigaRecibo.SinRed))
    }

    @Test
    fun `P1 impreso con fallo del servidor tampoco es error`() {
        assertEquals(TonoDelAviso.AVISO, tonoDeReimpresion(imprimio = true, liga = ResultadoLigaRecibo.FalloDelServidor(500)))
    }

    @Test
    fun `P1 si no imprimio SI es error`() {
        assertEquals(TonoDelAviso.ERROR, tonoDeReimpresion(imprimio = false, liga = ligaBuena))
        assertEquals(TonoDelAviso.ERROR, tonoDeReimpresion(imprimio = false, liga = ResultadoLigaRecibo.SinRed))
    }
}
