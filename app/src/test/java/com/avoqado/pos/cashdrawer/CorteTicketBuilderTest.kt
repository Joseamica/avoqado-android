package com.avoqado.pos.cashdrawer

import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.cashdrawer.data.CorteTicketBuilder
import com.avoqado.pos.cashdrawer.data.model.CashDrawerEventEntity
import com.avoqado.pos.cashdrawer.data.model.CashDrawerEventType
import com.avoqado.pos.cashdrawer.data.model.CashDrawerSessionEntity
import com.avoqado.pos.printing.data.model.PaperWidth
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lo que sale IMPRESO en el corte.
 *
 * Estos tests existen porque el corte parcial y la impresión del corte se
 * escribieron sin una impresora enfrente: leen los bytes ESC/POS que se le
 * mandan al papel, que es lo único que el cajero va a tener en la mano. Un
 * "compila" no dice nada sobre si el ticket miente.
 */
class CorteTicketBuilderTest {

    private val abierta = CashDrawerSessionEntity(
        id = "s1",
        venueId = "v1",
        deviceName = "Caja 1",
        openedByStaffId = "st1",
        openedByName = "Main Owner",
        openedAt = 1_785_000_000_000L,
        startingAmountCents = 50_000,
    )

    private val cerrada = abierta.copy(
        closedAt = 1_785_010_000_000L,
        actualAmountCents = 55_000,
    )

    private fun evento(type: CashDrawerEventType, cents: Int) = CashDrawerEventEntity(
        id = "e-${type.name}-$cents",
        sessionId = "s1",
        venueId = "v1",
        type = type.name,
        amountCents = cents,
        note = null,
        staffId = "st1",
        staffName = "Main Owner",
        createdAt = 1_785_005_000_000L,
    )

    private val eventos = listOf(
        evento(CashDrawerEventType.CASH_SALE, 3_000),
        evento(CashDrawerEventType.CASH_SALE, 2_000),
        evento(CashDrawerEventType.PAY_IN, 1_000),
        evento(CashDrawerEventType.PAY_OUT, 1_000),
    )

    private val tenders = listOf(
        CashDrawerRepository.TenderRow("CASH", 5_000),
        CashDrawerRepository.TenderRow("CREDIT_CARD", 12_000),
        CashDrawerRepository.TenderRow("DEBIT_CARD", 3_000),
        CashDrawerRepository.TenderRow("BANK_TRANSFER", 800),
    )

    private fun papel(
        session: CashDrawerSessionEntity = cerrada,
        tenders: List<CashDrawerRepository.TenderRow> = this.tenders,
        isPartial: Boolean = false,
    ): String = String(
        CorteTicketBuilder.build(
            session = session,
            events = eventos,
            tenders = tenders,
            venueName = "Restaurante El Atole",
            paperWidth = PaperWidth.MM80,
            isPartial = isPartial,
        ),
        Charsets.ISO_8859_1,
    )

    // MARK: - Corte parcial (lectura X)

    /**
     * Sin esta marca, dos tickets del mismo día se confunden y alguien cuadra
     * su turno contra el equivocado.
     */
    @Test
    fun `el corte parcial se identifica y avisa que la caja sigue abierta`() {
        val t = papel(session = abierta, isPartial = true)
        assertTrue("debe titularse parcial", t.contains("CORTE PARCIAL"))
        assertTrue("debe avisar que no cerró", t.contains("LA CAJA SIGUE ABIERTA"))
    }

    /**
     * El dinero todavía no se cuenta: imprimir un "Faltante" aquí le inventaría
     * un descuadre a alguien que no ha hecho nada mal.
     */
    @Test
    fun `el corte parcial NO imprime conteo real ni diferencia`() {
        val t = papel(session = abierta, isPartial = true)
        assertFalse("no debe haber conteo", t.contains("Conteo real"))
        assertFalse("no debe acusar faltante", t.contains("Faltante"))
        assertFalse("ni sobrante", t.contains("Sobrante"))
        assertTrue("sí dice cuánto debería haber", t.contains("Efectivo esperado"))
        assertTrue("y qué hacer después", t.contains("cierra la caja"))
    }

    // MARK: - Corte definitivo (Z)

    @Test
    fun `el corte definitivo sí lleva conteo y diferencia`() {
        val t = papel()
        assertTrue(t.contains("CORTE DE CAJA"))
        assertFalse(t.contains("LA CAJA SIGUE ABIERTA"))
        assertTrue(t.contains("Conteo real"))
        // esperado = 500 inicial + 50 ventas + 10 ingreso - 10 egreso = 550;
        // contado 550 → sin diferencia
        assertTrue("cuadre exacto", t.contains("Diferencia"))
    }

    @Test
    fun `un faltante se nombra faltante`() {
        val t = papel(session = cerrada.copy(actualAmountCents = 54_000))
        assertTrue(t.contains("Faltante"))
        assertFalse(t.contains("Sobrante"))
    }

    // MARK: - Desglose por método

    /**
     * El bug original: el ticket colapsaba todo en Efectivo/Tarjeta/Otros, así
     * que débito y crédito quedaban sumados y una transferencia desaparecía
     * dentro de "Otros" — justo lo que hace falta para cuadrar con el banco.
     */
    @Test
    fun `el desglose imprime un renglon por metodo real`() {
        val t = papel()
        assertTrue(t.contains("Efectivo"))
        assertTrue(t.contains("Tarjeta de crédito"))
        assertTrue(t.contains("Tarjeta de débito"))
        assertTrue(t.contains("Transferencia"))
        assertFalse("ya no debe existir la cubeta genérica", t.contains("Otros"))
    }

    @Test
    fun `el desglose va ordenado de mayor a menor`() {
        val t = papel()
        val credito = t.indexOf("Tarjeta de crédito")
        val efectivo = t.indexOf("Efectivo esperado").let { t.indexOf("Efectivo") }
        val debito = t.indexOf("Tarjeta de débito")
        val transferencia = t.indexOf("Transferencia")
        assertTrue("crédito (120) antes que efectivo (50)", credito < efectivo)
        assertTrue("efectivo (50) antes que débito (30)", efectivo < debito)
        assertTrue("débito (30) antes que transferencia (8)", debito < transferencia)
    }

    // MARK: - Sin conexión

    /**
     * Sin el desglose del server el POS NO sabe cuánto se cobró con tarjeta.
     * Imprimir "Tarjeta $0.00" sería mentir, y el dueño cerraría el turno
     * creyendo que no hubo un solo cobro con terminal.
     */
    @Test
    fun `sin desglose del server el ticket lo dice en vez de inventar ceros`() {
        val t = papel(tenders = emptyList())
        assertFalse("no debe afirmar que no hubo tarjeta", t.contains("Tarjeta de crédito"))
        assertTrue("avisa por qué falta", t.contains("Sin conexión"))
        assertTrue("y que aparecerá luego", t.contains("recuperar la conexión"))
        assertTrue("el efectivo sí es confiable", t.contains("Efectivo"))
        assertTrue("y el resumen se etiqueta como parcial", t.contains("(EFECTIVO)"))
    }

    // MARK: - Higiene del ticket

    @Test
    fun `el ticket corta el papel al final`() {
        // Sin el corte, el siguiente ticket sale pegado al anterior y el cajero
        // termina rasgándolo a mano sobre el mostrador.
        val bytes = CorteTicketBuilder.build(
            session = cerrada,
            events = eventos,
            tenders = tenders,
            venueName = "Restaurante El Atole",
            paperWidth = PaperWidth.MM80,
            isPartial = false,
        )
        val cut = byteArrayOf(0x1D, 0x56)
        val hasCut = bytes.toList().windowed(2).any { it[0] == cut[0] && it[1] == cut[1] }
        assertTrue("debe llevar el comando de corte", hasCut)
    }

    @Test
    fun `los acentos viajan en el juego de caracteres de la impresora`() {
        // La app manda Latin-1 con la página de códigos 16; si alguien cambiara el
        // encoding, "Tarjeta de crédito" saldría como "Tarjeta de crÃ©dito".
        val bytes = CorteTicketBuilder.build(
            session = cerrada,
            events = eventos,
            tenders = tenders,
            venueName = "Café Ñandú",
            paperWidth = PaperWidth.MM80,
            isPartial = false,
        )
        val texto = String(bytes, Charsets.ISO_8859_1)
        assertTrue("el nombre del local conserva sus acentos", texto.contains("Café Ñandú"))
    }
}
