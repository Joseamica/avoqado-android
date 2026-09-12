package com.avoqado.pos.payment.presentation

import androidx.lifecycle.ViewModel
import com.avoqado.pos.payment.data.CancelacionDeCobroCoordinator
import com.avoqado.pos.payment.data.FaseDeCancelacion
import com.avoqado.pos.payment.data.IntencionDeCancelarCobro
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Lo que queda por cancelar, FUERA del flujo de cobro.
 *
 * Sin esto, una cancelación que no se pudo confirmar sólo existía dentro de la pantalla de pago: el
 * cajero salía, la venta seguía abierta y nadie volvía a enterarse. Aquí se ve (banner) y se puede
 * empujar a mano (hoja de pendientes).
 */
@HiltViewModel
class CancelacionesPendientesViewModel @Inject constructor(
    private val cancelacionDeCobro: CancelacionDeCobroCoordinator,
) : ViewModel() {

    val pendientes: StateFlow<List<IntencionDeCancelarCobro>> = cancelacionDeCobro.pendientes

    val enCurso: StateFlow<Set<String>> = cancelacionDeCobro.enCurso

    /** «Volver a consultar»: sólo pregunta — jamás cobra ni borra por su cuenta. */
    fun volverAConsultar(id: String) = cancelacionDeCobro.procesarAhora(id)

    /** El aviso de un cobro que la terminal SÍ hizo; el resto son cancelaciones sin confirmar. */
    fun esAvisoDeCobro(intencion: IntencionDeCancelarCobro): Boolean =
        intencion.faseActual == FaseDeCancelacion.SE_COBRO
}
