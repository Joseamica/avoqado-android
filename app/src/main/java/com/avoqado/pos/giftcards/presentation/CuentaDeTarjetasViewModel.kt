package com.avoqado.pos.giftcards.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.core.util.aCentavos
import com.avoqado.pos.giftcards.data.*
import com.avoqado.pos.giftcards.domain.TextosDeTarjetas
import com.avoqado.pos.orders.data.OrdersRepository
import com.avoqado.pos.transactions.data.TransactionRepository
import com.avoqado.pos.transactions.data.RefundRepository
import com.avoqado.pos.transactions.data.model.Transaction
import com.avoqado.pos.payment.data.TerminalPaymentService
import com.avoqado.pos.tpvsettings.data.TpvSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import java.util.UUID

@HiltViewModel
class CuentaDeTarjetasViewModel @Inject constructor(
    private val repo: GiftCardsRepository,
    private val ordenes: OrdersRepository,
    private val transacciones: TransactionRepository,
    private val almacen: SecureStorage,
    val roles: RoleManager,
    private val red: ConnectivityMonitor,
    val refundRepository: RefundRepository,
    val terminalPaymentService: TerminalPaymentService,
    val ajustes: TpvSettingsRepository,
): ViewModel() {
    private val valor = MutableStateFlow<CuentaDeTarjetas?>(null)
    val cuenta = valor.asStateFlow()
    private val pagos = MutableStateFlow<List<Transaction>>(emptyList())
    val pagosPorDevolver = pagos.asStateFlow()
    private val avisoValor = MutableStateFlow<String?>(null)
    val aviso = avisoValor.asStateFlow()
    private val ocupacion = MutableStateFlow(false)
    val ocupado = ocupacion.asStateFlow()
    private val pasoValor = MutableStateFlow<String?>(null)
    val paso = pasoValor.asStateFlow()
    private val exitoValor = MutableStateFlow<String?>(null)
    val exito = exitoValor.asStateFlow()
    val conectado = red.isConnected
    val servidor = red.isServerReachable
    private data class Contexto(val venue: String, val usuario: String, val orden: String, val generacion: Long)
    private var generacion = 0L
    private var contexto: Contexto? = null
    private var dialogo: Pair<Boolean, String>? = null
    private var dialogoTerminado = false
    fun vigente(): Boolean = contexto?.let(::vigente) == true
    private fun vigente(c: Contexto) = contexto == c && almacen.venueId == c.venue && almacen.userId == c.usuario
    val puedeCancelar: Boolean get() = vigente() && roles.canReadGiftCards && roles.canManageGiftCards && valor.value?.cancel?.allowed == true
    val puedeLiberar: Boolean get() = vigente() && roles.canReadGiftCards && roles.canManageGiftCards && valor.value?.cancel?.reason == "IN_PROGRESS"
    val confirmacion: String? get() = valor.value?.let {
        TextosDeTarjetas.cancelarVentaConfirmacion(it.orderNumber,it.sobres.size,it.sobres.sumOf { s -> s.monto.aCentavos() })
    }
    fun abrir(orderId: String): Job {
        val venue = almacen.venueId
        val usuario = almacen.userId
        if (contexto?.let { it.orden != orderId || it.venue != venue || it.usuario != usuario } != false) {
            cerrar()
            if (!venue.isNullOrBlank() && !usuario.isNullOrBlank()) contexto = Contexto(venue,usuario,orderId,generacion)
        }
        return refrescar()
    }
    private fun enLinea(): Boolean {
        if (conectado.value && servidor.value) return true
        avisoValor.value = TextosDeTarjetas.SIN_RED
        return false
    }
    private fun terminado() = Job().apply { complete() }
    fun refrescar(): Job {
        val c = contexto ?: return terminado()
        if (!vigente(c) || ocupacion.value) return terminado()
        if (!roles.canReadGiftCards) {
            valor.value=null;pagos.value=emptyList();pasoValor.value=null
            avisoValor.value=TextosDeTarjetas.SIN_PERMISO_CONSULTAR
            return terminado()
        }
        if (!enLinea()) return terminado()
        return operar(c) { leerCuenta(c) }
    }
    private fun operar(c: Contexto, accion: suspend () -> Unit): Job {
        ocupacion.value=true
        return viewModelScope.launch(start=CoroutineStart.UNDISPATCHED) {
            try { accion() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (vigente(c)) { pagos.value=emptyList();avisoValor.value="No se pudo comprobar esta operación. Reintenta." }
            } finally { if (contexto == c) ocupacion.value=false }
        }
    }
    private suspend fun leerCuenta(c: Contexto) {
        if (!enLinea()) return
        val r = repo.cuenta(c.venue,c.orden)
        if (!vigente(c)) return
        when (r) {
            is Respuesta.Ok -> {
                if (r.valor.orderId != c.orden) { valor.value=null;pagos.value=emptyList();avisoValor.value=TextosDeTarjetas.CUENTA_NO_ENCONTRADA;return }
                valor.value=r.valor
                pagos.value=emptyList();pasoValor.value=null
                avisoValor.value=r.valor.cancel.message
                if (puedeLiberar) prepararDevoluciones(c)
            }
            is Respuesta.Rechazo -> { pagos.value=emptyList();avisoValor.value=r.fallo.message }
            Respuesta.SinRespuesta -> { pagos.value=emptyList();avisoValor.value="No se pudo consultar la cuenta. Reintenta." }
        }
    }
    private suspend fun prepararDevoluciones(c: Contexto) {
        pasoValor.value="DEVUELVE_COMPLETO"
        avisoValor.value=TextosDeTarjetas.DEVUELVE_COMPLETO
        pagos.value=emptyList()
        if (!enLinea()) return
        val detalle=ordenes.fetchOrderDetail(c.orden)
        if (!vigente(c)) return
        val lista=detalle?.takeIf { it.id == c.orden }?.payments
        if (lista == null) { avisoValor.value="No se pudieron consultar todos los cobros. Reintenta antes de devolver.";return }
        val completos=mutableListOf<Transaction>()
        for (pago in lista.filter { it.status == "COMPLETED" }.distinctBy { it.id }) {
            if (!enLinea()) return
            val tx=transacciones.fetchTransactionDetail(pago.id)
            if (!vigente(c)) return
            if (tx == null || tx.id != pago.id) { avisoValor.value="No se pudieron consultar todos los cobros. Reintenta antes de devolver.";return }
            if (tx.remainingRefundable.aCentavos() > 0) completos.add(tx)
        }
        if (vigente(c)) pagos.value=completos
    }
    fun abrirDialogo(liberar: Boolean = false) {
        if (ocupacion.value || !(if (liberar) puedeLiberar else puedeCancelar)) return
        dialogo=liberar to UUID.randomUUID().toString()
        dialogoTerminado=false
    }
    fun cerrarDialogo() { dialogo=null;dialogoTerminado=false }
    private fun rechazo(fallo: FalloDeTarjeta) {
        avisoValor.value=if (fallo.http == 403 && fallo.code == null) null else fallo.message
    }
    fun confirmarCancelacion(): Job? {
        if (!vigente() || !enLinea() || ocupacion.value || !puedeCancelar || dialogoTerminado) return null
        val c=contexto ?: return null
        val d=dialogo?.takeIf { !it.first } ?: return null
        exitoValor.value=null
        return operar(c) {
            val r=repo.cancelarVenta(c.venue,c.orden,d.second)
            if (!vigente(c) || dialogo != d) return@operar
            when (r) {
                is Respuesta.Ok -> {
                    dialogoTerminado=true
                    valor.value=valor.value?.copy(cancel=Cancelacion(false,"IN_PROGRESS"))
                    prepararDevoluciones(c)
                }
                is Respuesta.Rechazo -> rechazo(r.fallo)
                Respuesta.SinRespuesta -> avisoValor.value="No se pudo comprobar la cancelación. Reintenta con esta misma ventana."
            }
        }
    }
    fun liberar(confirmado: Boolean): Job? {
        if (!confirmado || !vigente() || !enLinea() || ocupacion.value || !puedeLiberar || dialogoTerminado) return null
        val c=contexto ?: return null
        val d=dialogo?.takeIf { it.first } ?: return null
        exitoValor.value=null
        return operar(c) {
            val r=repo.liberarCancelacion(c.venue,c.orden,d.second)
            if (!vigente(c) || dialogo != d) return@operar
            when (r) {
                is Respuesta.Ok -> {
                    dialogoTerminado=true
                    pagos.value=emptyList();pasoValor.value=null
                    leerCuenta(c)
                    if (vigente(c) && dialogo == d) exitoValor.value="Cancelación liberada"
                }
                is Respuesta.Rechazo -> rechazo(r.fallo)
                Respuesta.SinRespuesta -> avisoValor.value="No se pudo comprobar la liberación. Reintenta con esta misma ventana."
            }
        }
    }
    fun consumirExito() { exitoValor.value=null }
    fun cerrar() {
        generacion++;contexto=null;cerrarDialogo()
        valor.value=null;pagos.value=emptyList();avisoValor.value=null
        pasoValor.value=null;exitoValor.value=null;ocupacion.value=false
    }
}
