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
const val USAR_PUNTOS_YA_SE_INTENTO =
    "Esta venta ya se mandó una vez a «Pagar después»: termínala desde Atajos › «Pagar después» y canjea los puntos en Más › Pedidos."
const val USAR_PUNTOS_SIN_PERMISO_DE_CUENTA = "No tienes permiso para dejar cuentas por cobrar. Pídeselo a tu gerente."
const val USAR_PUNTOS_SIN_FILA =
    "Con cupón, premio de sellos o un descuento que pide aprobación, usa «Pagar después» y canjea los puntos en Más › Pedidos."
const val USAR_PUNTOS_LEYENDO = "Leyendo los puntos del cliente…"
const val USAR_PUNTOS_CLIENTE_CAMBIO = "El cliente cambió: vuelve a tocar «Usar puntos»."
/** Sin red o con el SERVIDOR sin contestar (503), la cuenta queda en la fila de «Pagar después»: neutro para los dos (Codex r2/r3 #7). */
fun usarPuntosCuentaEnLaFila(cliente: String) =
    "La cuenta de $cliente quedó guardada en el aparato y se sube sola. Cuando suba, canjea los puntos en Más › Pedidos."

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
    puedeCrear: Boolean,
    llavePrevia: Boolean,
    sinFila: Boolean,
    noSeDifiere: String?,
    lectura: LecturaDelAtajo?,
    enLinea: Boolean,
): String? = when {
    !conProductos -> USAR_PUNTOS_SIN_PRODUCTOS
    clienteId.isNullOrBlank() -> USAR_PUNTOS_SIN_CLIENTE
    !puedeCanjear -> RECOMPENSAS_SIN_PERMISO
    !puedeCrear -> USAR_PUNTOS_SIN_PERMISO_DE_CUENTA
    llavePrevia -> USAR_PUNTOS_YA_SE_INTENTO
    sinFila -> USAR_PUNTOS_SIN_FILA
    noSeDifiere != null -> noSeDifiere
    !enLinea -> RECOMPENSAS_SIN_RED
    lectura == null || lectura.customerId != clienteId -> USAR_PUNTOS_LEYENDO
    else -> motivoDelSaldo(lectura)
}

/**
 * Sin cuenta, el saldo sólo frena lo CLARO: programa apagado, menos del mínimo, sin puntos o puntos que valen $0. Lo que depende de la
 * cuenta (el tope de lo que queda por descontar, el redondeo) lo decide la cuenta al abrirla (Codex r1/r2 #3).
 */
private fun motivoDelSaldo(lectura: LecturaDelAtajo): String? {
    val l = lectura.lealtad ?: return motivoSinRecompensas(tieneCliente = true, lealtad = null, fallo = lectura.fallo, enLinea = true)
    val claro = !l.active || l.balance < l.minPointsRedeem || l.balance <= 0 || l.balanceValue <= 0
    return if (claro) motivoSinRecompensas(tieneCliente = true, lealtad = l.copy(canRedeem = false), fallo = null, enLinea = true) else null
}

/**
 * Codex r2 #1: entre «Continuar» y el envío (la espera de un código leído) la venta puede cambiar: otro cliente por el lector, o un premio
 * de sellos que empieza a aplicar. Lo que se manda se vuelve a revisar contra lo que la cajera confirmó (null = sigue igual).
 */
fun cambioDuranteElAtajo(clienteEsperado: String, clienteAhora: String?, sinFilaAhora: Boolean): String? = when {
    clienteAhora != clienteEsperado -> USAR_PUNTOS_CLIENTE_CAMBIO
    sinFilaAhora -> USAR_PUNTOS_SIN_FILA
    else -> null
}

/** La cuenta de «Pagar después» que el atajo pidió abrir, con la sucursal y el sello de la sesión que la mandó (Codex r1 #5) y su cliente. */
data class SolicitudDeRecompensas(
    val orderId: String, val venueId: String, val sello: Long, val cliente: String = "", val numero: String = "",
    /** Codex C1-r2 #4: el cobro de la ventana ya terminó («Listo»); vive aquí, no en la pantalla, para sobrevivir a girarla. */
    val cobrada: Boolean = false,
    /** Codex C1-r4 #5: se abrió (y se cerró) un cobro en efectivo desde la copia sin red; no se sabe si se cobró. Igual que [cobrada]. */
    val cobroSinRed: Boolean = false,
)

/**
 * Le dice a «Recompensas» de la cuenta que abrió el atajo que se abra sola (v3 «C»: la cuenta está encima de Cobrar). Una sola a la vez. Se
 * descarta si la sesión o la sucursal ya no son las que la mandaron, y se da por atendida al abrir «Recompensas», al avisar su razón o al
 * cerrar la ventana (Codex r1 #6).
 */
@Singleton
class PuertaDeRecompensas @Inject constructor() {
    private val _solicitud = MutableStateFlow<SolicitudDeRecompensas?>(null)
    val solicitud: StateFlow<SolicitudDeRecompensas?> = _solicitud.asStateFlow()

    fun pedir(s: SolicitudDeRecompensas) { _solicitud.value = s }

    /** Para ABRIR el diálogo: ¿esta cuenta sigue pedida por esta sesión y sucursal? */
    fun porAbrir(venueId: String?, sello: Long, orderId: String): Boolean = deEstaSesion(venueId, sello)?.orderId == orderId

    fun atendida(orderId: String) { _solicitud.value?.takeIf { it.orderId == orderId }?.let { _solicitud.compareAndSet(it, null) } }

    private fun deEstaSesion(venueId: String?, sello: Long): SolicitudDeRecompensas? {
        val s = _solicitud.value ?: return null
        if (s.venueId == venueId && s.sello == sello) return s
        _solicitud.compareAndSet(s, null)
        return null
    }
}

sealed interface AperturaDeRecompensas {
    data object Abrir : AperturaDeRecompensas
    data object Esperar : AperturaDeRecompensas
    data class Avisar(val texto: String) : AperturaDeRecompensas
}

/** Textos de espera de «Recompensas»: mientras salgan, la cuenta todavía no decide. Sin red también se espera a que vuelva. */
private val ESPERAS = setOf(USAR_PUNTOS_LEYENDO, "Actualizando la cuenta…", RECOMPENSAS_LEYENDO_LA_CUENTA, RECOMPENSAS_SIN_RED,
    "La cuenta todavía no llega del servidor, así que no se sabe si tiene cliente. Intenta en un momento.")

/** v3 «C»: la cuenta ya no está abierta (pagada, cancelada o borrada). */
fun cuentaCerrada(status: String, pagada: Boolean): Boolean = pagada || status in setOf("CANCELLED", "DELETED")

/** v3 «C»: la ventana de la cuenta se cierra sola sólo si se vio abierta y después quedó cerrada (la cobró o la canceló la cajera). */
fun cierraLaVentana(vioAbierta: Boolean, cerrada: Boolean): Boolean = vioAbierta && cerrada

/**
 * v3 «C»: la cajera cerró la ventana sin cobrar (o salió de Cobrar antes de que se creara); la cuenta sigue como cualquier «Pagar después».
 * Con el cliente y el folio si se conoce, sin prometer una ruta del menú que el aparato pudo esconder (Codex v3 #8).
 */
fun avisoCuentaEnPedidos(cliente: String, folio: String) =
    "La cuenta de $cliente${if (folio.isBlank()) "" else " ($folio)"} quedó por cobrar: está en Pedidos y en Cuentas por cobrar del panel web."

/**
 * Codex C1-r1 #5: el cobro terminó («Listo») pero la relectura no confirmó que la cuenta quedó en $0.00 (la terminal aprobó y la consulta
 * del saldo falló, o se cortó la relectura): no se dice «quedó por cobrar».
 */
fun avisoCobradaSinConfirmar(cliente: String, folio: String) =
    "Se cobró la cuenta de $cliente${if (folio.isBlank()) "" else " ($folio)"}, pero no se pudo confirmar que quedó en \$0.00: revísala en Pedidos."

/**
 * Codex C1-r2/r3 #5: la cajera abrió el cobro en efectivo desde la copia sin red y salió (cobró o no: ese cobro siempre vuelve «abierta»).
 * El pago pudo subir ya, seguir en la cola, quedar por revisar o no haberse hecho: el aviso no promete ninguno, manda a revisar.
 */
fun avisoCobradaSinRed(cliente: String, folio: String) =
    "La cuenta de $cliente${if (folio.isBlank()) "" else " ($folio)"} quedó en Pedidos: revisa ahí si el efectivo sin conexión quedó guardado."

/** La cuenta que pidió el atajo ya está pagada o cancelada cuando Pedidos la abre (Codex r3 #5). */
fun avisoCuentaYaCerrada(folio: String) = "La cuenta $folio ya no está abierta (pagada o cancelada): ya no admite puntos."

/** Qué hace Pedidos con la cuenta que pidió el atajo (Codex r1 #6). */
fun aperturaDeRecompensas(ofrece: Boolean, consultando: Boolean, motivo: String?, folio: String): AperturaDeRecompensas = when {
    consultando -> AperturaDeRecompensas.Esperar
    !ofrece -> AperturaDeRecompensas.Avisar("La cuenta $folio quedó en Pedidos, pero ya no admite puntos: revísala antes de cobrar.")
    motivo == null -> AperturaDeRecompensas.Abrir
    motivo in ESPERAS -> AperturaDeRecompensas.Esperar
    else -> AperturaDeRecompensas.Avisar("La cuenta $folio quedó en Pedidos: $motivo")
}
