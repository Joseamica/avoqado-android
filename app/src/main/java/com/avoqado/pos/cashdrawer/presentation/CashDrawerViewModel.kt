package com.avoqado.pos.cashdrawer.presentation

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.cashdrawer.data.ComprobanteDeRetiro
import com.avoqado.pos.cashdrawer.data.ComprobantesDeRetiroPorImprimir
import com.avoqado.pos.cashdrawer.data.ResultadoDelRetiro
import com.avoqado.pos.cashdrawer.data.RetiroTicketBuilder
import com.avoqado.pos.cashdrawer.data.llaveDelRetiro
import com.avoqado.pos.cashdrawer.data.nombreDeLaSesion
import com.avoqado.pos.cashdrawer.domain.AccesoAlRetiro
import com.avoqado.pos.cashdrawer.domain.accesoAlRetiro
import com.avoqado.pos.cashdrawer.domain.puedeRetirar
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.OverrideResult
import com.avoqado.pos.core.data.network.PermissionOverrideRepository
import com.avoqado.pos.core.data.network.ServerErrorText
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.core.util.VenueTimeZone
import com.avoqado.pos.core.util.formatMoneyFromCents
import com.avoqado.pos.printing.data.model.PrinterConnectionType
import com.avoqado.pos.printing.data.model.TablaDeAcentos
import com.avoqado.pos.tpvsettings.data.TpvSettingsRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter
import java.util.UUID
import com.avoqado.pos.cashdrawer.data.EstadoDeLaApertura
import com.avoqado.pos.cashdrawer.data.model.CashDrawerEventEntity
import com.avoqado.pos.cashdrawer.data.model.CashDrawerEventType
import com.avoqado.pos.cashdrawer.data.model.CashDrawerSessionEntity
import com.avoqado.pos.printing.data.ESCPOSPrinter
import com.avoqado.pos.printing.data.PrinterService
import com.avoqado.pos.printing.data.model.PrinterRole
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.avoqado.pos.core.domain.RoleManager
import javax.inject.Inject
import com.avoqado.pos.cashdrawer.data.CorteTicketBuilder

private const val TAG = "💰 CashDrawerVM"

enum class CashDrawerSection(val label: String) {
    // "Turno en curso": el encabezado de la pantalla ya dice "Turno de caja" (decisión del
    // founder, 27-ago/2-sep) — dejar esta pestaña como "Caja en curso" se leía inconsistente
    // justo debajo, porque las dos conviven a la vista en el mismo sidebar.
    CURRENT("Turno en curso"),
    HISTORY("Historial"),
}

@HiltViewModel
class CashDrawerViewModel @Inject constructor(
    private val repository: CashDrawerRepository,
    private val printerService: PrinterService,
    private val roleManager: RoleManager,
    private val tpvSettingsRepository: TpvSettingsRepository,
    private val conectividad: ConnectivityMonitor,
    private val permissionOverrideRepository: PermissionOverrideRepository,
    private val comprobantes: ComprobantesDeRetiroPorImprimir,
    private val secureStorage: SecureStorage,
) : ViewModel() {

    /**
     * Conteo CIEGO de verdad (P1 Codex 27-ago): esconder el esperado sólo en la hoja de cierre no
     * servía si la cabecera lo enseñaba todo el día. Toast lo resuelve por PERMISO ("Cash Drawers
     * (Blind)" vs "Full"); aquí se espeja el permiso PROPIO del server `cash-drawer:view-expected`
     * (MANAGER+ de fábrica). 🔴 No puede ser `shifts:close`: el alias con `tpv-shifts:close` se lo
     * regala a cajeros y meseros (Codex, 2ª auditoría). Quien no lo tiene cuenta a ciegas.
     */
    val puedeVerEsperado: Boolean
        get() = roleManager.hasVenuePermission(PERMISO_VER_ESPERADO, ROLES_VER_ESPERADO)

    // MARK: - State

    private val _currentSession = MutableStateFlow<CashDrawerSessionEntity?>(null)
    val currentSession: StateFlow<CashDrawerSessionEntity?> = _currentSession.asStateFlow()

    private val _events = MutableStateFlow<List<CashDrawerEventEntity>>(emptyList())
    val events: StateFlow<List<CashDrawerEventEntity>> = _events.asStateFlow()

    private val _expectedAmountCents = MutableStateFlow(0)
    val expectedAmountCents: StateFlow<Int> = _expectedAmountCents.asStateFlow()

    private val _closedSessions = MutableStateFlow<List<CashDrawerSessionEntity>>(emptyList())
    val closedSessions: StateFlow<List<CashDrawerSessionEntity>> = _closedSessions.asStateFlow()

    private val _selectedSection = MutableStateFlow(CashDrawerSection.CURRENT)
    val selectedSection: StateFlow<CashDrawerSection> = _selectedSection.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Tender breakdown for the corte (card + cash + other), keyed nowhere —
    // refetched per report shown. Empty until loaded / on failure.
    /** `null` = no se pudo consultar el desglose; lista vacía = el corte no tuvo cobros. */
    /**
     * 🔴 Movimientos que el servidor RECHAZÓ de plano. Antes se descartaban con un `Log.w` y
     * nadie se enteraba: el cajero cerraba su caja creyendo que su retiro había llegado, y el
     * arqueo salía con un faltante que no se podía explicar. Un fallo silencioso en dinero es
     * peor que uno ruidoso.
     */
    private val _rechazadas = MutableStateFlow<List<CashDrawerRepository.OperacionRechazada>>(emptyList())
    val rechazadas: StateFlow<List<CashDrawerRepository.OperacionRechazada>> = _rechazadas.asStateFlow()

    /**
     * 🔴 Cajas que se ADOPTARON del servidor en vez de abrir la del cajero (hallazgo I1). El
     * servidor liga en vez de rebotar y **nunca pisa el fondo de lo que ya estaba**: sin este aviso,
     * quien contó $2,000 opera sobre una caja de $500 y se entera al cerrar, como un sobrante de
     * $1,500 sin explicación.
     */
    private val _cajasAdoptadas = MutableStateFlow<List<CashDrawerRepository.CajaAdoptada>>(emptyList())
    val cajasAdoptadas: StateFlow<List<CashDrawerRepository.CajaAdoptada>> = _cajasAdoptadas.asStateFlow()

    /**
     * 🔴 «Caja abierta sin conexión» (P2 #4). Una caja cuyo `OPEN` sigue en la cola se veía
     * IDÉNTICA a una que el servidor ya conoce. Offline es un estado NORMAL y se DICE.
     */
    private val _estadoDeLaApertura = MutableStateFlow(EstadoDeLaApertura.CONFIRMADA)
    val estadoDeLaApertura: StateFlow<EstadoDeLaApertura> = _estadoDeLaApertura.asStateFlow()

    private val _tenderBreakdown = MutableStateFlow<List<CashDrawerRepository.TenderRow>?>(null)
    val tenderBreakdown: StateFlow<List<CashDrawerRepository.TenderRow>?> = _tenderBreakdown.asStateFlow()

    /** Fetch the payment-method breakdown for a session's window (corte de caja). */
    fun loadTenderBreakdown(fromMillis: Long, toMillis: Long) {
        viewModelScope.launch {
            _tenderBreakdown.value = repository.getTenderBreakdown(fromMillis, toMillis)
        }
    }

    // End of day ("Cierre del día") — day summary + blockers.
    private val _endOfDay = MutableStateFlow<com.avoqado.pos.cashdrawer.data.EndOfDaySummary?>(null)
    val endOfDay: StateFlow<com.avoqado.pos.cashdrawer.data.EndOfDaySummary?> = _endOfDay.asStateFlow()

    private val _isLoadingEndOfDay = MutableStateFlow(false)
    val isLoadingEndOfDay: StateFlow<Boolean> = _isLoadingEndOfDay.asStateFlow()

    fun loadEndOfDay() {
        viewModelScope.launch {
            _isLoadingEndOfDay.value = true
            _endOfDay.value = repository.getEndOfDay()
            _isLoadingEndOfDay.value = false
        }
    }

    // MARK: - Init

    // 🔴 Van ANTES del `init`: Kotlin inicializa en orden de declaración, y `alEntrar()` los usa
    // en el mismo instante (viewModelScope corre en Main.immediate).
    /**
     * ¿Lo que se ve de la caja ya lo confirmó el servidor? `false` = la última consulta no llegó
     * (sin red): la pantalla y el corte sólo conocen lo que registró ESTE aparato, y lo dicen.
     */
    private val _confirmadoConServidor = MutableStateFlow(true)
    val confirmadoConServidor: StateFlow<Boolean> = _confirmadoConServidor.asStateFlow()

    /** Dos entradas seguidas no mandan dos syncs encimados: el segundo espera al primero. */
    private val candadoDelSync = Mutex()

    /** El retiro en pantalla: hoja, teclado del gerente o aviso. Ver [tocarRetiro]. */
    private val _retiro = MutableStateFlow<RetiroUi>(RetiroUi.Cerrado)
    val retiro: StateFlow<RetiroUi> = _retiro.asStateFlow()

    /** Lo que quedó del retiro ya registrado: sin confirmar con el servidor, o sin papel. Tono normal, no de error. */
    private val _avisoDelRetiro = MutableStateFlow<String?>(null)
    val avisoDelRetiro: StateFlow<String?> = _avisoDelRetiro.asStateFlow()

    /**
     * Un papel por retiro: la reimpresión al entrar ([reimprimirPendientes]) y la del retiro recién hecho van por el mismo
     * candado, y la primera se salta los retiros que esta pantalla está registrando. Entrar dos veces seguidas (el `init` y la
     * pantalla) no saca dos copias. ponytail: por ViewModel; si se recrea a media impresión sale, a lo más, una copia
     * marcada REIMPRESIÓN (lo que ya acepta el ruling r3 #10).
     */
    private val candadoDelPapel = Mutex()
    private val retirosEnCurso = mutableSetOf<String>()

    init {
        alEntrar()
    }

    private suspend fun sincronizar(): Boolean =
        candadoDelSync.withLock { repository.syncFromApi() }.also { _confirmadoConServidor.value = it }

    /**
     * 🔴 CADA ENTRADA A CAJA PREGUNTA AL SERVIDOR, no sólo la primera.
     *
     * El ViewModel sobrevive entre visitas (días, en la D3), y volver a entrar sólo leía Room. Lo
     * que escribe el SERVIDOR —el egreso de un reembolso, la venta en efectivo cobrada en la
     * terminal u otro aparato, el cierre hecho en otro lado— no llegaba nunca: Testarudo,
     * 28-sep-2026, «Faltante $145» en la tablet contra $0.00 en el servidor, y Better Stack sin un
     * solo `GET /cash-drawer/current` en dos días. Espejo de iOS (`CashDrawerView.onAppear →
     * loadCurrentDrawer`): primero lo local (instantáneo, y lo único que hay sin red), luego el
     * servidor y se vuelve a pintar.
     */
    fun alEntrar() {
        loadCurrentSession()
        viewModelScope.launch { reimprimirPendientes() }
        viewModelScope.launch {
            sincronizar()
            loadCurrentSession()
            if (_selectedSection.value == CashDrawerSection.HISTORY) {
                _closedSessions.value = runCatching { repository.getHistory() }.getOrDefault(_closedSessions.value)
            }
        }
    }

    // MARK: - Navigation

    fun selectSection(section: CashDrawerSection) {
        _selectedSection.value = section
        when (section) {
            CashDrawerSection.CURRENT -> loadCurrentSession()
            CashDrawerSection.HISTORY -> loadHistory()
        }
    }

    /**
     * El cajero ya vio el aviso y decidió qué hacer con ese dinero: se saca de la cola.
     *
     * 🔴 Sobre una APERTURA no aplica y el repositorio la conserva (hallazgo I4): ahí el único
     * camino es [reintentarApertura], y por eso el aviso de una apertura no ofrece «Ya lo vi».
     */
    fun descartarRechazada(op: CashDrawerRepository.OperacionRechazada) {
        viewModelScope.launch {
            runCatching { repository.descartarRechazada(op.localKey) }
            _rechazadas.value = runCatching { repository.operacionesRechazadas() }.getOrDefault(emptyList())
        }
    }

    /** Vuelve a intentar una APERTURA rechazada: se le quita la marca y se reproduce la cola. */
    fun reintentarApertura(op: CashDrawerRepository.OperacionRechazada) {
        viewModelScope.launch {
            runCatching { repository.reintentarApertura(op.localKey) }
            runCatching { repository.reproducirPendientes() }
            _rechazadas.value = runCatching { repository.operacionesRechazadas() }.getOrDefault(emptyList())
            loadCurrentSession()
        }
    }

    /** El cajero cerró el aviso de la caja adoptada. Sólo desaparece cuando él lo cierra. */
    fun descartarAvisoDeAdopcion(caja: CashDrawerRepository.CajaAdoptada) {
        viewModelScope.launch {
            runCatching { repository.descartarAvisoDeAdopcion(caja.sessionId) }
            _cajasAdoptadas.value = runCatching { repository.cajasAdoptadas() }.getOrDefault(emptyList())
        }
    }

    // MARK: - Load Data

    fun loadCurrentSession() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // Un cierre que se quedó sin red se reintenta cada vez que se entra a Caja, no sólo al
                // crear el ViewModel (que sobrevive entre visitas): visto en la Samsung, 27-ago.
                runCatching { repository.reproducirCierresPendientes() }
                _rechazadas.value = runCatching { repository.operacionesRechazadas() }.getOrDefault(emptyList())
                _cajasAdoptadas.value = runCatching { repository.cajasAdoptadas() }.getOrDefault(emptyList())
                val session = repository.getOpenSession()
                _currentSession.value = session
                _estadoDeLaApertura.value = runCatching { repository.estadoDeApertura(session?.id) }
                    .getOrDefault(EstadoDeLaApertura.CONFIRMADA)
                if (session != null) {
                    _events.value = repository.getEvents(session.id)
                    _expectedAmountCents.value = repository.computeExpectedAmount(
                        session.id,
                        session.startingAmountCents,
                    )
                } else {
                    _events.value = emptyList()
                    _expectedAmountCents.value = 0
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error loading session: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun loadHistory() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // Sync from API first, then read from Room
                sincronizar()
                _closedSessions.value = repository.getHistory()
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error loading history: ${e.message}")
                // Fall back to local data
                try {
                    _closedSessions.value = repository.getHistory()
                } catch (_: Exception) { /* ignore */ }
            } finally {
                _isLoading.value = false
            }
        }
    }

    /// Drawer-op failures were Log.e-only — the VM had NO error channel at
    /// all, so a failed pay-out/close looked identical to success.
    val errorMessage = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    // MARK: - Actions

    fun openSession(startingAmountCents: Int) {
        viewModelScope.launch {
            try {
                val session = repository.openSession(startingAmountCents)
                _currentSession.value = session
                _events.value = repository.getEvents(session.id)
                // 🔴 EL ESPERADO SALE DE LA CAJA QUE QUEDÓ, NO DEL MONTO TECLEADO (hallazgo N4).
                // Si el servidor LIGÓ esta apertura a una caja que ya estaba abierta, el fondo
                // bueno es el de ESA caja: pintar los $2,000 que tecleó el cajero encima de una
                // caja de $500 es un número que miente hasta el siguiente `loadCurrentSession()`.
                _expectedAmountCents.value = repository.computeExpectedAmount(
                    session.id,
                    session.startingAmountCents,
                )
                Log.d(TAG, "✅ Session opened")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error opening session: ${e.message}")
                errorMessage.value = "No se pudo abrir la caja. Intenta de nuevo."
            }
            // 🔴 EL AVISO SE PINTA EN EL MOMENTO EN QUE LA ADOPCIÓN OCURRE (hallazgo N1).
            //
            // Abrir la caja es el ÚNICO instante en que el servidor LIGA, y este camino no volvía
            // a leer los avisos: `_cajasAdoptadas` sólo se llenaba en `loadCurrentSession()`, que
            // la pantalla llama al ENTRAR. El cajero tecleaba su fondo, tocaba «Abrir caja» y se
            // quedaba operando la caja de otro sin un solo indicio, hasta que salía y volvía a
            // entrar a Caja — normalmente ya para cerrarla, con el sobrante encima.
            //
            // Va FUERA del `try`: si la apertura falló, el aviso de una adopción anterior —o el de
            // una apertura rechazada— sigue siendo justo lo que hay que enseñar.
            _cajasAdoptadas.value = runCatching { repository.cajasAdoptadas() }.getOrDefault(emptyList())
            _rechazadas.value = runCatching { repository.operacionesRechazadas() }.getOrDefault(emptyList())
            _estadoDeLaApertura.value = runCatching { repository.estadoDeApertura(_currentSession.value?.id) }
                .getOrDefault(EstadoDeLaApertura.CONFIRMADA)
        }
    }

    fun addPayIn(amountCents: Int, note: String?) {
        viewModelScope.launch {
            try {
                repository.addPayIn(amountCents, note)
                loadCurrentSession()
                Log.d(TAG, "✅ Pay-in added: $amountCents")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error adding pay-in: ${e.message}")
                errorMessage.value = "No se pudo registrar la entrada de efectivo."
            }
        }
    }

    // MARK: - Retiro de efectivo (con o sin el PIN de un gerente)

    /**
     * Retiro con gerente (Task 11): quien tiene `cash-drawer:pay-out` retira directo, con o sin red; el cajero, con el PIN de un
     * gerente tecleado al CONFIRMAR, con el importe y el concepto a la vista. La pantalla sólo pinta este estado y llama aquí.
     */
    fun tocarRetiro() {
        if (_retiro.value != RetiroUi.Cerrado) return
        _retiro.value = when (accesoAhora()) {
            // El id local nace AQUÍ y es el de todo este retiro: la autorización del gerente se ata a él (`llaveDelRetiro`).
            AccesoAlRetiro.DIRECTO -> RetiroUi.Hoja(conPin = false, localId = UUID.randomUUID().toString())
            AccesoAlRetiro.CON_PIN -> RetiroUi.Hoja(conPin = true, localId = UUID.randomUUID().toString())
            AccesoAlRetiro.SIN_RED -> RetiroUi.Aviso(RETIRO_SIN_RED)
            AccesoAlRetiro.SOLO_GERENTE -> RetiroUi.Aviso(RETIRO_SOLO_GERENTE)
        }
    }

    /** Cómo puede retirar quien está en la sesión, AHORA: con la lista y el rol de este momento, el interruptor del PIN y la red. */
    private fun accesoAhora(): AccesoAlRetiro = accesoAlRetiro(
        puedeRetirar(
            listaVigente = secureStorage.listaDePermisosVigente,
            tieneElPermiso = roleManager.hasVenuePermission(PERMISO_RETIRAR),
            rol = roleManager.role,
        ),
        tpvSettingsRepository.managerPinOverrideEnabled.value,
        conectividad.isOnline(),
    )

    /** «Confirmar» de la hoja. Con PIN abre el teclado; directo, registra. Un segundo toque no hace nada. */
    fun confirmarRetiro(amountCents: Int, concepto: String) {
        val hoja = _retiro.value as? RetiroUi.Hoja ?: return
        val texto = concepto.trim()
        if (hoja.ocupado || amountCents <= 0 || texto.isEmpty()) return
        // 🔴 El camino directo se vuelve a decidir al confirmar (H3): si con la hoja abierta llegó la lista vigente que quita el
        // permiso, este retiro sigue como si se hubiera abierto así (PIN, o el aviso). Lo ya autorizado por PIN no se re-evalúa.
        val acceso = if (hoja.conPin) AccesoAlRetiro.CON_PIN else accesoAhora()
        when (acceso) {
            AccesoAlRetiro.DIRECTO -> enviar(hoja, amountCents, texto, autorizacion = null, autorizadoPor = null)
            // Cada «Confirmar» vuelve a pedir el PIN: si cambió el importe, la llave también (el importe es parte de ella).
            AccesoAlRetiro.CON_PIN -> _retiro.value = RetiroUi.PidiendoPin(
                hoja.copy(conPin = true, error = null), amountCents, texto, "Retiro de ${formatMoneyFromCents(amountCents)} · $texto",
            )
            AccesoAlRetiro.SIN_RED -> _retiro.value = RetiroUi.Aviso(RETIRO_SIN_RED)
            AccesoAlRetiro.SOLO_GERENTE -> _retiro.value = RetiroUi.Aviso(RETIRO_SOLO_GERENTE)
        }
    }

    /** El gerente teclea su PIN. Lo que no sea `Granted` se queda en el teclado, que ya sabe pintarlo. */
    suspend fun teclearPinDelRetiro(pin: String): OverrideResult {
        val p = _retiro.value as? RetiroUi.PidiendoPin ?: return OverrideResult.Failed("Este retiro ya no espera autorización.")
        val r = permissionOverrideRepository.requestToken(
            secureStorage.venueId.orEmpty(), pin, PERMISO_RETIRAR, llaveDelRetiro(p.hoja.localId, p.amountCents),
        )
        // Sólo si sigue abierto ESTE teclado: si lo cancelaron con el PIN en camino, no se manda nada.
        if (r is OverrideResult.Granted && _retiro.value === p) {
            enviar(p.hoja, p.amountCents, p.concepto, r.token, autorizadoPor = r.authorizedByName.ifBlank { "Gerente" })
        }
        return r
    }

    /** El gerente cerró el teclado: vuelve la hoja con lo escrito, sin mandar nada. */
    fun cancelarPin() {
        (_retiro.value as? RetiroUi.PidiendoPin)?.let { _retiro.value = it.hoja }
    }

    /** Cierra la hoja o el aviso. Con el retiro registrándose no se cierra: la respuesta tiene que verse. */
    fun cerrarRetiro() {
        if ((_retiro.value as? RetiroUi.Hoja)?.ocupado == true) return
        _retiro.value = RetiroUi.Cerrado
    }

    fun descartarAvisoDelRetiro() {
        _avisoDelRetiro.value = null
    }

    private fun enviar(hoja: RetiroUi.Hoja, amountCents: Int, concepto: String, autorizacion: String?, autorizadoPor: String?) {
        // 🔴 El papel se arma ANTES de enviar (r3 #9): si otra persona entra mientras viaja el retiro, sigue siendo de quien lo hizo.
        val c = comprobanteDelRetiro(hoja.localId, amountCents, concepto, autorizadoPor)
        _retiro.value = hoja.copy(ocupado = true, error = null)
        retirosEnCurso += hoja.localId
        viewModelScope.launch {
            // Salir de Caja con el retiro ya guardado no puede dejarlo sin papel.
            withContext(NonCancellable) {
                try {
                    // 🔴 Sólo el registro puede devolver la hoja (H11). Después de `Hecho` el retiro ya existe: nada que truene después
                    // (la impresora, la recarga) la reabre para editar un retiro con la misma llave.
                    val r = try {
                        repository.addPayOut(amountCents, concepto, hoja.localId, autorizacion, c)
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error adding pay-out: ${e.message}")
                        _retiro.value = hoja.copy(error = "No se pudo registrar la salida de efectivo.")
                        loadCurrentSession()
                        return@withContext
                    }
                    when (r) {
                        is ResultadoDelRetiro.Hecho -> {
                            _retiro.value = RetiroUi.Cerrado
                            loadCurrentSession()
                            val fallo = candadoDelPapel.withLock { imprimirYQuitar(c, reimpresion = false) }
                            _avisoDelRetiro.value = listOfNotNull(RETIRO_SIN_CONFIRMAR.takeUnless { r.confirmado }, fallo)
                                .joinToString("\n\n").ifEmpty { null }
                        }
                        // El repositorio ya lo deshizo (o dejó su rechazo marcado): nada que imprimir.
                        is ResultadoDelRetiro.Rechazado ->
                            _retiro.value = hoja.copy(error = "No se registró el retiro: ${ServerErrorText.humanize(r.mensaje)}")
                    }
                } finally {
                    retirosEnCurso -= hoja.localId
                }
            }
        }
    }

    private fun comprobanteDelRetiro(localId: String, amountCents: Int, concepto: String, autorizadoPor: String?): ComprobanteDeRetiro {
        val ahora = VenueTimeZone.now()
        val hizo = nombreDeLaSesion(secureStorage)
        return ComprobanteDeRetiro(
            venueId = secureStorage.venueId.orEmpty(),
            localId = localId,
            venueName = repository.venueName,
            nombreDelAparato = tpvSettingsRepository.terminalNavigation.value.terminalName,
            fechaTexto = ahora.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")),
            horaTexto = ahora.format(DateTimeFormatter.ofPattern("HH:mm")),
            concepto = concepto,
            amountCents = amountCents,
            hizo = hizo,
            rolDeQuienHizo = secureStorage.userRole?.uppercase(),
            autorizadoPor = autorizadoPor ?: hizo, // sin PIN lo autorizó quien lo hizo
        )
    }

    /**
     * Imprime el comprobante y lo quita SÓLO si el papel salió (H10). Sin impresora o con error, se queda: sale en la siguiente entrada
     * a Caja como REIMPRESIÓN. Devuelve el aviso si no salió. Va bajo [candadoDelPapel]. La impresora es la de recibos o, si no hay,
     * la integrada del aparato, como los demás recibos.
     */
    private suspend fun imprimirYQuitar(c: ComprobanteDeRetiro, reimpresion: Boolean): String? {
        val fallo = try {
            val printer = printerService.getDefaultPrinterWithHardwareFallback(PrinterRole.RECEIPT)
            if (printer == null) {
                RETIRO_SIN_IMPRESORA
            } else {
                printerService.sendPrintData(
                    RetiroTicketBuilder.build(
                        c,
                        reimpresion = reimpresion,
                        paperWidth = printer.paperWidth,
                        switchToSingleByteFirst = printer.connectionTypeEnum == PrinterConnectionType.INTERNAL,
                        tablaDeAcentos = TablaDeAcentos.deGuardada(printer.tablaDeAcentos),
                    ),
                    printer,
                )
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ No se pudo imprimir el comprobante del retiro: ${e.message}", e)
            RETIRO_NO_SE_IMPRIMIO
        }
        if (fallo == null) {
            runCatching { comprobantes.quitar(c.venueId, c.localId) }.onFailure { Log.e(TAG, "❌ No se pudo quitar el comprobante: ${it.message}") }
        }
        return fallo
    }

    /**
     * 🔴 Lo que quedó sin imprimir (la app murió entre guardar el retiro y el papel, r3 #8/#10, o la impresora falló, H10): sale con
     * SUS datos y la leyenda REIMPRESIÓN, y se quita al salir. Sólo los de ESTE negocio; nunca uno que esta pantalla está imprimiendo
     * ahora mismo, ni el de un retiro que el servidor RECHAZÓ (su operación sigue marcada en la cola, H9): ése se quita sin imprimir.
     */
    private suspend fun reimprimirPendientes() = withContext(NonCancellable) {
        val venueId = secureStorage.venueId ?: return@withContext
        candadoDelPapel.withLock {
            val pendientes = runCatching { comprobantes.pendientes(venueId) }.getOrDefault(emptyList())
            val rechazados = runCatching { repository.retirosRechazados(venueId) }.getOrDefault(emptySet())
            val porImprimir = pendientes.filter { it.localId !in retirosEnCurso }
            porImprimir.filter { it.localId in rechazados }.forEach { c ->
                runCatching { comprobantes.quitar(c.venueId, c.localId) }.onFailure { Log.e(TAG, "❌ No se pudo quitar el comprobante: ${it.message}") }
            }
            // Para en el primer papel que no sale: cada intento espera su tiempo bajo este candado (y el papel de un retiro nuevo, detrás).
            // Los demás se quedan para la siguiente entrada a Caja.
            for (c in porImprimir.filter { it.localId !in rechazados }) {
                val fallo = imprimirYQuitar(c, reimpresion = true) ?: continue
                _avisoDelRetiro.value = fallo
                break
            }
        }
    }

    /// Returns true only when the close actually succeeded — callers must gate
    /// the daily report on this (before, the report was FABRICATED client-side
    /// and shown even when the close failed).
    /**
     * El corte que se enseña al cerrar.
     *
     * 🔴 Se lee de Room DESPUÉS de que el servidor contestó el cierre: si lo aceptó, la caja ya
     * trae sus movimientos (el reembolso que la tablet no tenía) y su diferencia. Antes la pantalla
     * armaba el corte con una foto tomada ANTES de cerrar y el «Faltante $145» salía aunque el
     * servidor dijera $0.00. [confirmado] = `false` si el cierre sigue en la cola (sin red).
     */
    data class CorteCerrado(
        val session: CashDrawerSessionEntity,
        val events: List<CashDrawerEventEntity>,
        val confirmado: Boolean,
    )

    /** `null` = no se pudo cerrar; el motivo queda en [errorMessage]. */
    suspend fun closeSession(actualAmountCents: Int, note: String?): CorteCerrado? {
        return try {
            val cerrada = repository.closeSession(actualAmountCents, note)
            _currentSession.value = null
            _events.value = emptyList()
            _expectedAmountCents.value = 0
            if (cerrada == null) {
                errorMessage.value = "No hay una caja abierta en este aparato."
                return null
            }
            Log.d(TAG, "✅ Session closed")
            val confirmado = !repository.tieneCierrePendiente(cerrada.id)
            _confirmadoConServidor.value = confirmado
            CorteCerrado(
                session = repository.getSession(cerrada.id) ?: cerrada,
                events = repository.getEvents(cerrada.id),
                confirmado = confirmado,
            )
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error closing session: ${e.message}")
            errorMessage.value = "No se pudo cerrar la caja. Intenta de nuevo."
            null
        }
    }

    // MARK: - Load events for a specific session (used by history -> report)

    fun loadEventsForSession(sessionId: String, onResult: (List<CashDrawerEventEntity>) -> Unit) {
        viewModelScope.launch {
            try {
                val events = repository.getEvents(sessionId)
                onResult(events)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error loading events for session $sessionId: ${e.message}")
                onResult(emptyList())
            }
        }
    }

    // MARK: - Helpers

    fun cashSalesTotal(): Int {
        return _events.value
            .filter { it.type == CashDrawerEventType.CASH_SALE.name }
            .sumOf { it.amountCents }
    }

    fun payInsTotal(): Int {
        return _events.value
            .filter { it.type == CashDrawerEventType.PAY_IN.name }
            .sumOf { it.amountCents }
    }

    fun payOutsTotal(): Int {
        return _events.value
            .filter { it.type == CashDrawerEventType.PAY_OUT.name }
            .sumOf { it.amountCents }
    }

    /** Nombre del local, para el encabezado del corte impreso. */
    val venueName: String get() = repository.venueName

    // MARK: - Imprimir el corte

    /**
     * Resultado de intentar imprimir el corte. Se expone como estado para que la
     * pantalla diga qué pasó: el botón antes sólo lanzaba un Toast de "no
     * disponible" que además en la Sunmi queda detrás de la pantalla del cliente,
     * o sea que para el cajero el botón simplemente no hacía nada.
     */
    sealed interface PrintCorteResult {
        data class Success(val wasPartial: Boolean) : PrintCorteResult
        data class Failure(val reason: String) : PrintCorteResult
    }

    private val _printCorteResult = MutableStateFlow<PrintCorteResult?>(null)
    val printCorteResult: StateFlow<PrintCorteResult?> = _printCorteResult.asStateFlow()

    private val _isPrintingCorte = MutableStateFlow(false)
    val isPrintingCorte: StateFlow<Boolean> = _isPrintingCorte.asStateFlow()

    fun clearPrintCorteResult() {
        _printCorteResult.value = null
    }

    /**
     * Imprime el corte de caja en la impresora de recibos.
     *
     * El desglose por método sale de [tenders] (lo que mandó el server, con TODOS
     * los métodos). Si viene vacío —sin conexión— se imprime sólo el efectivo y
     * se dice en el papel, porque un corte que afirma "Tarjeta $0.00" cuando en
     * realidad no pudo consultar es peor que uno incompleto y honesto.
     */
    fun printCorte(
        session: CashDrawerSessionEntity,
        events: List<CashDrawerEventEntity>,
        tenders: List<CashDrawerRepository.TenderRow>?,
        venueName: String,
        // Corte PARCIAL: se saca con la caja todavía abierta para revisar el turno a
        // media jornada. No cierra nada, no cuenta el dinero y no arroja diferencia
        // — sólo dice cuánto DEBERÍA haber en el cajón en este momento.
        isPartial: Boolean = false,
        /** El corte no se pudo confirmar con el servidor: el papel lo dice. */
        sinConfirmar: Boolean = false,
    ) {
        val printer = printerService.getDefaultPrinter(PrinterRole.RECEIPT)
        if (printer == null) {
            _printCorteResult.value = PrintCorteResult.Failure(
                "No hay impresora de recibos en esta caja. Agrégala en Más › Impresoras y cajón.",
            )
            return
        }

        viewModelScope.launch {
            _isPrintingCorte.value = true
            try {
                val data = CorteTicketBuilder.build(
                    session = session,
                    events = events,
                    tenders = tenders,
                    venueName = venueName,
                    paperWidth = printer.paperWidth,
                    isPartial = isPartial,
                    showExpected = !isPartial || puedeVerEsperado, // conteo ciego también en el ticket del corte parcial
                    // La integrada de Sunmi necesita el cambio a un solo byte o el
                    // papel sale en blanco. Mismo criterio que `escposFor`.
                    switchToSingleByteFirst =
                        printer.connectionTypeEnum == com.avoqado.pos.printing.data.model.PrinterConnectionType.INTERNAL,
                    tablaDeAcentos = com.avoqado.pos.printing.data.model.TablaDeAcentos.deGuardada(printer.tablaDeAcentos),
                    sinConfirmar = sinConfirmar,
                )
                printerService.sendPrintData(data, printer)
                _printCorteResult.value = PrintCorteResult.Success(isPartial)
                Log.d(TAG, "✅ Corte${if (isPartial) " parcial" else ""} impreso en ${printer.name}")
            } catch (e: Exception) {
                Log.e(TAG, "❌ No se pudo imprimir el corte: ${e.message}", e)
                _printCorteResult.value = PrintCorteResult.Failure(
                    "No se pudo imprimir: ${e.message ?: "revisa que la impresora esté encendida y conectada"}",
                )
            } finally {
                _isPrintingCorte.value = false
            }
        }
    }

    /**
     * Corte PARCIAL de la caja en curso: lo que el operador saca a media jornada
     * para revisar cómo va el turno sin cerrarlo.
     *
     * Trae el desglose fresco desde la apertura hasta AHORA antes de imprimir —
     * usar el que quedó cacheado imprimiría una foto vieja, y el punto de una
     * lectura parcial es justamente saber cómo va en este momento. Si la consulta
     * falla (sin red) igual se imprime con el efectivo local, que es dato bueno:
     * negarse a imprimir sería peor que imprimir un corte honestamente parcial.
     */
    fun printPartialCorte(
        session: CashDrawerSessionEntity,
        events: List<CashDrawerEventEntity>,
    ) {
        viewModelScope.launch {
            // 🔴 El papel se archiva: antes de imprimirlo se le pregunta al servidor, o sale sin el
            // egreso del reembolso o la venta de la terminal que sólo él conoce. Sin red se imprime
            // con lo que hay y el ticket lo DICE.
            val confirmado = sincronizar()
            val frescos = runCatching { repository.getEvents(session.id) }.getOrDefault(events)
            loadCurrentSession()
            // `null` si no se pudo consultar — NUNCA `emptyList()`, que significa "no hubo cobros".
            val tenders = runCatching {
                repository.getTenderBreakdown(session.openedAt, System.currentTimeMillis())
            }.getOrNull()
            _tenderBreakdown.value = tenders
            printCorte(
                session = session,
                events = frescos,
                tenders = tenders,
                venueName = venueName,
                isPartial = true,
                sinConfirmar = !confirmado,
            )
        }
    }


}

/** Espejo EXACTO del permiso del server que gobierna el back-office de turnos. */
const val PERMISO_VER_ESPERADO = "cash-drawer:view-expected"
val ROLES_VER_ESPERADO = setOf("MANAGER", "ADMIN", "OWNER", "SUPERADMIN")

/** Espejo EXACTO del permiso del server que deja retirar efectivo de la caja (retiro con gerente). */
const val PERMISO_RETIRAR = "cash-drawer:pay-out"
val ROLES_RETIRAR = setOf("MANAGER", "ADMIN", "OWNER", "SUPERADMIN")

/** Lo que la pantalla de Caja pinta del retiro (retiro con gerente). */
sealed interface RetiroUi {
    data object Cerrado : RetiroUi
    /** La hoja. [localId] es el de ESTE retiro: la autorización del gerente se ata a él. */
    data class Hoja(val conPin: Boolean, val localId: String, val ocupado: Boolean = false, val error: String? = null) : RetiroUi
    /** El teclado del gerente sobre la hoja, con lo que va a autorizar ([etiqueta]). */
    data class PidiendoPin(val hoja: Hoja, val amountCents: Int, val concepto: String, val etiqueta: String) : RetiroUi
    /** No se puede retirar desde aquí (sin red, o sólo un gerente): se dice y no se abre nada. */
    data class Aviso(val texto: String) : RetiroUi
}

private const val RETIRO_SIN_RED =
    "Sin conexión. Para retirar necesitas el PIN de un gerente, y eso requiere internet. El efectivo se queda en la caja."
private const val RETIRO_SOLO_GERENTE =
    "Sólo un gerente puede retirar efectivo. Pídele que lo haga con su usuario, o que el dueño active el PIN de gerente en el panel."
private const val RETIRO_SIN_CONFIRMAR = "Retiro registrado. Se subirá al servidor cuando vuelva la conexión."
private const val RETIRO_NO_SE_IMPRIMIO = "El retiro quedó registrado, pero no se imprimió el comprobante."
private const val RETIRO_SIN_IMPRESORA = "El retiro quedó registrado, pero no hay impresora de recibos en esta caja."
