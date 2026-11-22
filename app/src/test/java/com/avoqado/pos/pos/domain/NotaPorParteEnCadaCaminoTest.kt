package com.avoqado.pos.pos.domain

import com.avoqado.pos.kds.data.parseKdsModifiers
import com.avoqado.pos.payment.domain.buildCounterCoursePedidos
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.data.model.SelectedModifier
import com.avoqado.pos.pos.data.model.ServiceCourseSnapshot
import com.avoqado.pos.printing.data.ESCPOSPrinter
import com.avoqado.pos.printing.data.model.KitchenItem
import com.avoqado.pos.printing.data.model.KitchenTicketData
import com.avoqado.pos.printing.data.model.PaperWidth
import com.avoqado.pos.printing.routing.PrintConfig
import com.avoqado.pos.printing.routing.PrintRoutingMapper
import com.avoqado.pos.printing.routing.RoutableItem
import com.avoqado.pos.tables.data.OrderDetailItem
import com.avoqado.pos.tables.data.OrderDetailModifier
import com.avoqado.pos.tables.data.buildRepeatedTableLines
import com.avoqado.pos.tables.data.renglonDeCocinaDeRonda
import com.avoqado.pos.tables.presentation.TableOrderViewModel
import java.util.Date
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D40 · pasada exhaustiva de la familia «la etiqueta de la parte» (Codex r2, decisión A): por CADA camino por donde viaja la
 * etiqueta de cocina, la nota de la parte llega pegada a su parte y, sin nota, la etiqueta es el nombre tal cual (como antes).
 */
class NotaPorParteEnCadaCaminoTest {

    private val consome = SelectedModifier("g-sopa", "Sopa", "consome", "Consomé", 0, note = "sin cebolla")
    private val cochinita = SelectedModifier("g-flautas", "Flautas", "cochinita", "Flautas Cochinita", 0, note = "salsa combinada")
    private val agua = SelectedModifier("g-bebida", "Bebida", "agua", "Agua", 0)
    private fun paquete(vararg partes: SelectedModifier, id: String = "l-1") = CartItem(
        id = id, type = CartItemType.ProductItem("paquete"), name = "Paquete Completo", unitPrice = 15000, selectedModifiers = partes.toList(),
    )
    private val esperado = listOf("Consomé\nNota: sin cebolla", "Flautas Cochinita\nNota: salsa combinada", "Flautas Cochinita\nNota: salsa combinada", "Agua")

    @Test
    fun `P1 Mostrador con tiempos arma la comanda con la nota de cada parte`() {
        val pedidos = buildCounterCoursePedidos(listOf(paquete(consome, cochinita, cochinita, agua).copy(
            serviceCourse = ServiceCourseSnapshot(id = "c1", label = "Aperitivos", kind = "STANDARD"),
        )))!!
        assertEquals(esperado, pedidos.single().lines.single().modifiers)
    }

    @Test
    fun `P1 la ronda de Mesas arma su comanda con la nota de cada parte`() {
        val linea = TableOrderViewModel.PendingLine(item = paquete(consome, cochinita, cochinita, agua), course = null, externalId = "sync:r:0")
        assertEquals(esperado, renglonDeCocinaDeRonda(linea).modifiers)
    }

    @Test
    fun `P1 el ruteo junta renglones con la misma nota y separa los que solo cambian la nota de una parte`() {
        fun renglon(id: String, vararg partes: SelectedModifier) =
            RoutableItem(id, "paquete", null, "Paquete Completo", 1, partes.toList().etiquetasEnCocina(), null)
        val ruteo = PrintRoutingMapper.rutear(
            listOf(renglon("a", consome, agua), renglon("b", consome, agua), renglon("c", consome.copy(note = "con cebolla"), agua)),
            PrintConfig(), omitirSinComanda = false,
        )
        val lineas = ruteo.plans.flatMap { it.lines }
        assertEquals(2, lineas.size)
        assertTrue(lineas.any { it.quantity == 2 && "Consomé\nNota: sin cebolla" in it.modifiers })
        assertTrue(lineas.any { it.quantity == 1 && "Consomé\nNota: con cebolla" in it.modifiers })
    }

    @Test
    fun `P1 la pantalla de cocina lee del SERVIDOR la etiqueta tal cual`() {
        assertEquals(listOf("Consomé\nNota: sin cebolla", "Agua"), parseKdsModifiers(JSONArray(listOf("Consomé\nNota: sin cebolla", "Agua"))))
    }

    @Test
    fun `P1 el papel pinta la nota en su propia linea debajo de su parte y sin nota sale como antes`() {
        val ticket = KitchenTicketData(
            orderNumber = "101", orderType = "En tienda", timestamp = Date(0),
            items = listOf(KitchenItem("Paquete Completo", 1, juntarExtrasRepetidos(esperado))),
        )
        val texto = String(ESCPOSPrinter(PaperWidth.MM80).generateKitchenTicket(ticket), Charsets.ISO_8859_1)
        val lineas = texto.split("\n")
        val i = lineas.indexOfFirst { it.endsWith("   -> 2x Flautas Cochinita") }
        assertTrue("la parte sale sola en su renglón", i >= 0)
        assertTrue("su nota, en el renglón de abajo", lineas[i + 1].contains("      Nota: salsa combinada"))
        assertTrue(lineas.any { it.endsWith("   -> Agua") })
        assertTrue("ninguna etiqueta cruda con el salto adentro", lineas.none { it.endsWith("Consomé") && it.contains("Nota") })
    }

    @Test
    fun `P1 reimprimir desde la cuenta y repetir un tiempo conservan la nota que devolvio el SERVIDOR`() {
        val delServidor = listOf(OrderDetailModifier("consome", "Consomé", 0.0, notes = "sin cebolla"), OrderDetailModifier("agua", "Agua", 0.0))
        assertEquals(listOf("Consomé\nNota: sin cebolla", "Agua"), delServidor.map { etiquetaEnCocina(it.name, it.notes) })
        val repetida = buildRepeatedTableLines(listOf(OrderDetailItem(id = "oi", productId = "paquete", modifiers = delServidor))).single()
        assertEquals(listOf("Consomé\nNota: sin cebolla", "Agua"), repetida.selectedModifiers.etiquetasEnCocina())
    }

    @Test
    fun `P1 la caja lee la nota en un renglon y el cliente no la ve`() {
        val item = paquete(consome, cochinita, cochinita, agua)
        assertEquals("Consomé (Nota: sin cebolla), 2x Flautas Cochinita (Nota: salsa combinada), Agua", item.modifiersSummaryEnCaja)
        assertEquals("Consomé, 2x Flautas Cochinita, Agua", item.modifiersSummary)
    }
}
