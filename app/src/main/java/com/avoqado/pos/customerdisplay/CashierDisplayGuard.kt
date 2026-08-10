package com.avoqado.pos.customerdisplay

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Display
import java.lang.ref.WeakReference
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

    private var accounting = RelaunchAccounting()

    /**
     * La instancia de caja a la que YA le pedimos que se mudara, con la mudanza
     * todavía en vuelo.
     *
     * 🔴 `startActivity` hacia otra pantalla es asíncrono: entre que se pide y
     * que el sistema recrea la Activity pasan cientos de ms, y en ese hueco
     * `enforce` se puede volver a llamar (onCreate y onResume de la MISMA
     * instancia se pisan en un arranque en frío). Sin esto, esa segunda llamada
     * gastaría uno de los DOS intentos del anti-bucle por nada —dejando al
     * equipo que sí necesitaba dos con uno solo— y encima lanzaría una segunda
     * mudanza sobre la que ya iba en camino: un parpadeo extra de la caja en
     * cada arranque invertido.
     *
     * Cuando la mudanza aterriza, Android recrea la caja: quien pase por aquí
     * será OTRA instancia y la comparación por identidad deja de coincidir. Es
     * decir, la cuenta de intentos pasa a significar lo correcto —"cuántas
     * instancias seguidas nacieron en la pantalla equivocada"— en vez de
     * "cuántas veces llamamos a este método".
     *
     * WeakReference porque este guard es @Singleton: jamás debe mantener viva a
     * una Activity muerta.
     */
    private var relaunchRequestedFor: WeakReference<Activity>? = null

    /**
     * Coloca la caja donde le toca. Es IDEMPOTENTE y barato cuando ya está en
     * destino ([shouldRelaunchCashier] devuelve false sin tocar nada) y cuando la
     * mudanza ya va en camino ([relaunchRequestedFor]), y por eso se puede llamar
     * en cada arranque, en cada regreso al frente y cada vez que cambia el
     * hardware de pantallas — que es justo lo que hace falta para que reconectar
     * la pantalla del cliente en caliente vuelva a colocar la caja.
     */
    fun enforce(activity: Activity) {
        val dm = activity.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        val displays = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).toList()

        val roles = resolveDisplayRoles(
            defaultDisplayId = Display.DEFAULT_DISPLAY,
            candidates = displays.map { CandidateDisplay(it.displayId, displayOwnerPackage(it)) },
            remoteCaptureHints = REMOTE_CAPTURE_HINTS,
            inverted = displayModePrefs.inverted.value,
        )

        // El contador se reinicia SOLO ante un escenario nuevo —cambió el
        // hardware presente o cambió el destino—, nunca por volver a pasar por
        // aquí. La decisión es pura y está testeada: ver accountForEnforce.
        val present = displays.map { it.displayId }.toSet()
        if (isNewRelaunchScenario(accounting, present, roles.cashierDisplayId)) {
            // Escenario nuevo: lo que hubiéramos pedido antes ya no aplica, así
            // que esta instancia vuelve a tener derecho a pedir la mudanza.
            relaunchRequestedFor = null
        }
        accounting = accountForEnforce(accounting, present, roles.cashierDisplayId)

        // 🔴 El plan original decía `activity.display?.displayId`: eso es
        // `Activity.getDisplay()`, API 30. Este proyecto soporta desde 26 y esa
        // llamada revienta con NoSuchMethodError FUERA de cualquier runCatching
        // en un Sunmi con Android 9/10 — tumbaría la caja. currentDisplayId() ya
        // está gateado por versión (ver DisplayRoles.kt).
        val current = activity.currentDisplayId()
        if (!shouldRelaunchCashier(current, roles.cashierDisplayId, accounting.attempts)) {
            val reachedTarget = current == roles.cashierDisplayId
            // Ya estamos donde toca (reachedTarget) o nos rendimos tras dos
            // intentos fallidos (!reachedTarget): en el primer caso limpia
            // cualquier "no soportado" que quedara de un intento anterior con
            // otro hardware; en el segundo, lo marca.
            state.setInvertUnsupported(!reachedTarget)
            return
        }

        // Va después de la rama de arriba a propósito: si el equipo movió la
        // tarea SIN recrear la Activity, la misma instancia llega aquí ya en
        // destino y tiene que pasar por el `setInvertUnsupported(false)`.
        if (relaunchRequestedFor?.get() === activity) {
            Log.d(tag, "La mudanza de la caja ya va en camino para esta instancia: no se pide otra")
            return
        }

        accounting = accounting.copy(attempts = accounting.attempts + 1)
        relaunchRequestedFor = WeakReference(activity)
        runCatching {
            val opts = ActivityOptions.makeBasic().setLaunchDisplayId(roles.cashierDisplayId)
            activity.startActivity(
                Intent(activity, activity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                opts.toBundle(),
            )
            Log.i(tag, "Moviendo la caja al display ${roles.cashierDisplayId} (intento ${accounting.attempts})")
        }.onFailure {
            Log.e(tag, "No se pudo mover la caja: ${it.message}")
            state.setInvertUnsupported(true)
        }
    }

    /** El usuario cambió el ajuste: merece intentos frescos. */
    fun resetAttempts() {
        // Se olvida el destino, no el hardware: el siguiente enforce ve un
        // escenario nuevo y vuelve a contar desde cero.
        accounting = accounting.copy(target = null, attempts = 0)
        // 🔴 Y se olvida la mudanza en vuelo: el interruptor lo toca la MISMA
        // instancia de caja que quizá ya pidió mudarse antes. Sin esto, el
        // enforce que sigue al toque se saltaría por "ya va en camino" y el
        // interruptor no movería nada.
        relaunchRequestedFor = null
        state.setInvertUnsupported(false)
    }
}
