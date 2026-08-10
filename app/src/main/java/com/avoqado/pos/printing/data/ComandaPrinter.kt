package com.avoqado.pos.printing.data

import android.util.Log
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
     */
    fun resolve(
        plan: TicketPlan,
        config: PrintConfig,
        orderNumber: String,
        orderType: String,
        serverName: String? = null,
    ): ResolvedComanda {
        val station = plan.stationId?.let { id -> config.stations.firstOrNull { it.id == id } }
        val printerInfo = station?.printerId?.let { pid -> config.printers.firstOrNull { it.id == pid } }
        val savedPrinter = printerInfo?.toKitchenSavedPrinter()
        val stationLabel = station?.name ?: UNROUTED_STATION_LABEL

        val ticket = KitchenTicketData(
            orderNumber = orderNumber,
            orderType = orderType,
            items = plan.lines.map { it.toKitchenItem() },
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
    ) {
        val nothingPrinted: Boolean get() = printed == 0
        val partial: Boolean get() = printed in 1 until attempted
    }

    suspend fun printComandas(
        plans: List<TicketPlan>,
        config: PrintConfig,
        orderNumber: String,
        orderType: String = "En tienda",
        serverName: String? = null,
    ): Result {
        var printed = 0
        var skipped = 0
        var lastError: String? = null
        for (plan in plans) {
            try {
                val resolved = resolve(plan, config, orderNumber, orderType, serverName)
                val printer = resolved.savedPrinter ?: printerService.getDefaultPrinter(PrinterRole.KITCHEN)
                if (printer == null) {
                    Log.w(
                        TAG,
                        "⚠️ No printer resolved for station='${resolved.stationLabel}' and no default " +
                            "KITCHEN printer configured — comanda skipped",
                    )
                    skipped++
                    continue
                }
                repeat(resolved.copies) { printerService.printKitchenTicket(resolved.ticket, printer) }
                printed++
                Log.d(TAG, "✅ Printed comanda for station='${resolved.stationLabel}' on ${printer.displayAddress}")
            } catch (e: Exception) {
                lastError = e.message
                Log.e(TAG, "❌ Comanda print failed for station='${plan.stationId}': ${e.message}", e)
            }
        }
        return Result(attempted = plans.size, printed = printed, skippedNoPrinter = skipped, lastError = lastError)
    }

    private fun ConsolidatedLine.toKitchenItem(): KitchenItem = KitchenItem(
        name = productName,
        quantity = quantity,
        modifiers = modifiers.ifEmpty { null },
        note = notes,
    )

    /**
     * Maps the server's Prisma `PrinterConnectionType` enum string
     * (`NETWORK` | `BLUETOOTH` | `USB_SPOOLER` | `TERMINAL_INTERNAL`) to the Android print
     * target. Case-insensitive to be robust to whatever casing the server sends.
     *
     * - `NETWORK` → WIFI, parsing `address` as "host:port" (default port 9100 when
     *   absent/unparsable) — unchanged from the original WiFi-only behavior.
     * - `BLUETOOTH` → BLUETOOTH, `address` = the MAC **verbatim**. A MAC contains `:`
     *   (e.g. "AA:BB:CC:DD:EE:FF"), so it must NOT go through the host:port splitter above —
     *   doing so would truncate/corrupt the MAC into a bogus host+port pair.
     * - Anything else (`USB_SPOOLER`, `TERMINAL_INTERNAL`, unknown) → null. The Android
     *   client can't service those transports; the caller logs and skips that station rather
     *   than falling back to a wrong transport.
     */
    private fun PrinterInfo.toKitchenSavedPrinter(): SavedPrinter? {
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
