package com.avoqado.pos.payment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * D12 (T12b): el cableado Compose que no se puede probar sin arnés de UI (este módulo no tiene pruebas Compose ni Robolectric),
 * fijado sobre el texto fuente al estilo de `RecargaDeLaMismaCuentaR5Test`. Las reglas que decide cada cable tienen su prueba pura
 * en `PedidoSinRedTest` y `CobroDeCuentaSinRedTest`.
 */
class CableadoDelCobroAbiertoTest {
    private fun fuente(ruta: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var codigo: String? = null
        while (dir != null && codigo == null) {
            File(dir, ruta).takeIf { it.isFile }?.let { codigo = it.readText() }
            dir = dir.parentFile
        }
        return requireNotNull(codigo) { "no encontré $ruta" }.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "").replace(Regex("""\s+"""), "")
    }

    private val cobro by lazy { fuente("app/src/main/java/com/avoqado/pos/payment/presentation/CobroDeCuentaScreen.kt") }
    private val pedidos by lazy { fuente("app/src/main/java/com/avoqado/pos/orders/presentation/OrdersScreen.kt") }
    private fun String.veces(texto: String) = windowed(texto.length).count { it == texto }

    /** M-n1: lo que arregla I1 es que los dos cobros se pinten ANTES de la salida temprana de las acciones. */
    @Test fun `los dos cobros abiertos van antes de la salida temprana`() {
        val salida = cobro.indexOf("if(!pinta.acciones)return")
        assertTrue(salida > 0)
        assertTrue(cobro.indexOf("if(pinta.cobroEnLinea)") in 0 until salida)
        assertTrue(cobro.indexOf("pinta.cobroSinRed?.let") in 0 until salida)
    }

    /** M-n2: al cerrar el cobro en línea se vuelve a pedir la OFERTA (que también recarga), no sólo la cuenta. */
    @Test fun `al cerrar el cobro en linea se refresca la oferta`() {
        val enLinea = cobro.substring(cobro.indexOf("if(pinta.cobroEnLinea)"), cobro.indexOf("pinta.cobroSinRed?.let"))
        assertTrue(enLinea.contains("scope.launch{if(enLinea)viewModel.ofertaParaCobrar(order.id,cuentaDelCarrito()==order.id)}"))
        assertFalse(enLinea.contains("viewModel.cargar("))
    }

    /** O2 y O3: el cobro con la copia sobrevive a reconstruir la Activity, y su revalidación compara el saldo del carrito. */
    @Test fun `el cobro con la copia se guarda y revalida con el saldo del carrito`() {
        assertTrue(cobro.contains("varcobroSinRedbyrememberSaveable(order.id){mutableStateOf<Int?>(null)}"))
        assertTrue(cobro.contains("antesDeRecibirEfectivo={viewModel.revalidarCobroSinRed(orderId,cents)}"))
    }

    /** Puntos 1 y 2: la lista enseña el aviso del repositorio (copia, búsqueda sin red o servidor), y vacía no dice «No hay pedidos». */
    @Test fun `la lista ensena el aviso del repositorio`() {
        assertTrue(pedidos.contains("valavisoDeLaListabyviewModel.avisoDeLaLista.collectAsState()"))
        assertTrue(pedidos.contains("orders.isEmpty()->if(avisoDeLaLista==null)OrdersEmptyState()"))
        assertFalse("el texto ya no se arma aquí", pedidos.contains("copiaVistaA"))
    }

    /** Punto 4 y O1: los DOS anfitriones del detalle (teléfono y tableta) llevan la guarda del cobro abierto. */
    @Test fun `teléfono y tableta no desmontan un cobro abierto`() {
        assertEquals(2, pedidos.veces("valcobrandobycom.avoqado.pos.payment.domain.PaymentOverlayState.isPaying.collectAsState()"))
        assertEquals(2, pedidos.veces("valultimo=ultimoDetalleVisto(selectedOrder)"))
        assertEquals(2, pedidos.veces("selectedOrder,errorMessage,cobrando,ultimo)"))
        assertEquals(2, pedidos.veces("if(vaACuentaDeTarjetas(pedido,cobrando))"))
        assertEquals(0, pedidos.veces("if(pedido.esCuentaDeTarjetas"))
        assertEquals(2, pedidos.veces("avisoSinRed=errorMessage.takeIf{detalleDeLaCopia!=null||pedido!==selectedOrder}"))
    }

    /** Final fix (F3): en los DOS anfitriones la copia sólo cobra mientras el pedido está sin red (con el servidor contestando, sólo se ve). */
    @Test fun `teléfono y tableta sólo cobran con la copia mientras el pedido está sin red`() {
        assertEquals(2, pedidos.veces("copia=copiaParaCobrar(detalleDeLaCopia,detalleSinRed),"))
        assertEquals(0, pedidos.veces("copia=detalleDeLaCopia,"))
        assertEquals(2, pedidos.veces("valdetalleSinRedbyviewModel.detalleSinRed.collectAsState()"))
    }
}
