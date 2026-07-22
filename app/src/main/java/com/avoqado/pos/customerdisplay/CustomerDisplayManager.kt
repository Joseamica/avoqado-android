package com.avoqado.pos.customerdisplay

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import dagger.hilt.android.qualifiers.ApplicationContext
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
) {
    private val tag = "🖥️CustomerDisplay"

    // Apps cuya pantalla virtual es una CAPTURA de la caja, no un display de
    // cliente. Se comparan como substring del paquete (en minúsculas), así que
    // basta la raíz de la marca para cubrir sus variantes.
    private val REMOTE_CAPTURE_HINTS = listOf(
        "anydesk", "teamviewer", "rustdesk", "vnc", "scrcpy",
        "airdroid", "splashtop", "screencap", "screenrecord",
    )
    private val handler = Handler(Looper.getMainLooper())

    private var displayManager: DisplayManager? = null
    private var presentation: CustomerDisplayPresentation? = null
    private var hostActivity: Activity? = null

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
        // La marca en reposo es del NEGOCIO, no de Avoqado.
        state.setVenueName(secureStorage.venueName)
        hostActivity = activity
        val dm = activity.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        displayManager = dm
        dm.registerDisplayListener(displayListener, handler)
        refresh()
    }

    /** Llamar desde MainActivity.onStop — sin esto la ventana se filtra. */
    fun detach() {
        displayManager?.unregisterDisplayListener(displayListener)
        dismiss()
        hostActivity = null
        displayManager = null
    }

    private fun refresh() {
        val activity = hostActivity ?: return
        // PRESENTATION = pantallas pensadas para mostrar contenido a terceros;
        // es justo la categoría en la que caen los displays de cliente.
        val candidates = displayManager
            ?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            ?.toList()
            .orEmpty()
        val target = pickCustomerDisplay(candidates)

        if (target == null) {
            if (presentation != null) Log.i(tag, "Segunda pantalla desconectada")
            dismiss()
            return
        }

        // Ya montada en esa misma pantalla: nada que hacer.
        if (presentation?.display?.displayId == target.displayId && presentation?.isShowing == true) return

        dismiss()
        runCatching {
            CustomerDisplayPresentation(activity, target, state).also {
                it.show()
                presentation = it
                isActive = true
                state.setPresenting(true)
                Log.i(tag, "Pantalla del cliente montada en display ${target.displayId} (${target.name})")
            }
        }.onFailure {
            // Nunca tumbar la caja por culpa de la pantalla del cliente.
            Log.e(tag, "No se pudo montar la pantalla del cliente: ${it.message}")
            isActive = false
            state.setPresenting(false)
        }
    }

    /**
     * Elige la pantalla del cliente entre las candidatas.
     *
     * 🔴 El problema real: una app de control remoto (AnyDesk, TeamViewer…)
     * crea una pantalla VIRTUAL para capturar la caja, y esa también se anuncia
     * como "de presentación". Con `firstOrNull()` le atinábamos a la buena por
     * PURA SUERTE (orden de enumeración): si la captura enumera primero,
     * montaríamos la interfaz del cliente DENTRO de la captura y su pantalla
     * física se quedaría en negro mientras alguien está conectado por remoto.
     *
     * No sirve "rechazar todas las virtuales": en el Sunmi T3 Pro la pantalla
     * del cliente ES virtual (la crea `com.sunmi.usbscreen`). Lo que distingue
     * a la buena de la captura es QUIÉN la creó:
     *   - física (HDMI del D3, Elo…): sin dueño → siempre la mejor opción.
     *   - virtual de un servicio de pantalla del vendor (Sunmi): válida.
     *   - virtual de una app de captura/remoto: se descarta.
     * Ante la duda (dueño desconocido) NO montamos: mejor nada que dentro de la
     * captura de otro.
     */
    private fun pickCustomerDisplay(candidates: List<Display>): Display? {
        val chosenId = chooseCustomerDisplayId(
            candidates.map { CandidateDisplay(it.displayId, ownerPackage(it)) },
            REMOTE_CAPTURE_HINTS,
        ) ?: return null
        return candidates.firstOrNull { it.displayId == chosenId }
    }

    /**
     * Paquete que creó una pantalla VIRTUAL; null en las físicas.
     * `getOwnerPackageName()` es @hide, por eso reflexión — envuelta para que si
     * un OEM la bloquea, caigamos al comportamiento previo en vez de crashear.
     */
    private fun ownerPackage(display: Display): String? = runCatching {
        Display::class.java.getMethod("getOwnerPackageName").invoke(display) as? String
    }.getOrNull()

    private fun dismiss() {
        runCatching { presentation?.dismiss() }
        presentation = null
        isActive = false
        state.setPresenting(false)
    }
}
