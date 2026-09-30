package com.avoqado.pos.customerdisplay

/*
 * Reemplazo de escritorio de DisplayRoles.kt: sólo la decisión PURA que la app usa afuera
 * (`resolveDisplayRoles`, desde DisplayCapabilitySnapshot), copiada tal cual del original. Lo que tocaba
 * `android.view.Display` / `Activity` (dueño de una pantalla virtual, pantalla de la Activity) y la contabilidad
 * del relanzamiento de la caja no se usa fuera de los archivos excluidos: no se trae.
 */

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

/** Decisión PURA de los roles (copiada del original, sin cambios). */
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
