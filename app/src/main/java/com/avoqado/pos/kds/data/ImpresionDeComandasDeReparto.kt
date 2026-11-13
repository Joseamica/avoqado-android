package com.avoqado.pos.kds.data

import android.util.Log
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.domain.printing.ComandaDispatcher
import com.avoqado.pos.kds.domain.KDSOrder
import com.avoqado.pos.printing.data.EstadoDeComanda
import com.avoqado.pos.printing.data.TrabajoPendiente
import com.avoqado.pos.printing.routing.ConsolidatedLine
import com.avoqado.pos.printing.routing.PrintConfigRepository
import com.avoqado.pos.printing.routing.RoutableItem
import com.avoqado.pos.printing.routing.TicketPlan
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
)

/**
 * Saca en papel las comandas de Rappi/Uber que llegaron SOLAS, desde cualquier pantalla (la de cocina, o un vigilante
 * de todo el proceso) y publica cada resultado en [impresas].
 */
@Singleton
class ImpresionDeComandasDeReparto @Inject constructor(
    private val kdsRepository: KDSRepository,
    private val comandaDispatcher: ComandaDispatcher,
    private val printConfigRepository: PrintConfigRepository,
    // El deviceId del outbox, NO uno nuevo (regla de offline-first).
    private val syncOutbox: Provider<SyncOutbox>,
) {
    private val _impresas = MutableSharedFlow<ComandaImpresa>(extraBufferCapacity = 64)
    val impresas: SharedFlow<ComandaImpresa> = _impresas.asSharedFlow()

    /**
     * Saca en papel las comandas que llegaron SOLAS y que nadie ha impreso.
     *
     * 🔴 Primero se RECLAMA en el servidor y sólo el ganador imprime. Un pedido de
     * marketplace aparece a la vez en todas las pantallas de cocina: sin árbitro, las tres
     * tablets del local sacan el mismo papel tres veces.
     *
     * Si NO salió nada se SUELTA en el acto, para que otro aparato lo intente sin
     * esperar a que caduque la reclamación. Una tablet sin papel no puede dejar a la cocina
     * sin enterarse del pedido.
     *
     * 🔴 Si salió una PARTE (Cocina sí, Barra no) se CONFIRMA: soltarla haría que el siguiente sondeo reimprimiera
     * TODO, y la cocina recibiría dos veces lo que ya salió. Lo que faltó se publica en [impresas] para que una
     * persona lo reimprima con [reimprimirLoQueFalto], que manda sólo eso.
     */
    suspend fun imprimirPendientes(pedidos: List<KDSOrder>) {
        val pendientes = pedidos.filter { it.needsPrint }
        if (pendientes.isEmpty()) return

        val deviceId = runCatching { syncOutbox.get().deviceId }.getOrNull() ?: return
        val venueId = kdsRepository.venueIdActual()

        for (pedido in pendientes) {
            if (!kdsRepository.reclamarImpresion(pedido.id, deviceId)) continue

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
                    // en vez de soltarse en segundos (`release-print`, abajo) para que otro
                    // aparato la reclame — comportamiento EXACTO de antes del reintento.
                    maxIntentos = 1,
                    // «La libreta» (Task 16) — el id REAL de la orden, ya presente en el pedido
                    // (KDSRepository lo llena del JSON del server); sin él, «la libreta» caía al
                    // orderNumber para armar el eventId, correcto pero menos preciso que el id real.
                    orderId = pedido.orderId,
                )
            }
            val estado = intento.getOrNull()

            // `null` = todo fue a pantallas (nada que imprimir); `Salio` y `SinComanda` tampoco dejan nada pendiente.
            val comandaOk = intento.isSuccess && (estado == null || estado is EstadoDeComanda.Salio || estado is EstadoDeComanda.SinComanda)
            val parcial = estado is EstadoDeComanda.NoSalio && estado.algoSalio

            if (!comandaOk && !parcial) {
                // Nada salió: se suelta SIN tocar el empaque (sería el único papel de un pedido que la cocina no tiene).
                kdsRepository.marcarImpresion(pedido.id, deviceId, "release-print")
                Log.e(TAG, "No se pudo imprimir la comanda ${pedido.orderNumber}; soltada para que otro aparato lo intente")
                _impresas.tryEmit(
                    ComandaImpresa(
                        kdsId = pedido.id, orderNumber = pedido.orderNumber, createdAt = pedido.createdAt, salioEnPapel = false,
                        faltaron = (estado as? EstadoDeComanda.NoSalio)?.estaciones.orEmpty(),
                    ),
                )
                continue
            }

            val faltaron = mutableListOf<String>()
            val porReimprimir = mutableListOf<TrabajoPendiente>()
            val sinReimprimir = mutableListOf<String>()
            if (estado is EstadoDeComanda.NoSalio) {
                faltaron += estado.estaciones
                if (estado.trabajo != null) porReimprimir += estado.trabajo else sinReimprimir += estado.estaciones
            }

            // El ticket de EMPAQUE: el pedido completo en UNA hoja, para quien mete todo en
            // la bolsa y se la da al repartidor. No es una comanda —esas dicen qué cocinar y
            // cada estación ve sólo su parte—: es la lista de verificación de la bolsa. En
            // una mesa el mesero lleva los platos y ve al cliente; aquí, si falta una salsa
            // el cliente se entera en su casa, y eso acaba en reembolso.
            //
            // Sólo si el negocio marcó una estación de empaque. Si no marcó ninguna, no sale
            // nada extra: no le cambiamos el papeleo a quien no lo pidió.
            imprimirTicketDeEmpaque(pedido, lineas, venueId, faltaron, porReimprimir, sinReimprimir)

            kdsRepository.marcarImpresion(pedido.id, deviceId, "confirm-print")
            _impresas.tryEmit(
                ComandaImpresa(
                    kdsId = pedido.id, orderNumber = pedido.orderNumber, createdAt = pedido.createdAt,
                    salioEnPapel = faltaron.isEmpty(),
                    faltaron = faltaron.distinct(),
                    porReimprimir = porReimprimir,
                    sinReimprimir = sinReimprimir.distinct(),
                ),
            )
        }
    }

    /**
     * Manda otra vez SÓLO lo que faltó: cada [TrabajoPendiente] congelado (con sus copias pendientes) por `reintentar`,
     * nunca `dispatch` — que volvería a rutear el pedido entero y repetiría lo que ya salió. Publica y devuelve la versión
     * nueva, con lo que siga faltando.
     */
    suspend fun reimprimirLoQueFalto(c: ComandaImpresa): ComandaImpresa {
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
        _impresas.tryEmit(nueva)
        return nueva
    }

    /** Sale por el MISMO camino que «Reimprimir» ([TrabajoPendiente]) para que sus copias queden contadas. */
    private suspend fun imprimirTicketDeEmpaque(
        pedido: KDSOrder,
        lineas: List<RoutableItem>,
        venueId: String?,
        faltaron: MutableList<String>,
        porReimprimir: MutableList<TrabajoPendiente>,
        sinReimprimir: MutableList<String>,
    ) {
        val config = printConfigRepository.getCurrentConfig()
        val estacion = config.packingStationId ?: return

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

        runCatching { comandaDispatcher.reintentar(trabajo, refrescar = false) }
            .onFailure {
                Log.e(TAG, "No se pudo imprimir el ticket de empaque de ${pedido.orderNumber}: ${it.message}")
                faltaron += config.stations.firstOrNull { s -> s.id == estacion }?.name ?: "Empaque"
            }
            .onSuccess { estado ->
                if (estado is EstadoDeComanda.NoSalio) {
                    faltaron += estado.estaciones
                    if (estado.trabajo != null) porReimprimir += estado.trabajo else sinReimprimir += estado.estaciones
                }
            }
    }
}
