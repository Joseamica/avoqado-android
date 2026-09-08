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
    data class NoSalio(val estaciones: List<String>, val causa: String?, val orderNumber: String) : EstadoDeComanda
}

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
) {
    private var esperar: suspend (Long) -> Unit = { delay(it) }

    /** Visible para pruebas: inyecta una espera falsa sin pasar por Hilt. */
    constructor(comandaPrinter: ComandaPrinter, esperar: suspend (Long) -> Unit) : this(comandaPrinter) {
        this.esperar = esperar
    }

    /**
     * @param maxIntentos tope de intentos para ESTA llamada — default el de
     *   [PoliticaDeReintento.INTENTOS_MAXIMOS] (~1 minuto). El mostrador no tiene a quién
     *   cederle el pedido y usa el default; el KDS SÍ tiene tablets hermanas y pasa 1 (nada de
     *   reintentos), porque insistir ahí retendría la reclamación del pedido hasta ~50 s en vez
     *   de soltarla en segundos para que otro aparato la tome — ver
     *   [com.avoqado.pos.kds.presentation.KDSViewModel].
     */
    suspend fun insistir(
        plans: List<TicketPlan>,
        config: PrintConfig,
        orderNumber: String,
        orderType: String,
        serverName: String?,
        comboNames: Map<String, String>,
        maxIntentos: Int = PoliticaDeReintento.INTENTOS_MAXIMOS,
        alCambiarEstado: (EstadoDeComanda) -> Unit = {},
    ): EstadoDeComanda {
        var pendientes = plans
        var ultimo = comandaPrinter.printComandas(pendientes, config, orderNumber, orderType, serverName, comboNames)
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
            ultimo = comandaPrinter.printComandas(pendientes, config, orderNumber, orderType, serverName, comboNames)
            intentos++
        }

        val sinComanda = ultimo.stationsSinComanda
        val estado = if (sinComanda.isEmpty()) {
            EstadoDeComanda.Salio
        } else {
            EstadoDeComanda.NoSalio(sinComanda, ultimo.lastError, orderNumber)
        }
        alCambiarEstado(estado)
        return estado
    }
}
