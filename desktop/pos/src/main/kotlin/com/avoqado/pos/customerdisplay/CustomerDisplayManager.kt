package com.avoqado.pos.customerdisplay

import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.kiosk.domain.KioskState
import java.awt.Rectangle
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Reemplazo de escritorio de CustomerDisplayManager.kt: la pantalla del cliente en un SEGUNDO MONITOR. Los monitores se ven
 * como pantallas de Android (ver MonitoresDeEscritorio.kt) y decide la misma función pura; el letrero es una ventana propia
 * (escritorio/VentanaDelCliente.kt) con el mismo contenido que `CustomerDisplayPresentation`.
 *
 * Lo que NO se trae, y por qué: el relanzamiento de la Activity, el re-frente de la caja y el puente de toques de la T3 Pro.
 * En escritorio las dos ventanas son nuestras: la del cliente no es enfocable (nunca le quita el teclado a la caja) y en
 * Windows cada pantalla táctil manda sus toques a la ventana que tiene debajo.
 *
 * 🔴 Copiado TAL CUAL del original (la huella de excluidos.txt avisa si Android lo cambia): CandidateDisplay,
 * chooseCustomerDisplayId, toDisplayCapabilitySnapshot, canMountCustomerWindow, observePhysicalDisplayMode,
 * observeCashierPhysicalMode y los tipos del aplicador físico.
 */

/** Datos mínimos de una pantalla candidata (copiado del original). */
internal data class CandidateDisplay(val displayId: Int, val ownerPackage: String?)

/** Decisión PURA de cuál pantalla usar (copiada del original, sin cambios). */
internal fun chooseCustomerDisplayId(
    candidates: List<CandidateDisplay>,
    remoteCaptureHints: List<String>,
): Int? {
    if (candidates.isEmpty()) return null
    // Física = sin dueño. Si hay, gana siempre (es la pantalla real del cliente).
    val physical = candidates.filter { it.ownerPackage == null }
    if (physical.isNotEmpty()) return physical.minByOrNull { it.displayId }?.displayId
    // Todas virtuales (T3 Pro): descartar las de captura/remoto por dueño.
    return candidates
        .filter { d ->
            val owner = d.ownerPackage?.lowercase().orEmpty()
            remoteCaptureHints.none { owner.contains(it) }
        }
        .minByOrNull { it.displayId }?.displayId
}

/** El mismo resultado de roles alimenta UI local y sincronización remota (copiado del original). */
internal fun DisplayRoles.toDisplayCapabilitySnapshot(): DisplayCapabilitySnapshot =
    DisplayCapabilitySnapshot(
        present = customerDisplayId != null,
        invertible = invertible,
    )

/** Nunca se monta contenido del cliente encima de la caja (copiado del original). */
internal fun canMountCustomerWindow(customerDisplayId: Int?, cashierDisplayId: Int): Boolean =
    customerDisplayId != null && customerDisplayId != cashierDisplayId

/** Observación física simétrica; ninguna preferencia participa en la respuesta (copiado del original). */
internal fun observePhysicalDisplayMode(
    defaultDisplayId: Int,
    normalCustomerDisplayId: Int?,
    cashierDisplayId: Int,
    presentationDisplayId: Int?,
    presentationShowing: Boolean,
    defaultCustomerActivityStarted: Boolean,
): Boolean? {
    val customerDisplayId = normalCustomerDisplayId ?: return null
    val presentationReady = presentationShowing && presentationDisplayId == customerDisplayId
    return when (cashierDisplayId) {
        defaultDisplayId -> {
            if (presentationReady && !defaultCustomerActivityStarted) false else null
        }

        customerDisplayId -> {
            if (defaultCustomerActivityStarted && !presentationReady) true else null
        }

        else -> null
    }
}

/** Fallback físico para un rechazo: sólo ubicación de caja, nunca preferencias (copiado del original). */
internal fun observeCashierPhysicalMode(
    defaultDisplayId: Int,
    normalCustomerDisplayId: Int?,
    cashierDisplayId: Int,
): Boolean? = when (cashierDisplayId) {
    defaultDisplayId -> false
    normalCustomerDisplayId -> true
    else -> null
}

sealed interface PhysicalDisplayModeResult {
    data class Confirmed(val inverted: Boolean) : PhysicalDisplayModeResult
    data class Rejected(
        val resultCode: DisplayModeAckResultCode,
        val confirmedInverted: Boolean,
    ) : PhysicalDisplayModeResult
    data object Pending : PhysicalDisplayModeResult
}

internal interface DisplayModePhysicalApplier {
    suspend fun applyAndConfirm(desiredInverted: Boolean): PhysicalDisplayModeResult
    suspend fun observeConfirmedMode(): Boolean?
}

// MARK: - Costuras de escritorio (Main da las de verdad; las pruebas, unas falsas)

/** La ventana de la caja, vista por el manager. */
internal interface CajaDeEscritorio {
    /** Dónde está ahora (coordenadas de AWT), o null si todavía no se ve. */
    fun limites(): Rectangle?

    /** Llevarla a ese monitor, maximizada. */
    fun moverA(monitor: Rectangle)
}

/** Un letrero del cliente ya abierto. */
internal interface LetreroDelCliente {
    fun cerrar()

    /** Si Windows lo minimizó o escondió (Win+D), volverlo a mostrar SIN quitarle el foco a la caja. */
    fun asegurarVisible()
}

internal fun interface FabricaDeLetreros {
    /** Abre el letrero en [limites]; [alTronar] lo llama la ventana si su contenido truena. Puede lanzar. */
    fun abrir(limites: Rectangle, alTronar: () -> Unit): LetreroDelCliente

    /** Al cerrar la app: suelta lo que la fábrica reutiliza entre montajes (la ventana de verdad). */
    fun liberar() {}
}

@Singleton
class CustomerDisplayManager @Inject constructor(
    private val state: CustomerDisplayState,
    @Suppress("unused") private val kioskState: KioskState,
    private val secureStorage: SecureStorage,
    // Se inyecta para FORZAR su construcción, como en Android: es quien carga el ajuste guardado dentro del state.
    @Suppress("unused") private val prefs: CustomerDisplayPrefs,
    private val displayModePrefs: DisplayModePrefs,
) : DisplayModePhysicalApplier {
    private val tag = "🖥️CustomerDisplay"

    /** Todo lo de AWT y del state en el hilo de Swing (Dispatchers.Main en escritorio). */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observadorDelModo: Job? = null
    private var vigia: Job? = null

    internal var fuenteDeMonitores: () -> List<Monitor> = ::monitoresDeAwt
    private var caja: CajaDeEscritorio? = null
    private var fabrica: FabricaDeLetreros? = null

    private var letrero: LetreroDelCliente? = null
    private var letreroEn: Int? = null
    private var limitesDelLetrero: Rectangle? = null

    /** true cuando hay un letrero del cliente montado (para UI de diagnóstico). */
    var isActive: Boolean = false
        private set

    /**
     * Lo llama Main tras el primer cuadro de la caja (lo que en Android hace `MainActivity.onStart` con `attach`). La colecta
     * del modo invertido ES el primer resync (un StateFlow entrega su valor al colectar). Después, cada [VIGILANCIA_MS] se
     * mira si cambiaron los monitores (AWT no avisa de forma confiable al enchufar o desenchufar uno) o si el cajero llevó
     * su ventana a otro monitor, y se refresca la marca (al iniciar sesión o cambiar de sucursal no hay otro aviso).
     */
    internal fun arrancar(caja: CajaDeEscritorio, fabrica: FabricaDeLetreros) {
        detener()
        this.caja = caja
        this.fabrica = fabrica
        observadorDelModo = scope.launch { displayModePrefs.inverted.collect { resync() } }
        vigia = scope.launch {
            var ultima = fuenteDeMonitores()
            var cajaAntes = monitorDeLaCaja(numerar(ultima))
            while (true) {
                delay(VIGILANCIA_MS)
                ponerMarca()
                val ahora = fuenteDeMonitores()
                val cajaAhora = monitorDeLaCaja(numerar(ahora))
                when {
                    ahora != ultima -> {
                        Log.i(tag, "Cambiaron los monitores: ${ahora.size}")
                        resyncar(recolocarCaja = true)
                    }
                    // El cajero arrastró su ventana (o Win+Shift+flecha): no se la regresamos; sólo se quita el letrero de
                    // debajo de ella, o se vuelve a montar cuando su monitor queda libre.
                    cajaAhora != cajaAntes -> resyncar(recolocarCaja = false)
                    else -> letrero?.let { l -> runCatching { l.asegurarVisible() } }
                }
                ultima = ahora
                cajaAntes = monitorDeLaCaja(numerar(ultima))
            }
        }
    }

    private fun monitorDeLaCaja(numerados: Map<Int, Monitor>): Int? = caja?.limites()?.let { monitorDe(it, numerados) }

    private fun ponerMarca() = state.setVenueBranding(secureStorage.venueDisplayName, secureStorage.venueLogo)

    /** Suelta todo (al cerrar la app y en las pruebas). */
    internal fun detener() {
        observadorDelModo?.cancel(); observadorDelModo = null
        vigia?.cancel(); vigia = null
        quitar()
        fabrica?.let { f -> runCatching { f.liberar() } }
    }

    /**
     * Vuelve a colocar la caja Y a montar el letrero del cliente (en el hilo de Swing). Mismo papel que en Android: se llama
     * cuando el escenario pudo cambiar (monitores, modo invertido). No se llama en cada vuelta del vigía: un cajero que
     * arrastra su ventana al otro monitor no la ve regresar sola hasta el siguiente cambio de verdad.
     */
    fun resync() = resyncar(recolocarCaja = true)

    private fun resyncar(recolocarCaja: Boolean) {
        val caja = caja ?: return
        ponerMarca()
        val numerados = numerar(fuenteDeMonitores())
        val roles = rolesDeEscritorio(numerados, displayModePrefs.inverted.value)
        state.updateCapabilities(roles.toDisplayCapabilitySnapshot())

        val destino = numerados[roles.cashierDisplayId]
        if (recolocarCaja && destino != null && caja.limites()?.let { monitorDe(it, numerados) } != roles.cashierDisplayId) {
            runCatching { caja.moverA(destino.limites) }.onFailure { Log.w(tag, "No se pudo mover la caja: ${it.message}") }
        }
        val cajaEn = caja.limites()?.let { monitorDe(it, numerados) } ?: roles.cashierDisplayId

        val cliente = roles.customerDisplayId
        if (!canMountCustomerWindow(cliente, cajaEn)) {
            if (letrero != null) Log.i(tag, "Sin monitor para el cliente (cliente=$cliente, caja=$cajaEn): se quita el letrero")
            quitar()
            return
        }
        val limites = numerados.getValue(cliente!!).limites
        if (letrero != null && letreroEn == cliente && limitesDelLetrero == limites) return
        quitar()
        mostrar(cliente, limites)
    }

    private fun mostrar(monitor: Int, limites: Rectangle) {
        val fabrica = fabrica ?: return
        runCatching {
            lateinit var nuevo: LetreroDelCliente
            nuevo = fabrica.abrir(Rectangle(limites)) { alTronarElLetrero(nuevo) }
            nuevo
        }.onSuccess {
            letrero = it
            letreroEn = monitor
            limitesDelLetrero = Rectangle(limites)
            isActive = true
            state.setPresenting(true)
            // Un monitor es pantalla FÍSICA: recibe sus propios toques (como la HDMI del D3 en Android).
            state.setTouchCapable(true)
            Log.i(tag, "Pantalla del cliente montada en el monitor $monitor (${limites.width}×${limites.height})")
        }.onFailure {
            Log.e(tag, "No se pudo montar la pantalla del cliente: ${it.message}", it)
            quitar()
        }
    }

    /** Degradar, nunca bloquear: el letrero truena ⇒ se quita ÉL y la caja sigue. Se repone en el siguiente cambio real. */
    private fun alTronarElLetrero(cual: LetreroDelCliente) {
        if (letrero !== cual) return
        Log.w(tag, "La pantalla del cliente tronó: se quita y la caja sigue")
        quitar()
    }

    private fun quitar() {
        val habia = letrero
        letrero = null
        letreroEn = null
        limitesDelLetrero = null
        habia?.let { runCatching { it.cerrar() } }
        // Siempre, no sólo si había: `isPresenting` true sin letrero dejaría el cobro esperando un toque imposible.
        isActive = false
        state.setPresenting(false)
    }

    override suspend fun applyAndConfirm(desiredInverted: Boolean): PhysicalDisplayModeResult {
        val inicio = withContext(Dispatchers.Main) {
            if (caja == null) return@withContext PhysicalDisplayModeResult.Pending
            resync()
            observar()?.let { if (it == desiredInverted) return@withContext PhysicalDisplayModeResult.Confirmed(it) }
            val capacidad = state.capabilities.value
            if (capacidad?.present != true || !capacidad.invertible) {
                val confirmado = observar() ?: observarCaja() ?: return@withContext PhysicalDisplayModeResult.Pending
                return@withContext PhysicalDisplayModeResult.Rejected(DisplayModeAckResultCode.DISPLAY_NOT_INVERTIBLE, confirmado)
            }
            null
        }
        if (inicio != null) return inicio
        repeat(CONFIRMACIONES) {
            delay(CONFIRMACION_MS)
            if (observeConfirmedMode() == desiredInverted) return PhysicalDisplayModeResult.Confirmed(desiredInverted)
        }
        return PhysicalDisplayModeResult.Pending
    }

    override suspend fun observeConfirmedMode(): Boolean? = withContext(Dispatchers.Main) { observar() }

    /** Dónde están DE VERDAD la caja y el letrero, con la decisión de Android (en el hilo de Swing). */
    private fun observar(): Boolean? {
        val caja = caja ?: return null
        val numerados = numerar(fuenteDeMonitores())
        val normal = rolesDeEscritorio(numerados, invertido = false)
        val cajaEn = caja.limites()?.let { monitorDe(it, numerados) } ?: return null
        if (!normal.invertible) return observeCashierPhysicalMode(0, normal.customerDisplayId, cajaEn)
        val en = letreroEn.takeIf { letrero != null }
        return observePhysicalDisplayMode(
            defaultDisplayId = 0,
            normalCustomerDisplayId = normal.customerDisplayId,
            cashierDisplayId = cajaEn,
            presentationDisplayId = en?.takeIf { it != 0 },
            presentationShowing = en != null && en != 0,
            defaultCustomerActivityStarted = en == 0,
        )
    }

    private fun observarCaja(): Boolean? {
        val caja = caja ?: return null
        val numerados = numerar(fuenteDeMonitores())
        val normal = rolesDeEscritorio(numerados, invertido = false)
        val cajaEn = caja.limites()?.let { monitorDe(it, numerados) } ?: return null
        return observeCashierPhysicalMode(0, normal.customerDisplayId, cajaEn)
    }

    private companion object {
        const val VIGILANCIA_MS = 2_000L
        const val CONFIRMACIONES = 30
        const val CONFIRMACION_MS = 100L
    }
}
