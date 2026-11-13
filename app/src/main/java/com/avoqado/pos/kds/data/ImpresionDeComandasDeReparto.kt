package com.avoqado.pos.kds.data

import android.util.Log
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.domain.printing.ComandaDispatcher
import com.avoqado.pos.kds.domain.ImpresionReparto
import com.avoqado.pos.kds.domain.KDSOrder
import com.avoqado.pos.printing.data.EstadoDeComanda
import com.avoqado.pos.printing.data.TrabajoPendiente
import com.avoqado.pos.printing.routing.ConsolidatedLine
import com.avoqado.pos.printing.routing.PrintConfig
import com.avoqado.pos.printing.routing.PrintConfigRepository
import com.avoqado.pos.printing.routing.PrintRoutingMapper
import com.avoqado.pos.printing.routing.RoutableItem
import com.avoqado.pos.printing.routing.TicketPlan
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

private const val TAG = "🍳 ImpresionReparto"

/**
 * Lo que pasó con la comanda de un pedido de reparto que ESTE aparato reclamó.
 *
 * @property salioEnPapel todo salió: la comanda y el ticket de empaque (`faltaron` vacío).
 * @property faltaron estaciones sin papel (incluidas las saltadas por no tener impresora).
 * @property porReimprimir lo que el botón «Reimprimir lo que faltó» puede mandar, congelado y con sus copias.
 * @property sinReimprimir estaciones de un `NoSalio` sin trabajo reenviable (se saltaron: no tienen impresora). El botón no
 *   las puede mandar y reimprimir otra cosa NUNCA las borra de [faltaron].
 */
data class ComandaImpresa(
    val kdsId: String,
    val orderNumber: String,
    val createdAt: Long,
    val salioEnPapel: Boolean,
    val faltaron: List<String> = emptyList(),
    val porReimprimir: List<TrabajoPendiente> = emptyList(),
    val sinReimprimir: List<String> = emptyList(),
    /** La sucursal del aparato al imprimir: la franja sólo enseña los de la sucursal de ahora. */
    val venueId: String? = null,
    /** `RAPPI`, `UBER_EATS`…, de `impresionReparto` del pedido: la franja dice de quién es CADA pedido. `null` = servidor viejo. */
    val proveedor: String? = null,
)

/**
 * Saca en papel las comandas de Rappi/Uber que llegaron SOLAS, desde cualquier pantalla (la de cocina, o un vigilante
 * de todo el proceso) y apunta cada resultado en la pizarra [AvisosDeReparto], que es lo que enseña la franja.
 */
@Singleton
class ImpresionDeComandasDeReparto @Inject constructor(
    private val kdsRepository: KDSRepository,
    private val comandaDispatcher: ComandaDispatcher,
    private val printConfigRepository: PrintConfigRepository,
    // El deviceId del outbox, NO uno nuevo (regla de offline-first).
    private val syncOutbox: Provider<SyncOutbox>,
    private val avisos: AvisosDeReparto,
) {
    /**
     * Saca en papel las comandas que llegaron SOLAS y que nadie ha impreso, donde lo eligió su canal
     * (`impresionReparto`: como en el salón o todo junto en una estación, y el ticket de empaque).
     *
     * 🔴 Primero se RECLAMA en el servidor y sólo el ganador imprime. Un pedido de
     * marketplace aparece a la vez en todas las pantallas de cocina: sin árbitro, las tres
     * tablets del local sacan el mismo papel tres veces.
     *
     * `confirm-print` / `release-print` se decide con las hojas que salieron de los DOS papeles juntos (comanda y
     * empaque). Si NO salió ni una hoja se SUELTA en el acto, para que otro aparato lo intente sin esperar a que caduque
     * la reclamación. Una tablet sin papel no puede dejar a la cocina sin enterarse del pedido.
     *
     * 🔴 Si salió una PARTE (Cocina sí, Barra no) se CONFIRMA: soltarla haría que el siguiente sondeo reimprimiera
     * TODO, y la cocina recibiría dos veces lo que ya salió. Lo que faltó se apunta en [avisos] para que una
     * persona lo reimprima con [reimprimirLoQueFalto], que manda sólo eso.
     */
    suspend fun imprimirPendientes(pedidos: List<KDSOrder>) {
        // Un pedido MANUAL que falta aceptar no sale en papel desde NINGÚN llamador (vigilante o pantalla de cocina): sale
        // al aceptarlo. El filtro vive aquí para que cualquier llamador lo tenga.
        val pendientes = pedidos.filter { it.needsPrint && !it.needsAcceptance }
        if (pendientes.isEmpty()) return

        val deviceId = runCatching { syncOutbox.get().deviceId }.getOrNull() ?: return
        val venueId = kdsRepository.venueIdActual()

        for (pedido in pendientes) {
            // La época se toma ANTES de imprimir: si alguien toca «Entendido» o cambia de sucursal a media impresión,
            // este resultado ya no se apunta.
            val epoca = avisos.epoca()
            if (!kdsRepository.reclamarImpresion(pedido.id, deviceId)) continue

            // 🔴 UNA config por pedido (Codex p1 #2 y p2 #3): un refresco con tope y con ESA se decide y salen los dos
            // papeles. Sin esto, «un solo papel» (que no pasa por `dispatch`) salía con la impresora vieja de la caché, y
            // una descarga tardía entre la comanda y el empaque los mandaba a impresoras distintas.
            venueId?.let { printConfigRepository.refreshConTope(it) }
            val config = printConfigRepository.getCurrentConfig()

            val lineas = pedido.items.map { item ->
                RoutableItem(
                    orderItemId = item.id,
                    productId = item.productId,
                    categoryId = item.categoryId,
                    productName = item.productName,
                    quantity = item.quantity,
                    modifiers = item.modifiers,
                    notes = item.notes,
                )
            }
            val impresion = pedido.impresionReparto
            val empaque = estacionDeEmpaque(impresion, config)
            val estacionFija = impresion?.estacionUnicaId
            val papel = Papel()

            when {
                // «Un solo papel» (spec §7.1): todo junto en la misma estación del empaque ⇒ UN ticket con todo el pedido
                // («EMPAQUE · Delivery»), no dos casi iguales.
                estacionFija != null && estacionFija == empaque ->
                    imprimirTicketDeEmpaque(pedido, lineas, venueId, config, estacionFija, papel)
                // 🔴 Lo «Sin comanda» sale de la comanda SÓLO si el empaque, que lo lleva, YA imprimió (Codex p1 #1): por
                // eso el empaque va primero. Si no salió, la comanda lo conserva, como hoy: nunca un pedido sin su papel.
                empaque != null && PrintRoutingMapper.rutear(lineas, config, omitirSinComanda = true).omitidasSinComanda > 0 -> {
                    val salioElEmpaque = imprimirTicketDeEmpaque(pedido, lineas, venueId, config, empaque, papel)
                    imprimirComanda(pedido, lineas, venueId, config, estacionFija, omitirSinComanda = salioElEmpaque, papel)
                }
                // El orden de siempre. Si la comanda no sacó nada, el empaque NO sale: sería el único papel de un pedido que
                // la cocina no tiene, y se suelta para que otro aparato saque los dos.
                else -> if (imprimirComanda(pedido, lineas, venueId, config, estacionFija, omitirSinComanda = false, papel) && empaque != null) {
                    imprimirTicketDeEmpaque(pedido, lineas, venueId, config, empaque, papel)
                }
            }

            if (!papel.salio) {
                kdsRepository.marcarImpresion(pedido.id, deviceId, "release-print")
                Log.e(TAG, "No salió en papel el pedido ${pedido.orderNumber}; soltado para que otro aparato lo intente")
                avisos.publicar(
                    ComandaImpresa(
                        kdsId = pedido.id, orderNumber = pedido.orderNumber, createdAt = pedido.createdAt, salioEnPapel = false,
                        faltaron = papel.faltaron.distinct(),
                        venueId = venueId,
                        proveedor = impresion?.proveedor,
                    ),
                    epoca,
                )
                continue
            }

            kdsRepository.marcarImpresion(pedido.id, deviceId, "confirm-print")
            avisos.publicar(
                ComandaImpresa(
                    kdsId = pedido.id, orderNumber = pedido.orderNumber, createdAt = pedido.createdAt,
                    salioEnPapel = papel.faltaron.isEmpty(),
                    faltaron = papel.faltaron.distinct(),
                    porReimprimir = papel.porReimprimir,
                    sinReimprimir = papel.sinReimprimir.distinct(),
                    venueId = venueId,
                    proveedor = impresion?.proveedor,
                ),
                epoca,
            )
        }
    }

    /**
     * Dónde sale el ticket de empaque de este pedido, o `null` si no sale. Sin instrucción del canal (servidor viejo o
     * canal sin ajuste), el del negocio como hoy; la estación del canal sólo si sigue elegible en ESTA config.
     */
    private fun estacionDeEmpaque(impresion: ImpresionReparto?, config: PrintConfig): String? = when {
        impresion == null -> config.packingStationId
        !impresion.empaque -> null
        else -> impresion.empaqueEstacionId
            ?.takeIf { id -> config.stations.any { it.id == id && it.active && it.sendsComanda } }
            ?: config.packingStationId
    }

    /** La comanda de cocina; apunta su resultado en [papel] y dice si sacó papel. */
    private suspend fun imprimirComanda(
        pedido: KDSOrder,
        lineas: List<RoutableItem>,
        venueId: String?,
        config: PrintConfig,
        estacionFija: String?,
        omitirSinComanda: Boolean,
        papel: Papel,
    ): Boolean {
        // 🔴 «No lanzó excepción» NO es «imprimió». `dispatch` NUNCA lanza ante un fallo de
        // impresora: devuelve el estado. Con `.isSuccess` el KDS mandaba `confirm-print`
        // sobre una comanda que no salió, y eso IMPIDE que otra tablet la recoja — el pedido
        // se pierde con la reclamación consumida (P1 #11 de la auditoría de Codex).
        val intento = runCatching {
            comandaDispatcher.dispatch(
                venueId = venueId,
                lines = lineas,
                orderNumber = pedido.orderNumber,
                orderType = "Delivery",
                // 🔴 SIN reintento: a diferencia del mostrador, el KDS SÍ tiene tablets
                // hermanas que pueden tomar este pedido si esta impresora no lo saca. Con
                // el default (~1 min) la reclamación se quedaría retenida todo ese tiempo
                // en vez de soltarse en segundos (`release-print`) para que otro
                // aparato la reclame — comportamiento EXACTO de antes del reintento.
                maxIntentos = 1,
                // «La libreta» (Task 16) — el id REAL de la orden, ya presente en el pedido
                // (KDSRepository lo llena del JSON del server); sin él, «la libreta» caía al
                // orderNumber para armar el eventId, correcto pero menos preciso que el id real.
                orderId = pedido.orderId,
                // La config del pedido: `dispatch` no refresca ni relee la vigente.
                configDeLaRonda = config,
                omitirSinComanda = omitirSinComanda,
                estacionFija = estacionFija,
                // Quitar lo «Sin comanda» nunca calla un fallo: en delivery la comanda que no salió siempre se avisa.
                callarSinCocina = false,
            )
        }
        intento.exceptionOrNull()?.let { Log.e(TAG, "No se pudo imprimir la comanda de ${pedido.orderNumber}: ${it.message}") }
        return papel.apuntar(intento, siTruena = "Comanda")
    }

    /**
     * El ticket de EMPAQUE: el pedido completo en UNA hoja, para quien mete todo en la bolsa y se la da al repartidor.
     * No es una comanda —esas dicen qué cocinar y cada estación ve sólo su parte—: es la lista de verificación de la bolsa.
     * En una mesa el mesero lleva los platos y ve al cliente; aquí, si falta una salsa el cliente se entera en su casa, y
     * eso acaba en reembolso. Sale por el MISMO camino que «Reimprimir» ([TrabajoPendiente]) para que sus copias queden
     * contadas, y con la config del pedido ([config]), no con la vigente. Apunta en [papel] y dice si sacó papel.
     */
    private suspend fun imprimirTicketDeEmpaque(
        pedido: KDSOrder,
        lineas: List<RoutableItem>,
        venueId: String?,
        config: PrintConfig,
        estacion: String,
        papel: Papel,
    ): Boolean {
        // UN solo plan con TODOS los renglones, dirigido a la estación de empaque. Se arma
        // aquí en vez de rutear, justamente porque el punto es que NO se reparta.
        val plan = TicketPlan(
            stationId = estacion,
            unrouted = false,
            lines = lineas.map { l ->
                ConsolidatedLine(
                    productName = l.productName,
                    quantity = l.quantity,
                    modifiers = l.modifiers,
                    notes = l.notes,
                    orderItemIds = listOf(l.orderItemId),
                )
            },
        )
        val trabajo = TrabajoPendiente(
            planes = listOf(plan),
            config = config,
            orderNumber = pedido.orderNumber,
            // Lo que se lee ARRIBA del papel. Tiene que gritar que es para empacar, no
            // otra comanda de cocina.
            orderType = "EMPAQUE · Delivery",
            serverName = null,
            comboNames = emptyMap(),
            venueId = venueId,
            orderId = pedido.orderId,
        )
        val intento = runCatching { comandaDispatcher.reintentar(trabajo, refrescar = false, configFija = config) }
        intento.exceptionOrNull()?.let { Log.e(TAG, "No se pudo imprimir el ticket de empaque de ${pedido.orderNumber}: ${it.message}") }
        return papel.apuntar(intento, siTruena = config.stations.firstOrNull { it.id == estacion }?.name ?: "Empaque")
    }

    /** Lo que dejaron los papeles de UN pedido (comanda y empaque); [salio] decide `confirm` o `release`. */
    private class Papel {
        /** Salió al menos una hoja de alguno de los dos (o la comanda no necesitaba papel). */
        var salio = false
        val faltaron = mutableListOf<String>()
        val porReimprimir = mutableListOf<TrabajoPendiente>()
        val sinReimprimir = mutableListOf<String>()

        /**
         * Apunta un intento y dice si sacó papel. `SinComanda` no es papel propio (no confirma solo); `null` (nada que
         * imprimir: todo iba a pantallas) cuenta como hecho, como siempre. Lo que truena falta y no tiene qué reenviar.
         */
        fun apuntar(intento: Result<EstadoDeComanda?>, siTruena: String): Boolean {
            val estado = intento.getOrElse {
                faltaron += siTruena
                sinReimprimir += siTruena
                return false
            }
            if (estado is EstadoDeComanda.NoSalio) {
                faltaron += estado.estaciones
                if (estado.trabajo != null) porReimprimir += estado.trabajo else sinReimprimir += estado.estaciones
            }
            val hoja = estado == null || estado is EstadoDeComanda.Salio || (estado is EstadoDeComanda.NoSalio && estado.algoSalio)
            salio = salio || hoja
            return hoja
        }
    }

    /**
     * «Reimprimir lo que faltó» del pedido [kdsId]: manda otra vez SÓLO cada [TrabajoPendiente] congelado (con sus copias
     * pendientes) por `reintentar`, nunca `dispatch` — que volvería a rutear el pedido entero y repetiría lo que ya salió.
     * Apunta la versión nueva ANTES de soltar el pedido, y la devuelve; `null` si no había nada que mandar o ya había un
     * «Reimprimir» en curso para ese pedido (el segundo toque no manda otra copia).
     */
    suspend fun reimprimirLoQueFalto(kdsId: String): ComandaImpresa? {
        val (c, epoca) = avisos.tomarParaReimprimir(kdsId) ?: return null
        try {
            // Lo que no tiene impresora sigue faltando pase lo que pase: reimprimir el empaque no lo borra.
            val faltaron = c.sinReimprimir.toMutableList()
            val porReimprimir = mutableListOf<TrabajoPendiente>()
            val sinReimprimir = c.sinReimprimir.toMutableList()
            for (trabajo in c.porReimprimir) {
                val estado = runCatching { comandaDispatcher.reintentar(trabajo) }.getOrNull()
                when {
                    estado is EstadoDeComanda.NoSalio -> {
                        faltaron += estado.estaciones
                        if (estado.trabajo != null) porReimprimir += estado.trabajo else sinReimprimir += estado.estaciones
                    }
                    // Salio / SinComanda: este trabajo ya no falta.
                    estado is EstadoDeComanda.Salio || estado is EstadoDeComanda.SinComanda -> Unit
                    // Excepción u otro estado: no se sabe qué salió, se conserva el trabajo tal cual para otro intento.
                    else -> {
                        faltaron += trabajo.planes.mapNotNull { it.stationId }
                        porReimprimir += trabajo
                    }
                }
            }
            val nueva = c.copy(
                salioEnPapel = faltaron.isEmpty(),
                faltaron = faltaron.distinct(),
                porReimprimir = porReimprimir,
                sinReimprimir = sinReimprimir.distinct(),
            )
            avisos.publicar(nueva, epoca)
            return nueva
        } finally {
            avisos.soltarReimpresion(kdsId)
        }
    }
}
