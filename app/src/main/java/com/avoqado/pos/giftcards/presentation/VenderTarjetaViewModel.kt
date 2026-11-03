package com.avoqado.pos.giftcards.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.giftcards.data.*
import com.avoqado.pos.giftcards.domain.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import javax.inject.Inject

@HiltViewModel class VenderTarjetaViewModel @Inject constructor(
    private val repo: GiftCardsRepository,
    private val store: SolicitudesDeTarjetaStore,
    private val almacen: SecureStorage,
    private val red: ConnectivityMonitor,
) : ViewModel() {
    sealed interface Estado {
        data object Editando : Estado
        data object Enviando : Estado
        data class Error(val texto: String) : Estado
        data class Cobro(val orderId: String) : Estado
        data class ResultadoPendiente(val orderId: String) : Estado
    }
    private val valor = MutableStateFlow<Estado>(Estado.Editando)
    val estado = valor.asStateFlow()
    private val campos = MutableStateFlow(FormularioDeVenta(listOf(SobreEnFormulario(null, "PRINTED", "", "", "", "")), ""))
    val formulario = campos.asStateFlow()
    val solicitudes = store.solicitudes
    val conectado = red.isConnected
    val servidor = red.isServerReachable
    val cambiosDeAjustes = repo.cambiosDeAjustes
    fun ajustesVigentes(venueId: String): AjustesDeTarjetas? = repo.ajustesParaEntrada(venueId)
    val conservaOperacion: Boolean get() = bloqueado || valor.value is Estado.Cobro || valor.value is Estado.ResultadoPendiente ||
        solicitudes.value.any { it.venueId == (venue ?: almacen.venueId) && it.tipo == "GIFT_CARD_SALE" }
    var llave: String = UUID.randomUUID().toString()
        private set
    private var venue: String? = null
    private var ajustes: AjustesDeTarjetas? = null
    val bloqueado: Boolean get() = valor.value == Estado.Enviando || pendiente() != null
    private fun pendiente() = venue?.let { v -> store.delNegocio(v).firstOrNull { it.llave == llave } }
    fun alAbrir(venueId: String, ajustes: AjustesDeTarjetas) {
        if (venue != null) return
        venue = venueId; this.ajustes = ajustes
    }
    fun cambiar(nuevo: FormularioDeVenta) {
        if (bloqueado || nuevo == campos.value) return
        campos.value = nuevo
        llave = UUID.randomUUID().toString()
        valor.value = Estado.Editando
    }
    fun reanudar(llave: String): Boolean {
        if (valor.value == Estado.Enviando) return false
        val v = venue ?: return false
        if (store.delNegocio(v).none { it.llave == llave && it.tipo == "GIFT_CARD_SALE" }) return false
        this.llave = llave
        valor.value = Estado.Editando
        return true
    }
    private fun mostrarCuenta(venueId: String, orderId: String, estados: List<String?>) {
        if (almacen.venueId != venueId) { valor.value = Estado.Error(TextosDeTarjetas.SIN_RED); return }
        valor.value = when {
            estados.isNotEmpty() && estados.all { it == "ACTIVE" } -> Estado.ResultadoPendiente(orderId)
            estados.isNotEmpty() && estados.all { it == "DEACTIVATED" } -> {
                if (!store.quitar(llave)) Estado.Error(TextosDeTarjetas.NO_SE_PUDO_GUARDAR)
                else { llave = UUID.randomUUID().toString(); Estado.Editando }
            }
            else -> Estado.Cobro(orderId)
        }
    }
    // La pantalla puede dejar de esperar; la creación conserva su dueño hasta terminar.
    internal suspend fun cobrarDesdePantalla() = viewModelScope.async(start = CoroutineStart.UNDISPATCHED) { cobrar() }.await()

    suspend fun cobrar() {
        if (valor.value == Estado.Enviando) return
        val v = venue ?: return
        if (almacen.venueId != v || !red.isConnected.value || !red.isServerReachable.value) {
            valor.value = Estado.Error(TextosDeTarjetas.SIN_RED); return
        }
        val estabaGuardada = pendiente() != null
        val a = (if (estabaGuardada) ajustes else repo.ajustesParaEntrada(v)) ?: return
        val llaveEnVuelo = llave
        if (!store.enVueloDeEsteProceso.add(llaveEnVuelo)) return
        try {
            if (llave != llaveEnVuelo) return
            val previa = pendiente()
            if (estabaGuardada && previa == null) return
            if (previa != null && previa.tipo != "GIFT_CARD_SALE") return
            if (previa == null && (!a.vende || !a.sale.sellable || campos.value.invalidos(a).isNotEmpty())) return
            if (previa?.orderId != null) {
                valor.value = Estado.Enviando
                when (val r = repo.cuenta(v, previa.orderId)) {
                    is Respuesta.Ok -> mostrarCuenta(v, previa.orderId, r.valor.sobres.map { it.status })
                    is Respuesta.Rechazo -> valor.value = Estado.Error(r.fallo.message ?: TextosDeTarjetas.SIN_RED)
                    Respuesta.SinRespuesta -> valor.value = Estado.Error(TextosDeTarjetas.SIN_RED)
                }
                return
            }
            val s = previa ?: run {
                val autor = almacen.userId
                if (autor.isNullOrBlank()) { valor.value = Estado.Error(TextosDeTarjetas.SIN_PERMISO_VENDER); return }
                val nombre = listOfNotNull(almacen.userFirstName, almacen.userLastName).joinToString(" ").trim()
                SolicitudDeTarjeta(tipo = "GIFT_CARD_SALE", llave = llave, venueId = v,
                    creadaEn = System.currentTimeMillis(), cuerpo = campos.value.cuerpo(llave, autor, nombre),
                    iniciadoPor = autor, iniciadoPorNombre = nombre).also {
                    if (!store.guardar(it)) { valor.value = Estado.Error(TextosDeTarjetas.NO_SE_PUDO_GUARDAR); return }
                }
            }
            valor.value = Estado.Enviando
            when (val r = repo.vender(v, s.cuerpo)) {
                is Respuesta.Ok -> {
                    if (!store.anotarOrden(llave, r.valor.orderId, r.valor.orderNumber)) {
                        valor.value = Estado.Error(TextosDeTarjetas.NO_SE_PUDO_GUARDAR); return
                    }
                    mostrarCuenta(v, r.valor.orderId, r.valor.sobres.map { it.status })
                }
                is Respuesta.Rechazo -> {
                    if (r.fallo.http != 401) store.quitar(llave)
                    val texto = when {
                        r.fallo.http == 401 -> TextosDeTarjetas.SIN_RED
                        r.fallo.http == 403 && r.fallo.code == null -> TextosDeTarjetas.SIN_PERMISO_VENDER
                        else -> r.fallo.message ?: TextosDeTarjetas.SIN_RED
                    }
                    valor.value = Estado.Error(texto)
                }
                Respuesta.SinRespuesta -> valor.value = Estado.Error(TextosDeTarjetas.SIN_RED)
            }
        } finally { store.enVueloDeEsteProceso.remove(llaveEnVuelo) }
    }
}
