package com.avoqado.pos.core.data.lan

import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "LanHubService"

/**
 * Hub LAN — punto de entrada de la app: arma el coordinador y lo ENGANCHA al transporte único (3.5, D1).
 *
 * Existe para que el resto del POS no tenga que saber nada de mDNS ni de sockets: pide una mesa y recibe un
 * [LeaseOutcome]. El `deviceId`, el cableado y el `boot` los pone el transporte (uno por aparato, regla §2.4 del hub).
 *
 * Preexistente y arreglado aquí (D12): `start()` era idempotente por instancia y NUNCA se reiniciaba al cambiar de
 * venue (el TXT y el filtro se quedaban con el viejo), y `stop()` no tenía llamadores. Ahora se recuerda el venue, se
 * desengancha al cambiar ([sincronizarVenue], desde `AppState`) y se apaga al cerrar sesión.
 */
@Singleton
class LanHubService @Inject constructor(
    private val secureStorage: SecureStorage,
    private val transporte: TransporteLan,
    /** D12 · Opción D: las cuentas abiertas entre cajas viven con el hub Premium (contesta las suyas y pregunta las ajenas). */
    private val cuentas: CuentasEntreCajas? = null,
    /** Hub D3: lo que esta caja declara (su mesa y sus aperturas), del PROCESO para que un rearme no lo pierda. */
    mesas: MesasDeEstaCaja? = null,
) {
    /** null (pruebas viejas) ⇒ una caja sin mesa actual y con vida 1. */
    private val mesas = mesas
        ?: MesasDeEstaCaja({ null }, flowOf(null), ContadorDeVidas({ 0L }, { true })) { System.nanoTime() / 1_000_000 }

    private var coordinator: LanHubCoordinator? = null
    private var venueActivo: String? = null

    private val _enabled = MutableStateFlow(false)
    /** ¿El hub está corriendo? Falso = el POS trabaja como isla. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** Mesas que ESTE dispositivo sostiene (para pintarlas distinto). */
    val myTables: StateFlow<Set<String>>? get() = coordinator?.myTables

    val isArbiter: StateFlow<Boolean>? get() = coordinator?.isArbiter

    /**
     * Enciende el hub. Idempotente POR VENUE: llamarlo dos veces no engancha dos coordinadores; con otro venue rearma.
     * Requiere venue: sin él no se puede filtrar a los peers de OTRO negocio.
     */
    fun start() {
        val venueId = secureStorage.venueId ?: run {
            Log.d(TAG, "Sin venue todavía — el hub no arranca")
            return
        }
        if (coordinator != null && venueActivo == venueId) return
        stop()
        transporte.iniciar(venueId)
        // Su `start()` también fija la sucursal de las aperturas (`mesas.fijarVenue`): las de otra se sueltan.
        val hub = LanHubCoordinator(
            discovery = transporte,
            mesas = mesas,
            deviceId = transporte.deviceId,
            venueId = venueId,
            nombre = { secureStorage.selectedStaffNameForCurrentVenue ?: secureStorage.userFirstName.orEmpty() },
        )
        coordinator = hub
        venueActivo = venueId
        hub.start()
        cuentas?.encender(venueId)
        _enabled.value = true
        Log.i(TAG, "🛰️ Hub LAN enganchado | venue=$venueId device=${transporte.deviceId.take(6)}")
    }

    fun stop() {
        cuentas?.apagar()
        coordinator?.stop()
        coordinator = null
        venueActivo = null
        _enabled.value = false
    }

    /** Cambió la sucursal (`AppState.startOfflineOutbox`): el hub del venue viejo se desengancha; `TablesViewModel` lo rearma al abrir el plano. */
    fun sincronizarVenue(venueId: String) {
        if (coordinator != null && venueActivo != venueId) stop()
    }

    /** Hub D3: el ticket de un intento de abrir una mesa LIBRE, al instante (despierta al reconciliador). Su dueño lo termina. */
    fun abrirApertura(tableId: String, staffId: String, staffName: String): Apertura =
        mesas.abrirApertura(tableId, staffId, staffName, secureStorage.venueId.orEmpty())

    /**
     * Espera el veredicto, hasta 15 s desde el toque. Sin hub, [LeaseOutcome.NoHub] al instante: modo isla, NUNCA bloquea al mesero.
     * [alEsperar] suena cuando el árbitro pide esperar («Revisando con las otras cajas…»); [sigue] en `false` suelta la espera.
     */
    suspend fun esperarApertura(ticket: Apertura, alEsperar: () -> Unit = {}, sigue: () -> Boolean = { true }): LeaseOutcome =
        if (coordinator == null) LeaseOutcome.NoHub else mesas.esperar(ticket, alEsperar, sigue)

    /** Idempotente, por id: nunca por nombre de mesa. */
    fun terminarApertura(ticket: Apertura) = mesas.terminarApertura(ticket)

    /** ADAPTADOR TEMPORAL sólo para `TablesViewModel` (abrir → esperar → terminar); la Task 7 lo borra. */
    suspend fun acquire(tableId: String, staffId: String, staffName: String): LeaseOutcome {
        val ticket = abrirApertura(tableId, staffId, staffName)
        return try {
            esperarApertura(ticket)
        } finally {
            terminarApertura(ticket)
        }
    }

    /** Misma firma de siempre (cuatro llamadores y las pruebas de D12). Lo declarado sale de la sesión de mesa: sólo despierta al reconciliador. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun release(tableId: String) {
        coordinator?.despertar()
    }
}
