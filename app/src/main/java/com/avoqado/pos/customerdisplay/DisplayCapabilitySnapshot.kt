package com.avoqado.pos.customerdisplay

/** Hechos físicos que el POS puede anunciar sin inferir permisos ni producto. */
data class DisplayCapabilitySnapshot(
    val present: Boolean,
    val invertible: Boolean,
)

/**
 * Deriva capacidades de la misma decisión canónica que usa el manager.
 *
 * No enumera displays ni replica heurísticas: una pantalla virtual de captura
 * descartada por [resolveDisplayRoles] tampoco puede aparecer como capacidad
 * remota, y una virtual OEM puede estar presente sin ser invertible.
 */
internal fun resolveDisplayCapabilitySnapshot(
    defaultDisplayId: Int,
    candidates: List<CandidateDisplay>,
    remoteCaptureHints: List<String>,
): DisplayCapabilitySnapshot {
    val roles = resolveDisplayRoles(
        defaultDisplayId = defaultDisplayId,
        candidates = candidates,
        remoteCaptureHints = remoteCaptureHints,
        inverted = false,
    )
    return DisplayCapabilitySnapshot(
        present = roles.customerDisplayId != null,
        invertible = roles.invertible,
    )
}
