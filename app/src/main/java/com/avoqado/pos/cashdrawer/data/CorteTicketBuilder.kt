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

    /** Lo que esta app escribe en la nota al registrar el egreso de un reembolso. */
    const val PREFIJO_REEMBOLSO = "Reembolso:"


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
        val payOutsTodos = sumOf(CashDrawerEventType.PAY_OUT)
        // Los reembolsos en efectivo van APARTE de los demás egresos, como en
        // Square ("Reembolsos en efectivo" es una línea propia de su arqueo).
        // Mezclarlos con los pagos a proveedores o el retiro de propinas impide
        // saber cuánto se devolvió, que es lo que el dueño quiere revisar cuando
        // el cajón sale corto.
        //
        // Se distinguen por el prefijo de la nota, que pone esta misma app al
        // registrar el egreso. Frágil a propósito y a falta de un tipo de evento
        // propio: añadirlo obliga a tocar el enum del server y migrar.
        val reembolsos = events
            .filter { it.type == CashDrawerEventType.PAY_OUT.name && it.note?.startsWith(PREFIJO_REEMBOLSO) == true }
            .sumOf { it.amountCents }
        val payOuts = payOutsTodos - reembolsos
        // 🔴 El esperado resta payOutsTODOS, no `payOuts`: el dinero devuelto salió
        // del cajón igual. Separarlos es sólo para PRESENTARLOS aparte; usar aquí
        // la cifra ya descontada inflaría el esperado y acusaría un faltante
        // inexistente por el importe de las devoluciones.
        val expected = session.startingAmountCents + cashSales + payIns - payOutsTodos
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

        // PROPINAS — su propia sección, como en el Corte Z de SoftRestaurant.
        //
        // Va aparte porque la propina NO es dinero del negocio: se le entrega al
        // mesero. Antes iba sumada dentro de cada método sin distinguirse, así que
        // el corte enseñaba "Efectivo $5,158" sin decir que $552 de ahí eran
        // propinas que hay que sacar del cajón. El total de cada método sigue
        // incluyéndolas —es lo que hay físicamente— pero ahora se ve cuánto es.
        val tips = tenders.filter { it.tipsCents != 0 }
        if (tips.isNotEmpty()) {
            p.setBold(true)
            p.printLine("PROPINAS")
            p.setBold(false)
            tips.sortedByDescending { it.tipsCents }.forEach {
                p.printTwoColumns(tenderLabel(it.method), money(it.tipsCents))
            }
            p.setBold(true)
            p.printTwoColumns("Total propinas", money(tips.sumOf { it.tipsCents }))
            p.setBold(false)
            val propinaEfectivo = tips.firstOrNull { it.method == "CASH" }?.tipsCents ?: 0
            if (propinaEfectivo > 0) {
                // El dato que el cajero necesita para no descuadrar: de lo que hay en
                // el cajón, esto le toca al mesero. Se saca con un egreso.
                p.printLine("De estas, " + money(propinaEfectivo) + " estan en el cajon")
                p.printLine("y se pagan al mesero (registra un egreso).")
            }
            p.printDivider()
        }

        p.setBold(true)
        p.printLine("MOVIMIENTOS DE EFECTIVO")
        p.setBold(false)
        p.printTwoColumns("Monto inicial", money(session.startingAmountCents))
        p.printTwoColumns("Ventas en efectivo", "+" + money(cashSales))
        p.printTwoColumns("Ingresos", "+" + money(payIns))
        p.printTwoColumns("Egresos", "-" + money(payOuts))
        if (reembolsos > 0) {
            p.printTwoColumns("Reembolsos en efectivo", "-" + money(reembolsos))
        }
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
