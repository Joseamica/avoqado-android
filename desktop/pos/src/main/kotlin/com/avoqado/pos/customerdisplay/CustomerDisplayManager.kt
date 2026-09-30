package com.avoqado.pos.customerdisplay

import javax.inject.Inject
import javax.inject.Singleton

/*
 * Reemplazo de escritorio de CustomerDisplayManager.kt. La pantalla del cliente (segunda pantalla) es de la fase
 * siguiente: aquí el equipo se comporta como una caja de UNA sola pantalla. Se conserva lo que la app usa afuera:
 * la decisión pura de la pantalla (CandidateDisplay, chooseCustomerDisplayId, copiadas tal cual), los tipos del
 * aplicador físico y el manager que DisplayModeRequestProcessor inyecta.
 *
 * 🔴 Nada aquí pone `CustomerDisplayState.isPresenting` en true: el cobro nunca espera un toque del cliente en una
 * pantalla que no existe.
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

@Singleton
class CustomerDisplayManager @Inject constructor() : DisplayModePhysicalApplier {
    /** Lo mismo que contesta el original en una caja sin segunda pantalla: la caja está en la principal ⇒ «no invertido». */
    override suspend fun observeConfirmedMode(): Boolean? = false

    /** Igual que el original sin segunda pantalla: «normal» ya está; «invertido» se rechaza porque no hay a dónde. */
    override suspend fun applyAndConfirm(desiredInverted: Boolean): PhysicalDisplayModeResult {
        if (!desiredInverted) return PhysicalDisplayModeResult.Confirmed(false)
        android.util.Log.w("Escritorio", "No disponible en Windows todavía: pantalla del cliente (modo invertido)")
        return PhysicalDisplayModeResult.Rejected(DisplayModeAckResultCode.DISPLAY_NOT_INVERTIBLE, confirmedInverted = false)
    }
}
