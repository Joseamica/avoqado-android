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
