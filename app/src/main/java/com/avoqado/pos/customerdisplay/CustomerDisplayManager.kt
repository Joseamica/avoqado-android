package com.avoqado.pos.customerdisplay

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Monta la pantalla del cliente en la pantalla SECUNDARIA cuando existe.
 *
 * API estándar de Android (DisplayManager + Presentation), NO el SDK de Sunmi:
 * así el mismo código sirve para Sunmi T3/D3, Elo, y cualquier POS de doble
 * pantalla — y si el equipo no tiene segunda pantalla, simplemente no pasa nada.
 *
 * Se conecta/desconecta en caliente: si desenchufan el monitor y lo vuelven a
 * enchufar a media venta, la pantalla se rehace sola con el estado vigente.
 */
/** Datos mínimos de una pantalla candidata; separado de [Display] para poder testear la decisión. */
internal data class CandidateDisplay(val displayId: Int, val ownerPackage: String?)

/**
 * Decisión PURA de cuál pantalla usar (sin Android): física primero, luego
 * virtual que no sea de captura/remoto, ante la duda ninguna. Top-level e
 * `internal` para tener test unitario del caso AnyDesk sin hardware.
 */
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

@Singleton
class CustomerDisplayManager @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val state: CustomerDisplayState,
    private val secureStorage: com.avoqado.pos.core.data.local.SecureStorage,
    // Se inyecta para FORZAR su construcción: es quien carga el ajuste guardado
    // dentro del state. Sin esto el ajuste solo se aplicaría si alguien abre
    // la pantalla de Ajustes.
    @Suppress("unused") private val prefs: CustomerDisplayPrefs,
    private val displayModePrefs: DisplayModePrefs,
) {
    private val tag = "🖥️CustomerDisplay"

    private val handler = Handler(Looper.getMainLooper())

    // Scope propio del manager (singleton, vive más allá de un solo ciclo de
    // attach/detach). Lo que entra/sale con attach()/detach() es el JOB que
    // colecta el interruptor, no este scope.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var invertedObserverJob: Job? = null

    private var displayManager: DisplayManager? = null
    private var presentation: CustomerDisplayPresentation? = null
    private var hostActivity: Activity? = null

    /**
     * Pantalla para la que hay un lanzamiento de [CustomerDisplayActivity]
     * PENDIENTE: se pone justo antes de `startActivity()` y se limpia cuando
     * la Activity confirma que arrancó ([onCustomerActivityStarted]), o cuando
     * el manager decide que ya no la quiere ([dismiss] / el guard anti-bucle).
     *
     * 🔴 Por qué existe: `startActivity()` hacia otra pantalla es ASÍNCRONO —
     * hay una ventana entre ESE `startActivity()` y el `onCreate()` real de la
     * Activity donde el `instance` del companion todavía es null. Si en esa
     * ventana un `refresh()` decide cerrar (el caso que importa: el guard
     * anti-bucle, cuando la pantalla destino resulta ser la de la caja),
     * `CustomerDisplayActivity.finishIfShowing()` es un no-op porque no hay
     * instancia que cerrar — y el lanzamiento en vuelo ATERRIZA de todos
     * modos, tapando la caja. Este campo es lo que le permite a la propia
     * Activity, en `onStart()`, preguntarle al manager "¿TODAVÍA me quieres
     * aquí?" en vez de asumirlo por haber llegado a existir, y cerrarse sola
     * si la respuesta es no. La decisión sigue siendo del manager: la Activity
     * solo pregunta.
     */
    private var desiredCustomerDisplayId: Int? = null

    /** true cuando hay una segunda pantalla activa (para UI de diagnóstico). */
    var isActive: Boolean = false
        private set

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = refresh()
        override fun onDisplayRemoved(displayId: Int) = refresh()
        override fun onDisplayChanged(displayId: Int) = refresh()
    }

    /** Llamar desde MainActivity.onStart. */
    fun attach(activity: Activity) {
        // La marca en reposo es del NEGOCIO (logo si hay, si no el nombre).
        state.setVenueBranding(secureStorage.venueDisplayName, secureStorage.venueLogo)
        hostActivity = activity
        val dm = activity.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        displayManager = dm
        dm.registerDisplayListener(displayListener, handler)
        refresh()
        // Reacciona al interruptor de Ajustes en caliente: sin esto, tocarlo
        // no hacía nada hasta desenchufar un monitor o mandar la app a
        // segundo plano. refresh() ya es idempotente, así que recibir aquí el
        // valor inicial del StateFlow no produce un doble montaje visible.
        invertedObserverJob = scope.launch {
            displayModePrefs.inverted.collect { refresh() }
        }
    }

    /** Llamar desde MainActivity.onStop — sin esto la ventana se filtra. */
    fun detach() {
        // Cancelar el JOB, no el scope: el scope es del manager (singleton) y
        // vive más allá de un solo ciclo de attach/detach; sin cancelar aquí,
        // cada MainActivity.onStart/onStop deja una colecta huérfana corriendo
        // — una corrutina filtrada por ciclo.
        invertedObserverJob?.cancel()
        invertedObserverJob = null
        displayManager?.unregisterDisplayListener(displayListener)
        dismiss()
        hostActivity = null
        displayManager = null
    }

    /**
     * Lo consulta [CustomerDisplayActivity.onStart]: ¿el manager TODAVÍA la
     * quiere en esta pantalla? La decisión de "¿todavía la quieres?" es del
     * manager, no de la Activity — ella solo pregunta, nunca adivina.
     */
    internal fun wantsCustomerDisplayOn(displayId: Int): Boolean =
        desiredCustomerDisplayId == displayId

    /**
     * La Activity confirma que arrancó de verdad: el lanzamiento deja de estar
     * "pendiente" (ya está "mostrándose", que se rastrea aparte vía
     * [CustomerDisplayActivity.isShowingOn]).
     */
    internal fun onCustomerActivityStarted(displayId: Int) {
        if (desiredCustomerDisplayId == displayId) desiredCustomerDisplayId = null
    }

    private fun refresh() {
        val activity = hostActivity ?: return
        // PRESENTATION = pantallas pensadas para mostrar contenido a terceros;
        // es justo la categoría en la que caen los displays de cliente.
        val displays = displayManager
            ?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            ?.toList()
            .orEmpty()

        val roles = resolveDisplayRoles(
            defaultDisplayId = Display.DEFAULT_DISPLAY,
            candidates = displays.map { CandidateDisplay(it.displayId, displayOwnerPackage(it)) },
            remoteCaptureHints = REMOTE_CAPTURE_HINTS,
            inverted = displayModePrefs.inverted.value,
        )
        state.setInvertible(roles.invertible)

        val customerId = roles.customerDisplayId
        if (customerId == null) {
            if (presentation != null || CustomerDisplayActivity.isShowingOn(Display.DEFAULT_DISPLAY)) {
                Log.i(tag, "Segunda pantalla desconectada")
            }
            dismiss()
            return
        }

        if (customerId == Display.DEFAULT_DISPLAY) {
            // 🔴 GUARD ANTI-BUCLE: si la caja (MainActivity) TODAVÍA vive en la
            // pantalla DEFAULT —arranque en frío antes de que el guard de Task 4
            // la mueva, `setLaunchDisplayId` ignorado por el fabricante, o ese
            // guard ya se rindió (invertUnsupported)— montar aquí el letrero del
            // cliente TAPARÍA la caja. Eso dispara MainActivity.onStop →
            // detach() → dismiss() → finishIfShowing() → (la caja reaparece)
            // onStart → attach() → refresh() → vuelve a montar el letrero: un
            // bucle infinito de parpadeo con la caja inservible. La regla del
            // dominio es "degradar, nunca bloquear": la pantalla del cliente es
            // decoración, cobrar es el negocio — se queda sin letrero, nunca sin
            // caja usable.
            if (activity.currentDisplayId() == customerId) {
                Log.i(
                    tag,
                    "Modo invertido pero la caja sigue en la pantalla principal: no se monta el letrero del cliente para no taparla",
                )
                dismiss()
                return
            }
            // Modo invertido: el cliente va en la pantalla principal, y ahí
            // TYPE_PRESENTATION está prohibido → Activity.
            dismissPresentation()
            showCustomerActivity(activity, customerId)
            return
        }

        finishCustomerActivity()
        val target = displays.firstOrNull { it.displayId == customerId } ?: return
        showPresentation(activity, target)
    }

    /** Modo invertido: el cliente en una Activity sobre la pantalla principal. */
    private fun showCustomerActivity(activity: Activity, displayId: Int) {
        if (CustomerDisplayActivity.isShowingOn(displayId)) return
        // Se marca ANTES de startActivity(): si el resultado tarda (es
        // asíncrono) y otro refresh() cambia de opinión mientras tanto, la
        // Activity va a preguntar por este valor en su propio onStart().
        desiredCustomerDisplayId = displayId
        runCatching {
            val opts = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
            activity.startActivity(
                Intent(activity, CustomerDisplayActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                opts.toBundle(),
            )
            isActive = true
            // 🔴 NO `state.setPresenting(true)` aquí: que `startActivity` no haya
            // lanzado excepción solo dice que el sistema aceptó la intención, no
            // que la ventana llegó a aparecer. La señal fiable es el propio
            // ciclo de vida de CustomerDisplayActivity (ver su onStart/onStop).
            // La principal siempre es física y táctil: el cliente sí puede
            // elegir propina y calificación — solo en éxito, simétrico con
            // showPresentation.
            state.setTouchCapable(true)
            Log.i(tag, "Pantalla del cliente (Activity) montada en display $displayId")
        }.onFailure {
            // Nunca tumbar la caja por culpa de la pantalla del cliente.
            Log.e(tag, "No se pudo abrir la pantalla del cliente: ${it.message}")
            isActive = false
            state.setPresenting(false)
            desiredCustomerDisplayId = null
        }
    }

    /** Modo normal: el cliente en un Presentation sobre la secundaria. */
    private fun showPresentation(activity: Activity, target: Display) {
        if (presentation?.display?.displayId == target.displayId && presentation?.isShowing == true) return
        dismissPresentation()
        runCatching {
            CustomerDisplayPresentation(activity, target, state).also {
                it.show()
                presentation = it
                isActive = true
                state.setPresenting(true)
                // Detección automática por hardware: una pantalla FÍSICA (sin
                // dueño) sí entrega toques; una virtual de Sunmi (NP511 del T3
                // Pro) NO. De esto depende delegar propina/calificación.
                val touchCapable = displayOwnerPackage(target) == null
                state.setTouchCapable(touchCapable)
                Log.i(tag, "Pantalla del cliente montada en display ${target.displayId} (${target.name}), táctil=$touchCapable")
            }
        }.onFailure {
            Log.e(tag, "No se pudo montar la pantalla del cliente: ${it.message}")
            isActive = false
            state.setPresenting(false)
        }
    }

    private fun dismissPresentation() {
        runCatching { presentation?.dismiss() }
        presentation = null
    }

    /**
     * Cierra la Activity del cliente si está viva Y cancela cualquier
     * lanzamiento pendiente. Las dos cosas juntas: un `finish()` a una
     * instancia que TODAVÍA no existe (ver [desiredCustomerDisplayId]) no
     * cancela nada por sí solo, y el lanzamiento en vuelo aterrizaría igual.
     */
    private fun finishCustomerActivity() {
        desiredCustomerDisplayId = null
        CustomerDisplayActivity.finishIfShowing()
    }

    private fun dismiss() {
        dismissPresentation()
        // Si la caja se va a segundo plano, el cliente NO puede quedarse viendo
        // un total congelado.
        finishCustomerActivity()
        isActive = false
        state.setPresenting(false)
    }
}
