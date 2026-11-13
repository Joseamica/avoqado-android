package com.avoqado.pos.tables.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.avoqado.pos.payment.presentation.SplitConfig

/**
 * D61 (13-nov): el cobro de una MESA vive en el ViewModel de la mesa (la entrada «table-order» de la navegación), no en la
 * composición de la pantalla.
 *
 * Girar el aparato, cambiar el tema o el tamaño de letra recrea la Activity. Con `remember`, la pantalla de cobro DESAPARECÍA
 * mientras su `PaymentFlowViewModel` (de la misma entrada, sobrevive) seguía cobrando; el cajero veía la cuenta otra vez y
 * «Pagar» abría OTRO cobro de la misma mesa. `rememberSaveable` tampoco basta (Codex r1 #1): en un aparato chico como la N86,
 * girarlo cruza los 600 dp y la app cambia de árbol (`AvoqadoNavGraph`: un `NavHost` para teléfono y otro para tableta), y lo
 * guardado en un árbol no se restaura en el otro. El ViewModel de la entrada sí pasa de uno a otro: el `NavController` es el
 * mismo.
 *
 * Por lo mismo la [apertura] del cobro vive aquí y se le pasa a `PaymentFlowScreen` (como la bandeja de «Pedidos en línea»):
 * la que guarda la pantalla del cobro en su `rememberSaveable` se perdería al cambiar de árbol y el flujo arrancaría OTRO cobro
 * encima del que va en vuelo. Una apertura nueva por cada vez que se ABRE el cobro ([abrir]); la misma mientras sigue abierto.
 *
 * Si el proceso muere, esto se va con él, igual que la sesión de la mesa (`TableSession`, en memoria): la pantalla regresa
 * al plano, como hoy.
 */
internal class CobroDeLaMesa {
    /** La pantalla de cobro está abierta. Se ABRE sólo con [abrir]; se cierra bajándola. */
    var abierto by mutableStateOf(false)

    /** UNA por cada vez que se abre el cobro: `PaymentFlowScreen` la reconoce (`esElCobroEnCurso`) y no arranca otro. */
    var apertura by mutableStateOf<String?>(null)
        private set

    /** Lo que se cobra (la cuenta, o lo que queda), congelado al abrir. */
    var semillaCents by mutableStateOf(0)

    /** El reparto ya elegido en «Dividir importe». */
    var reparto by mutableStateOf(SplitConfig())

    /** La hoja «Dividir importe» está abierta. */
    var dividiendo by mutableStateOf(false)

    /** Lo que el cobro confirmado dejó por pagar (null = no consta un cobro confirmado). Decide la salida tras el recibo. */
    var restanteConfirmado by mutableStateOf<Int?>(null)

    /** «Cobro anterior resuelto»: el dinero de un cobro que el cajero creía sin confirmar, hasta que lo reconoce. */
    var avisoCobroAnterior by mutableStateOf<String?>(null)

    fun abrir() {
        apertura = java.util.UUID.randomUUID().toString()
        abierto = true
    }
}

/** Qué hace la mesa cuando el cajero sale del recibo de su cobro («Venta nueva»). */
internal enum class SalidaTrasElRecibo {
    /** Queda resto (pago parcial) o no consta un cobro: regresa a la cuenta por `cerrarCobro()` (ORDERING y relee). */
    CERRAR_COBRO,

    /** La mesa quedó pagada (ya se liberó al confirmar): regresa al plano. */
    SALIR_AL_PLANO,
}

/** `restanteConfirmado`: lo que el cobro confirmado dejó por pagar (null = no consta un cobro confirmado en esta pantalla). */
internal fun salidaTrasElRecibo(restanteConfirmado: Int?): SalidaTrasElRecibo =
    if (restanteConfirmado == 0) SalidaTrasElRecibo.SALIR_AL_PLANO else SalidaTrasElRecibo.CERRAR_COBRO
