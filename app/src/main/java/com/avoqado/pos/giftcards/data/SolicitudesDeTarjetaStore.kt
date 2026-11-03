package com.avoqado.pos.giftcards.data

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.giftcards.domain.SolicitudDeTarjeta
import com.avoqado.pos.giftcards.domain.SolicitudesDeTarjeta
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton class SolicitudesDeTarjetaStore @Inject constructor(private val secureStorage: SecureStorage) {
    private val trabadas = MutableStateFlow<Set<String>>(emptySet())
    val cuentasTrabadas = trabadas.asStateFlow()
    private val foto = MutableStateFlow<List<SolicitudDeTarjeta>>(emptyList())
    val solicitudes = foto.asStateFlow()
    val enVueloDeEsteProceso: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val entregando = mutableSetOf<String>()
    init { publicar() }
    private fun publicar() {
        val confirmadas = visibles()
        foto.value = confirmadas
        trabadas.value = confirmadas
            .filter { it.tipo == "GIFT_CARD_REDEEM" && it.mandado && !it.porTerminar && !it.porRecuperar }
            .mapNotNull { it.orderId }.toSet()
    }
    @Synchronized private fun mutar(cambio: (String?) -> String?): Boolean {
        if (!secureStorage.escribirSolicitudesDeTarjeta(cambio)) return false
        publicar()
        return true
    }
    fun guardar(s: SolicitudDeTarjeta): Boolean = mutar { SolicitudesDeTarjeta.devolverEntrega(it, s) ?: SolicitudesDeTarjeta.agregar(it, s) }
    @Synchronized fun reservarEntrega(llave: String): Boolean {
        if (!mutar { SolicitudesDeTarjeta.reservarEntrega(it, llave) }) return false
        entregando.add(llave); publicar(); return true
    }
    @Synchronized fun devolverEntrega(s: SolicitudDeTarjeta) {
        guardar(s.copy(porRecuperar = true, cuerpo = ""))
        entregando.remove(s.llave); publicar()
    }
    @Synchronized fun confirmarEntrega(llave: String): Boolean {
        if (llave !in entregando || !mutar { SolicitudesDeTarjeta.confirmarEntrega(it, llave) }) return false
        entregando.remove(llave); publicar(); return true
    }
    private fun visibles(): List<SolicitudDeTarjeta> {
        val raw = secureStorage.giftCardRequestsJson
        return SolicitudesDeTarjeta.leer(raw) + SolicitudesDeTarjeta.reservas(raw).filter { it.llave !in entregando }
    }
    fun quitar(llave: String): Boolean = mutar { SolicitudesDeTarjeta.quitar(it, llave) }
    fun marcarMandado(llave: String): Boolean = mutar { raw ->
        if (SolicitudesDeTarjeta.leer(raw).none { it.llave == llave && it.tipo == "GIFT_CARD_REDEEM" }) null
        else SolicitudesDeTarjeta.conMandado(raw, llave)
    }
    fun anotarOrden(llave: String, orderId: String, orderNumber: String?): Boolean = mutar { raw ->
        if (SolicitudesDeTarjeta.leer(raw).none { it.llave == llave }) null
        else SolicitudesDeTarjeta.conOrden(raw, llave, orderId, orderNumber)
    }
    fun anotarImpreso(llave: String, giftCardId: String): Boolean = mutar { raw ->
        if (SolicitudesDeTarjeta.leer(raw).none { it.llave == llave }) null
        else SolicitudesDeTarjeta.conImpreso(raw, llave, giftCardId)
    }
    fun dejarPorTerminar(llave: String, orderId: String, orderNumber: String?, restanteCents: Int?): Boolean = mutar { raw ->
        if (SolicitudesDeTarjeta.leer(raw).none { it.llave == llave && it.tipo == "GIFT_CARD_REDEEM" }) null
        else SolicitudesDeTarjeta.conPorTerminar(raw, llave, orderId, orderNumber, restanteCents)
    }
    fun dejarPorRecuperar(llave: String): Boolean = mutar { raw ->
        if (SolicitudesDeTarjeta.leer(raw).none { it.llave == llave && it.tipo == "GIFT_CARD_REDEEM" && it.nuevaCuenta && it.orderId == null }) null
        else SolicitudesDeTarjeta.conPorRecuperar(raw, llave)
    }
    fun reenviarDesde(llave: String): Long? = SolicitudesDeTarjeta.reenviarDesde(secureStorage.giftCardRequestsJson, llave)
    fun aplazarReenvio(llave: String, hasta: Long): Boolean = mutar { raw ->
        if (SolicitudesDeTarjeta.leer(raw).none { it.llave == llave && it.tipo == "GIFT_CARD_REDEEM" && it.mandado }) null
        else SolicitudesDeTarjeta.conReenvioDesde(raw, llave, hasta)
    }
    fun delNegocio(venueId: String): List<SolicitudDeTarjeta> = visibles().filter { it.venueId == venueId }
}
