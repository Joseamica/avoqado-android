package com.avoqado.pos.printing.data

import android.util.Log
import com.avoqado.pos.printing.data.model.ComboPrintLines
import com.avoqado.pos.printing.data.model.KitchenItem
import com.avoqado.pos.printing.data.model.KitchenTicketData
import com.avoqado.pos.printing.data.model.PrinterConnectionType
import com.avoqado.pos.printing.data.model.PrinterRole
import com.avoqado.pos.printing.data.model.SavedPrinter
import com.avoqado.pos.printing.routing.ConsolidatedLine
import com.avoqado.pos.printing.routing.PrintConfig
import com.avoqado.pos.printing.routing.PrinterInfo
import com.avoqado.pos.printing.routing.TicketPlan
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ComandaPrinter"
private const val DEFAULT_PRINT_PORT = 9100

/** Valor del enum del server para "la impresora integrada del propio POS". */
private const val POS_INTERNAL_TYPE = "POS_INTERNAL"

/** Station label used on the ticket header for the unrouted safety-net bucket
 *  and whenever a routed plan's station can't be resolved from the config. */
const val UNROUTED_STATION_LABEL = "SIN ESTACIÓN"

/**
 * PRINT_STATIONS — prints one kitchen comanda per station from the routing
 * engine's [TicketPlan]s (built by [com.avoqado.pos.printing.routing.PrintRoutingMapper]).
 *
 * Deliberately separate from [PrinterService]: [resolve] is a PURE function
 * (no sockets, no Context) that decides which printer/copies/label a plan
 * maps to — that's the part worth unit-testing. [printComandas] is the thin
 * I/O shell around it, delegating the actual send to [PrinterService].
 */
@Singleton
class ComandaPrinter @Inject constructor(
    private val printerService: PrinterService,
) {

    /** Everything [printComandas] needs to know about ONE plan — resolved with no I/O. */
    data class ResolvedComanda(
        /** null when the station has no printer assigned/resolvable (or the plan is unrouted) —
         *  the caller falls back to the default KITCHEN printer. */
        val savedPrinter: SavedPrinter?,
        val copies: Int,
        val stationLabel: String,
        val ticket: KitchenTicketData,
    )

    /**
     * Cascade: unrouted (stationId == null) OR a deleted/unknown station → "SIN ESTACIÓN".
     * A routed station with no printer configured yet keeps ITS name on the ticket (so staff
     * see "Cocina" even while it prints on the fallback) but still signals savedPrinter = null
     * so the caller uses the default KITCHEN printer as a safety net.
     *
     * @param internalPrinter la impresora integrada de ESTE aparato (o null si no trae
     *   cabezal): es a lo que resuelve una estación con impresora `POS_INTERNAL`. Va por
     *   parámetro para que [resolve] siga siendo PURA — el I/O de averiguarla vive en
     *   [PrinterService.internalPrinterForRouting] y lo hace [printComandas].
     */
    fun resolve(
        plan: TicketPlan,
        config: PrintConfig,
        orderNumber: String,
        orderType: String,
        serverName: String? = null,
        comboNames: Map<String, String> = emptyMap(),
        internalPrinter: SavedPrinter? = null,
    ): ResolvedComanda {
        val station = plan.stationId?.let { id -> config.stations.firstOrNull { it.id == id } }
        val printerInfo = station?.printerId?.let { pid -> config.printers.firstOrNull { it.id == pid } }
        val savedPrinter = printerInfo?.toKitchenSavedPrinter(internalPrinter)
        val stationLabel = station?.name ?: UNROUTED_STATION_LABEL

        val ticket = KitchenTicketData(
            orderNumber = orderNumber,
            orderType = orderType,
            // COMBOS — el nombre del combo encabeza a SUS productos, en cada estación
            // por separado (Fudo). La llave es el NOMBRE porque el motor ya consolidó
            // líneas de instancias distintas. Sin combos, `comboNames` vacío ⇒ la lista
            // sale idéntica a la de siempre.
            items = ComboPrintLines.kitchen(
                plan.lines.map { line ->
                    val comboName = line.orderItemIds.firstNotNullOfOrNull { comboNames[it] }
                    val tag = comboName?.let { ComboPrintLines.Tag(key = it, name = it) }
                    tag to line.toKitchenItem()
                },
            ),
            serverName = serverName,
            stationName = stationLabel,
        )

        return ResolvedComanda(
            savedPrinter = savedPrinter,
            copies = (station?.copies ?: 1).coerceAtLeast(1),
            stationLabel = stationLabel,
            ticket = ticket,
        )
    }

    /**
     * Print one comanda per station. Each plan is wrapped independently — a failing
     * printer (offline, unreachable, misconfigured) is logged and does NOT abort the
     * other stations' tickets (mirrors the server's per-job resilience).
     */
    /**
     * Qué pasó de verdad al imprimir. Lo IGNORA el camino automático (una
     * impresora caída jamás puede frenar una venta) y lo LEE el manual, donde
     * el mesero pidió la impresión y merece saber si salió.
     *
     * 🔴 Antes esta función devolvía Unit y se tragaba cada excepción en un log:
     * "Volver a imprimir pedido" cantaba "Comandas reimpresas" aunque no hubiera
     * salido ni un papel. Medido en la T3 el 2026-08-09: 10 s de timeout contra
     * una impresora inalcanzable y aun así palomita de éxito.
     */
    data class Result(
        val attempted: Int,
        val printed: Int,
        /** Sin impresora resuelta NI default de cocina: no se intentó siquiera. */
        val skippedNoPrinter: Int,
        val lastError: String?,
        /** Estaciones cuya impresora TRONÓ (offline, timeout, sin papel…). */
        val failedStations: List<String> = emptyList(),
        /** Estaciones que se SALTARON (sin impresora resoluble ni default de cocina). */
        val skippedStations: List<String> = emptyList(),
    ) {
        val nothingPrinted: Boolean get() = printed == 0
        val partial: Boolean get() = printed in 1 until attempted

        /**
         * Las estaciones que se quedaron SIN su comanda, con nombre — es lo que el aviso
         * al cajero necesita decir ("revisa la impresora de Barra"), no un conteo anónimo.
         */
        val stationsSinComanda: List<String> get() = (failedStations + skippedStations).distinct()
    }

    suspend fun printComandas(
        plans: List<TicketPlan>,
        config: PrintConfig,
        orderNumber: String,
        orderType: String = "En tienda",
        serverName: String? = null,
        /** COMBOS — `orderItemId` → nombre del combo. Vacío = comanda de siempre. */
        comboNames: Map<String, String> = emptyMap(),
    ): Result {
        // PEREZOSO a propósito: sólo se le pregunta al hardware por la integrada cuando la
        // config trae alguna impresora POS_INTERNAL — el resto de los venues no paga el bind.
        val internalPrinter = if (config.printers.any { it.connectionType.trim().uppercase() == POS_INTERNAL_TYPE }) {
            printerService.internalPrinterForRouting()
        } else {
            null
        }

        var printed = 0
        var lastError: String? = null
        val failedStations = mutableListOf<String>()
        val skippedStations = mutableListOf<String>()
        for (plan in plans) {
            // La etiqueta se conoce ANTES de intentar imprimir, para que un fallo también
            // sepa decir de QUÉ estación era la comanda que no salió.
            var stationLabel = UNROUTED_STATION_LABEL
            try {
                val resolved = resolve(plan, config, orderNumber, orderType, serverName, comboNames, internalPrinter)
                stationLabel = resolved.stationLabel
                val printer = resolved.savedPrinter ?: printerService.getDefaultPrinter(PrinterRole.KITCHEN)
                if (printer == null) {
                    Log.w(
                        TAG,
                        "⚠️ No printer resolved for station='${resolved.stationLabel}' and no default " +
                            "KITCHEN printer configured — comanda skipped",
                    )
                    skippedStations += stationLabel
                    continue
                }
                repeat(resolved.copies) { printerService.printKitchenTicket(resolved.ticket, printer) }
                printed++
                Log.d(TAG, "✅ Printed comanda for station='${resolved.stationLabel}' on ${printer.displayAddress}")
            } catch (e: Exception) {
                lastError = e.message
                failedStations += stationLabel
                Log.e(TAG, "❌ Comanda print failed for station='${plan.stationId}': ${e.message}", e)
            }
        }
        return Result(
            attempted = plans.size,
            printed = printed,
            skippedNoPrinter = skippedStations.size,
            lastError = lastError,
            failedStations = failedStations,
            skippedStations = skippedStations,
        )
    }

    private fun ConsolidatedLine.toKitchenItem(): KitchenItem = KitchenItem(
        name = productName,
        quantity = quantity,
        modifiers = modifiers.ifEmpty { null },
        note = notes,
    )

    /**
     * Maps the server's Prisma `PrinterConnectionType` enum string
     * (`NETWORK` | `BLUETOOTH` | `POS_INTERNAL` | `USB_SPOOLER` | `TERMINAL_INTERNAL`) to the
     * Android print target. Case-insensitive to be robust to whatever casing the server sends.
     *
     * - `POS_INTERNAL` → la impresora integrada de ESTE aparato ([internalPrinter]): la
     *   comanda sale donde se cobró, sin IP de por medio. En un equipo sin cabezal (T3)
     *   [internalPrinter] es null y la estación cae al respaldo KITCHEN del caller. Se
     *   conservan id/nombre del server (para logs y estado) y el ANCHO DEL HARDWARE — el
     *   server registra 80 mm por default y un ESC/POS de 80 en un cabezal de 58 corta líneas.
     * - `NETWORK` → WIFI, parsing `address` as "host:port" (default port 9100 when
     *   absent/unparsable) — unchanged from the original WiFi-only behavior.
     * - `BLUETOOTH` → BLUETOOTH, `address` = the MAC **verbatim**. A MAC contains `:`
     *   (e.g. "AA:BB:CC:DD:EE:FF"), so it must NOT go through the host:port splitter above —
     *   doing so would truncate/corrupt the MAC into a bogus host+port pair.
     * - Anything else (`USB_SPOOLER`, `TERMINAL_INTERNAL`, unknown) → null. The Android
     *   client can't service those transports; the caller logs and skips that station rather
     *   than falling back to a wrong transport.
     */
    private fun PrinterInfo.toKitchenSavedPrinter(internalPrinter: SavedPrinter?): SavedPrinter? {
        // POS_INTERNAL va ANTES del chequeo de dirección: la integrada no lleva dirección
        // a propósito (registrarle una IP fue justo el bug que originó este tipo).
        if (connectionType.trim().uppercase() == POS_INTERNAL_TYPE) {
            return internalPrinter?.copy(
                id = id,
                name = name,
                roles = listOf(PrinterRole.KITCHEN.value),
            )
        }

        val raw = address?.trim()
        if (raw.isNullOrEmpty()) return null

        return when (connectionType.trim().uppercase()) {
            "NETWORK" -> {
                val separatorIdx = raw.lastIndexOf(':')
                val parsedPort = if (separatorIdx > 0) raw.substring(separatorIdx + 1).toIntOrNull() else null
                val host = if (parsedPort != null) raw.substring(0, separatorIdx) else raw

                SavedPrinter(
                    id = id,
                    name = name,
                    connectionType = PrinterConnectionType.WIFI.value,
                    address = host,
                    port = parsedPort ?: DEFAULT_PRINT_PORT,
                    roles = listOf(PrinterRole.KITCHEN.value),
                    paperWidthMm = paperWidthMm,
                )
            }
            "BLUETOOTH" -> SavedPrinter(
                id = id,
                name = name,
                connectionType = PrinterConnectionType.BLUETOOTH.value,
                address = raw,
                port = null,
                roles = listOf(PrinterRole.KITCHEN.value),
                paperWidthMm = paperWidthMm,
                leftMarginChars = leftMarginChars,
            )
            else -> {
                Log.w(
                    TAG,
                    "⚠️ Unsupported printer connectionType='$connectionType' for printer id='$id' " +
                        "name='$name' — station skipped (Android can't service this transport)",
                )
                null
            }
        }
    }
}
