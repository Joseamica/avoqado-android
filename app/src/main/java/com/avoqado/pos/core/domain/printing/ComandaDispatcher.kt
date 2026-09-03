// Disparo de comanda — el mecanismo compartido por los DOS momentos en que una comanda puede salir:
//
//   POST-PAGO  mostrador: se cobra y ENTONCES sale la comanda (PaymentFlowViewModel).
//   PRE-PAGO   vale de área: el área imprime el vale, se queda con el producto y necesita su
//              comanda ANTES de que el cliente pase a la caja (§5.6 del spec
//              docs/superpowers/specs/2026-07-28-vales-por-area-y-bascula-design.md).
//
// Hasta hoy el único disparo pre-pago vivía enterrado en el flujo de MESAS
// (TableOrderViewModel.printRoundComandas). Esta clase saca el mecanismo de ahí para que un vale
// pueda usarlo sin duplicar el ruteo: PrintRoutingMapper.buildComandas y ComandaPrinter.printComandas
// siguen siendo los mismos de siempre, esto sólo es la secuencia que los encadena.
//
// 🔴 LA REGLA QUE MANDA SOBRE TODO LO DEMÁS. Este archivo está en el camino de impresión de
// comandas, y ese camino lo usan TODOS los restaurantes. Un venue SIN el feature `AREA_TICKETS`
// tiene que comportarse EXACTAMENTE igual que antes de que esta clase existiera: las mismas
// llamadas, con los mismos argumentos, en el mismo orden. Por eso [ComandaDispatcher.dispatch] no
// consulta feature flags, ni planes, ni modos de área — es puro mecanismo. La decisión de "¿este
// venue emite vales?" vive en quien emite el vale, jamás aquí: un restaurante que deja de imprimir
// comandas es una cocina que no se entera de los pedidos.
//
// Y por la misma razón NO hay guard de configuración delante de la impresión
// (.claude/rules/offline-first-y-hub-lan.md §4.1a): sin estaciones activas se rutea igual, el motor
// arma un ticket "SIN ESTACIÓN" y ComandaPrinter cae a la impresora KITCHEN local. La ÚNICA
// excepción es [NoStationsFallback.LegacySingleTicket], y existe para reproducir tal cual lo que el
// mostrador YA hacía — no para dejar de imprimir.
package com.avoqado.pos.core.domain.printing

import android.util.Log
import com.avoqado.pos.printing.data.ComandaPrinter
import com.avoqado.pos.printing.data.PrinterService
import com.avoqado.pos.printing.data.model.KitchenItem
import com.avoqado.pos.printing.data.model.KitchenTicketData
import com.avoqado.pos.printing.routing.PrintConfigRepository
import com.avoqado.pos.printing.routing.PrintRoutingMapper
import com.avoqado.pos.printing.routing.RoutableItem
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ComandaDispatcher"

/**
 * Qué hacer cuando el venue no tiene NINGUNA estación activa. Los dos flujos que ya existían hacían
 * cosas distintas y las dos son correctas para su caso — por eso es un parámetro explícito y no una
 * decisión escondida adentro del despachador.
 */
sealed interface NoStationsFallback {

    /**
     * Rutear igual (fail-open). El motor mete todo en un plan sin estación y [ComandaPrinter] cae a
     * la impresora con rol KITCHEN. Es lo que hace el flujo de MESAS —incluso sin red— y es el
     * default para cualquier disparo nuevo, vales incluidos.
     */
    data object RouteAnyway : NoStationsFallback

    /**
     * UN solo ticket de cocina abanicado a TODAS las impresoras con rol KITCHEN
     * ([PrinterService.autoPrintKitchenTicket]). Es EXACTAMENTE lo que el mostrador hace hoy después
     * de cobrar cuando el venue no tiene estaciones configuradas.
     *
     * Los [KitchenItem] los arma quien llama, a propósito: ese ticket lleva `category` (el subtítulo
     * del carrito) y el ruteo por estaciones no la lleva. Pasándolos ya construidos, el ticket sale
     * idéntico al de antes en vez de "parecido".
     */
    data class LegacySingleTicket(val items: List<KitchenItem>) : NoStationsFallback
}

@Singleton
class ComandaDispatcher @Inject constructor(
    private val printConfigRepository: PrintConfigRepository,
    private val comandaPrinter: ComandaPrinter,
    private val printerService: PrinterService,
) {

    /**
     * Dispara las comandas de [lines]. Es la MISMA secuencia de siempre, en el mismo orden:
     *
     *  1. refrescar la config de impresión (sólo si hay [venueId] — igual que antes),
     *  2. leer la config vigente (cache-first: un refresh fallido nunca borra la buena),
     *  3. rutear con [PrintRoutingMapper.buildComandas] e imprimir con [ComandaPrinter.printComandas];
     *     salvo que no haya estaciones activas y quien llama haya pedido
     *     [NoStationsFallback.LegacySingleTicket].
     *
     * Nunca lanza hacia afuera de lo que ya lanzaba: `refresh` falla en silencio por contrato y
     * `printComandas` envuelve cada estación por separado.
     *
     * @return el [ComandaPrinter.Result] del ruteo, para que el caller pueda AVISAR de una
     *   comanda que no salió (Testarudo cobró días sin comanda de barra porque este resultado
     *   se tiraba). `null` cuando no hubo ruteo que reportar: sin renglones, o el camino
     *   legado ([NoStationsFallback.LegacySingleTicket]), cuyo abanico es fire-and-forget.
     *   El aviso es del caller — imprimir jamás frena un cobro.
     */
    suspend fun dispatch(
        venueId: String?,
        lines: List<RoutableItem>,
        orderNumber: String,
        orderType: String,
        serverName: String? = null,
        noStationsFallback: NoStationsFallback = NoStationsFallback.RouteAnyway,
    ): ComandaPrinter.Result? {
        // Sin renglones no hay nada que imprimir — y nos ahorramos hasta el refresh, igual que el
        // mostrador, que salía antes de tocar la red. No es un guard de configuración: es que
        // literalmente no hay qué mandar a cocina.
        if (lines.isEmpty()) return null

        // El refresh nunca lanza (falla abierto y conserva la config vigente), así que una red lenta
        // o caída sólo significa "imprime con lo último que sabías" — jamás "no imprimas".
        venueId?.let { printConfigRepository.refresh(it) }
        val config = printConfigRepository.getCurrentConfig()

        val legacy = noStationsFallback as? NoStationsFallback.LegacySingleTicket
        if (legacy != null && config.stations.none { it.active }) {
            printerService.autoPrintKitchenTicket(
                KitchenTicketData(
                    orderNumber = orderNumber,
                    orderType = orderType,
                    items = legacy.items,
                ),
            )
            return null
        }

        val plans = PrintRoutingMapper.buildComandas(lines, config)
        return comandaPrinter.printComandas(
            plans = plans,
            config = config,
            orderNumber = orderNumber,
            orderType = orderType,
            serverName = serverName,
            // COMBOS — el nombre viaja aparte del motor de ruteo (que es espejo byte a byte
            // del server y no sabe de promociones) y se vuelve a atar por `orderItemId` ya
            // ruteado, para que cada estación encabece SUS productos con su combo.
            comboNames = lines.mapNotNull { line -> line.comboName?.let { line.orderItemId to it } }.toMap(),
        )
    }

    /**
     * PRE-PAGO — punto de entrada del vale de área (§5.6). **Todavía nadie lo llama**: conectar el
     * vale va en otro cambio; esto es el enchufe, no el cable.
     *
     * Quien emita el vale es quien debe haber comprobado que el venue tiene `AREA_TICKETS`. Aquí NO
     * se comprueba, a propósito: un feature flag adentro del camino de impresión es justo el guard
     * que ya dejó a un local entero sin comandas.
     *
     * @return `true` si la comanda se disparó; `false` si el modo del área no la pide en este
     *   momento (ver [AreaComandaPolicy], que es donde vive esa decisión y donde se prueba).
     */
    suspend fun dispatchAreaComanda(
        venueId: String?,
        lines: List<RoutableItem>,
        areaTicketCode: String,
        areaName: String?,
        mode: FulfillmentMode,
        moment: ComandaMoment,
        serverName: String? = null,
    ): Boolean {
        if (!AreaComandaPolicy.shouldPrint(mode, moment)) {
            Log.d(TAG, "🎟️ Vale $areaTicketCode: $mode no pide comanda en $moment — se omite")
            return false
        }

        dispatch(
            venueId = venueId,
            lines = lines,
            // El vale (10 dígitos) es el papel que el cliente trae en la mano y contra el que el
            // área compara. El `orderNumber` de la orden es `ORD-<epoch>`, 17 caracteres, y no cabe
            // confiable en 58 mm (§4.4 del spec).
            orderNumber = areaTicketCode,
            orderType = areaTicketHeader(areaName, moment),
            serverName = serverName,
            // Vales: fail-open siempre. Un área sin estación configurada tiene que seguir sacando su
            // papel por la impresora de cocina local.
            noStationsFallback = NoStationsFallback.RouteAnyway,
        )
        return true
    }

    /** Encabezado del ticket: el área, y si ya está pagado lo dice — el área lo necesita para saber
     *  si puede entregar o sólo preparar. */
    private fun areaTicketHeader(areaName: String?, moment: ComandaMoment): String {
        val area = areaName?.takeIf { it.isNotBlank() } ?: "Vale de área"
        return if (moment == ComandaMoment.AREA_TICKET_PAID) "$area · PAGADO" else area
    }
}
