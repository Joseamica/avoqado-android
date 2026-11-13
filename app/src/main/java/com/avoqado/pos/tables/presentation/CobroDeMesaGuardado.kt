package com.avoqado.pos.tables.presentation

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import com.avoqado.pos.payment.presentation.SplitConfig
import com.avoqado.pos.payment.presentation.SplitType

/**
 * D61 (13-nov): el reparto del cobro de una MESA («Dividir importe») se guarda con `rememberSaveable` para que girar el aparato,
 * cambiar el tema o el tamaño de letra (la Activity se recrea) no lo pierda a media venta. Sólo tipos que caben en un Bundle.
 */
internal val GuardadoDelReparto: Saver<SplitConfig, Any> = listSaver(
    save = { listOf(it.type.name, ArrayList(it.selectedItemIds), it.numberOfParts, it.customAmountCents) },
    restore = { guardado ->
        @Suppress("UNCHECKED_CAST")
        SplitConfig(
            type = SplitType.entries.firstOrNull { it.name == guardado[0] } ?: SplitType.FULL_PAYMENT,
            selectedItemIds = (guardado[1] as List<String>).toList(),
            numberOfParts = guardado[2] as Int,
            customAmountCents = guardado[3] as Int,
        )
    },
)

/** Qué hace la mesa cuando el cajero sale del recibo de su cobro («Venta nueva»). */
internal enum class SalidaTrasElRecibo {
    /** Queda resto (pago parcial) o no consta un cobro: regresa a la cuenta por `cerrarCobro()` (ORDERING y relee). */
    CERRAR_COBRO,

    /** Hoy: sólo se baja la bandera del cobro (la sesión se queda como esté). */
    SOLO_OCULTAR,

    /** La mesa quedó pagada (ya se liberó al confirmar): regresa al plano. */
    SALIR_AL_PLANO,
}

/** `restanteConfirmado`: lo que el cobro confirmado dejó por pagar (null = no consta un cobro confirmado en esta pantalla). */
internal fun salidaTrasElRecibo(restanteConfirmado: Int?): SalidaTrasElRecibo = when {
    restanteConfirmado == null -> SalidaTrasElRecibo.SOLO_OCULTAR
    restanteConfirmado == 0 -> SalidaTrasElRecibo.SALIR_AL_PLANO
    else -> SalidaTrasElRecibo.CERRAR_COBRO
}
