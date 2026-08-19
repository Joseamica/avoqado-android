package com.avoqado.pos.customerdisplay

import android.app.Activity
import android.os.Build
import android.view.Display

/**
 * Apps cuya pantalla virtual es una CAPTURA de la caja, no un display de
 * cliente. Se comparan como substring del paquete en minúsculas, así que basta
 * la raíz de la marca para cubrir sus variantes.
 *
 * Vive aquí (y no en el manager) porque el guard de arranque necesita la MISMA
 * lista: dos listas que se separen darían dos decisiones distintas sobre la
 * misma pantalla.
 */
internal val REMOTE_CAPTURE_HINTS: List<String> = listOf(
    "anydesk", "teamviewer", "rustdesk", "vnc", "scrcpy",
    "airdroid", "splashtop", "screencap", "screenrecord",
)

/**
 * Paquete que creó una pantalla VIRTUAL; null en las físicas.
 * `getOwnerPackageName()` es @hide, por eso reflexión — envuelta para que si un
 * OEM la bloquea, caigamos al comportamiento previo en vez de crashear.
 */
internal fun displayOwnerPackage(display: Display): String? = runCatching {
    Display::class.java.getMethod("getOwnerPackageName").invoke(display) as? String
}.getOrNull()

/**
 * En qué pantalla vive esta Activity.
 *
 * 🔴 `Activity.getDisplay()` es API 30 y este proyecto soporta desde 26: en un
 * Sunmi con Android 9 la llamada directa es un NoSuchMethodError que tumba la
 * caja. El camino viejo está deprecado pero es el único que existe ahí.
 */
@Suppress("DEPRECATION")
internal fun Activity.currentDisplayId(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        display?.displayId ?: Display.DEFAULT_DISPLAY
    } else {
        windowManager.defaultDisplay.displayId
    }

/**
 * Qué pantalla le toca a quién.
 *
 * @param cashierDisplayId dónde debe vivir `MainActivity` (la caja).
 * @param customerDisplayId dónde se muestra al cliente; null si no hay segunda pantalla usable.
 * @param invertible si este equipo admite el modo invertido (ver [resolveDisplayRoles]).
 */
internal data class DisplayRoles(
    val cashierDisplayId: Int,
    val customerDisplayId: Int?,
    val invertible: Boolean,
)

/**
 * Decisión PURA de los roles. Sin Android, sin red, con todo por parámetro:
 * toda la corrección del feature vive aquí y por eso aquí están los tests.
 *
 * 🔴 `invertible` exige que la segunda pantalla sea FÍSICA (sin dueño). Una
 * virtual de vendor (la del Sunmi T3 Pro, creada por com.sunmi.usbscreen) sirve
 * perfectamente para MOSTRARLE al cliente, pero Android no le entrega toques a
 * la app: poner la caja ahí dejaría al cajero sin poder tocar nada. Ante la
 * duda, no se invierte.
 *
 * 🔴 Y el puente táctil ([CustomerTouchBridge]) NO cambia esto — no lo
 * "simplifiques" a una sola condición junto con `touchCapable`. El puente
 * reenvía los toques huérfanos a NUESTRA ventana de cliente: le alcanza a un
 * cliente para apretar botones grandes. Invertir es otra cosa: es el CAJERO
 * trabajando ahí, con la app completa, campos de texto y teclado en pantalla —
 * y el puente no da foco de entrada ni IME (la ventana del cliente es
 * `FLAG_NOT_FOCUSABLE` a propósito, para no robarle el teclado a la caja).
 * Táctil para el cliente ≠ utilizable como caja.
 */
internal fun resolveDisplayRoles(
    defaultDisplayId: Int,
    candidates: List<CandidateDisplay>,
    remoteCaptureHints: List<String>,
    inverted: Boolean,
): DisplayRoles {
    val secondaryId = chooseCustomerDisplayId(candidates, remoteCaptureHints)
    val secondary = candidates.firstOrNull { it.displayId == secondaryId }
    val invertible = secondary != null &&
        secondary.ownerPackage == null &&
        secondary.displayId != defaultDisplayId

    return if (inverted && invertible && secondary != null) {
        DisplayRoles(
            cashierDisplayId = secondary.displayId,
            customerDisplayId = defaultDisplayId,
            invertible = true,
        )
    } else {
        DisplayRoles(
            cashierDisplayId = defaultDisplayId,
            customerDisplayId = secondaryId,
            invertible = invertible,
        )
    }
}

/**
 * ¿Hay que relanzar la caja en otra pantalla?
 *
 * 🔴 El `maxAttempts` no es paranoia: si un equipo ignora `setLaunchDisplayId`,
 * la Activity vuelve a nacer en la pantalla equivocada y volvería a relanzarse
 * — para siempre. El contador lo corta.
 */
internal fun shouldRelaunchCashier(
    currentDisplayId: Int,
    targetDisplayId: Int,
    attemptsForTarget: Int,
    maxAttempts: Int = 2,
): Boolean = currentDisplayId != targetDisplayId && attemptsForTarget < maxAttempts

/**
 * La contabilidad del anti-bucle: qué hardware había, a qué pantalla íbamos y
 * cuántas veces lo hemos intentado.
 *
 * @param displaySet las pantallas presentes la última vez que se contó.
 * @param target la pantalla a la que le tocaba la caja entonces; null = todavía ninguna.
 * @param attempts relanzamientos ya gastados PARA ESE escenario.
 */
internal data class RelaunchAccounting(
    val displaySet: Set<Int> = emptySet(),
    val target: Int? = null,
    val attempts: Int = 0,
)

/**
 * ¿El escenario es genuinamente NUEVO respecto a la última cuenta?
 *
 * Solo dos cosas lo hacen nuevo: cambió el hardware presente (alguien enchufó o
 * desenchufó una pantalla) o cambió el destino (el usuario tocó el interruptor).
 * Volver a pasar por `enforce` NO es un escenario nuevo.
 */
internal fun isNewRelaunchScenario(
    previous: RelaunchAccounting,
    presentDisplays: Set<Int>,
    target: Int,
): Boolean = presentDisplays != previous.displaySet || previous.target != target

/**
 * Actualiza la contabilidad al entrar a un `enforce`. PURA (sin Android) porque
 * es la única lógica no trivial del guard y el precio de equivocarse es
 * asimétrico en los dos sentidos:
 *
 * - Reiniciar de más = relanzar la caja para siempre en un equipo que ignora
 *   `setLaunchDisplayId`. La caja parpadeando sin parar es el peor final posible.
 * - Reiniciar de menos = quedarse con la caja en la pantalla equivocada aunque el
 *   escenario haya cambiado (justo el caso de reconectar la pantalla en caliente:
 *   el hardware volvió, y volver a intentarlo es lo correcto).
 */
internal fun accountForEnforce(
    previous: RelaunchAccounting,
    presentDisplays: Set<Int>,
    target: Int,
): RelaunchAccounting = RelaunchAccounting(
    displaySet = presentDisplays,
    target = target,
    attempts = if (isNewRelaunchScenario(previous, presentDisplays, target)) 0 else previous.attempts,
)

/** Cuántos remontes seguidos se permiten antes de rendirse. Ver [decideCustomerRemount]. */
internal const val MAX_CUSTOMER_REMOUNTS = 3

/**
 * Cuánto tiene que aguantar la ventana en pantalla para que la ráfaga se
 * considere terminada. Ver [decideCustomerRemount].
 */
internal const val CUSTOMER_REMOUNT_QUIET_PERIOD_MS = 10_000L

/**
 * Veredicto de [decideCustomerRemount].
 *
 * @param remount hay que reponer la ventana del cliente.
 * @param attempts remontes ya gastados en la ráfaga en curso, DESPUÉS de este evento.
 * @param gaveUp se alcanzó el tope: no se repone y hay que dejar rastro en el log.
 */
internal data class RemountVerdict(
    val remount: Boolean,
    val attempts: Int,
    val gaveUp: Boolean,
)

/**
 * La ventana del cliente dejó de estar al frente. ¿Se repone?
 *
 * 🔴 El hueco que cierra: el letrero del cliente NO usa lock-task, así que un
 * swipe desde el borde trae las barras del sistema y un HOME lo manda al fondo —
 * la Activity sigue viva pero fuera de pantalla. Lo mismo hace un overlay del
 * sistema o la gestión de energía del fabricante. Hasta ahora sólo se recuperaba
 * en el siguiente evento de pantalla o cuando la caja volvía al frente: mientras
 * tanto el cliente se quedaba viendo el escritorio de Android.
 *
 * 🔴 Por qué NO puede entrar en lazo, que es el riesgo real de remontar desde el
 * propio `onStop` del letrero:
 *
 * 1. **El deseo manda.** Sólo se repone si el manager SIGUE queriendo una ventana
 *    de cliente en ESA pantalla (`desiredDisplayId == stoppedDisplayId`). Cuando el
 *    manager la cierra a propósito retira el deseo ANTES de llamar a `finish()`
 *    (ver `CustomerDisplayManager.finishCustomerActivity`), así que el `onStop`
 *    que provoca ese cierre llega aquí con el deseo ya en `null` y contesta que
 *    no. El lazo `dismiss → onStop → remontar` es imposible por construcción, no
 *    por temporización.
 * 2. **Tope por ráfaga.** Si algo ajeno tumba la ventana una y otra vez —el modo
 *    el fijado de pantalla bloquea lanzar esta Activity (es tarea nueva, y lock-task lo rechaza
 *    devolviendo un código, sin excepción que atrapar), un overlay que gana
 *    siempre— se corta a los [MAX_CUSTOMER_REMOUNTS] intentos seguidos.
 *
 * La ráfaga se cierra sola cuando la ventana aguanta [CUSTOMER_REMOUNT_QUIET_PERIOD_MS]
 * en pantalla: rendirse es para el parpadeo rápido, no para el turno entero. Sin
 * esto, el tercer HOME de la mañana dejaría al cliente viendo el escritorio hasta
 * reiniciar la app.
 *
 * @param desiredDisplayId pantalla en la que el manager quiere al cliente; null = ninguna.
 * @param stoppedDisplayId pantalla de la ventana que acaba de dejar de estar al frente.
 * @param previousAttempts remontes gastados en la ráfaga anterior.
 * @param lastRemountAtMs cuándo se pidió el último remonte (misma base de tiempo que [nowMs]).
 */
internal fun decideCustomerRemount(
    desiredDisplayId: Int?,
    stoppedDisplayId: Int,
    previousAttempts: Int,
    lastRemountAtMs: Long,
    nowMs: Long,
    maxAttempts: Int = MAX_CUSTOMER_REMOUNTS,
    quietPeriodMs: Long = CUSTOMER_REMOUNT_QUIET_PERIOD_MS,
): RemountVerdict {
    // El manager ya no quiere una ventana aquí: no se repone, y la ráfaga muere
    // con el deseo (el siguiente montaje empieza con la cuenta limpia).
    if (desiredDisplayId == null || desiredDisplayId != stoppedDisplayId) {
        return RemountVerdict(remount = false, attempts = 0, gaveUp = false)
    }
    val burst = if (nowMs - lastRemountAtMs >= quietPeriodMs) 0 else previousAttempts
    // Se conserva la cuenta al rendirse: mientras el parpadeo siga siendo rápido
    // seguimos rendidos; en cuanto haya calma, la rama de arriba la reinicia.
    if (burst >= maxAttempts) return RemountVerdict(remount = false, attempts = burst, gaveUp = true)
    return RemountVerdict(remount = true, attempts = burst + 1, gaveUp = false)
}
