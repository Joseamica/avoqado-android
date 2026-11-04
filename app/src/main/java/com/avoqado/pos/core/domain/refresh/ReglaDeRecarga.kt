package com.avoqado.pos.core.domain.refresh

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.PendingPaymentDao
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.util.ConnectivityMonitor
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Lo que la regla necesita saber de la pantalla: si está en «no se cargó», si ya enseña datos, o ninguna de las dos. */
enum class EstadoParaRecargar { NO_SE_CARGO, CON_DATOS, OTRO }

/** Una pantalla que sólo lee del servidor (Ventas, Informes), vista desde la regla de recarga. */
interface PantallaQueSeRecarga {
    /** Su estado, en vivo. */
    val estadoParaRecargar: Flow<EstadoParaRecargar>

    /** T1: recarga por el gate, sin forzarlo: el TTL, el enfriamiento y el vuelo único mandan. */
    suspend fun recargar(): RefreshOutcome

    /**
     * T2: volvieron red y servidor. Como la fábrica del gate al recuperar la red (spec refresco §4.3 inv. 4), reinicia el
     * enfriamiento y recarga sin forzar el TTL ni el vuelo único. Sin esto, en el mismo flanco ganaba el enfriamiento viejo
     * a la fábrica, y con internet que vuelve sin bajar el WiFi la fábrica ni se entera.
     */
    suspend fun recargarAlVolverLaRed(): RefreshOutcome

    /** T3: recarga por el gate sin forzarlo, pero invalida antes el TTL: se pide lo recién subido aunque lo de la pantalla sea fresco. */
    suspend fun recargarConDatosNuevos(): RefreshOutcome
}

/**
 * La regla ÚNICA de recarga automática de Ventas e Informes (D18 r3, 3-nov-2026; reemplaza a `VueltaDeLaRed`, que Codex r2
 * tumbó tres veces por la misma familia: H1 la espera de 30 s, H2 internet que vuelve sin apagar el WiFi, H3 la reconexión
 * que se olvidaba al pausar). Corre sólo mientras la pantalla se ve —se llama dentro de `repeatOnLifecycle(RESUMED)`— y
 * siempre por FLANCOS, nunca por nivel; no espera ni recuerda nada entre pausas:
 *
 * - **T1** al volver a la pantalla: si está en «no se cargó» y hay red Y servidor, recarga una vez.
 * - **T2** cuando «red y servidor» pasa de no a sí (también internet que vuelve con el WiFi siempre arriba, y en WINDOWS,
 *   donde la red del aparato no baja): si está en «no se cargó», recarga una vez.
 * - **T3** cuando lo que falta por subir de la sucursal (cobros en cola o subiendo, más la cola de operaciones) pasa de
 *   algo a nada, con red: recarga una vez AUNQUE ya enseñe datos —para que entren los cobros recién subidos—, invalidando
 *   el TTL. Si se unió a una consulta que ya iba en camino (la de T2, que salió antes de que subiera la cola), al terminar
 *   pide otra. Una cola atorada no frena a T2: se recarga con lo que tenga el servidor y la banda sigue diciendo «N por
 *   sincronizar»; cuando por fin se vacía, T3.
 *
 * Ninguna fuerza el gate (`manual = false`); T2 sólo reinicia el enfriamiento, como la fábrica al recuperar la red.
 * 🔴 Y T2 se GASTA si su propia recarga falla: la bandera del servidor sí cambia con ese fallo (el `ConnectivityInterceptor`
 * la apaga en un IOException o un 5xx y el ping la vuelve a prender a los 10 s), así que sin esto un fallo parcial con el
 * servidor alcanzable recargaba en cada parpadeo. Se vuelve a armar cuando la pantalla enseña datos, cuando la red del
 * aparato de verdad se cae (otra vuelta) o al volver a verse; T1 y T3 no se gastan.
 */
@Singleton
class ReglaDeRecarga internal constructor(
    private val red: Flow<Boolean>,
    private val servidor: Flow<Boolean>,
    private val porSubir: Flow<Int>,
) {
    @Inject
    constructor(
        connectivityMonitor: ConnectivityMonitor,
        cobros: PendingPaymentDao,
        operaciones: SyncOutbox,
        secureStorage: SecureStorage,
    ) : this(
        red = connectivityMonitor.isConnected,
        servidor = connectivityMonitor.isServerReachable,
        // La sucursal se lee al empezar a mirar, no al construir: es la de la sesión de ese momento.
        porSubir = flow {
            emitAll(combine(cobros.observarPorSubir(secureStorage.venueId.orEmpty()), operaciones.pendingCount) { c, o -> c + o })
        },
    )

    private val enLinea: Flow<Boolean> get() = combine(red, servidor) { conRed, conServidor -> conRed && conServidor }

    /** Mientras la pantalla se ve. Nunca termina sola: la cancela el `repeatOnLifecycle` al pausar. */
    suspend fun mientrasSeVe(pantalla: PantallaQueSeRecarga): Unit = coroutineScope {
        val estado = pantalla.estadoParaRecargar.stateIn(this)
        var t2Armado = true
        launch { estado.collect { if (it == EstadoParaRecargar.CON_DATOS) t2Armado = true } }
        // Una caída real de la red del aparato es otra vuelta; el parpadeo de la bandera del servidor no (Codex r1 #4).
        launch { red.collect { if (!it) t2Armado = true } }

        // T2: red y servidor de no a sí.
        launch {
            enLinea.distinctUntilChanged().drop(1).collect { enLineaAhora ->
                if (!enLineaAhora || !t2Armado || estado.value != EstadoParaRecargar.NO_SE_CARGO) return@collect
                if (pantalla.recargarAlVolverLaRed() == RefreshOutcome.Failed) t2Armado = false
            }
        }

        // T3: lo que falta por subir, de algo a nada, con red.
        launch {
            var habiaPorSubir: Boolean? = null
            porSubir.distinctUntilChanged().collect { pendientes ->
                val antes = habiaPorSubir
                habiaPorSubir = pendientes > 0
                if (antes == true && pendientes == 0 && enLinea.first()) {
                    // Si se unió a una consulta en camino, ésa salió antes de que subiera la cola: al terminar, se pide otra.
                    if (pantalla.recargarConDatosNuevos() == RefreshOutcome.Joined) pantalla.recargarConDatosNuevos()
                }
            }
        }

        // T1: al volver a la pantalla.
        if (estado.value == EstadoParaRecargar.NO_SE_CARGO && enLinea.first()) pantalla.recargar()
    }
}
