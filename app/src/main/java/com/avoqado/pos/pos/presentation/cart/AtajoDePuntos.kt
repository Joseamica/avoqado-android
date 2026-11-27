package com.avoqado.pos.pos.presentation.cart

import com.avoqado.pos.core.data.network.FalloDeLectura
import com.avoqado.pos.payment.presentation.RECOMPENSAS_LEYENDO_LA_CUENTA
import com.avoqado.pos.payment.presentation.RECOMPENSAS_SIN_PERMISO
import com.avoqado.pos.tables.data.CustomerLoyalty
import com.avoqado.pos.tables.data.RECOMPENSAS_SIN_RED
import com.avoqado.pos.tables.data.motivoSinRecompensas
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/*
 * D39 · atajo «Usar puntos» → «Pagar después» (founder, 26-nov; promesa v2 en
 * `docs/audit/2026-11-25-defectos-lealtad-referidos/d39/promesa-atajo.md`). No agrega reglas de dinero ni de puntos: manda la venta a
 * «Pagar después» (el camino con fila) y abre esa cuenta en Pedidos con «Recompensas» (fase 1). Aquí viven sus reglas puras y la puerta
 * que lleva la cuenta por abrir de Cobrar a Pedidos.
 */

const val USAR_PUNTOS = "Usar puntos"
const val USAR_PUNTOS_SIN_PRODUCTOS = "Agrega productos para usar los puntos."
const val USAR_PUNTOS_SIN_CLIENTE = "Agrega el cliente para usar sus puntos."
const val USAR_PUNTOS_YA_SE_INTENTO = "Esta venta ya se intentó dejar para después: búscala en Más › Pedidos."
const val USAR_PUNTOS_SIN_FILA =
    "Con cupón, premio de sellos o un descuento que pide aprobación, usa «Pagar después» y canjea los puntos en Más › Pedidos."
const val USAR_PUNTOS_LEYENDO = "Leyendo los puntos del cliente…"
const val USAR_PUNTOS_CLIENTE_CAMBIO = "El cliente cambió: vuelve a tocar «Usar puntos»."
const val USAR_PUNTOS_SIN_RED_GUARDADA = "Canjea los puntos en Más › Pedidos cuando vuelva la red."

/** Lo que dice la ventana antes de *Continuar* (Codex r1 #3 y #7): la venta queda como cuenta por cobrar y los puntos se gastan al canjear. */
fun explicacionDelAtajo(cliente: String) =
    "Se guardará una cuenta por cobrar a nombre de $cliente y se abrirá en Pedidos para canjear. Los puntos se descuentan cuando toques " +
        "«Canjear»; si no alcanzan, la cuenta se cobra normal o se cancela."

/** El saldo del cliente leído para el atajo, sin cuenta todavía (`getCustomerLoyalty(..., orderId = null)`): sólo orienta. */
data class LecturaDelAtajo(val customerId: String, val lealtad: CustomerLoyalty?, val fallo: FalloDeLectura?)

/**
 * Por qué «Usar puntos» no se puede (null = se puede). La lectura previa sólo orienta: la cuenta real decide al abrirla (Codex r1 #3). Una
 * lectura de otro cliente no cuenta (Codex r1 #4).
 */
fun motivoSinUsarPuntos(
    conProductos: Boolean,
    clienteId: String?,
    puedeCanjear: Boolean,
    llavePrevia: Boolean,
    sinFila: Boolean,
    noSeDifiere: String?,
    lectura: LecturaDelAtajo?,
    enLinea: Boolean,
): String? = when {
    !conProductos -> USAR_PUNTOS_SIN_PRODUCTOS
    clienteId.isNullOrBlank() -> USAR_PUNTOS_SIN_CLIENTE
    !puedeCanjear -> RECOMPENSAS_SIN_PERMISO
    llavePrevia -> USAR_PUNTOS_YA_SE_INTENTO
    sinFila -> USAR_PUNTOS_SIN_FILA
    noSeDifiere != null -> noSeDifiere
    !enLinea -> RECOMPENSAS_SIN_RED
    lectura == null || lectura.customerId != clienteId -> USAR_PUNTOS_LEYENDO
    else -> motivoSinRecompensas(tieneCliente = true, lealtad = lectura.lealtad, fallo = lectura.fallo, enLinea = true)
}

/** La cuenta de «Pagar después» que el atajo pidió abrir, con la sucursal y el sello de la sesión que la mandó (Codex r1 #5). */
data class SolicitudDeRecompensas(val orderId: String, val venueId: String, val sello: Long)

/**
 * Lleva la cuenta por abrir de Cobrar a Más › Pedidos (como `VigilanteDePedidosEnLinea.bandejaSolicitada`). Una sola a la vez. Se descarta si
 * la sesión o la sucursal ya no son las que la mandaron, y se da por atendida al abrir «Recompensas» o al avisar su razón (Codex r1 #6).
 */
@Singleton
class PuertaDeRecompensas @Inject constructor() {
    private val _solicitud = MutableStateFlow<SolicitudDeRecompensas?>(null)
    val solicitud: StateFlow<SolicitudDeRecompensas?> = _solicitud.asStateFlow()

    fun pedir(s: SolicitudDeRecompensas) { _solicitud.value = s }

    /** La solicitud si sigue siendo de esta sesión y esta sucursal; si no, se descarta (nunca revive). */
    fun vigente(venueId: String?, sello: Long): SolicitudDeRecompensas? {
        val s = _solicitud.value ?: return null
        if (s.venueId == venueId && s.sello == sello) return s
        _solicitud.compareAndSet(s, null)
        return null
    }

    fun atendida(orderId: String) { _solicitud.value?.takeIf { it.orderId == orderId }?.let { _solicitud.compareAndSet(it, null) } }
}

sealed interface AperturaDeRecompensas {
    data object Abrir : AperturaDeRecompensas
    data object Esperar : AperturaDeRecompensas
    data class Avisar(val texto: String) : AperturaDeRecompensas
}

/** Textos de espera de «Recompensas»: mientras salgan, la cuenta todavía no decide. Sin red también se espera a que vuelva. */
private val ESPERAS = setOf(USAR_PUNTOS_LEYENDO, "Actualizando la cuenta…", RECOMPENSAS_LEYENDO_LA_CUENTA, RECOMPENSAS_SIN_RED,
    "La cuenta todavía no llega del servidor, así que no se sabe si tiene cliente. Intenta en un momento.")

/** Qué hace Pedidos con la cuenta que pidió el atajo (Codex r1 #6). */
fun aperturaDeRecompensas(ofrece: Boolean, consultando: Boolean, motivo: String?, folio: String): AperturaDeRecompensas = when {
    consultando -> AperturaDeRecompensas.Esperar
    !ofrece -> AperturaDeRecompensas.Avisar("La cuenta $folio quedó en Pedidos, pero ya no admite puntos: revísala antes de cobrar.")
    motivo == null -> AperturaDeRecompensas.Abrir
    motivo in ESPERAS -> AperturaDeRecompensas.Esperar
    else -> AperturaDeRecompensas.Avisar("La cuenta $folio quedó en Pedidos: $motivo")
}
