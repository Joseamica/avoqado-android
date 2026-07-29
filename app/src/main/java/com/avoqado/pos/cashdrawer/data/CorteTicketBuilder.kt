package com.avoqado.pos.cashdrawer.data

import com.avoqado.pos.cashdrawer.data.model.CashDrawerEventEntity
import com.avoqado.pos.cashdrawer.data.model.CashDrawerEventType
import com.avoqado.pos.cashdrawer.data.model.CashDrawerSessionEntity
import com.avoqado.pos.cashdrawer.presentation.tenderLabel
import com.avoqado.pos.printing.data.ESCPOSPrinter
import com.avoqado.pos.printing.data.model.PaperWidth

/**
 * Arma el ticket ESC/POS del corte de caja.
 *
 * Vive fuera del ViewModel porque hay DOS cosas que lo imprimen —el corte
 * definitivo al cerrar la caja y el corte parcial con la caja abierta— y porque
 * así se puede probar lo que realmente sale en el papel sin una impresora
 * enfrente. Es lo único que quedó sin verificar en hardware.
 *
 * Espejo de `CortePrinter` en iOS.
 */
object CorteTicketBuilder {

    fun build(
        session: CashDrawerSessionEntity,
        events: List<CashDrawerEventEntity>,
        tenders: List<CashDrawerRepository.TenderRow>,
        venueName: String,
        paperWidth: com.avoqado.pos.printing.data.model.PaperWidth,
        isPartial: Boolean,
        /**
         * 🔴 La impresora INTEGRADA de Sunmi arranca en multibyte (GB18030) y se
         * come los bytes Latin-1 que le mandamos: el papel sale EN BLANCO y ni
         * corta. Hay que pasarla a un solo byte con `FS .` ANTES de escribir.
         *
         * Esto ya estaba resuelto en el resto de la app vía `escposFor`, y este
         * builder lo perdió al construir el ESCPOSPrinter por su cuenta. Salió
         * imprimiendo el primer corte en la D3.
         */
        switchToSingleByteFirst: Boolean = false,
    ): ByteArray {
        val zone = com.avoqado.pos.core.util.VenueTimeZone.zoneId()
        val fecha = java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy", java.util.Locale("es", "MX"))
        val hora = java.time.format.DateTimeFormatter.ofPattern("HH:mm", java.util.Locale("es", "MX"))
        fun money(cents: Int) = "$" + String.format(java.util.Locale.US, "%.2f", cents / 100.0)
        fun at(millis: Long) = java.time.Instant.ofEpochMilli(millis).atZone(zone)

        fun sumOf(type: CashDrawerEventType) =
            events.filter { it.type == type.name }.sumOf { it.amountCents }

        val cashSales = sumOf(CashDrawerEventType.CASH_SALE)
        val payIns = sumOf(CashDrawerEventType.PAY_IN)
        val payOuts = sumOf(CashDrawerEventType.PAY_OUT)
        val expected = session.startingAmountCents + cashSales + payIns - payOuts
        val actual = session.actualAmountCents ?: 0
        val diff = actual - expected
        val hasServerBreakdown = tenders.isNotEmpty()
        val totalSales = if (hasServerBreakdown) tenders.sumOf { it.totalCents } else cashSales
        val txCount = events.count { it.type == CashDrawerEventType.CASH_SALE.name }

        val p = ESCPOSPrinter(paperWidth, switchToSingleByteFirst)
        p.reset()
        p.setAlignment(ESCPOSPrinter.TextAlignment.CENTER)
        p.setBold(true)
        p.setLargeText(true)
        p.printLine(if (isPartial) "CORTE PARCIAL" else "CORTE DE CAJA")
        p.setLargeText(false)
        p.printLine(venueName)
        p.setBold(false)
        if (isPartial) {
            // Que quede en el papel: este ticket NO cerró el turno. Sin esto, dos
            // cortes del mismo día se confunden y alguien cuadra contra el equivocado.
            p.setBold(true)
            p.printLine("LA CAJA SIGUE ABIERTA")
            p.setBold(false)
        }
        p.printLine(at(session.openedAt).format(fecha))
        p.printLine(
            if (isPartial) {
                "Apertura: " + at(session.openedAt).format(hora) +
                    "  Impreso: " + java.time.ZonedDateTime.now(zone).format(hora)
            } else {
                "Apertura: " + at(session.openedAt).format(hora) +
                    "  Cierre: " + (session.closedAt?.let { at(it).format(hora) } ?: "--")
            },
        )
        p.printLine("Operador: ${session.openedByName}")
        p.printDivider()

        p.setAlignment(ESCPOSPrinter.TextAlignment.LEFT)
        p.setBold(true)
        p.printLine(if (hasServerBreakdown) "RESUMEN DE VENTAS" else "RESUMEN DE VENTAS (EFECTIVO)")
        p.setBold(false)
        p.printTwoColumns(if (hasServerBreakdown) "Ventas totales" else "Ventas en efectivo", money(totalSales))
        p.printTwoColumns("Transacciones", "$txCount")
        p.printTwoColumns("Ticket promedio", money(if (txCount > 0) totalSales / txCount else 0))
        p.printDivider()

        p.setBold(true)
        p.printLine("DESGLOSE POR MÉTODO DE PAGO")
        p.setBold(false)
        if (hasServerBreakdown) {
            tenders.sortedByDescending { it.totalCents }.forEach {
                p.printTwoColumns(tenderLabel(it.method), money(it.totalCents))
            }
        } else {
            p.printTwoColumns("Efectivo", money(cashSales))
            p.printLine("Sin conexión: sólo se muestra el efectivo.")
            p.printLine("Tarjeta y otros medios aparecerán al")
            p.printLine("recuperar la conexión.")
        }
        p.printDivider()

        p.setBold(true)
        p.printLine("MOVIMIENTOS DE EFECTIVO")
        p.setBold(false)
        p.printTwoColumns("Monto inicial", money(session.startingAmountCents))
        p.printTwoColumns("Ventas en efectivo", "+" + money(cashSales))
        p.printTwoColumns("Ingresos", "+" + money(payIns))
        p.printTwoColumns("Egresos", "-" + money(payOuts))
        p.printDivider()
        p.setBold(true)
        p.printTwoColumns("Efectivo esperado", money(expected))
        if (isPartial) {
            // NADA de "Conteo real" ni de diferencia: el dinero no se ha contado.
            // Imprimir "Faltante $1,058.00" aquí sería inventar un descuadre.
            p.setBold(false)
            p.printLine("Cuenta el cajón y cierra la caja para")
            p.printLine("obtener el corte definitivo.")
        } else {
            p.printTwoColumns("Conteo real", money(actual))
            p.printTwoColumns(
                when {
                    diff > 0 -> "Sobrante"
                    diff < 0 -> "Faltante"
                    else -> "Diferencia"
                },
                money(kotlin.math.abs(diff)),
            )
            p.setBold(false)
        }
        p.feedLines(3)
        p.cut()
        return p.getData()
    }
}
