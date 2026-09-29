package com.avoqado.pos.printing.routing

import com.avoqado.pos.core.data.lan.KdsComanda
import com.avoqado.pos.core.data.lan.KdsComandaItem

/**
 * Etapa 3 del KDS, fase 3.4 — «la caja decide por estación» (spec docs/superpowers/specs/2026-09-27-kds-etapa-3-design.md
 * §5). PURA: sin red, sin impresora, sin reloj. Espejo EXACTO de `KitchenDeliveryPolicy.swift` de avoqado-ios: mismos
 * casos en `KitchenDeliveryPolicyTest` / `KitchenDeliveryPolicyTests`, mismos textos.
 *
 * | Estación                                           | Qué hace la caja                                          |
 * |----------------------------------------------------|-----------------------------------------------------------|
 * | sólo impresora                                     | imprime como hoy                                          |
 * | impresora + pantalla                               | imprime como hoy y además la empuja por el WiFi del local |
 * | sólo pantalla y ≥ 1 pantalla suya ACUSÓ por WiFi   | NO imprime: la pantalla ya la tiene                       |
 * | sólo pantalla sin acuse                            | papel de RESPALDO + marca `FALLBACK_PRINTED`, con o sin internet |
 *
 * 🔴 Desde la 3.5 (D6) la única prueba de que la pantalla va a ver la comanda es su ACUSE por el WiFi del local. Que la
 * venta llegara al servidor ya no basta: la pantalla puede estar apagada con la caja en línea (primer límite de la 3.4).
 * Sin acuse, papel.
 */
object KitchenDeliveryPolicy {

    /** `action` del intent `KDS_TICKET_MARK` (servidor: `applyKdsTicketMark` en sync.mobile.service.ts). */
    const val FALLBACK_PRINTED = "FALLBACK_PRINTED"

    /** `action` del intent `KDS_TICKET_MARK` cuando la pantalla marcó LISTO sin red (3.5, D10). */
    const val BUMP = "BUMP"

    /**
     * «Sólo pantalla» es EXACTAMENTE: pantalla efectiva (casilla Y plan, la manda el servidor) y sin impresora. No «sin
     * impresora guardada»: un venue SIN pantallas sigue imprimiendo igual que hoy (H4 de la spec).
     */
    fun esSoloPantalla(station: StationInfo): Boolean =
        station.active && station.hasKitchenDisplay && station.printerId == null

    data class Reparto(
        /** Los planes que SÍ salen en papel (incluidos los de respaldo). */
        val aImprimir: List<TicketPlan>,
        /** Estaciones «sólo pantalla» que salen en papel de respaldo porque ninguna de sus pantallas acusó. */
        val respaldo: List<String>,
    )

    /**
     * Etapa 3 del KDS (3.5, D6): el ACUSE de la pantalla decide, no el servidor. Una estación «sólo pantalla» con acuse
     * de ≥ 1 pantalla no se imprime; sin acuse sale en papel de RESPALDO, con o sin internet (cierra el primer límite de
     * la 3.4: pantalla apagada con la caja en línea). Por ESTACIÓN: Barra puede acusar mientras Postres está apagada.
     *
     * @param acusadas ids de estación con acuse por WiFi en ESTE envío (vacío = nadie contestó, o no se empujó).
     */
    fun decidir(plans: List<TicketPlan>, config: PrintConfig, acusadas: Set<String>): Reparto {
        val soloPantalla = config.stations.filter { esSoloPantalla(it) }.map { it.id }.toSet()
        if (soloPantalla.isEmpty()) return Reparto(plans, emptyList())
        val aImprimir = plans.filter { it.stationId !in soloPantalla || it.stationId !in acusadas }
        val respaldo = plans.mapNotNull { it.stationId }.filter { it in soloPantalla && it !in acusadas }.distinct()
        return Reparto(aImprimir, respaldo)
    }

    /** Los planes que se EMPUJAN por WiFi (D6): estación activa con pantalla (sólo pantalla o impresora+pantalla). «Sin estación» no. */
    fun planesConPantalla(plans: List<TicketPlan>, config: PrintConfig): List<TicketPlan> =
        plans.filter { p -> p.stationId != null && config.stations.any { it.id == p.stationId && it.active && it.hasKitchenDisplay } }

    /** El mensaje `comanda` de UN plan (D4). El folio es el del servidor; el id de cada renglón, el del carrito o uno sintético. */
    fun mensajeParaPantalla(
        plan: TicketPlan,
        venueId: String,
        deviceId: String,
        origen: String,
        orderNumber: String,
        orderType: String,
        orderId: String?,
        createdAtMillis: Long,
        /** KDS 3.6: el tiempo de estos renglones («Aperitivos»); viaja en cada uno porque la pantalla junta los cursos. */
        curso: String? = null,
    ): KdsComanda {
        val sourceKey = folio(origen, plan.stationId)
        return KdsComanda(
            venueId = venueId,
            deviceId = deviceId,
            sourceKey = sourceKey,
            stationId = requireNotNull(plan.stationId) { "un plan sin estación no se empuja" },
            orderNumber = orderNumber,
            orderType = orderType,
            orderId = orderId,
            createdAtMillis = createdAtMillis,
            items = plan.lines.mapIndexed { i, l ->
                KdsComandaItem(
                    id = l.orderItemIds.firstOrNull() ?: "$sourceKey#$i", productName = l.productName, quantity = l.quantity,
                    modifiers = l.modifiers, notes = l.notes, course = curso,
                )
            },
        )
    }

    /**
     * La config CONGELADA de un trabajo de impresión, con sus estaciones de respaldo marcadas (`respaldoLocal`).
     * `ComandaPrinter` lee la marca; `TrabajoPendiente` la conserva para los reintentos.
     */
    fun conRespaldo(config: PrintConfig, respaldo: Collection<String>): PrintConfig {
        if (respaldo.isEmpty()) return config
        return config.copy(stations = config.stations.map { if (it.id in respaldo) it.copy(respaldoLocal = true) else it })
    }

    /** «Volver a imprimir» con la config VIGENTE sin olvidar qué estaciones iban de respaldo. */
    fun heredarRespaldo(de: PrintConfig, en: PrintConfig): PrintConfig =
        conRespaldo(en, de.stations.filter { it.respaldoLocal }.map { it.id })

    /** Encabezado de la hoja de respaldo (spec §5). La impresora lo pone en mayúsculas. */
    fun encabezadoDeRespaldo(estacion: String): String = "RESPALDO · $estacion · pantalla sin conexión"

    /**
     * El folio de la comanda — espejo EXACTO del servidor (`kitchenTicketPlanning.ts`: `originKeyFor` +
     * `:${destino ?? 'none'}`). Mostrador: `sale:<Order.externalId>`; mesa: `round:<roundKey>`. Si no coincidieran, la
     * marca quedaría huérfana y la cocina vería la comanda en papel Y en pantalla (M-6 de la 3.1): nunca se pierde.
     */
    fun folio(origen: String, stationId: String?): String = "$origen:${stationId ?: "none"}"

    data class MarcaDePapel(val sourceKey: String, val stationId: String, val label: String)

    /**
     * Las marcas `FALLBACK_PRINTED` de las estaciones de respaldo que SÍ salieron en papel.
     *
     * 🔴 La que NO salió no se marca: al volver la red, la pantalla la muestra (tarde, pero la cocina se entera). Marcar
     * antes de imprimir y que el papel no salga escondería una comanda que nadie vio.
     *
     * @param sinPapel nombres de las estaciones que se quedaron sin comanda (`EstadoDeComanda.NoSalio.estaciones`).
     * @param origen `sale:<externalId>` o `round:<roundKey>`; `null` = no se sabe el folio ⇒ no se marca nada.
     */
    fun marcasDeRespaldo(
        respaldo: List<String>,
        config: PrintConfig,
        sinPapel: List<String>,
        origen: String?,
        label: String,
    ): List<MarcaDePapel> {
        if (origen == null) return emptyList()
        return respaldo.mapNotNull { id ->
            val nombre = config.stations.firstOrNull { it.id == id }?.name ?: return@mapNotNull null
            // ponytail: se compara por NOMBRE porque `EstadoDeComanda` sólo trae nombres; dos estaciones con el mismo
            // nombre compartirían el veredicto. Si eso llega a pasar, que `NoSalio` traiga los ids.
            if (nombre in sinPapel) null else MarcaDePapel(folio(origen, id), id, label)
        }
    }

    /**
     * La línea que la banda de «Sin conexión» agrega (3.5, D11): sin red, estas estaciones salen en papel SI LA PANTALLA
     * NO CONTESTA por el WiFi. En el orden de la config (el servidor ya la manda por `displayOrder`). `null` = no hay
     * estaciones «sólo pantalla».
     */
    fun avisoSinRed(config: PrintConfig): String? {
        val nombres = config.stations.filter { esSoloPantalla(it) }.map { it.name }
        if (nombres.isEmpty()) return null
        return "Las comandas de ${lista(nombres)} salen en papel si la pantalla no contesta"
    }

    /**
     * Racha (D11): tras 3 entregas seguidas sin acuse a una estación, se dice fijo — con o sin internet. `null` = ninguna.
     *
     * 🔴 I1 de la revisión de la Task 10: sólo nombra estaciones ACTIVAS con pantalla (mismo predicado que decide a
     * quién se empuja, [planesConPantalla]). Si el dueño apaga la pantalla de una estación después de que la caja
     * avisó, esa estación ya no vuelve a acusar — sin este filtro el aviso se quedaría pegado para siempre aunque ya
     * no haya pantalla que alcanzar.
     */
    fun avisoDeRacha(sinAlcance: Set<String>, config: PrintConfig): String? {
        val nombres = config.stations.filter { it.id in sinAlcance && it.active && it.hasKitchenDisplay }.map { it.name }
        if (nombres.isEmpty()) return null
        return if (nombres.size == 1) {
            "La pantalla de ${nombres.single()} no se alcanza por el WiFi"
        } else {
            "Las pantallas de ${lista(nombres)} no se alcanzan por el WiFi"
        }
    }

    private fun lista(nombres: List<String>): String =
        if (nombres.size == 1) nombres.single() else nombres.dropLast(1).joinToString(", ") + " y " + nombres.last()
}
