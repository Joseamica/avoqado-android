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

    /**
     * Prefijo con el que llega la nota del egreso de un reembolso.
     *
     * 🔴 Ya NO lo escribe esta app: desde el 2026-08-16 lo escribe el SERVIDOR
     * (`DRAWER_REFUND_NOTE_PREFIX` en `avoqado-server/src/services/shared/
     * cashDrawerPosting.ts`), y el evento baja por `syncFromApi()`. Es un contrato
     * entre repos: si allá cambia la cadena, aquí el dinero sí sale del cajón pero
     * el ticket lo cuenta como un retiro a mano y el dueño no puede explicar el
     * hueco. Mismo valor en iOS.
     */
    const val PREFIJO_REEMBOLSO = "Reembolso:"

    /** Mismo texto en pantalla ([AVISO_SIN_CONFIRMAR]) y en iOS (`CortePrinter.swift`). */
    const val AVISO_SIN_CONFIRMAR =
        "Sin conexión: este corte sólo incluye lo registrado en este aparato. Reembolsos o cobros " +
            "hechos desde el dashboard o la terminal pueden faltar. Vuelve a abrirlo con conexión."

    /** La versión del papel: renglones cortos, sin depender de cómo parta el texto la impresora. */
    val TICKET_SIN_CONFIRMAR = listOf(
        "SIN CONEXIÓN: sólo incluye lo",
        "registrado en este aparato.",
        "Reembolsos o cobros de la",
        "terminal o del dashboard pueden",
        "faltar. Reimprímelo con conexión.",
    )


    /** Lo que el corte dice bajo los métodos escondidos cuando el conteo es ciego. */
    const val OCULTO_CIEGO = "Se revelan al cerrar la caja."

    /**
     * Conteo ciego: qué métodos del desglose se esconden. Sólo las TARJETAS se ven: nunca pasan
     * por el cajón. Un tipo de pago propio puede contar como efectivo físico
     * (`countsAsPhysicalCash`, p. ej. vales de despensa) y llega con método `OTHER` u otro; el
     * desglose no dice cuál, así que se esconde todo lo que no sea tarjeta (Codex, 4-oct).
     */
    fun ocultoEnCiego(method: String): Boolean = method != "CREDIT_CARD" && method != "DEBIT_CARD"

    /**
     * «Ventas netas» = lo cobrado SIN propina. Los totales del desglose traen la propina adentro
     * (es lo que hay físicamente por método), pero la propina es del personal, no venta (regla 6
     * de product-decisions; SAT 30/IVA/N). El corte de Windows del 3-oct decía $113 con $105 vendidos.
     */
    fun ventasNetasCents(tenders: List<CashDrawerRepository.TenderRow>): Int =
        tenders.sumOf { it.totalCents - it.tipsCents }

    /**
     * Ticket promedio de las ventas en efectivo del cajón, sin la propina en efectivo cuando el
     * server la desglosa. Sin desglose no se conoce la propina: se queda lo cobrado.
     */
    fun ticketPromedioEfectivoCents(
        cashSalesCents: Int,
        txCount: Int,
        tenders: List<CashDrawerRepository.TenderRow>?,
    ): Int {
        if (txCount <= 0) return 0
        // `tipsCents` viene NETO de reembolsos (puede ser negativo) y las ventas del cajón son brutas:
        // restar una propina negativa inflaría el promedio. Sólo se descuenta propina positiva.
        val propinaEfectivo = (tenders?.firstOrNull { it.method == "CASH" }?.tipsCents ?: 0).coerceAtLeast(0)
        return ((cashSalesCents - propinaEfectivo) / txCount).coerceAtLeast(0)
    }

    /**
     * Movimientos de la caja ABIERTA que se listan. Conteo ciego: cada venta en efectivo con su
     * monto es el esperado en partes (fondo + ventas + ingresos − egresos), igual que la tarjeta
     * «Ventas» que ya se escondía. Ingresos y egresos se quedan: ya están en sus tarjetas.
     */
    fun movimientosALaVista(
        events: List<CashDrawerEventEntity>,
        puedeVerEsperado: Boolean,
    ): List<CashDrawerEventEntity> =
        if (puedeVerEsperado) events else events.filter { it.type != CashDrawerEventType.CASH_SALE.name }

    fun build(
        session: CashDrawerSessionEntity,
        events: List<CashDrawerEventEntity>,
        /** `null` = no se pudo consultar; vacía = no hubo cobros. */
        tenders: List<CashDrawerRepository.TenderRow>?,
        venueName: String,
        paperWidth: com.avoqado.pos.printing.data.model.PaperWidth,
        isPartial: Boolean,
        /** Conteo ciego: en el corte PARCIAL el esperado sólo se imprime a quien tiene permiso. */
        showExpected: Boolean = true,
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
        tablaDeAcentos: com.avoqado.pos.printing.data.model.TablaDeAcentos = com.avoqado.pos.printing.data.model.TablaDeAcentos.WINDOWS_1252,
        /**
         * 🔴 El corte salió SIN confirmarlo con el servidor (sin red): sólo trae lo que registró
         * este aparato. Lo que escribe el servidor —el egreso de un reembolso, la venta en efectivo
         * de la terminal— puede faltar, y el papel se archiva: tiene que decirlo, o un faltante
         * inventado queda como prueba (Testarudo, 28-sep-2026).
         */
        sinConfirmar: Boolean = false,
    ): ByteArray {
        val zone = com.avoqado.pos.core.util.VenueTimeZone.zoneId()
        val fecha = java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy", java.util.Locale("es", "MX"))
        val hora = java.time.format.DateTimeFormatter.ofPattern("HH:mm", java.util.Locale("es", "MX"))
        // El signo va ANTES del símbolo («-$129.00»), como en pantalla (`formatMoneyFromCents`).
        fun money(cents: Int) = com.avoqado.pos.core.util.formatMoneyFromCents(cents)
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
        // Se distinguen por el prefijo de la nota, que pone el SERVIDOR al registrar
        // el egreso (ver `PREFIJO_REEMBOLSO`). Frágil a propósito y a falta de un
        // tipo de evento propio: añadirlo obliga a tocar el enum del server y migrar.
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
        val hasServerBreakdown = tenders != null
        val totalSales = if (tenders != null) ventasNetasCents(tenders) else cashSales
        val txCount = events.count { it.type == CashDrawerEventType.CASH_SALE.name }
        // Conteo ciego: corte PARCIAL de quien no tiene `cash-drawer:view-expected`.
        val ciego = isPartial && !showExpected

        val p = ESCPOSPrinter(paperWidth, switchToSingleByteFirst, tablaDeAcentos = tablaDeAcentos)
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
        // «Netas»: el desglose del server ya resta los reembolsos (una venta de $138 devuelta entera da $0).
        if (showExpected || !isPartial) p.printTwoColumns(if (hasServerBreakdown) "Ventas netas" else "Ventas en efectivo", money(totalSales))
        // 🔴 `txCount` cuenta SOLO las ventas en efectivo del cajón (es lo único
        // que el cajón conoce), pero `totalSales` incluye tarjeta y otros cuando
        // el server manda el desglose. Sin decirlo, las tres filas se leen como
        // un bloque coherente y no lo son: "$10,274.73 / 3 transacciones" hace
        // creer que hubo 3 ventas de ~$3,425, y el ticket promedio no
        // corresponde a nada visible. Se etiqueta el alcance en vez de inventar
        // un promedio: el server no manda el conteo total.
        // Mismos textos en avoqado-ios (CortePrinter.swift).
        p.printTwoColumns(if (hasServerBreakdown) "Transacciones en efectivo" else "Transacciones", "$txCount")
        // Ciego: promedio × transacciones = ventas en efectivo, o sea el esperado en una suma.
        if (!ciego) {
            p.printTwoColumns(
                if (hasServerBreakdown) "Ticket promedio en efectivo" else "Ticket promedio",
                money(ticketPromedioEfectivoCents(cashSales, txCount, tenders)),
            )
        }
        p.printDivider()

        p.setBold(true)
        p.printLine("DESGLOSE POR MÉTODO DE PAGO")
        p.setBold(false)
// 🔴 Tres casos, no dos. El servidor contestando "no hubo cobros" NO es lo mismo que no
        // haber podido preguntar, y el ticket impreso queda en el cajón como comprobante: decir
        // "sin conexión" cuando sí la había vuelve el papel una prueba falsa de lo que pasó.
        // Conteo ciego: el renglón se queda (se sabe que hubo ese método) pero sin importe.
        fun renglon(method: String, cents: Int) =
            p.printTwoColumns(tenderLabel(method), if (ciego && ocultoEnCiego(method)) "--" else money(cents))
        if (hasServerBreakdown && tenders.orEmpty().isEmpty()) {
            renglon("CASH", cashSales)
            p.printLine("No hubo cobros en este corte.")
        } else if (hasServerBreakdown) {
            tenders.orEmpty().sortedByDescending { it.totalCents }.forEach { renglon(it.method, it.totalCents) }
        } else {
            renglon("CASH", cashSales)
            p.printLine("No se pudo consultar el desglose.")
            p.printLine("Tarjeta y otros medios aparecerán al")
            p.printLine("volver a imprimirlo con conexión.")
        }
        if (ciego) p.printLine(OCULTO_CIEGO)
        p.printDivider()

        // PROPINAS — su propia sección, como en el Corte Z de SoftRestaurant.
        //
        // Va aparte porque la propina NO es dinero del negocio: se le entrega al
        // mesero. Antes iba sumada dentro de cada método sin distinguirse, así que
        // el corte enseñaba "Efectivo $5,158" sin decir que $552 de ahí eran
        // propinas que hay que sacar del cajón. El total de cada método sigue
        // incluyéndolas —es lo que hay físicamente— pero ahora se ve cuánto es.
        val tips = tenders.orEmpty().filter { it.tipsCents != 0 }
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
                // De lo que hay en el cajón, esto le toca al personal.
                //
                // NO se pide registrar un egreso: el arqueo se cuenta ANTES de repartir,
                // así que la propina tiene que estar adentro para que cuadre (el efectivo
                // esperado del server ya la incluye). El egreso sólo haría falta si se
                // repartiera a media jornada, y eso desharía el cuadre del cierre.
                p.printLine("De estas, " + money(propinaEfectivo) + " estan en el cajon")
                p.printLine("y le tocan al personal. Repartelas al cerrar.")
            }
            p.printDivider()
        }

        p.setBold(true)
        p.printLine("MOVIMIENTOS DE EFECTIVO")
        p.setBold(false)
        p.printTwoColumns("Monto inicial", money(session.startingAmountCents))
        if (showExpected || !isPartial) p.printTwoColumns("Ventas en efectivo", "+" + money(cashSales))
        p.printTwoColumns("Ingresos", "+" + money(payIns))
        p.printTwoColumns("Egresos", "-" + money(payOuts))
        if (reembolsos > 0) {
            p.printTwoColumns("Reembolsos en efectivo", "-" + money(reembolsos))
        }
        p.printDivider()
        p.setBold(true)
        if (showExpected || !isPartial) p.printTwoColumns("Efectivo esperado", money(expected)) else p.printLine("Efectivo esperado: se revela al cerrar")
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
        if (sinConfirmar) {
            p.printDivider()
            TICKET_SIN_CONFIRMAR.forEach { p.printLine(it) }
        }
        p.feedLines(3)
        p.cut()
        return p.getData()
    }
}
