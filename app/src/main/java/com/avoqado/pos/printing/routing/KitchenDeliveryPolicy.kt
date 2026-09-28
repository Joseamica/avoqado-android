package com.avoqado.pos.printing.routing

/**
 * Etapa 3 del KDS, fase 3.4 — «la caja decide por estación» (spec docs/superpowers/specs/2026-09-27-kds-etapa-3-design.md
 * §5). PURA: sin red, sin impresora, sin reloj. Espejo EXACTO de `KitchenDeliveryPolicy.swift` de avoqado-ios: mismos
 * casos en `KitchenDeliveryPolicyTest` / `KitchenDeliveryPolicyTests`, mismos textos.
 *
 * | Estación                                           | Qué hace la caja                                          |
 * |----------------------------------------------------|-----------------------------------------------------------|
 * | sólo impresora                                     | imprime como hoy                                          |
 * | impresora + pantalla                               | imprime como hoy (la pantalla la recibe del servidor)     |
 * | sólo pantalla y el servidor YA tiene el envío      | NO imprime: el servidor arma la comanda y la pantalla la ve |
 * | sólo pantalla y el servidor NO lo tiene            | papel de RESPALDO + marca `FALLBACK_PRINTED`              |
 *
 * 🔴 En la 3.4 no hay red local (llega en la 3.5, con acuse de la pantalla). La única prueba de que la pantalla va a ver
 * la comanda es que la venta o la ronda LLEGÓ al servidor, que es quien las arma. Sin esa prueba, papel.
 */
object KitchenDeliveryPolicy {

    /** `action` del intent `KDS_TICKET_MARK` (servidor: `applyKdsTicketMark` en sync.mobile.service.ts). */
    const val FALLBACK_PRINTED = "FALLBACK_PRINTED"

    /**
     * «Sólo pantalla» es EXACTAMENTE: pantalla efectiva (casilla Y plan, la manda el servidor) y sin impresora. No «sin
     * impresora guardada»: un venue SIN pantallas sigue imprimiendo igual que hoy (H4 de la spec).
     */
    fun esSoloPantalla(station: StationInfo): Boolean =
        station.active && station.hasKitchenDisplay && station.printerId == null

    data class Reparto(
        /** Los planes que SÍ salen en papel (incluidos los de respaldo). */
        val aImprimir: List<TicketPlan>,
        /** Estaciones «sólo pantalla» que salen en papel de respaldo porque el servidor no tiene el envío. */
        val respaldo: List<String>,
    )

    fun decidir(plans: List<TicketPlan>, config: PrintConfig, servidorLaTiene: Boolean): Reparto {
        val soloPantalla = config.stations.filter { esSoloPantalla(it) }.map { it.id }.toSet()
        if (soloPantalla.isEmpty()) return Reparto(plans, emptyList())
        if (servidorLaTiene) return Reparto(plans.filter { it.stationId !in soloPantalla }, emptyList())
        return Reparto(plans, plans.mapNotNull { it.stationId }.filter { it in soloPantalla }.distinct())
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
     * La línea que la banda de «Sin conexión» agrega: mientras no haya red, estas estaciones salen en papel. En el orden
     * de la config (el servidor ya la manda por `displayOrder`). `null` = no hay estaciones «sólo pantalla».
     */
    fun avisoSinRed(config: PrintConfig): String? {
        val nombres = config.stations.filter { esSoloPantalla(it) }.map { it.name }
        if (nombres.isEmpty()) return null
        val lista = if (nombres.size == 1) nombres.single() else nombres.dropLast(1).joinToString(", ") + " y " + nombres.last()
        return "Las comandas de $lista salen en papel"
    }
}
