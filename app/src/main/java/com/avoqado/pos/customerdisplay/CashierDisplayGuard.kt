package com.avoqado.pos.customerdisplay

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Display
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deja la caja en la pantalla que le toca.
 *
 * El launcher SIEMPRE abre en la pantalla por defecto, así que en modo
 * invertido cada arranque en frío necesita un relanzamiento: se ve ~1 s de la
 * caja en la pantalla equivocada y luego aparece en la correcta. Es el precio
 * aceptado del feature.
 *
 * 🔴 Degradar, nunca bloquear: si el equipo no permite mover la Activity, la
 * caja se queda donde está, se marca el modo como no soportado EN MEMORIA (la
 * preferencia del usuario no se toca) y Ajustes lo explica. Jamás se impide
 * cobrar por esto.
 */
@Singleton
class CashierDisplayGuard @Inject constructor(
    private val displayModePrefs: DisplayModePrefs,
    private val state: CustomerDisplayState,
) {
    private val tag = "🖥️CashierDisplay"

    private var attemptsTarget: Int? = null
    private var attempts = 0
    private var lastDisplaySet: Set<Int> = emptySet()

    fun enforce(activity: Activity) {
        val dm = activity.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        val displays = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).toList()

        // El contador se reinicia SOLO cuando cambia el hardware presente:
        // conectar o desconectar una pantalla es un escenario nuevo y merece
        // otro intento.
        val present = displays.map { it.displayId }.toSet()
        if (present != lastDisplaySet) {
            lastDisplaySet = present
            attempts = 0
            attemptsTarget = null
        }

        val roles = resolveDisplayRoles(
            defaultDisplayId = Display.DEFAULT_DISPLAY,
            candidates = displays.map { CandidateDisplay(it.displayId, displayOwnerPackage(it)) },
            remoteCaptureHints = REMOTE_CAPTURE_HINTS,
            inverted = displayModePrefs.inverted.value,
        )

        if (attemptsTarget != roles.cashierDisplayId) {
            attemptsTarget = roles.cashierDisplayId
            attempts = 0
        }

        // 🔴 El plan original decía `activity.display?.displayId`: eso es
        // `Activity.getDisplay()`, API 30. Este proyecto soporta desde 26 y esa
        // llamada revienta con NoSuchMethodError FUERA de cualquier runCatching
        // en un Sunmi con Android 9/10 — tumbaría la caja. currentDisplayId() ya
        // está gateado por versión (ver DisplayRoles.kt).
        val current = activity.currentDisplayId()
        if (!shouldRelaunchCashier(current, roles.cashierDisplayId, attempts)) {
            val reachedTarget = current == roles.cashierDisplayId
            // Ya estamos donde toca (reachedTarget) o nos rendimos tras dos
            // intentos fallidos (!reachedTarget): en el primer caso limpia
            // cualquier "no soportado" que quedara de un intento anterior con
            // otro hardware; en el segundo, lo marca.
            state.setInvertUnsupported(!reachedTarget)
            if (reachedTarget) requestCashierFocus(activity, current)
            return
        }

        attempts++
        runCatching {
            val opts = ActivityOptions.makeBasic().setLaunchDisplayId(roles.cashierDisplayId)
            activity.startActivity(
                Intent(activity, activity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                opts.toBundle(),
            )
            Log.i(tag, "Moviendo la caja al display ${roles.cashierDisplayId} (intento $attempts)")
        }.onFailure {
            Log.e(tag, "No se pudo mover la caja: ${it.message}")
            state.setInvertUnsupported(true)
        }
    }

    /**
     * 🔴 Hallazgo en el D3 físico (2026-08-10, ver progress.md): tras relanzar la
     * caja, `mTopFocusedDisplayId` se queda apuntando a la pantalla del CLIENTE y
     * NINGUNA ventana tiene foco de teclado hasta que el cajero toca la suya — el
     * primer toque del turno se gasta en "despertarlo" en vez de hacer lo que el
     * cajero pidió.
     *
     * Qué display recibe el foco entre varios es una decisión de
     * WindowManagerService, y no hay API pública para pedírsela a la app sin un
     * evento de entrada real. Se evaluó y se DESCARTÓ a propósito
     * `Window.setLocalFocus` + `WindowManager.LayoutParams.FLAG_LOCAL_FOCUS_MODE`
     * (el candidato obvio, verificado leyendo el fuente de AOSP): esa combinación
     * pone la ventana en un modo donde deja de recibir toques/teclas REALES del
     * sistema — solo eventos inyectados localmente. "Arreglar" el toque
     * desperdiciado así habría dejado la pantalla del cajero completamente muda
     * al tacto: exactamente lo que "degradar, nunca bloquear" prohíbe, y peor que
     * el problema que resuelve.
     *
     * Lo que sí es seguro: pedir el foco de VISTA del árbol de Compose en cuanto
     * la ventana consiga foco real (por sistema o por ese primer toque), para no
     * perder un segundo golpe reclamando un campo en particular. Esto NO tiene
     * garantía de eliminar el toque desperdiciado — la causa vive en el servidor
     * de ventanas, fuera del alcance de una app normal — así que si se sigue
     * reproduciendo en hardware, es un costo aceptado del feature, igual que el
     * parpadeo de ~1 s en el arranque en frío.
     */
    private fun requestCashierFocus(activity: Activity, currentDisplayId: Int) {
        if (currentDisplayId == Display.DEFAULT_DISPLAY) return
        runCatching {
            activity.window.decorView.post {
                activity.window.decorView.requestFocus()
            }
        }.onFailure {
            Log.w(tag, "No se pudo pedir el foco de teclado: ${it.message}")
        }
    }

    /** El usuario cambió el ajuste: merece intentos frescos. */
    fun resetAttempts() {
        attempts = 0
        attemptsTarget = null
        state.setInvertUnsupported(false)
    }
}
