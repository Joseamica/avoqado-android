package com.avoqado.pos.transactions

import com.avoqado.pos.customers.presentation.telefonoIncompleto
import com.avoqado.pos.transactions.data.model.*
import com.avoqado.pos.transactions.presentation.exchange.ExchangeSettlement
import com.avoqado.pos.transactions.presentation.exchange.TextosCambio
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** A5 (SAMSUNG, 1-nov): lo que el recorrido en el aparato encontró. W-1, W-3, O-1 y O-2; O-3 vive con los modelos de cada pantalla. */
class PulidoA5Test {

    @Test fun `W-1 el teléfono obligatorio dice que le faltan dígitos sólo cuando lo tecleado no alcanza`() {
        assertEquals("10 a 15 dígitos", TextosCambio.TELEFONO_DIGITOS)
        assertTrue(telefonoIncompleto("554000123", requirePhone = true))
        assertFalse(telefonoIncompleto("5540001234", requirePhone = true))
        assertFalse("vacío todavía no es error: la ayuda ya dice qué falta", telefonoIncompleto("", requirePhone = true))
        assertFalse("sin teléfono obligatorio no se marca nada", telefonoIncompleto("554000123", requirePhone = false))
    }

    @Test fun `W-3 una devolución a saldo a favor se llama Saldo a favor y dice a quién`() {
        val aSaldo = Transaction("refund", method = "OTHER", storeCredit = StoreCreditAbono(customerName = "Ana López", amountCents = 15000))
        assertEquals(TextosCambio.SALDO_A_FAVOR, aSaldo.methodDescription)
        assertEquals("Ana López", aSaldo.clienteVisible)
        // La destinataria del saldo manda sobre la clienta de la venta; sin saldo, como siempre.
        assertEquals("Ana López", aSaldo.copy(customerName = "Mamá").clienteVisible)
        val otro = Transaction("pago", method = "OTHER", customerName = "Mamá")
        assertEquals("Otro", otro.methodDescription)
        assertEquals("Mamá", otro.clienteVisible)
        assertNull(Transaction("pago").clienteVisible)
    }

    @Test fun `O-1 lo que se ve elegido se queda al marcar Es por defecto, y sólo se mueve si ya no se ofrece`() {
        val saldo = OpcionDeDevolucion(DEVOLVER_CON_SALDO, TextosCambio.SALDO_A_FAVOR)
        val efectivo = opcionesParaDevolver("CASH", null)
        // «Sólo saldo a favor» + Productos devueltos: sólo saldo; lo que se ve elegido es el saldo.
        var enPantalla = devolucionEscogida(opcionesConSaldo(efectivo, efectivo.first(), saldo, restringe = true), null)
        assertEquals(DEVOLVER_CON_SALDO, enPantalla)
        // «Es por defecto»: vuelven todas; el saldo se sigue ofreciendo, así que se queda.
        enPantalla = devolucionEscogida(opcionesConSaldo(efectivo, efectivo.first(), saldo, restringe = false), enPantalla)
        assertEquals(DEVOLVER_CON_SALDO, enPantalla)
        // La hoja guarda lo que se ve (no sólo el último toque): si no, el toque vacío regresaba a «Efectivo de la caja».
        val hoja = File(raiz(), "app/src/main/java/com/avoqado/pos/transactions/presentation/IssueRefundSheet.kt").readText()
        assertTrue(hoja.contains("SideEffect { if (devolverConAMano != devolverCon) devolverConAMano = devolverCon }"))
    }

    @Test fun `O-2 la destinataria sin elegir no repite el motivo del botón`() {
        assertEquals("Saldo a favor para: sin elegir", ExchangeSettlement.storeCreditCustomerLabel(null))
        assertNotEquals(TextosCambio.SALDO_ELIGE_CLIENTE, ExchangeSettlement.storeCreditCustomerLabel(null))
        assertEquals(TextosCambio.SALDO_ELIGE_CLIENTE, ExchangeSettlement.storeCreditBlock(online = true, customer = null))
    }

    @Test fun `W-2 lo tecleado en el alta sobrevive a girar`() {
        val alta = File(raiz(), "app/src/main/java/com/avoqado/pos/customers/presentation/CreateCustomerView.kt").readText()
        for (campo in listOf("firstName", "lastName", "phone", "email")) {
            assertTrue(campo, Regex("""var $campo by rememberSaveable \{""").containsMatchIn(alta))
        }
    }

    private fun raiz(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "app/src/main/java").isDirectory) return dir
            dir = dir.parentFile
        }
        throw AssertionError("No se encontró la raíz del repo")
    }
}
