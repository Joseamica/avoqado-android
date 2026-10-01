package com.avoqado.pos.transactions.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 🔴 Las dos reglas que deciden CUÁNTO dinero se devuelve desde la hoja de reembolso.
 *
 * Origen: Testarudo, 2026-09-11. Al revisar esa pantalla salió que el importe se
 * convertía a centavos con `(pesos * 100).toInt()`, que TRUNCA. El dashboard web y iOS
 * redondean; Android era el único que cortaba, y siempre en contra del cliente.
 */
class RefundSubmitRulesTest {

    // ── El importe se redondea, nunca se trunca ──────────────────────────────────────

    /**
     * El barrido es el que de verdad guarda esto: un puñado de ejemplos elegidos a mano
     * se puede colar entre los valores que el truncamiento sí acierta. Con `toInt()`
     * caen 1 145 de estos 20 000.
     */
    @Test
    fun `todo importe de 1 centavo a 200 pesos vuelve a sus centavos exactos`() {
        val fallos = mutableListOf<String>()
        for (centavos in 1..20_000) {
            val pesos = centavos / 100.0
            val obtenido = centavosDelImporte(pesos)
            if (obtenido != centavos) {
                fallos += "$%.2f → %d (esperado %d)".format(pesos, obtenido, centavos)
            }
        }
        assertEquals(
            "El importe se está truncando en vez de redondear; el cliente recibe de menos en: " +
                fallos.take(5).joinToString("; ") + " … (${fallos.size} importes afectados)",
            emptyList<String>(),
            fallos,
        )
    }

    @Test
    fun `los casos que el truncamiento corta en un centavo`() {
        // Cada uno reproducido: 4.35 * 100 vale 434.99999999999994 en doble.
        assertEquals(29, centavosDelImporte(0.29))
        assertEquals(113, centavosDelImporte(1.13))
        assertEquals(201, centavosDelImporte(2.01))
        assertEquals(435, centavosDelImporte(4.35))
    }

    @Test
    fun `los importes redondos no cambian`() {
        assertEquals(0, centavosDelImporte(0.0))
        assertEquals(5_000, centavosDelImporte(50.0))
        assertEquals(6_500, centavosDelImporte(65.0))
        assertEquals(53_290, centavosDelImporte(532.90))
    }

    // ── La propina: el CERO es una decisión, no un vacío ─────────────────────────────

    @Test
    fun `desmarcar la propina manda 0 y deja intacta la del mesero`() {
        assertEquals(0, tipRefundCentsParaEnvio(paymentTipAmount = 68.45, includeTip = false))
    }

    @Test
    fun `con la propina marcada no se manda nada y reparte el servidor`() {
        assertNull(tipRefundCentsParaEnvio(paymentTipAmount = 68.45, includeTip = true))
    }

    @Test
    fun `un cobro sin propina nunca manda el campo, marque lo que marque`() {
        assertNull(tipRefundCentsParaEnvio(paymentTipAmount = 0.0, includeTip = false))
        assertNull(tipRefundCentsParaEnvio(paymentTipAmount = 0.0, includeTip = true))
    }

    // ── El TOPE depende de si la propina viaja ───────────────────────────────────────
    //
    // Testarudo, 17-sep-2026: cobro CASH de $200 + $20. El cajero desmarcó «Incluir
    // propina» y dejó el importe en $220 (el máximo que la hoja mostraba INCLUÍA la
    // propina). La app mandó `amount: 22000, tipRefundCents: 0` y el servidor rechazó,
    // con razón, «Sale portion of refund (22000) exceeds original sale amount (20000)».
    // Cinco 400 seguidos; el reembolso salió 4.7 h después por otro camino.

    @Test
    fun `con la propina incluida el tope es todo lo disponible`() {
        assertEquals(220.0, topeReembolsable(remainingRefundable = 220.0, remainingRefundableSale = 200.0, paymentTipAmount = 20.0, includeTip = true), 0.0)
    }

    @Test
    fun `sin la propina el tope es la venta restante que manda el servidor`() {
        assertEquals(200.0, topeReembolsable(remainingRefundable = 220.0, remainingRefundableSale = 200.0, paymentTipAmount = 20.0, includeTip = false), 0.0)
    }

    @Test
    fun `con devoluciones previas manda la venta restante del servidor, no una resta local`() {
        // $200 + $20; ya se devolvieron $50 de venta + $5 de propina ⇒ disponible $165, venta restante $150.
        assertEquals(150.0, topeReembolsable(remainingRefundable = 165.0, remainingRefundableSale = 150.0, paymentTipAmount = 20.0, includeTip = false), 0.0)
    }

    @Test
    fun `sin la propina y con un servidor viejo (sin saldo por componente) el tope es lo disponible menos la propina`() {
        assertEquals(200.0, topeReembolsable(remainingRefundable = 220.0, remainingRefundableSale = null, paymentTipAmount = 20.0, includeTip = false), 0.0)
    }

    @Test
    fun `el tope nunca baja de cero`() {
        assertEquals(0.0, topeReembolsable(remainingRefundable = 15.0, remainingRefundableSale = null, paymentTipAmount = 20.0, includeTip = false), 0.0)
    }

    @Test
    fun `un cobro sin propina tiene el mismo tope marque lo que marque`() {
        assertEquals(80.0, topeReembolsable(remainingRefundable = 80.0, remainingRefundableSale = 80.0, paymentTipAmount = 0.0, includeTip = false), 0.0)
        assertEquals(80.0, topeReembolsable(remainingRefundable = 80.0, remainingRefundableSale = null, paymentTipAmount = 0.0, includeTip = true), 0.0)
    }

    // ── Al bajar el tope, el importe escrito se recorta; lo que cabe no se toca ──────
    //
    // Codex (20-sep-2026): «quien escribió $50 sin propina quiere devolver $50» — no se
    // resta la propina de cualquier importe manual; sólo se recorta lo que ya no cabe.

    @Test
    fun `un importe por encima del nuevo tope se recorta al tope`() {
        assertEquals("200.00", importeAjustadoAlTope(amountStr = "220", tope = 200.0))
        assertEquals("200.00", importeAjustadoAlTope(amountStr = "220,00", tope = 200.0))
    }

    @Test
    fun `un importe que cabe no se toca ni se reformatea`() {
        assertEquals("50", importeAjustadoAlTope(amountStr = "50", tope = 200.0))
        assertEquals("", importeAjustadoAlTope(amountStr = "", tope = 200.0))
        assertEquals("abc", importeAjustadoAlTope(amountStr = "abc", tope = 200.0))
    }

    // ── Reembolso por artículos: la casilla «Incluir propina» ─────────────────────────

    @Test
    fun `arranca marcada al devolver toda la venta que queda`() {
        assertTrue(propinaMarcadaPorDefecto(145.0, 145.0))
    }

    @Test
    fun `arranca desmarcada al devolver una parte`() {
        assertFalse(propinaMarcadaPorDefecto(65.0, 145.0))
    }

    @Test
    fun `uno o dos centavos de redondeo del reparto de unidades no la desmarcan`() {
        assertTrue(propinaMarcadaPorDefecto(144.99, 145.0))
        assertTrue(propinaMarcadaPorDefecto(144.98, 145.0))
    }

    @Test
    fun `tres centavos ya no son redondeo`() {
        assertFalse(propinaMarcadaPorDefecto(144.97, 145.0))
    }

    @Test
    fun `servidor viejo sin venta restante arranca desmarcada`() {
        assertFalse(propinaMarcadaPorDefecto(145.0, null))
    }

    @Test
    fun `sin articulos elegidos no arranca marcada`() {
        assertFalse(propinaMarcadaPorDefecto(0.0, 0.0))
    }

    // Firma: (marcada, propinaRestante, totalRestante, importeArticulos). Cobro de $145 + $14.50 sin acumulado
    // histórico ⇒ total restante $159.50.

    @Test
    fun `marcada manda la propina restante en centavos redondeados`() {
        assertEquals(1450, tipRefundCentsPorArticulos(true, 14.5, 159.5, 145.0))
        assertEquals(435, tipRefundCentsPorArticulos(true, 4.35, 100.0, 50.0))
    }

    @Test
    fun `desmarcada no manda el campo`() {
        assertNull(tipRefundCentsPorArticulos(false, 14.5, 159.5, 145.0))
    }

    @Test
    fun `sin propina restante no manda el campo aunque este marcada`() {
        assertNull(tipRefundCentsPorArticulos(true, null, 159.5, 145.0))
        assertNull(tipRefundCentsPorArticulos(true, 0.0, 159.5, 145.0))
    }

    @Test
    fun `sin acumulado historico la propina completa cabe y no cambia`() {
        // total = venta + propina: $159.50 − $145 = $14.50 = la propina restante.
        assertEquals(1450, tipRefundCentsPorArticulos(true, 14.5, 159.5, 145.0))
    }

    @Test
    fun `con acumulado historico sin filas la propina se topa con lo que queda del total`() {
        // $145 + $14.50 con $9.50 devueltos sin filas: el total restante es $150. Todos los artículos ($145)
        // dejan $5.00; mandar los $14.50 completos daba $159.50 y el servidor respondía 400.
        assertEquals(500, tipRefundCentsPorArticulos(true, 14.5, 150.0, 145.0))
    }

    @Test
    fun `articulos que agotan el total no mandan el campo`() {
        assertNull(tipRefundCentsPorArticulos(true, 14.5, 145.0, 145.0))
        assertNull(tipRefundCentsPorArticulos(true, 14.5, 100.0, 145.0))
    }

    @Test
    fun `el tope del total se calcula en centavos enteros`() {
        // En dobles 1.0 − 0.67 = 0.33000000000000007; en centavos son 33 exactos.
        assertEquals(33, tipRefundCentsPorArticulos(true, 14.5, 1.0, 0.67))
    }

    @Test
    fun `sin total restante conocido se comporta como antes`() {
        assertEquals(1450, tipRefundCentsPorArticulos(true, 14.5, null, 145.0))
    }

    // ── ¿Se abre en la terminal o se reembolsa como el efectivo? ─────────────────────

    /**
     * P1 Testarudo, 30-sep-2026: una transferencia de $10,334 pedía «Abrir en la terminal». Regla del
     * founder: sólo la tarjeta presente en NUESTRA terminal va a la terminal; todo lo demás (transferencia,
     * tipos de pago del negocio, tarjeta de otra terminal) se reembolsa como el efectivo. Manda el servidor.
     */
    @Test
    fun `manda el veredicto del servidor, diga lo que diga el metodo`() {
        assertTrue(seDevuelveEnTerminal(refundOnTerminal = true, method = "DEBIT_CARD"))
        // «Tarjeta de crédito» registrada a mano: el método dice tarjeta, la terminal nunca la cobró.
        assertFalse(seDevuelveEnTerminal(refundOnTerminal = false, method = "CREDIT_CARD"))
    }

    @Test
    fun `servidor anterior sin el campo cae a la regla vieja del servidor solo tarjeta`() {
        assertTrue(seDevuelveEnTerminal(refundOnTerminal = null, method = "CREDIT_CARD"))
        assertTrue(seDevuelveEnTerminal(refundOnTerminal = null, method = "DEBIT_CARD"))
        listOf("CASH", "BANK_TRANSFER", "TRANSFER", "OTHER", "DIGITAL_WALLET", "CRYPTOCURRENCY", null).forEach {
            assertFalse("$it no se abre en la terminal", seDevuelveEnTerminal(refundOnTerminal = null, method = it))
        }
    }

    // ── Devolver con (30-sep-2026) ─────────────────────────────────────────────────────

    @Test
    fun `efectivo ofrece efectivo de la caja y transferencia`() {
        assertEquals(
            listOf(OpcionDeDevolucion(null, "Efectivo de la caja"), OpcionDeDevolucion("BANK_TRANSFER", "Transferencia")),
            opcionesParaDevolver("CASH", null),
        )
    }

    @Test
    fun `transferencia ofrece transferencia y efectivo de la caja`() {
        assertEquals(
            listOf(OpcionDeDevolucion(null, "Transferencia"), OpcionDeDevolucion("CASH", "Efectivo de la caja")),
            opcionesParaDevolver("BANK_TRANSFER", "Transferencia"),
        )
    }

    @Test
    fun `un metodo propio ofrece su nombre mas las dos`() {
        assertEquals(
            listOf(
                OpcionDeDevolucion(null, "Vale de despensa"),
                OpcionDeDevolucion("CASH", "Efectivo de la caja"),
                OpcionDeDevolucion("BANK_TRANSFER", "Transferencia"),
            ),
            opcionesParaDevolver("OTHER", "Vale de despensa"),
        )
        // Sin nombre del negocio: la etiqueta de siempre del método.
        assertEquals("Otro", opcionesParaDevolver("OTHER", null).first().label)
    }

    @Test
    fun `la leyenda dice si sale de la caja`() {
        assertEquals("Sale de la caja: el corte lo descuenta solo.", leyendaDeDevolucion("BANK_TRANSFER", "CASH"))
        assertEquals("Sale de la caja: el corte lo descuenta solo.", leyendaDeDevolucion("CASH", null))
        assertEquals("Tú le haces la transferencia; aquí sólo queda registrado.", leyendaDeDevolucion("CASH", "BANK_TRANSFER"))
        assertEquals("Se devuelve por el mismo medio con que se pagó.", leyendaDeDevolucion("OTHER", null))
        // Transferencia original sin cambiar: la leyenda va por el método EFECTIVO (Codex P2 #8).
        assertEquals("Tú le haces la transferencia; aquí sólo queda registrado.", leyendaDeDevolucion("BANK_TRANSFER", null))
    }

    @Test
    fun `el aviso de solo registro sale cuando no hay terminal ni selector ni es efectivo`() {
        assertEquals(false, avisoDeSoloRegistro(enTerminal = false, hayOpciones = false, method = "CASH"))
        assertEquals(false, avisoDeSoloRegistro(enTerminal = true, hayOpciones = false, method = "CREDIT_CARD"))
        assertEquals(false, avisoDeSoloRegistro(enTerminal = false, hayOpciones = true, method = "BANK_TRANSFER"))
        assertEquals(true, avisoDeSoloRegistro(enTerminal = false, hayOpciones = false, method = "CREDIT_CARD"))
        assertEquals(true, avisoDeSoloRegistro(enTerminal = false, hayOpciones = false, method = "BANK_TRANSFER"))
    }

    @Test
    fun `una etiqueta del negocio igual a una opcion fija pasa a original`() {
        val opciones = opcionesParaDevolver("OTHER", "Efectivo de la caja")
        assertEquals("Efectivo de la caja (original)", opciones.first().label)
        assertEquals(opciones.size, opciones.map { it.label }.toSet().size)
        assertEquals("transferencia (original)", opcionesParaDevolver("OTHER", " transferencia ").first().label)
    }

    // ── Devolver en efectivo un cobro que no fue en efectivo: qué se le avisa a quien no tiene el permiso (1-oct-2026) ──

    @Test
    fun `escoger efectivo sin el permiso y con el codigo del encargado activo avisa que el encargado lo autoriza`() {
        assertEquals(
            "Necesitarás que un encargado lo autorice con su código.",
            textoDeAutorizacionParaEfectivo(refundMethod = "CASH", puede = false, codigoDeEncargadoActivo = true),
        )
    }

    @Test
    fun `escoger efectivo sin el permiso y con el codigo del encargado apagado no promete un teclado que no existe`() {
        assertEquals(
            "Sólo un encargado puede devolver en efectivo: pídele que lo haga desde su usuario.",
            textoDeAutorizacionParaEfectivo(refundMethod = "CASH", puede = false, codigoDeEncargadoActivo = false),
        )
    }

    @Test
    fun `con el permiso no hay aviso, con el codigo prendido o apagado`() {
        assertNull(textoDeAutorizacionParaEfectivo(refundMethod = "CASH", puede = true, codigoDeEncargadoActivo = true))
        assertNull(textoDeAutorizacionParaEfectivo(refundMethod = "CASH", puede = true, codigoDeEncargadoActivo = false))
    }

    @Test
    fun `sin escoger efectivo no hay aviso aunque falte el permiso`() {
        assertNull(textoDeAutorizacionParaEfectivo(refundMethod = "BANK_TRANSFER", puede = false, codigoDeEncargadoActivo = true))
        assertNull(textoDeAutorizacionParaEfectivo(refundMethod = null, puede = false, codigoDeEncargadoActivo = false))
    }

    // QA en la CPad (1-oct): el cajero SÍ puede reembolsar; el 403 de efectivo decía «No tienes permiso para emitir reembolsos».
    @Test
    fun `el 403 de efectivo dice que lo que falta es devolver en efectivo y ofrece la salida`() {
        assertEquals(
            "No tienes permiso para devolver en efectivo. Devuélvelo por el mismo medio o pídele a un encargado que lo haga.",
            textoDeSinPermisoParaReembolsar(refundMethod = "CASH"),
        )
    }

    @Test
    fun `el 403 sin efectivo sigue siendo el de reembolsar`() {
        assertEquals("No tienes permiso para emitir reembolsos", textoDeSinPermisoParaReembolsar(refundMethod = null))
        assertEquals("No tienes permiso para emitir reembolsos", textoDeSinPermisoParaReembolsar(refundMethod = "BANK_TRANSFER"))
    }
}
