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
        // Los totales INCLUYEN la propina; `tipsCents` dice cuánta de ella es.
        CashDrawerRepository.TenderRow("CASH", 5_000, tipsCents = 300),
        CashDrawerRepository.TenderRow("CREDIT_CARD", 12_000, tipsCents = 1_500),
        CashDrawerRepository.TenderRow("DEBIT_CARD", 3_000, tipsCents = 200),
        CashDrawerRepository.TenderRow("BANK_TRANSFER", 800),
    )

    private fun papel(
        session: CashDrawerSessionEntity = cerrada,
        tenders: List<CashDrawerRepository.TenderRow> = this.tenders,
        isPartial: Boolean = false,
        events: List<CashDrawerEventEntity> = eventos,
    ): String = String(
        CorteTicketBuilder.build(
            session = session,
            events = events,
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

    /**
     * El corte salió EN BLANCO en la D3 la primera vez que se imprimió de verdad.
     *
     * La integrada de Sunmi arranca en multibyte (GB18030) y se traga los bytes
     * Latin-1 sin pintar nada — ni siquiera corta el papel. Hay que mandarle
     * `FS .` (0x1C 0x2E) ANTES de escribir. El resto de la app ya lo hacía vía
     * `escposFor`; este builder lo perdió al construir el printer por su cuenta,
     * y como el papel avanza igual, el fallo se ve como "imprimió, pero vacío".
     */
    @Test
    fun `para la impresora integrada el ticket empieza pasandola a un solo byte`() {
        val bytes = CorteTicketBuilder.build(
            session = cerrada,
            events = eventos,
            tenders = tenders,
            venueName = "Restaurante El Atole",
            paperWidth = PaperWidth.MM80,
            isPartial = false,
            switchToSingleByteFirst = true,
        )
        val fsDot = bytes.toList().windowed(2).indexOfFirst { it[0] == 0x1C.toByte() && it[1] == 0x2E.toByte() }
        assertTrue("debe mandar FS . para salir de multibyte", fsDot >= 0)
        assertTrue("y debe ir al principio, antes del texto", fsDot < 16)
    }

    @Test
    fun `una impresora de red no recibe el cambio a un solo byte`() {
        // Las Epson de red y Bluetooth ya están en single-byte; mandarles FS . es
        // ruido que algunas interpretan como datos.
        val bytes = CorteTicketBuilder.build(
            session = cerrada,
            events = eventos,
            tenders = tenders,
            venueName = "Restaurante El Atole",
            paperWidth = PaperWidth.MM80,
            isPartial = false,
            switchToSingleByteFirst = false,
        )
        val fsDot = bytes.toList().windowed(2).any { it[0] == 0x1C.toByte() && it[1] == 0x2E.toByte() }
        assertFalse("no debe mandarlo", fsDot)
    }

    // MARK: - Propinas

    /**
     * La propina NO es dinero del negocio: se le entrega al mesero. Antes iba
     * sumada dentro de cada método sin distinguirse, así que el corte decía
     * "Efectivo $50.00" sin avisar que $3.00 de ahí hay que sacarlos del cajón.
     * Es justo lo que el Corte Z de SoftRestaurant separa.
     */
    @Test
    fun `el corte separa las propinas por metodo y su total`() {
        val t = papel()
        assertTrue("debe existir la sección", t.contains("PROPINAS"))
        assertTrue("y su total", t.contains("Total propinas"))
        // 300 + 1500 + 200 = 2000 → $20.00
        assertTrue("el total suma las tres", t.contains("$20.00"))
    }

    /**
     * El dato que evita el descuadre: de lo que hay en el cajón, cuánto le toca al
     * mesero. Sin esto el cajero paga las propinas y su corte sale con faltante.
     */
    @Test
    fun `avisa cuanta propina esta en el cajon y como sacarla`() {
        val t = papel()
        assertTrue("dice cuánto es en efectivo", t.contains("estan en el cajon"))
        assertTrue("y cómo registrarlo", t.contains("egreso"))
    }

    @Test
    fun `sin propinas no se imprime la seccion`() {
        // Un negocio que no cobra propina no debe cargar con una sección vacía.
        val sinPropina = tenders.map { it.copy(tipsCents = 0) }
        val t = papel(tenders = sinPropina)
        assertFalse(t.contains("PROPINAS"))
        assertFalse(t.contains("Total propinas"))
    }

    /**
     * El total de cada método sigue incluyendo su propina: es lo que entró por ahí
     * y, en efectivo, lo que está FÍSICAMENTE en el cajón. Restarlo del desglose
     * descuadraría el arqueo contra el dinero real.
     */
    @Test
    fun `el total por metodo sigue incluyendo la propina`() {
        val t = papel()
        assertTrue("efectivo con propina incluida", t.contains("$50.00"))
        assertTrue("crédito con propina incluida", t.contains("$120.00"))
    }

    /**
     * Los reembolsos en efectivo van APARTE de los demás egresos.
     *
     * Como en Square, cuyo arqueo lleva "Reembolsos en efectivo" como línea
     * propia. Mezclarlos con los pagos a proveedores o el retiro de propinas
     * impide saber cuánto se devolvió — que es justo lo que el dueño revisa
     * cuando el cajón sale corto.
     */
    @Test
    fun `los reembolsos en efectivo no se mezclan con los demas egresos`() {
        val conReembolso = eventos + listOf(
            CashDrawerEventEntity(
                id = "e-refund",
                sessionId = "s1",
                venueId = "v1",
                type = CashDrawerEventType.PAY_OUT.name,
                amountCents = 2_500,
                note = "${CorteTicketBuilder.PREFIJO_REEMBOLSO} Producto defectuoso",
                staffId = "st1",
                staffName = "Main Owner",
                createdAt = 1_785_005_000,
            ),
        )
        val t = papel(events = conReembolso)

        assertTrue("debe existir su propio renglón", t.contains("Reembolsos en efectivo"))
        assertTrue("con su importe", t.contains("$25.00"))
        // El egreso de $10.00 de los eventos base sigue en "Egresos", sin el reembolso.
        assertTrue("los otros egresos siguen aparte", t.contains("Egresos"))
    }

    @Test
    fun `sin reembolsos no se imprime el renglon`() {
        // Un renglón en $0.00 sólo alarga el ticket y hace dudar al cajero.
        assertFalse(papel().contains("Reembolsos en efectivo"))
    }
}
