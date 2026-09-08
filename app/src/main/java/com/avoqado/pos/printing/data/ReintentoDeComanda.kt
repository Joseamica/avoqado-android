package com.avoqado.pos.printing.data

import com.avoqado.pos.printing.routing.PrintConfig
import com.avoqado.pos.printing.routing.TicketPlan
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Qué está pasando con la comanda, para que la pantalla pueda decirlo sin adivinar.
 *
 * 🔴 [Insistiendo] y [NoSalio] llevan `orderNumber` — el pedido al que pertenecen. Sin esto,
 * `PaymentFlowViewModel` no puede distinguir el aviso de la venta ANTERIOR (que puede seguir
 * reintentando en segundo plano hasta ~1 minuto) del de la venta que el cajero acaba de cobrar,
 * y un `Salio` tardío de una podía limpiar el aviso —todavía sin resolver— de la otra.
 */
sealed interface EstadoDeComanda {
    data object Salio : EstadoDeComanda
    data class Insistiendo(val intento: Int, val de: Int, val estaciones: List<String>, val orderNumber: String) : EstadoDeComanda

    /**
     * @param trabajo lo que hace falta para volver a mandar EXACTAMENTE lo que faltó. Nulo sólo
     *   cuando no quedó nada reenviable (todas las estaciones se SALTARON: no tienen impresora,
     *   así que insistir no se la inventa). Ver [TrabajoPendiente].
     */
    data class NoSalio(
        val estaciones: List<String>,
        val causa: String?,
        val orderNumber: String,
        val trabajo: TrabajoPendiente? = null,
    ) : EstadoDeComanda
}

/**
 * El trabajo que se quedó sin imprimir, congelado y COMPLETO — la única forma honesta de
 * reintentar a mano.
 *
 * 🔴 Nace de dos P1 de la auditoría de Codex (2026-09-07). Antes, «Volver a imprimir»
 * reconstruía la comanda desde el carrito que el cajero tuviera ENFRENTE, y eso rompía dos
 * cosas a la vez:
 *
 *  1. **Duplicaba.** Si Cocina imprimió y Barra no, reenviar el carrito le mandaba a Cocina un
 *     ticket idéntico — un platillo de más, que es peor que el que faltó.
 *  2. **Imprimía OTRA venta.** Si el cajero ya empezó la venta siguiente, el carrito actual es
 *     el de ESA venta: el botón del aviso de la venta A imprimía la B, con el folio de la B, y
 *     A se quedaba igual de pendiente.
 *
 * Por eso lleva todo lo que necesita el reenvío (planes, config, folio, mesero, combos) en vez
 * de una llave para volver a buscarlo: lo que se busca puede haber cambiado.
 */
@kotlinx.serialization.Serializable
data class TrabajoPendiente(
    val planes: List<TicketPlan>,
    /**
     * Copias que faltan por entregar, por estación.
     *
     * 🔴 Sin esto, «Volver a imprimir» reimprimía las copias que YA salieron: el arreglo de las
     * copias sólo protegía los intentos del MISMO ciclo automático, y el reintento manual
     * arranca un ciclo nuevo (P1 #1 de la 2ª auditoría de Codex, 2026-09-07).
     */
    val copiasPendientes: Map<String?, Int> = emptyMap(),
    /**
     * Estaciones que se SALTARON (sin impresora resoluble). No se reintentan —insistir no les
     * inventa una impresora— pero tienen que seguir contando en el veredicto.
     *
     * 🔴 Sin esto, reparar la impresora de Cocina y reimprimir daba «Salio» y borraba el aviso
     * ENTERO, aunque Barra nunca hubiera impreso: el acumulado de saltadas vivía sólo dentro de
     * una llamada a `insistir` (P1 #2 de la 2ª auditoría de Codex).
     */
    val saltadas: List<String> = emptyList(),
    val config: PrintConfig,
    val orderNumber: String,
    val orderType: String,
    val serverName: String?,
    val comboNames: Map<String, String>,
    val venueId: String?,
    val orderId: String?,
)

/**
 * Insiste con las comandas que no salieron.
 *
 * 🔴 Existe porque la app imprimía la comanda UNA sola vez: si la impresora no contestaba en ese
 * instante, la comanda se perdía para siempre — sin reintento, sin cola, sin botón en mostrador
 * para volver a mandarla. Esto es el que insiste, siguiendo [PoliticaDeReintento].
 *
 * 🔴 Reintenta SÓLO los planes que TRONARON (`Result.failedPlans`). Reintentar el lote entero
 * volvería a imprimir las estaciones que sí salieron — un ticket duplicado en la cocina es
 * peor que el que faltó. Un plan SALTADO (sin impresora resoluble) nunca entra a `failedPlans`
 * a propósito, así que tampoco se reintenta — insistir no le inventa una impresora.
 *
 * La espera se inyecta para que el test barre los seis intentos sin dormir 50 segundos reales.
 *
 * 🔴 `esperar` NO va en el constructor `@Inject`: Dagger/Hilt no puede resolver un tipo función
 * (`suspend (Long) -> Unit`) y, aunque tenga default de Kotlin, Dagger llama al constructor REAL
 * —no al `$default` sintético que genera el compilador— así que el default se ignora y la
 * inyección revienta con `[Dagger/MissingBinding]`. Quedó latente porque nada la usaba a través
 * de Hilt hasta que otra tarea la enganchó en `ComandaDispatcher`. La prueba la reemplaza con el
 * constructor secundario de abajo, que Hilt ignora por completo (sólo mira el `@Inject`).
 */
@Singleton
class ReintentoDeComanda @Inject constructor(
    private val comandaPrinter: ComandaPrinter,
    /** «La libreta» (Task 16) — se reporta AL TERMINAR, nunca a media insistencia. */
    private val reporteDeComandas: ReporteDeComandas,
) {
    private var esperar: suspend (Long) -> Unit = { delay(it) }

    /** Visible para pruebas: inyecta una espera falsa sin pasar por Hilt. */
    constructor(
        comandaPrinter: ComandaPrinter,
        esperar: suspend (Long) -> Unit,
        reporteDeComandas: ReporteDeComandas,
    ) : this(comandaPrinter, reporteDeComandas) {
        this.esperar = esperar
    }

    /**
     * @param maxIntentos tope de intentos para ESTA llamada — default el de
     *   [PoliticaDeReintento.INTENTOS_MAXIMOS] (~1 minuto). El mostrador no tiene a quién
     *   cederle el pedido y usa el default; el KDS SÍ tiene tablets hermanas y pasa 1 (nada de
     *   reintentos), porque insistir ahí retendría la reclamación del pedido hasta ~50 s en vez
     *   de soltarla en segundos para que otro aparato la tome — ver
     *   [com.avoqado.pos.kds.presentation.KDSViewModel].
     * @param venueId y @param orderId son para [ReporteDeComandas] — sin `venueId` no se reporta
     *   nada (nunca se inventa un venue); sin `orderId` el reporte cae a `orderNumber` para no
     *   colisionar el `eventId` de dos órdenes sin id persistido.
     */
    suspend fun insistir(
        plans: List<TicketPlan>,
        config: PrintConfig,
        orderNumber: String,
        orderType: String,
        serverName: String?,
        comboNames: Map<String, String>,
        maxIntentos: Int = PoliticaDeReintento.INTENTOS_MAXIMOS,
        venueId: String? = null,
        orderId: String? = null,
        /** Copias que faltan de un ciclo ANTERIOR — ver [TrabajoPendiente.copiasPendientes]. */
        copiasPendientesIniciales: Map<String?, Int> = emptyMap(),
        /** Saltadas arrastradas de un ciclo ANTERIOR — ver [TrabajoPendiente.saltadas]. */
        saltadasPrevias: List<String> = emptyList(),
        alCambiarEstado: (EstadoDeComanda) -> Unit = {},
    ): EstadoDeComanda {
        var pendientes = plans
        var ultimo = comandaPrinter.printComandas(
            pendientes, config, orderNumber, orderType, serverName, comboNames, copiasPendientesIniciales,
        )
        // 🔴 Las estaciones SALTADAS se acumulan entre intentos. Un intento posterior sólo lleva
        // los planes que TRONARON, así que su resultado ya no menciona la que se saltó — y el
        // veredicto, que se calculaba sobre el ÚLTIMO resultado, la perdía: salía «Salio» con una
        // estación que nunca imprimió. Es el bug original volviendo por otra puerta (P1 #1 de la
        // auditoría de Codex, 2026-09-07).
        val saltadas = LinkedHashSet<String>(saltadasPrevias).apply { addAll(ultimo.skippedStations) }
        var intentos = 1

        while (ultimo.failedPlans.isNotEmpty() && intentos < maxIntentos) {
            val paso = PoliticaDeReintento.siguientePaso(intentos)
            if (paso is PasoDeReintento.Rendirse) break
            alCambiarEstado(
                EstadoDeComanda.Insistiendo(intentos + 1, maxIntentos, ultimo.failedStations, orderNumber),
            )
            esperar((paso as PasoDeReintento.Esperar).esperaMs)
            // 🔴 SÓLO lo que tronó — nunca `plans` (el lote original). Ver el KDoc de la clase.
            pendientes = ultimo.failedPlans
            // 🔴 Se arrastran las copias que FALTAN. Sin esto, una estación de 2 copias cuya
            // segunda falló recibía las dos otra vez al reintentar (P1 #4 de Codex).
            val copiasQueFaltan = ultimo.copiasPendientes
            ultimo = comandaPrinter.printComandas(
                pendientes, config, orderNumber, orderType, serverName, comboNames, copiasQueFaltan,
            )
            saltadas += ultimo.skippedStations
            intentos++
        }

        // NO se usa `ultimo.stationsSinComanda`: ése sólo ve el último intento. Ver `saltadas`.
        val sinComanda = (ultimo.failedStations + saltadas).distinct()
        val estado = if (sinComanda.isEmpty()) {
            EstadoDeComanda.Salio
        } else {
            EstadoDeComanda.NoSalio(
                estaciones = sinComanda,
                causa = ultimo.lastError,
                orderNumber = orderNumber,
                // 🔴 SÓLO lo que TRONÓ. Un plan saltado no tiene impresora: reenviarlo no le
                // inventa una, y meterlo aquí haría que el botón prometa algo imposible.
                trabajo = ultimo.failedPlans.takeIf { it.isNotEmpty() }?.let {
                    TrabajoPendiente(
                        planes = it,
                        copiasPendientes = ultimo.copiasPendientes,
                        saltadas = saltadas.toList(),
                        config = config,
                        orderNumber = orderNumber,
                        orderType = orderType,
                        serverName = serverName,
                        comboNames = comboNames,
                        venueId = venueId,
                        orderId = orderId,
                    )
                },
            )
        }
        alCambiarEstado(estado)
        reportar(estado, ultimo, orderNumber, intentos, venueId, orderId)
        return estado
    }

    /**
     * «La libreta»: un reporte AGREGADO si todo salió (no hay, hoy, una lista de estaciones que
     * SÍ salieron — sólo [ComandaPrinter.Result.failedPlans], que Task 2 ya expone), y uno POR
     * ESTACIÓN que de verdad TRONÓ cuando algo se quedó sin comanda — para que el dashboard sepa
     * QUÉ impresora falló, no sólo que "algo" falló. Nunca frena ni rompe: ver [ReporteDeComandas].
     */
    private suspend fun reportar(
        estado: EstadoDeComanda,
        ultimo: ComandaPrinter.Result,
        orderNumber: String,
        intentos: Int,
        venueId: String?,
        orderId: String?,
    ) {
        when {
            estado is EstadoDeComanda.Salio -> {
                reporteDeComandas.reportar(venueId, orderId, orderNumber, estado, intentos)
            }
            ultimo.failedPlans.isNotEmpty() -> {
                for (plan in ultimo.failedPlans) {
                    reporteDeComandas.reportar(venueId, orderId, orderNumber, estado, intentos, stationId = plan.stationId)
                }
            }
            else -> {
                // Todo lo que faltó fue SALTADO (sin impresora resoluble) — no un fallo de
                // impresión. `Result` no trae el id de esas estaciones (Task 2 sólo agregó
                // `failedPlans`), así que se reporta AGREGADO en vez de no reportar nada.
                reporteDeComandas.reportar(venueId, orderId, orderNumber, estado, intentos)
            }
        }
    }

    /**
     * Vuelve a mandar un [TrabajoPendiente] tal cual — ni una línea más.
     *
     * 🔴 Es lo que hay detrás de «Volver a imprimir». No recibe un carrito ni un id que volver a
     * resolver: recibe los planes YA congelados del fallo, así que no puede duplicar una estación
     * que sí imprimió ni imprimir la venta equivocada.
     *
     * @param maxIntentos por default UNO. El cajero acaba de tocar el botón y está mirando: si
     *   vuelve a fallar, quiere enterarse ahora, no dentro de un minuto de insistencia muda.
     */
    suspend fun reintentar(
        trabajo: TrabajoPendiente,
        maxIntentos: Int = 1,
        alCambiarEstado: (EstadoDeComanda) -> Unit = {},
    ): EstadoDeComanda = insistir(
        plans = trabajo.planes,
        config = trabajo.config,
        orderNumber = trabajo.orderNumber,
        orderType = trabajo.orderType,
        serverName = trabajo.serverName,
        comboNames = trabajo.comboNames,
        maxIntentos = maxIntentos,
        venueId = trabajo.venueId,
        orderId = trabajo.orderId,
        copiasPendientesIniciales = trabajo.copiasPendientes,
        saltadasPrevias = trabajo.saltadas,
        alCambiarEstado = alCambiarEstado,
    )
}
