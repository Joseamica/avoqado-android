package com.avoqado.pos.giftcards

import com.avoqado.pos.giftcards.domain.SolicitudDeTarjeta
import com.avoqado.pos.orders.data.model.OrderDetail
import com.avoqado.pos.payment.presentation.solicitudesDeLaCuenta
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Igualdad de claves real + guard de cableado; sin arnés Compose, visibilidad física pendiente ROOT R1. */
class RecargaDeLaMismaCuentaR5Test {
    private val orden = OrderDetail(id = "A", total = 100.0, esCuentaDeTarjetas = true)
    private val propia = fila("a", "A")
    private val otra = fila("b", "B")
    private fun fila(llave: String, orderId: String, venueId: String = "venue") = SolicitudDeTarjeta(
        tipo = "GIFT_CARD_REDEEM", llave = llave, venueId = venueId, creadaEn = 1L,
        orderId = orderId, cuerpo = "{}", porTerminar = true, restanteCents = 10000,
    )
    private inner class Pantalla(filas: List<SolicitudDeTarjeta>) {
        private var clave = orden to solicitudesDeLaCuenta(filas, "venue", "A")
        var cargas = 0
        var detalle: String? = "Detalle de A"
        var dialogo: String? = "Cancelar A"
        val motivo = "El cliente cambió de opinión"
        fun emitir(filas: List<SolicitudDeTarjeta>, order: OrderDetail = orden) {
            val nueva = order to solicitudesDeLaCuenta(filas, "venue", "A")
            if (clave != nueva) { cargar(); clave = nueva }
        }
        private fun cargar() { cargas++; detalle = null; dialogo = null }
        fun sigueAbierta() {
            assertEquals("No se llamó cargar de A", 0, cargas)
            assertEquals("Detalle de A", detalle)
            assertEquals("Cancelar A", dialogo)
            assertEquals("El cliente cambió de opinión", motivo)
        }
    }
    @Test fun `resolver o cambiar B conserva detalle dialogo y motivo de A`() {
        val p = Pantalla(listOf(propia, otra))
        p.emitir(listOf(propia, otra.copy(mandado = true)))
        p.sigueAbierta()
        p.emitir(listOf(propia)) // El resolutor quitó B al volver la red.
        p.sigueAbierta()
    }
    @Test fun `cambiar agregar o quitar fila propia si recarga`() {
        val p = Pantalla(listOf(propia, otra))
        p.emitir(listOf(propia.copy(restanteCents = 5000), otra))
        assertEquals(1, p.cargas)
        p.emitir(listOf(otra))
        assertEquals(2, p.cargas)
        p.emitir(listOf(propia, otra))
        assertEquals(3, p.cargas)
    }
    @Test fun `misma order recompuesta por contenido no recarga`() {
        val p = Pantalla(listOf(propia, otra))
        p.emitir(listOf(propia.copy(), otra.copy()), orden.copy())
        p.sigueAbierta()
    }
    @Test fun `cambiar campos de la propia orden si recarga aunque conserve id`() {
        val p = Pantalla(listOf(propia, otra))
        p.emitir(listOf(propia, otra), orden.copy(total = 50.0))
        assertEquals(1, p.cargas)
    }
    @Test fun `sin fila propia cambiar otras cuentas no recarga`() {
        val p = Pantalla(listOf(otra))
        p.emitir(listOf(otra.copy(porTerminar = false)))
        p.sigueAbierta()
        p.emitir(emptyList())
        p.sigueAbierta()
    }
    @Test fun `otro venue con mismo orderId y orden de la lista son ajenos`() {
        val ajena = fila("ajena", "A", "otro-venue")
        val propia2 = fila("a2", "A")
        val p = Pantalla(listOf(propia, ajena, propia2, otra))
        p.emitir(listOf(otra, propia2.copy(), ajena.copy(mandado = true), propia.copy()))
        p.sigueAbierta()
        assertEquals(setOf(propia, propia2), solicitudesDeLaCuenta(listOf(otra, propia, ajena, propia2), "venue", "A"))
        assertTrue(solicitudesDeLaCuenta(listOf(propia), null, "A").isEmpty())
    }
    @Test fun `efecto usa proyeccion propia y order completa sin monitor ni diario global`() {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        val ruta = "app/src/main/java/com/avoqado/pos/payment/presentation/CobroDeCuentaScreen.kt"
        var codigo: String? = null
        while (dir != null && codigo == null) {
            val file = File(dir, ruta)
            if (file.isFile) codigo = file.readText()
            dir = dir.parentFile
        }
        val limpio = requireNotNull(codigo).replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "").replace(Regex("""\s+"""), "")
        assertTrue(limpio.contains("valsolicitudesPropias=solicitudesDeLaCuenta(solicitudes,viewModel.venueActual,order.id)"))
        assertTrue(limpio.contains("LaunchedEffect(order,solicitudesPropias){if(enLinea)viewModel.ofertaParaCobrar(order.id,"))
        assertFalse(limpio.contains("LaunchedEffect(order,solicitudes)"))
    }
}
