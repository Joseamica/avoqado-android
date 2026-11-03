package com.avoqado.pos.giftcards.data

import com.avoqado.pos.giftcards.domain.SolicitudDeTarjeta
import com.avoqado.pos.tables.data.restanteCents
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.util.ConnectivityMonitor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton class ResolutorDeSolicitudesDeTarjeta @Inject constructor(
    private val repo: GiftCardsRepository,
    private val store: SolicitudesDeTarjetaStore,
    private val secureStorage: SecureStorage,
    private val connectivity: ConnectivityMonitor,
    private val cobroConSaldo: CobroConSaldoCoordinator? = null,
    private val cuentas: com.avoqado.pos.tables.data.TableServiceRepository? = null,
) {
    private val listas = MutableStateFlow<List<String>>(emptyList())
    val ventasListas = listas.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var iniciado = false
    private var arranquePendiente = true
    private var sondeo: Job? = null
    private val cobrosRecuperados = MutableStateFlow<List<CobroConSaldo>>(emptyList())
    val recuperados = cobrosRecuperados.asStateFlow()
    private val recuperables = MutableStateFlow<List<SolicitudDeTarjeta>>(emptyList())
    val porRecuperar = recuperables.asStateFlow()
    private val cuentasPorOfrecer = MutableStateFlow<List<SolicitudDeTarjeta>>(emptyList())
    val porOfrecerAlArrancar = cuentasPorOfrecer.asStateFlow()
    private val ofrecidas = mutableSetOf<String>()
    fun visto(paymentId: String) { cobrosRecuperados.value = cobrosRecuperados.value.filterNot { it.paymentId == paymentId } }
    fun ofrecido(orderId: String) { ofrecidas.add(orderId); cuentasPorOfrecer.value = cuentasPorOfrecer.value.filterNot { it.orderId == orderId } }
    private var venueDeLasListas: String? = null
    private data class Turno(val version: Long, val usuario: String?, val venue: String?)
    private var identidad = secureStorage.userId to secureStorage.venueId
    private var sesionDisponible = secureStorage.isLoggedIn
    private var version = 0L
    private var programada: Turno? = null

    @Synchronized fun onSessionChanged(sesionDisponible: Boolean) {
        val actual = if (sesionDisponible) secureStorage.userId to secureStorage.venueId else null to null
        if (this.sesionDisponible != sesionDisponible || identidad != actual) {
            this.sesionDisponible = sesionDisponible
            identidad = actual
            version++
            programada = null
            listas.value = emptyList()
            venueDeLasListas = null
            cobrosRecuperados.value = emptyList()
            recuperables.value = emptyList()
            cuentasPorOfrecer.value = emptyList()
            sondeo?.cancel()
            sondeo = null
        }
        if (sesionDisponible) programar()
    }

    @Synchronized private fun turno() = Turno(version, secureStorage.userId, secureStorage.venueId)
    @Synchronized private fun vigente(t: Turno) = sesionDisponible && secureStorage.isLoggedIn && version == t.version &&
        identidad == (t.usuario to t.venue) && secureStorage.userId == t.usuario && secureStorage.venueId == t.venue

    @Synchronized private fun programar() {
        val t = turno()
        if (!vigente(t) || t.usuario == null || t.venue == null) return
        if (!connectivity.isConnected.value || !connectivity.isServerReachable.value) {
            programada = null
            val arranque = arranquePendiente.also { arranquePendiente = false }
            scope.launch { resolver(t, arranque) }
            return
        }
        if (programada == t) return
        programada = t
        scope.launch { resolver(t, arranque = arranquePendiente.also { arranquePendiente = false }) }
    }
    @Synchronized fun start() {
        if (iniciado) return
        iniciado = true
        scope.launch {
            combine(connectivity.isConnected, connectivity.isServerReachable) { conectado, servidor -> conectado && servidor }
                .distinctUntilChanged().collect { programar() }
        }
        scope.launch {
            store.solicitudes.map { filas -> filas.filter {
                it.tipo == "GIFT_CARD_REDEEM" && it.mandado && !it.porTerminar && !it.porRecuperar
            }.map { it.venueId to it.llave }.toSet() }.distinctUntilChanged().drop(1).collect { dudas ->
                val t = turno()
                if (dudas.any { it.first == t.venue }) resolver(t, soloDudas = true)
            }
        }
    }
    suspend fun resolverAhora(arranque: Boolean = false) = resolver(turno(), arranque)
    private suspend fun resolver(t: Turno, arranque: Boolean = false, soloDudas: Boolean = false) {
      mutex.withLock {
        if (!vigente(t)) return@withLock
        val venue = t.venue ?: return@withLock
        for (fila in store.delNegocio(venue).filter { !soloDudas && it.tipo == "GIFT_CARD_REDEEM" && !it.mandado && !it.porTerminar && !it.porRecuperar }) {
            if (!store.enVueloDeEsteProceso.add(fila.llave)) continue
            try {
                val actual = store.delNegocio(venue).firstOrNull { it.llave == fila.llave } ?: continue
                if (actual.mandado || actual.porTerminar || actual.porRecuperar) continue
                if (actual.orderId == null) store.quitar(actual.llave)
                else if (arranque && actual.deMostrador) store.dejarPorTerminar(actual.llave, actual.orderId, actual.orderNumber, null)
            } finally { store.enVueloDeEsteProceso.remove(fila.llave) }
        }
        recuperables.value = store.delNegocio(venue).filter { it.porRecuperar }
        if (!connectivity.isConnected.value || !connectivity.isServerReachable.value) return@withLock
        for (fila in store.delNegocio(venue).filter { it.tipo == "GIFT_CARD_REDEEM" && (!soloDudas || it.mandado && !it.porTerminar && !it.porRecuperar) }) {
            if (!vigente(t) || !connectivity.isConnected.value || !connectivity.isServerReachable.value) return@withLock
            if (fila.llave in store.enVueloDeEsteProceso || fila.porRecuperar || !fila.mandado && !fila.porTerminar) continue
            if (fila.porTerminar) {
                val orderId = fila.orderId ?: continue
                if (!store.enVueloDeEsteProceso.add(fila.llave)) continue
                try {
                    val actual = store.delNegocio(venue).firstOrNull { it.llave == fila.llave && it.porTerminar } ?: continue
                    val detalle = cuentas?.getOrderDetail(venue, orderId)?.getOrNull() ?: continue
                    if (detalle.paymentStatus == "PAID" || detalle.status == "CANCELLED") store.quitar(actual.llave)
                    else {
                        val restante = restanteCents(detalle)
                        if (store.dejarPorTerminar(actual.llave, orderId, actual.orderNumber, restante) && vigente(t) && restante != null && restante > 0 && orderId !in ofrecidas) {
                            cuentasPorOfrecer.value = (cuentasPorOfrecer.value.filterNot { it.orderId == orderId } + actual.copy(restanteCents = restante)).distinctBy { it.orderId }
                        }
                    }
                } finally { store.enVueloDeEsteProceso.remove(fila.llave) }
            } else {
                val r = cobroConSaldo?.resolver(fila.llave)
                if (r is DesenlaceDeCobro.Cobrado && vigente(t)) cobrosRecuperados.value =
                    (cobrosRecuperados.value + r.cobro).distinctBy { it.paymentId }
            }
        }
        recuperables.value = store.delNegocio(venue).filter { it.porRecuperar }
        synchronized(this) {
            if (!vigente(t)) return@withLock
            if (venueDeLasListas != venue) { listas.value = emptyList(); venueDeLasListas = venue }
        }
        for (s in store.delNegocio(venue).filter { !soloDudas && it.tipo == "GIFT_CARD_SALE" }) {
            if (!vigente(t)) return@withLock
            if (!store.enVueloDeEsteProceso.add(s.llave)) continue
            try {
                val actual = store.delNegocio(venue).firstOrNull { it.llave == s.llave } ?: continue
                if (actual.tipo != "GIFT_CARD_SALE") continue
                if (actual.orderId != null) {
                    when (val r = repo.cuenta(venue, actual.orderId)) {
                        is Respuesta.Ok -> estado(s.llave, t, r.valor.sobres.map { it.status })
                        is Respuesta.Rechazo -> Unit // Un rechazo de S4 no destruye la evidencia de una venta con folio.
                        Respuesta.SinRespuesta -> Unit
                    }
                } else {
                    when (val r = repo.vender(venue, actual.cuerpo)) {
                        is Respuesta.Ok -> if (store.anotarOrden(s.llave, r.valor.orderId, r.valor.orderNumber)) {
                            estado(s.llave, t, r.valor.sobres.map { it.status })
                        }
                        is Respuesta.Rechazo -> if (vigente(t) && r.fallo.code != null) store.quitar(s.llave)
                        Respuesta.SinRespuesta -> Unit
                    }
                }
            } finally { store.enVueloDeEsteProceso.remove(s.llave) }
        }
    }
      mantenerSondeo(t)
    }
    @Synchronized private fun mantenerSondeo(t: Turno) {
        if (sondeo?.isActive == true || !vigente(t) || !connectivity.isConnected.value || !connectivity.isServerReachable.value) return
        val venue = t.venue ?: return
        fun hayDudas() = store.delNegocio(venue).any { it.tipo == "GIFT_CARD_REDEEM" && it.mandado && !it.porTerminar && !it.porRecuperar }
        if (!hayDudas()) return
        sondeo = scope.launch {
            while (vigente(t) && connectivity.isConnected.value && connectivity.isServerReachable.value && hayDudas()) {
                delay(5000)
                if (vigente(t)) resolver(t, soloDudas = true)
            }
        }
    }
    @Synchronized private fun estado(llave: String, t: Turno, estados: List<String?>) {
        if (estados.isEmpty()) return
        when {
            estados.any { it == "ACTIVE" } && vigente(t) -> listas.value = (listas.value + llave).distinct()
            estados.all { it == "DEACTIVATED" } -> if (store.quitar(llave)) listas.value = listas.value - llave
        }
    }
}
