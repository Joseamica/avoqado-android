package com.avoqado.pos.pos.presentation.cart

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.areatickets.data.tieneAbono
import com.avoqado.pos.areatickets.data.AreaTicketCheckout
import com.avoqado.pos.areatickets.data.AreaTicketRepository
import com.avoqado.pos.areatickets.data.moneyToCents
import com.avoqado.pos.auth.data.AuthRepository
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.PlanManager
import com.avoqado.pos.core.util.formatMoney

import com.avoqado.pos.payment.data.DesenlacePagarDespues
import com.avoqado.pos.payment.data.OrderRepository
import com.avoqado.pos.payment.data.model.CreateOrderRequest
import com.avoqado.pos.pos.data.ActiveCartState
import com.avoqado.pos.pos.data.ClassCheckoutSeed
import com.avoqado.pos.pos.data.DiscountsRepository
import com.avoqado.pos.pos.data.ProductsRepository
import com.avoqado.pos.pos.data.soloVendedores
import com.avoqado.pos.pos.data.SavedCartsRepository
import com.avoqado.pos.pos.data.StaffMember
import com.avoqado.pos.pos.data.StaffRepository
import com.avoqado.pos.pos.data.model.CambioDePrecio
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.data.model.DescuentoCongelado
import com.avoqado.pos.pos.data.model.Discount
import com.avoqado.pos.pos.data.model.aplicarCambiosDePrecio
import com.avoqado.pos.pos.data.model.avisoDeCambioDePrecio
import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.pos.data.model.Promotion
import com.avoqado.pos.pos.data.model.SavedCart
import com.avoqado.pos.pos.data.model.SavedCartItem
import com.avoqado.pos.pos.data.model.SavedModifier
import com.avoqado.pos.pos.data.model.SelectedModifier
import com.avoqado.pos.pos.data.model.buildOrderItemRequests
import com.avoqado.pos.pos.presentation.product.variantes.Familia
import com.avoqado.pos.pos.presentation.product.variantes.ResultadoDeBusqueda
import com.avoqado.pos.pos.presentation.product.variantes.buscarEnCatalogo
import com.avoqado.pos.pos.presentation.product.variantes.familiasDe
import com.avoqado.pos.pos.presentation.promotions.opcionesElegidas
import com.avoqado.pos.pos.presentation.promotions.preciosUnitariosDePromocion
import com.avoqado.pos.referrals.domain.model.ValidationResult as ReferralValidationResult
import com.avoqado.pos.referrals.domain.repository.ReferralValidationException
import com.avoqado.pos.referrals.domain.usecase.CaptureReferralUseCase
import com.avoqado.pos.referrals.domain.usecase.ValidateReferralUseCase
import com.avoqado.pos.referrals.presentation.ReferralCaptureUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import com.avoqado.pos.core.util.aCentavos

sealed interface ScannedBarcodeResult {
    data class ProductFound(val product: Product) : ScannedBarcodeResult
    data class WeightedProductFound(val product: Product, val weightKg: Double) : ScannedBarcodeResult
    data class AreaTicketsAdded(val ticketCount: Int) : ScannedBarcodeResult
    /**
     * El código escaneado era la tarjeta digital de un cliente, no mercancía.
     *
     * 🔴 Va como un caso MÁS de este mismo `sealed interface` y no como una pantalla
     * aparte: así el cajero apunta con el mismo botón de siempre y el sistema decide
     * qué leyó. Obligarlo a elegir antes "voy a escanear una tarjeta" es fricción en
     * el momento de más prisa del mostrador.
     */
    data class CustomerCardFound(val card: com.avoqado.pos.loyalty.data.WalletScanResponse) : ScannedBarcodeResult
    data class Unknown(val code: String) : ScannedBarcodeResult
    data class Error(val message: String) : ScannedBarcodeResult
}

/**
 * Lo que encontró la búsqueda Y el texto que lo produjo. Se calcula fuera del hilo principal, así que puede ir un paso atrás del
 * campo: ni «Sin resultados» (y «Crear producto») ni la lista se enseñan salvo que estos resultados sean de lo escrito ahora. Sin
 * lo primero salía un destello de «Sin resultados» en la primera tecla (ronda de arreglo 1 de T4, #2); sin lo segundo quedaban
 * visibles y TOCABLES los de la consulta anterior (Codex P2-3: se vendía «Gorra» con «AG-1» escrito).
 */
data class Busqueda(val texto: String = "", val resultados: List<ResultadoDeBusqueda> = emptyList()) {
    fun resultadosPara(query: String): List<ResultadoDeBusqueda> = if (query.isBlank() || texto != query) emptyList() else resultados
    fun sinResultadosPara(query: String): Boolean = query.isNotBlank() && texto == query && resultados.isEmpty()
}

data class CartState(
    val items: List<CartItem> = emptyList(),
    /**
     * Premio de cartilla que el cajero marcó tras escanear la tarjeta del cliente.
     *
     * 🔴 Viaja DENTRO del carrito y no como parámetro de navegación a propósito: así
     * llega solo hasta la creación de la orden, que es donde el servidor lo aplica. Y
     * se limpia con el carrito, así que no puede arrastrarse a la venta siguiente —
     * que sería regalar un premio dos veces.
     *
     * 🔴 DINERO. Como en Square y Toast, el premio es un descuento de la cuenta ANTES de
     * cobrar: [totalCents] ya lo resta ([stampRewardCents], un estimado). Lo que manda al
     * cobrar es lo que el servidor confirma — ver `totalACobrarCents`.
     */
    val pendingStampReward: com.avoqado.pos.loyalty.data.PremioPorAplicar? = null,
    val orderDiscount: Discount? = null,
    val orderNote: String? = null,
    val orderTaxPercent: Int? = null,
    /** Cumplimiento (Square): DINE_IN | TAKEOUT | DELIVERY | PICKUP. La venta
     *  rápida siempre fue "En tienda" en la UI — ahora el dato lo respeta. */
    val orderType: String = "DINE_IN",
    val selectedStaffId: String = "",
    val selectedStaffName: String = "Staff",
    /** Set when the cart was seeded from a walk-in class reservation; flows
     *  through to the order so the sale links back to the reservation. */
    val reservationId: String? = null,
    val carritoGuardadoId: String? = null,
    /** Identidad de la venta en memoria; guardar una copia no la cambia. */
    val identidadDeVenta: String = UUID.randomUUID().toString(),
    /**
     * 🔴 DINERO. «Se cobra el precio que vio el cliente» (17-oct): el descuento de CUENTA que viajó en la orden cuyo servidor
     * corrigió precios. Mientras el carrito siga siendo esa venta ([DescuentoCongelado.vigentePara]), [orderDiscountCents] usa
     * este monto en vez de recalcular el %: así el carrito cobra lo mismo que la orden. Sólo nace de [conPreciosDelServidor] con
     * cambios; se suelta al poner o quitar [orderDiscount] y al empezar otra venta; una edición de líneas lo deja sin efecto.
     */
    val descuentoDeCuentaCongelado: DescuentoCongelado? = null,
    /**
     * 🔴 «Pagar después» (27-oct, R2-2): la llave (`externalId`) de ESTA venta. Nace al primer toque y se reusa en todo
     * intento de la misma venta: si uno anterior sí creó la cuenta, el servidor devuelve ésa en vez de crear otra.
     */
    val llavePagarDespues: String? = null,
) {
    /**
     * 🔴 DINERO. Las líneas con el precio que decidió el servidor ([aplicarCambiosDePrecio]). Con [cambios] vacío devuelve el
     * MISMO estado: no corrige ni congela nada.
     * - [congelar] (un 201: la orden YA existe con el descuento que viajó): si había descuento de cuenta, queda fijo en el monto
     *   de ANTES de corregir —el que mandó [orderDiscountCents]; con uno congelado vigente es ese mismo, que se vuelve a sellar—
     *   con las huellas de las líneas ya corregidas.
     * - Sin [congelar] (un 409: no se creó nada): el reintento lleva lo que calcule el carrito y el servidor conserva eso. Un %
     *   sigue siendo %: congelar el 10 % de $100 que sube a $125 cobraría $115 en vez de $112.50, y una cortesía cobraría $25.
     */
    fun conPreciosDelServidor(cambios: List<CambioDePrecio>, congelar: Boolean = true): CartState {
        if (cambios.isEmpty()) return this
        val queViajo = orderDiscountCents
        val corregidas = aplicarCambiosDePrecio(items, cambios)
        return copy(
            items = corregidas,
            descuentoDeCuentaCongelado = DescuentoCongelado.de(queViajo, corregidas).takeIf { congelar && queViajo > 0 },
        )
    }

    val itemCount: Int get() = items.sumOf { it.quantity }

    /**
     * 🔴 BRUTO — precio de lista, sin rebajar. El descuento se muestra en su
     * propio renglón ([discountCents]) para que el cliente LO VEA, en pantalla y
     * en el ticket impreso (decisión del founder, 2026-08-17).
     *
     * También es lo que hace el server: guarda `subtotal` bruto y `discountAmount`
     * aparte (`order.mobile.service.ts`). Con el subtotal ya rebajado, el papel y
     * el sistema decían números distintos aunque el total coincidiera, y quien
     * conciliara tropezaba.
     *
     * 🔴 El TOTAL no cambia con esto: es el MISMO dinero, desglosado.
     */
    val subtotalCents: Int get() = items.sumOf { it.grossPrice }

    /** Suma de los descuentos por línea (artículo). Se pinta dentro de [discountCents]. */
    val itemDiscountCents: Int get() = items.sumOf { it.itemDiscountCents }

    /** Lo que de verdad se debe antes de aplicar un descuento de ORDEN. */
    private val netoTrasDescuentosDeLineaCents: Int get() = (subtotalCents - itemDiscountCents).coerceAtLeast(0)

    val taxableSubtotalCents: Int get() = items.filter { it.type is CartItemType.ProductItem }.sumOf { it.totalPrice }

    /**
     * 🔴 El descuento de ORDEN pega sobre lo que QUEDA tras los de línea, no sobre
     * el bruto. Sobre el bruto regalaría de más en cada venta que combine los dos,
     * y además es lo que hace el server: `orderLevelDiscount` se topa contra
     * `subtotal - itemDiscountTotal`.
     *
     * Con el descuento congelado vigente ([descuentoDeCuentaCongelado]) es el monto que viajó, con el mismo tope. El renglón del
     * descuento ([orderDiscountDisplay]) sale de aquí: lo que ve el cajero es lo que se cobra. Android tiene UN descuento de
     * cuenta, así que no hay reparto entre renglones (iOS sí lo reparte).
     */
    val orderDiscountCents: Int
        get() {
            val descuento = orderDiscount ?: return 0
            descuentoDeCuentaCongelado?.takeIf { it.vigentePara(items) }
                ?.let { return it.cents.coerceAtMost(netoTrasDescuentosDeLineaCents) }
            return descuento.calculateDiscount(netoTrasDescuentosDeLineaCents)
        }

    /** Lo que se pinta como "Descuento": línea + orden, juntos. */
    val discountCents: Int get() = itemDiscountCents + orderDiscountCents

    /**
     * Sólo prorratea el descuento de ORDEN: [taxableSubtotalCents] ya viene NETO de
     * los descuentos por línea, así que meterlos otra vez los contaría dos veces y
     * bajaría el impuesto de más.
     */
    val taxableDiscountCents: Int
        get() = if (netoTrasDescuentosDeLineaCents <= 0 || orderDiscountCents <= 0 || taxableSubtotalCents <= 0) {
            0
        } else {
            ((orderDiscountCents.toDouble() * taxableSubtotalCents.toDouble()) / netoTrasDescuentosDeLineaCents.toDouble())
                .toInt()
                .coerceAtMost(taxableSubtotalCents)
        }
    val taxableAmountAfterDiscountCents: Int
        get() = (taxableSubtotalCents - taxableDiscountCents).coerceAtLeast(0)
    val taxCents: Int
        get() {
            val percent = orderTaxPercent ?: return 0
            if (percent <= 0 || taxableAmountAfterDiscountCents <= 0) return 0
            return ((taxableAmountAfterDiscountCents * percent) / 100.0).toInt()
        }
    val pendingStampRewardId: String? get() = pendingStampReward?.id

    /**
     * Cuánto baja la cuenta el premio pendiente — ESTIMADO con la regla del servidor, sobre la
     * cuenta ya descontada. No va en [discountCents]: ese viaja al servidor como `discount`, y
     * el premio lo canjea el servidor por su cuenta (mandarlo en los dos lo restaría dos veces).
     */
    val stampRewardCents: Int
        get() = pendingStampReward?.takeIf { premioAplica }?.let {
            com.avoqado.pos.loyalty.data.premioEstimadoCents(it, items, (subtotalCents - discountCents).coerceAtLeast(0))
        } ?: 0

    /**
     * 🔴 El premio sólo se canjea en una venta que CREA orden con productos (`confirmPayment` /
     * `processCashPayment`). El cobro rápido («Otro importe»), la cuenta de mesa y los vales no lo
     * llevan: restarlo ahí regalaría el descuento sin gastar el premio.
     */
    val premioAplica: Boolean
        get() = items.any { it.type is CartItemType.ProductItem } && items.none { it.locked }
    val stampRewardDisplay: String get() = formatCents(stampRewardCents)

    val totalCents: Int get() = (subtotalCents - discountCents - stampRewardCents + taxCents).coerceAtLeast(0)
    val isEmpty: Boolean get() = items.isEmpty()

    /**
     * D10 (27-oct, founder «Sí, gris en esos casos»): por qué «Por producto» sale en gris, o null si se puede. En estas dos
     * ventas la tablet no puede saber el reparto exacto del SERVIDOR (Codex D10 r2): una promoción guarda sus piezas
     * redondeadas, y el producto gratis empatado lo desempata el SERVIDOR por un id que aquí no existe todavía.
     */
    val razonSinPorProducto: String?
        get() = when {
            items.any { it.promotionInstanceId != null } &&
                (orderDiscount != null || descuentoDeCuentaCongelado != null || (pendingStampReward != null && premioAplica)) ->
                POR_PRODUCTO_CON_PROMOCION
            // Codex D10 r3: con «producto gratis» el SERVIDOR pone el regalo ENTERO en un renglón que la tablet no puede
            // seguir con certeza (empates por un id que aquí no existe, precios que el SERVIDOR corrige, y tras la primera
            // parte el premio ya sólo es un monto). Siempre en gris, no sólo con empate.
            pendingStampReward?.takeIf { premioAplica && it.tipo?.uppercase() == "FREE_PRODUCT" } != null -> POR_PRODUCTO_PREMIO_EMPATADO
            else -> null
        }

    val subtotalDisplay: String get() = formatCents(subtotalCents)
    val discountDisplay: String get() = formatCents(discountCents)
    /** Los dos descuentos por separado: se pintan en renglones distintos, con su
     *  propio nombre, para no atribuirle a uno el monto del otro. */
    val itemDiscountDisplay: String get() = formatCents(itemDiscountCents)
    val orderDiscountDisplay: String get() = formatCents(orderDiscountCents)
    val taxDisplay: String get() = formatCents(taxCents)
    val totalDisplay: String get() = formatCents(totalCents)
}

private fun formatCents(cents: Int): String {
    return formatMoney(cents / 100.0)
}

internal const val AVISO_CARRITO_SIN_IMPUESTO =
    "Este carrito guardado sumaba impuesto aparte. Ya no se suma: el precio ya incluye el IVA."

sealed interface Restauracion {
    data object Hecha : Restauracion
    data class PideConfirmacion(val cobroIniciadoEn: Long) : Restauracion
    /** D5: este carrito es una venta de vales con abono; restaurar otro la vaciaría. */
    data object BloqueadaPorAbono : Restauracion
    /** 27-oct (Codex): la copia está ligada a una cuenta de «Pagar después» viva; venderla otra vez la duplicaría. */
    data object YaDejadaParaDespues : Restauracion
}

/** Mismo texto en iOS (`AreaTicketCartError.noPagarDespues`). */
const val VALES_NO_PAGAR_DESPUES = "Los vales se cobran en la caja: no se pueden dejar para pagar después."

/** D4 (20-oct). Mismo texto en iOS (`AreaTicketCartError.ventaAMedias`). */
const val VALE_CON_VENTA_A_MEDIAS = "Termina de cobrar el saldo pendiente de esta venta antes de escanear un vale."

// Rediseño r7 (21-oct). Mismos textos en iOS.
const val VALES_ABONO_CAMBIADA = "Esta venta de vales ya tiene un abono: cobra sólo su saldo pendiente. Quita lo que agregaste."
const val VALES_SOLTAR_ANTERIOR = "No se pudo soltar la venta de vales anterior. Revisa la conexión y vuelve a escanear el vale."
const val VALES_SALDO_CON_OTRA_VENTA = "Termina, guarda o vacía la venta del carrito para cobrar el saldo pendiente."
const val VALES_SALDO_YA_COBRADO = "Esa venta de vales ya se terminó de cobrar."

/** J5 (vales-ciclo, 26-oct): el cajero empezó otra venta mientras se consultaba el vale. Mismo texto en iOS. */
const val VALES_VENTA_CAMBIO_AL_LEER = "La venta cambió mientras se leía el vale: vuelve a escanearlo."

/** Tablero D3 (Codex r9): guardar o diferir una venta a medias se llevaba su saldo a otra venta. Mismo texto en iOS. */
const val VENTA_A_MEDIAS_NO_SE_GUARDA =
    "Esta venta ya tiene un cobro parcial: cobra primero su saldo pendiente. No se puede guardar ni dejar para pagar después."

/** Lo que el cajero ve al terminar «Pagar después». */
sealed interface ResultadoPagarDespues {
    data object Creada : ResultadoPagarDespues
    /** Ámbar, nunca rojo: quedó guardada sin red, o la cuenta existe por otro importe. */
    data class Aviso(val texto: String) : ResultadoPagarDespues
    /** No se dejó para después: la venta volvió al carrito (o a «Carritos guardados»). */
    data class Error(val texto: String) : ResultadoPagarDespues
}

/** La venta que se está mandando: lo que enseña «Guardando la cuenta…» con el carrito ya vacío. */
data class EnvioPagarDespues(val cliente: String, val totalCents: Int)

// «Pagar después» sin red (27-oct). Mismos textos en iOS.
const val GUARDANDO_LA_CUENTA = "Guardando la cuenta…"
const val PAGAR_DESPUES_PREMIO_SIN_RED = "Sin conexión: el premio de sellos necesita internet. Quita el premio o espera la red."
const val PAQUETES_NO_PAGAR_DESPUES = "Los paquetes se cobran en el momento: no se pueden dejar para pagar después."
fun pagarDespuesGuardadaSinRed(cliente: String) = "Sin conexión: la cuenta de $cliente quedó guardada en el aparato y se sube sola."
fun pagarDespuesOtroImporte(cliente: String, registradoCents: Int, esperadoCents: Int) =
    "La cuenta de $cliente quedó por ${com.avoqado.pos.core.util.formatMoneyFromCents(registradoCents)}, " +
        "no por ${com.avoqado.pos.core.util.formatMoneyFromCents(esperadoCents)}. Revísala en el panel."
fun pagarDespuesACarritosGuardados(cliente: String) = "La venta de $cliente no se pudo dejar para después: quedó en Carritos guardados."
const val YA_SE_DEJO_PARA_PAGAR_DESPUES = "Esta venta ya se dejó para pagar después."
const val PAGAR_DESPUES_EN_CURSO = "Se está dejando esta venta para pagar después: espera a que termine."
fun pagarDespuesNiGuardada(cliente: String) =
    "La venta de $cliente no se pudo dejar para después ni guardar: quedó en Pendientes por revisar."
const val PAGAR_DESPUES_NO_SE_GUARDO = "No se pudo guardar la cuenta en el aparato. Intenta de nuevo."

/** Tablero D3 (21-oct). Mismo texto en iOS. */
const val VENTA_A_MEDIAS_CAMBIADA =
    "Esta venta ya tiene un cobro parcial: cobra primero su saldo pendiente. Quita lo que agregaste y véndelo aparte."

// D1 (26-oct, founder «B»): dividir en tienda pide conexión; una parte guardada sin red hace esperar al resto. Mismos textos en iOS.
/** D10: «Por producto» en gris — promoción + descuento de toda la cuenta (o premio). */
const val POR_PRODUCTO_CON_PROMOCION =
    "Esta venta tiene una promoción y un descuento de toda la cuenta: divídela por monto o en partes iguales."

/** D10: «Por producto» en gris — la venta lleva un premio de «producto gratis». */
const val POR_PRODUCTO_PREMIO_EMPATADO =
    "Esta venta lleva un producto gratis del premio de sellos: divídela por monto o en partes iguales."

const val DIVIDIR_SIN_CONEXION =
    "Dividir necesita conexión con Avoqado. Cobra todo junto o espera a que vuelva la conexión."
const val PARTE_SIN_RED_SUBIENDO =
    "La primera parte de esta venta se guardó sin conexión. El resto se cobra en esta misma venta en cuanto se suba."
const val PARTE_SIN_RED_POR_REVISAR =
    "La primera parte de esta venta se guardó sin conexión y quedó por revisar. Revísala en «Operaciones por revisar»: " +
        "el resto se cobra en esta misma venta cuando se resuelva."
const val PARTE_SIN_RED_YA_NO_ESTA =
    "La primera parte de esta venta ya no está en este aparato: el resto se cobra como venta aparte."
const val PARTE_SIN_RED_VENTA_PAGADA =
    "Al subir la primera parte, esta venta quedó pagada: no queda saldo por cobrar."
const val PARTE_SIN_RED_PAGADA_CON_ALGO_MAS =
    "Al subir la primera parte, esa venta quedó pagada. Lo que hay en el carrito se cobra como venta aparte."

/** El título del aviso ámbar del carrito: el de vales sólo para vales (D1 reusa el mismo aviso). */
fun tituloDelAviso(aviso: String): String = when {
    aviso == PAGAR_DESPUES_EN_CURSO -> "Pagar después"
    aviso == DIVIDIR_SIN_CONEXION -> "Sin conexión"
    aviso == VENTA_A_MEDIAS_CAMBIADA || aviso.startsWith("La primera parte de esta venta") ||
        aviso.startsWith("Al subir la primera parte") -> "Venta a medio cobrar"
    else -> "Venta de vales"
}

fun parteSinRedSaldoDistinto(carritoCents: Int, servidorCents: Int): String =
    "Al subir la primera parte, Avoqado dice que esta venta debe ${com.avoqado.pos.core.util.formatMoneyFromCents(servidorCents)} " +
        "y el carrito dice ${com.avoqado.pos.core.util.formatMoneyFromCents(carritoCents)}. Revísala en «Ventas» antes de cobrar; " +
        "si vacías el carrito, el resto se cobra como venta aparte."

/** Regla 10: una venta de vales con abono que el carrito no trae. Lo pinta el aviso fijo «Cobrar saldo». */
data class SaldoDeVales(val orderId: String, val saldoCents: Int)

@HiltViewModel
class CartViewModel @Inject constructor(
    val productsRepository: ProductsRepository,
    val discountsRepository: DiscountsRepository,
    private val savedCartsRepository: SavedCartsRepository,
    private val authRepository: AuthRepository,
    private val secureStorage: SecureStorage,
    private val activeCartState: ActiveCartState,
    private val orderRepository: OrderRepository,
    private val staffRepository: StaffRepository,
    private val classCheckoutSeed: ClassCheckoutSeed,
    private val validateReferralUseCase: ValidateReferralUseCase,
    private val captureReferralUseCase: CaptureReferralUseCase,
    private val planManager: PlanManager,
    private val tableSession: com.avoqado.pos.tables.data.TableSession,
    private val customerDisplay: com.avoqado.pos.customerdisplay.CustomerDisplayState,
    private val areaTicketRepository: AreaTicketRepository,
    private val walletScanRepository: com.avoqado.pos.loyalty.data.WalletScanRepository,
    /**
     * Lector de pistola (USB/Bluetooth). Lleva default para que las pruebas que construyen
     * este ViewModel a mano no tengan que conocerlo: Hilt inyecta siempre el singleton real
     * (el que alimenta `MainActivity`), y la app nunca usa el default.
     */
    private val lectorHidBus: com.avoqado.pos.pos.data.LectorHidBus = com.avoqado.pos.pos.data.LectorHidBus(),
    private val serviceCoursesRepository: com.avoqado.pos.tables.data.ServiceCoursesRepository? = null,
    /** D1 (26-oct): la parte de una venta dividida que se guardó sin red. Default para las pruebas que no la usan. */
    private val cashPaymentRepository: com.avoqado.pos.payment.data.CashPaymentRepository? = null,
    /** D1 (26-oct): «Dividir» en tienda pide conexión con Avoqado. Sin monitor (pruebas) se da por conectado. */
    private val connectivityMonitor: com.avoqado.pos.core.util.ConnectivityMonitor? = null,
    /** «Pagar después» sin red (27-oct). Default para las pruebas que no la usan; sin ella no se difiere. */
    private val payLaterQueue: com.avoqado.pos.payment.data.PayLaterQueue? = null,
) : ViewModel() {

    private val _cartState = MutableStateFlow(defaultCartState())
    val cartState: StateFlow<CartState> = _cartState.asStateFlow()

    /**
     * Códigos que llegan del lector de pistola. La pantalla los mete por el MISMO camino
     * que un escaneo de cámara ([resolveScannedBarcode]): producto, vale, báscula o la
     * tarjeta del cliente — el lector sirve para todo lo que la cámara ya leía.
     */
    val codigosEscaneados: kotlinx.coroutines.flow.SharedFlow<String>
        get() = lectorHidBus.codigos

    /**
     * 🔴 DINERO. El CARRITO —no la pantalla— sabe si hay un cobro abierto y cuántos códigos leídos se están consultando.
     * Vive aquí porque la pantalla se recrea (cambiar de pestaña) y con ella se perdía: un vale lento podía entrar después
     * de abrir el cobro, sin cobrarse (pendientes del 19-oct, Codex r1 #2 a r4 #1). Con el cobro abierto, lo leído no toca
     * el carrito ([resolveScannedBarcode]); mientras haya consultas, el cobro no se abre (`CheckoutScreen.proceedToPayment`).
     */
    @Volatile var cobroAbierto: Boolean = false
        private set
    fun abrirCobro() { cobroAbierto = true }
    fun cerrarCobro() { cobroAbierto = false }
    /** Flujo (no un contador suelto) para que «Pagar después» pueda ESPERAR a que lleguen antes de separar la venta (R3-2). */
    private val consultasDeCodigo = MutableStateFlow(0)
    val hayConsultasDeCodigo: Boolean get() = consultasDeCodigo.value > 0

    private val _staffOptions = MutableStateFlow<List<StaffMember>>(emptyList())
    val staffOptions: StateFlow<List<StaffMember>> = _staffOptions.asStateFlow()

    private val _isStaffLoading = MutableStateFlow(false)
    val isStaffLoading: StateFlow<Boolean> = _isStaffLoading.asStateFlow()

    // Aviso NO bloqueante al agregar un producto que marca 0 o menos: la venta
    // procede y el inventario quedará en negativo (señal de descuadre). La UI lo
    // pinta como toast ámbar y lo consume con consumeStockWarning().
    private val _stockWarning = MutableStateFlow<String?>(null)
    val stockWarning: StateFlow<String?> = _stockWarning.asStateFlow()

    fun consumeStockWarning() {
        _stockWarning.value = null
    }

    // D13 (IVA por producto, plan 7a): aviso ámbar al restaurar un carrito guardado que sumaba impuesto aparte. Se pinta
    // como `stockWarning` y se consume con consumeAvisoDelCarrito(). Espejo de `avisoDelCarrito` en iOS.
    private val _avisoDelCarrito = MutableStateFlow<String?>(null)
    val avisoDelCarrito: StateFlow<String?> = _avisoDelCarrito.asStateFlow()

    fun consumeAvisoDelCarrito() {
        _avisoDelCarrito.value = null
    }

    private fun avisarSiAgotado(product: Product) {
        if (product.isOutOfStock) {
            _stockWarning.value = "\"${product.name}\" marcaba 0 en inventario. Se agregó a la venta: revisa tus existencias."
        }
    }

    private val _staffError = MutableStateFlow<String?>(null)
    val staffError: StateFlow<String?> = _staffError.asStateFlow()

    // MARK: - Split de mostrador: la venta que quedó a medio cobrar

    /**
     * 🔴 DINERO. Vínculo entre el carrito ACTUAL y la orden que ya está abierta
     * porque una parte de esta misma venta ya se cobró.
     *
     * Vive aquí, y no en el flujo de pago, por una razón concreta: el carrito
     * ES la venta. Al morir el carrito muere el vínculo, así que el id no puede
     * sobrevivir a la venta que lo creó — que es el único riesgo real de todo
     * esto (un id de más cobra la venta NUEVA contra la orden vieja).
     *
     * @param itemIds  qué renglones venían de esa venta.
     * @param totalCents  cuánto valía el carrito al quedar el saldo.
     */
    private data class PendingSplitOrder(
        /** null mientras espera a que suba su parte guardada sin red ([parteEnCola]). */
        val orderId: String?,
        val itemIds: Set<String>,
        val totalCents: Int,
        /** Foto exacta de los renglones y del descuento de cuenta al quedar el saldo (regla 11: venta de vales con abono). */
        val renglones: List<CartItem> = emptyList(),
        val descuentoDeCuenta: Discount? = null,
        /** D1: la llave en `pending_payments` de la parte que se guardó sin red; mientras no suba, el resto no se cobra. */
        val parteEnCola: String? = null,
        /** D1: nació de una parte sin red ⇒ el carrito tiene que seguir EXACTO (un descuento lo abarataría, Codex r2 #5). */
        val exacta: Boolean = false,
    )

    private val _pendingSplitOrder = MutableStateFlow<PendingSplitOrder?>(null)

    /**
     * 🔴 DINERO. La orden contra la que corre el cobro que está ABIERTO ahora
     * mismo. Se congela al abrir el cobro ([resolvePendingSplitOrderForCharge])
     * y se suelta al cerrarlo ([releaseChargingOrder]).
     *
     * **Vive en el ViewModel para sobrevivir a que se recree la Activity.** Antes
     * era un `remember` de la pantalla y bastaba **girar la tablet** —o cambiar
     * tamaño de fuente, tema, idioma— para perderlo: `MainActivity` no declara
     * `configChanges`, la pantalla de cobro sí revive (`rememberSaveable`) pero
     * el vínculo no, y la parte 2 volvía a crear una SEGUNDA orden. El bug
     * original, entero, en una tablet de mostrador que alguien gira.
     *
     * 🔴 Es una propiedad simple **a propósito, no un StateFlow**: se lee durante
     * la composición y NO debe reaccionar sola. Congelarlo es el punto — si
     * cambiara a media venta, `PaymentFlowScreen` reiniciaría su `LaunchedEffect`
     * y borraría la pantalla de recibo. Cada cambio real va acompañado de una
     * escritura a `showPaymentFlow`, que es lo que recompone.
     *
     * 🔴 Por esa misma razón **`clearCart()` NO lo toca**, aunque sí borre el
     * vínculo durable de abajo: el checkout llama a `clearCart()` DENTRO de
     * `onPaymentCommitted`, con el recibo todavía en pantalla. Soltarlo ahí
     * cambiaba el valor a media venta y reiniciaba el cobro encima del recibo —
     * lo cazó su propio test. No hace falta: [resolvePendingSplitOrderForCharge]
     * lo reescribe SIEMPRE (id o null) antes de cada cobro, así que nunca se lee
     * uno viejo.
     */
    var chargingAgainstOrderId: String? = null
        private set

    /** Se cerró el flujo de cobro (terminó, se canceló, o se resolvió otro). */
    fun releaseChargingOrder() {
        chargingAgainstOrderId = null
    }

    // MARK: - Membresías capturadas al abrir el cobro

    /** Las membresías que este cobro tiene que otorgar, y a quién. */
    data class PendingPackGrant(val customerId: String, val packIds: List<String>)

    /**
     * 🔴 DINERO. Membresías (credit packs) que el carrito llevaba al abrirse el
     * cobro, junto con el cliente que las recibe. Se congelan aquí y se entregan
     * cuando el pago se confirma.
     *
     * **Vive en el ViewModel por la MISMA razón que [chargingAgainstOrderId]:
     * para sobrevivir a que se recree la Activity.** Era un `remember` pelón de
     * `CheckoutScreen`, dos líneas más arriba del vínculo de orden y con el mismo
     * defecto — pero éste cuesta dinero de verdad: el cliente compra "Membresía
     * 10 clases $500", el cajero toca Cobrar, **gira la tablet** (o Android entra
     * en modo oscuro al anochecer, o cambia el tamaño de fuente), la Activity se
     * recrea, `showPaymentFlow` revive porque es `rememberSaveable` y esto volvía
     * a null. Se cobraban los $500, `grantPacks` no se llamaba nunca y
     * `clearCart()` borraba la evidencia: cliente sin una sola clase.
     *
     * Igual que el vínculo de orden, **`clearCart()` no lo toca**: se reescribe
     * SIEMPRE al abrir el cobro ([capturePendingPackGrant]), así que nunca se
     * puede leer uno viejo.
     */
    var pendingPackGrant: PendingPackGrant? = null
        private set

    /**
     * Congela las membresías del carrito al abrir el cobro. Escribe siempre —
     * con `null` cuando no hay cliente o no hay packs— para que una venta nunca
     * herede la captura de la anterior.
     */
    fun capturePendingPackGrant(customerId: String?, cart: CartState) {
        val id = customerId?.trim()?.takeIf { it.isNotEmpty() }
        val packIds = cart.items.mapNotNull { (it.type as? CartItemType.CreditPack)?.packId }
        pendingPackGrant = if (id == null || packIds.isEmpty()) null else PendingPackGrant(id, packIds)
    }

    /**
     * Entrega las membresías UNA sola vez: devolver y limpiar en el mismo acto,
     * para que un segundo commit del mismo cobro no las otorgue por duplicado.
     */
    fun consumePendingPackGrant(): PendingPackGrant? {
        val grant = pendingPackGrant
        pendingPackGrant = null
        return grant
    }

    /**
     * Marca el carrito TAL COMO ESTÁ AHORA como la continuación de [orderId].
     * Se llama desde el checkout justo después de dejar el saldo en el carrito
     * (o de quitar los artículos ya cobrados, en el split por artículos).
     *
     * `null` o vacío borra el vínculo — es la forma explícita de cerrar la venta.
     */
    fun markPendingSplitOrder(orderId: String?) {
        val id = orderId?.trim()?.takeIf { it.isNotEmpty() }
        if (id == null) {
            _pendingSplitOrder.value = null
            return
        }
        val cart = _cartState.value
        _pendingSplitOrder.value = PendingSplitOrder(
            orderId = id,
            itemIds = cart.items.map { it.id }.toSet(),
            totalCents = cart.totalCents,
            renglones = cart.items,
            descuentoDeCuenta = cart.orderDiscount,
        )
        // Si es la orden de la sesión de vales, ya tiene un abono aunque su copia no lo diga: que no vuelva a proyectarse
        // aunque luego se vacíe el carrito (D3, Codex r1). El saldo es lo que quedó en el carrito (regla 10).
        areaTicketRepository.session.marcarConAbono(id, saldoCents = cart.totalCents)
        Log.d("🛒", "Split de mostrador a medias: la venta sigue contra la orden $id")
    }

    /** La venta de vales con abono vigente, si hay una (su orden ya armada). */
    private fun ventaDeValesConAbono(): AreaTicketCheckout? =
        areaTicketRepository.session.current()?.takeIf { it.tieneAbono() && it.order != null }

    /**
     * 🔴 DINERO (regla 10). El carrito trae la venta de vales con abono: continúa su orden y conserva sus renglones tal como
     * quedaron. Si salió por cualquier puerta (cambiar usuario, reimprimir, reemplazar el carrito…), no la trae y el aviso
     * fijo «Cobrar saldo» la ofrece de nuevo.
     */
    private fun traeLaVentaDeVales(venta: AreaTicketCheckout, cart: CartState = _cartState.value): Boolean {
        val pendiente = _pendingSplitOrder.value ?: return false
        if (pendiente.orderId != venta.order?.id) return false
        return pendiente.renglones.all { foto -> cart.items.any { it == foto } }
    }

    /**
     * 🔴 D5 (20-oct). El carrito trae una venta de vales que ya tiene un abono: «Vaciar» no se deja. El servidor no la
     * cancela (409), así que vaciar sólo escondía la venta y el abono quedaba en la caja sin venta que lo explique. El
     * mercado tampoco lo deja (Toast, Clover); la salida es cobrar el saldo o devolver el abono. Si el carrito ya no la trae,
     * vaciar se deja: la venta sigue a salvo en el aviso «Cobrar saldo».
     */
    fun vaciarBloqueadoPorAbono(): Boolean = ventaDeValesConAbono()?.let(::traeLaVentaDeVales) == true

    /**
     * 🔴 DINERO (regla 11, Codex r6 #1 y #2). La venta de vales con abono sólo se cobra por su saldo: si el carrito la trae
     * con algo agregado, otro descuento o un renglón cambiado, el cobro no se abre (y el vínculo NO se rompe: romperlo apagaba
     * todos los candados de D5). Devuelve `true` y avisa cuando hay que detenerse.
     */
    fun detenerCobroDeVentaCambiada(): Boolean {
        val pendiente = _pendingSplitOrder.value ?: return false
        val cart = _cartState.value
        val venta = ventaDeValesConAbono()?.takeIf { it.order?.id == pendiente.orderId }
        if (venta != null) {
            val igual = cart.items == pendiente.renglones && cart.orderDiscount == pendiente.descuentoDeCuenta
            if (igual) return false
            _avisoDeVales.value = VALES_ABONO_CAMBIADA
            return true
        }
        // 🔴 Tablero D3 (21-oct, founder «arréglalo de una vez»): una venta normal a medio cobrar con algo agregado se cobraba
        // como venta NUEVA llevándose adentro su «Saldo pendiente» (o sus renglones sin cobrar): el cliente pagaba el saldo y la
        // orden vieja seguía debiéndolo. Ahora no se abre el cobro: primero su saldo, lo nuevo aparte (como vales, regla 11).
        if (!carritoCambioDesde(pendiente, cart)) return false
        _avisoDeVales.value = VENTA_A_MEDIAS_CAMBIADA
        return true
    }

    /** El carrito continúa una venta cobrada a medias (tiene saldo en su orden). */
    fun hayVentaAMedias(): Boolean = _pendingSplitOrder.value != null

    /** Hay un renglón que la orden no tiene, o el carrito creció en dinero (`addProduct` FUSIONA: misma línea, cantidad 2). */
    private fun carritoCambioDesde(pendiente: PendingSplitOrder, cart: CartState): Boolean =
        // Exacta: renglones, descuento Y total (quitar al cliente quita su premio y sube el total sin tocar renglones, Codex r2 #1).
        if (pendiente.exacta) cart.items != pendiente.renglones || cart.orderDiscount != pendiente.descuentoDeCuenta ||
            cart.totalCents != pendiente.totalCents
        else cart.items.any { it.id !in pendiente.itemIds } || cart.totalCents > pendiente.totalCents

    // MARK: - D1: la parte que se guardó sin red (founder «B», 26-oct)

    private var observadorDeParte: kotlinx.coroutines.Job? = null
    private val _estadoDeParte = MutableStateFlow<com.avoqado.pos.payment.data.EstadoDeParte?>(null)

    /**
     * 🔴 DINERO (D1). La parte de una venta dividida se cobró en efectivo y se guardó SIN RED: todavía no tiene orden en el
     * servidor. El carrito queda con su saldo ligado a ESA parte y no se cobra hasta que suba; al subir, el vínculo pasa a la
     * orden que le dio el servidor (con la foto de ahora) y el resto se cobra contra ella, como cualquier parte 2. Antes el
     * resto salía como venta rápida suelta: dos registros y el inventario sin bajar.
     */
    fun esperarParteEnCola(colaId: String) {
        val repo = cashPaymentRepository ?: return
        val cart = _cartState.value
        _pendingSplitOrder.value = PendingSplitOrder(
            orderId = null,
            itemIds = cart.items.map { it.id }.toSet(),
            totalCents = cart.totalCents,
            renglones = cart.items,
            descuentoDeCuenta = cart.orderDiscount,
            parteEnCola = colaId,
            exacta = true,
        )
        _estadoDeParte.value = com.avoqado.pos.payment.data.EstadoDeParte.Subiendo
        observadorDeParte?.cancel()
        observadorDeParte = viewModelScope.launch {
            repo.observarParte(colaId).collect { estado ->
                // El carrito ya es otra venta (se vació, se cobró, se cambió de venta): se deja de mirar.
                val vigente = _pendingSplitOrder.value?.takeIf { it.parteEnCola == colaId }
                if (vigente == null) {
                    _estadoDeParte.value = null
                    observadorDeParte?.cancel()
                    return@collect
                }
                when (estado) {
                    is com.avoqado.pos.payment.data.EstadoDeParte.Subida -> adoptarOrdenDeLaParte(vigente, estado)
                    com.avoqado.pos.payment.data.EstadoDeParte.SinOrden -> {
                        // Venta sin productos: el servidor la cerró con lo cobrado; el resto es un cobro rápido, como antes.
                        _pendingSplitOrder.value = null
                        _estadoDeParte.value = null
                    }
                    com.avoqado.pos.payment.data.EstadoDeParte.NoEsta -> {
                        _pendingSplitOrder.value = null
                        _estadoDeParte.value = null
                        _avisoDeVales.value = PARTE_SIN_RED_YA_NO_ESTA
                    }
                    else -> _estadoDeParte.value = estado
                }
            }
        }
    }

    /**
     * La parte subió. Sólo se sigue SOLA cuando el servidor y el carrito dicen lo mismo; nada se ajusta ni se borra solo
     * (Codex D1 código r1 #2-#6: ajustar el saldo, vaciar el carrito o soltar al cliente en automático cobraba de más, borraba
     * mercancía agregada o dejaba pegado al cliente).
     */
    private fun adoptarOrdenDeLaParte(vigente: PendingSplitOrder, subida: com.avoqado.pos.payment.data.EstadoDeParte.Subida) {
        observadorDeParte?.cancel()
        val cart = _cartState.value
        val sinCambios = !carritoCambioDesde(vigente, cart)
        val saldo = subida.saldoCents
        when {
            // Ya quedó pagada al subir: con el carrito como estaba, la venta termina (como cualquier cobro completo); si se
            // le agregó algo, eso es otra venta y se queda en el carrito.
            subida.pagada || (saldo != null && saldo <= 0) -> {
                _pendingSplitOrder.value = null
                _estadoDeParte.value = null
                if (sinCambios) finalizarVenta()
                _avisoDeVales.value = if (sinCambios) PARTE_SIN_RED_VENTA_PAGADA else PARTE_SIN_RED_PAGADA_CON_ALGO_MAS
            }
            // El servidor dice lo mismo que el carrito (un centavo de tolerancia): el resto se cobra contra esa orden.
            saldo == null || kotlin.math.abs(saldo - vigente.totalCents) <= 1 -> {
                _pendingSplitOrder.value = vigente.copy(orderId = subida.orderId, parteEnCola = null)
                _estadoDeParte.value = null
            }
            // Debe otra cosa: no se cobra solo. El cajero lo ve y decide (vaciar ⇒ el resto es otra venta).
            else -> {
                _pendingSplitOrder.value = vigente.copy(orderId = subida.orderId)
                _estadoDeParte.value = com.avoqado.pos.payment.data.EstadoDeParte.SaldoDistinto(subida.orderId, saldo)
            }
        }
        Log.d("🛒", "D1: la parte sin red subió (orden ${subida.orderId}, saldo $saldo, pagada ${subida.pagada})")
    }

    /**
     * D1: el mensaje que ve el cajero al tocar Cobrar mientras la parte guardada sin red no sube. null = se puede cobrar.
     * «Por revisar» no se arregla esperando: lo resuelve un gerente (Codex r2 #6).
     */
    fun mensajeDeParteEnEspera(): String? {
        val pendiente = _pendingSplitOrder.value ?: return null
        if (pendiente.parteEnCola == null) return null
        return when (val estado = _estadoDeParte.value) {
            com.avoqado.pos.payment.data.EstadoDeParte.PorRevisar -> PARTE_SIN_RED_POR_REVISAR
            is com.avoqado.pos.payment.data.EstadoDeParte.SaldoDistinto -> parteSinRedSaldoDistinto(pendiente.totalCents, estado.saldoCents)
            else -> PARTE_SIN_RED_SUBIENDO
        }
    }

    /** D1: el cobro no se abre mientras la parte guardada sin red no sube; lo dice el aviso. true = detenerse. */
    fun detenerCobroPorParteEnEspera(): Boolean {
        val mensaje = mensajeDeParteEnEspera() ?: return false
        _avisoDeVales.value = mensaje
        return true
    }

    /** D1: «Dividir» en tienda sin conexión no abre la hoja; lo dice el aviso. true = detenerse. */
    fun detenerDividirSinConexion(): Boolean {
        if (hayConexionConAvoqado()) return false
        _avisoDeVales.value = DIVIDIR_SIN_CONEXION
        return true
    }

    /** D1: «Dividir» en tienda pide conexión con Avoqado (founder «B»). Sin monitor (pruebas) se da por conectado. */
    fun hayConexionConAvoqado(): Boolean =
        connectivityMonitor?.let { it.isConnected.value && it.isServerReachable.value } ?: true

    /** Aviso de vales (rojo, no el de impuesto): un candado de D5 o del rediseño r7 que el cajero tiene que leer. */
    private val _avisoDeVales = MutableStateFlow<String?>(null)
    val avisoDeVales: StateFlow<String?> = _avisoDeVales.asStateFlow()

    fun consumeAvisoDeVales() {
        _avisoDeVales.value = null
    }

    /** Regla 10: la venta de vales con abono que el carrito no trae (el aviso fijo «Cobrar saldo»), o `null`. */
    val saldoDeVales: StateFlow<SaldoDeVales?> by lazy {
        kotlinx.coroutines.flow.combine(
            areaTicketRepository.session.checkout,
            _pendingSplitOrder,
            _cartState,
        ) { sesion, _, cart ->
            val venta = sesion?.takeIf { it.tieneAbono() } ?: return@combine null
            val orden = venta.order ?: return@combine null
            if (traeLaVentaDeVales(venta, cart)) return@combine null
            val saldo = orden.remainingBalance.moneyToCents().takeIf { it > 0 }
                ?: (orden.total.moneyToCents() - orden.paidAmount.moneyToCents()).coerceAtLeast(0)
            SaldoDeVales(orderId = orden.id, saldoCents = saldo)
        }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, null)
    }

    /**
     * Regla 10. «Cobrar saldo»: relee la venta (si hay red) y, con el carrito vacío, la siembra como «Saldo pendiente» que
     * continúa su orden. Con otra venta en el carrito pide terminarla primero; nunca la mezcla ni la borra.
     */
    fun cobrarSaldoDeVales() {
        viewModelScope.launch {
            if (!_cartState.value.isEmpty) {
                _avisoDeVales.value = VALES_SALDO_CON_OTRA_VENTA
                return@launch
            }
            // F3 (vales-ciclo, Codex r3): la venta y el negocio de ESTE toque. Si mientras se relee el cajero agrega algo y vacía, el
            // carrito vuelve a estar vacío pero ya es otra venta: el saldo no se siembra en ella (el aviso sigue para tocarlo otra vez).
            val ventaAlTocar = _cartState.value.identidadDeVenta
            val negocioAlTocar = secureStorage.venueId
            val releida = runCatching { areaTicketRepository.refresh() }.getOrNull()
            if (releida != null && releida.status == "PAID") {
                _avisoDeVales.value = VALES_SALDO_YA_COBRADO
                return@launch
            }
            val venta = ventaDeValesConAbono() ?: return@launch
            val orden = venta.order ?: return@launch
            if (!_cartState.value.isEmpty) {
                _avisoDeVales.value = VALES_SALDO_CON_OTRA_VENTA
                return@launch
            }
            if (_cartState.value.identidadDeVenta != ventaAlTocar || secureStorage.venueId != negocioAlTocar) return@launch
            val saldo = orden.remainingBalance.moneyToCents().takeIf { it > 0 }
                ?: (orden.total.moneyToCents() - orden.paidAmount.moneyToCents()).coerceAtLeast(0)
            if (saldo <= 0) return@launch
            addCustomAmount(name = "Saldo pendiente", amountCents = saldo)
            markPendingSplitOrder(orden.id)
        }
    }

    /**
     * Resuelve —y si hace falta ROMPE— la continuidad, justo antes de cobrar, y
     * **congela** el resultado en [chargingAgainstOrderId] para que el cobro
     * abierto sobreviva a que se recree la Activity (girar la tablet).
     *
     * Se valida contra el carrito REAL en este momento y no en cada toque, para
     * que un producto agregado por error y quitado enseguida no mate la venta.
     *
     * 🔴 El vínculo sólo vale si el carrito sigue siendo lo que la orden ya
     * tiene registrado: sin renglones nuevos y sin haber crecido en dinero. Ojo
     * con lo segundo: `addProduct` FUSIONA en la línea existente, así que
     * mirar sólo los ids dejaría pasar una cantidad que subió de 1 a 2 y
     * cobraríamos dos artículos contra una orden que sólo tiene uno.
     *
     * 🔴 Tablero D3 (21-oct): el vínculo ya NO se rompe. Romperlo cobraba lo agregado como venta nueva llevándose el saldo
     * de la vieja, que seguía debiéndolo. La pantalla no abre el cobro ([detenerCobroDeVentaCambiada]); si algo llegara
     * aquí con el carrito cambiado, el cobro no continúa ninguna orden y el vínculo sigue para cuando se quite lo agregado.
     */
    fun resolvePendingSplitOrderForCharge(): String? {
        val pending = _pendingSplitOrder.value
        // D1: mientras su parte sin red no sube (o el saldo no cuadró), no hay orden que continuar (Codex r2 #2).
        if (pending == null || pending.parteEnCola != null) {
            chargingAgainstOrderId = null
            return null
        }
        if (!carritoCambioDesde(pending, _cartState.value)) {
            chargingAgainstOrderId = pending.orderId
            return pending.orderId
        }
        chargingAgainstOrderId = null
        Log.w("🛒", "Split de mostrador: el carrito cambió; el cobro no se abre y el vínculo con ${pending.orderId} sigue")
        return null
    }

    // MARK: - Referral capture (Plan 5B)

    /**
     * Plan gate (REFERRAL_PROGRAM, Pro): when false the cart's referral
     * section renders a compact teaser instead of the capture input.
     * Fail-open when the plan is unknown.
     */
    val referralPlanAllowed: Boolean
        get() = planManager.hasFeature("REFERRAL_PROGRAM")

    /** Cliente atado a la venta: el `id` es lo que viaja al server, `name` lo que se pinta. */
    data class SelectedCustomer(val id: String, val name: String)

    /**
     * 🔴 El cliente de esta venta. Vive en el ViewModel —y no en un `remember` de
     * `CheckoutScreen`— **para sobrevivir a que se recree la Activity**, por la
     * MISMA razón que [chargingAgainstOrderId] y [pendingPackGrant]: girar la
     * tablet, que Android entre en modo oscuro al anochecer, o un cambio de
     * idioma/tamaño de fuente recrean `MainActivity` (no declara `configChanges`)
     * y un `remember` pelón vuelve a null. El carrito sobrevive, el cliente no: la
     * orden nacía SIN cliente y se perdían lealtad, CFDI e historial de esa
     * persona. Tercer caso del mismo patrón en esta pantalla.
     *
     * Ojo: el bug NO era sólo que la copia frágil se leyera al armar la orden.
     * La pantalla espejaba su `remember` hacia acá con un `LaunchedEffect`, así que
     * al recrearse **también borraba esta copia** (`setSelectedCustomer(null)`)
     * antes de que nadie la leyera. Por eso ya no hay espejo: hay una sola copia,
     * ésta, y la pantalla la lee con `collectAsState()`.
     *
     * Guarda también el NOMBRE porque si no la pantalla diría "Agregar cliente"
     * mientras la venta sí lleva cliente — igual de confuso que perderlo. Es exacto
     * lo que hace iOS, que nunca tuvo este bug: `CartViewModel.attachedCustomerId`
     * + `customerName`, los dos en el carrito.
     *
     * 🔴 A diferencia de los otros dos, éste SÍ es un `StateFlow`: la pantalla lo
     * pinta (encabezado del carrito, créditos del cliente) y tiene que recomponerse
     * cuando el cajero elige a alguien.
     */
    private val _selectedCustomer = MutableStateFlow<SelectedCustomer?>(null)
    val selectedCustomer: StateFlow<SelectedCustomer?> = _selectedCustomer.asStateFlow()

    /**
     * 🔴 El premio de cartilla no quedó como lo anunció el carrito al diferir la venta («pagar
     * después»): la cuenta abierta quedó por otro monto. null = nada que decir.
     */
    private val _avisoDelPremio = MutableStateFlow<String?>(null)
    val avisoDelPremio: StateFlow<String?> = _avisoDelPremio.asStateFlow()

    fun descartarAvisoDelPremio() {
        _avisoDelPremio.value = null
    }

    /// True when a class-seed was skipped because the cart already links a
    /// different reservation — the UI shows a message so the class isn't
    /// silently dropped.
    private val _seedConflict = MutableStateFlow(false)
    val seedConflict: StateFlow<Boolean> = _seedConflict.asStateFlow()
    fun clearSeedConflict() { _seedConflict.value = false }

    private val _referralCode = MutableStateFlow("")
    val referralCode: StateFlow<String> = _referralCode.asStateFlow()

    private val _referralValidation = MutableStateFlow<ReferralCaptureUiState>(ReferralCaptureUiState.Idle)
    val referralValidation: StateFlow<ReferralCaptureUiState> = _referralValidation.asStateFlow()

    init {
        // 🔴 DINERO (D1, 20-oct). Los renglones de vale son la copia del apartado del servidor: cuando el apartado se suelta
        // («Cancelar» en el cobro, vencido o pagado), salen del carrito. Si se quedaban, el siguiente cobro los vendía como
        // importe libre y el vale quedaba sin pagar. Sólo la transición vivo → nada: al abrir no hay apartado todavía.
        var habiaApartado = false
        areaTicketRepository.session.checkout
            .onEach { apartado ->
                if (habiaApartado && apartado == null) {
                    _cartState.update { state -> state.copy(items = state.items.filter { it.areaTicketId == null }) }
                }
                habiaApartado = apartado != null
            }
            .launchIn(viewModelScope)
        serviceCoursesRepository?.let { repository -> viewModelScope.launch {
            repository.courses.collect { freezePreparationForPendingProducts() }
        } }
        // Clear cart when venue changes (like iOS)
        viewModelScope.launch {
            authRepository.venueSwitched.collect {
                // 🔴 `finalizarVenta()`, no `clearCart()`: el cliente es de ESTE local.
                // Dejarlo puesto hacía que la primera orden del local B naciera con un
                // `customerId` del local A — un id de OTRO tenant dentro de la orden.
                Log.d("🛒", "Venue switched — clearing cart")
                finalizarVenta()
                fetchStaff()
            }
        }

        // Keep ActiveCartState in sync so other screens can check if cart has items
        viewModelScope.launch {
            _cartState.collect { state ->
                activeCartState.update(state.itemCount, state.totalDisplay)
                // Espejo a la pantalla del cliente (POS de doble pantalla): el
                // cliente ve su carrito en vivo mientras el cajero teclea.
                // No-op en equipos de una sola pantalla.
                customerDisplay.showCart(state)
            }
        }
    }

    /**
     * Re-sincroniza la pantalla del cliente con el carrito ACTUAL. Se llama al
     * cerrar el pago: si el carrito quedó vacío vuelve al logo del negocio, si
     * quedó saldo muestra ese carrito. Sin esto "Gracias" se quedaba pegado —
     * el StateFlow del carrito deduplica y no re-emite un vacío que ya era vacío.
     */
    fun refreshCustomerDisplay() {
        customerDisplay.showCart(_cartState.value)
    }

    val products = productsRepository.products
    val categories = productsRepository.categories
    val isLoading = productsRepository.isLoading
    val savedCarts = savedCartsRepository.savedCarts

    private val _selectedCategoryId = MutableStateFlow<String?>(null)
    val selectedCategoryId: StateFlow<String?> = _selectedCategoryId.asStateFlow()

    val filteredProducts: StateFlow<List<Product>> = combine(
        productsRepository.products,
        _selectedCategoryId,
    ) { products, categoryId ->
        if (categoryId == null) products
        else products.filter { it.categoryId == categoryId }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    /** Seam de pruebas: dónde se calculan las familias y la búsqueda. En producción, fuera del hilo principal. Molde: `TerminalPaymentService.io`. */
    @androidx.annotation.VisibleForTesting
    internal var calculoDeCatalogo: CoroutineDispatcher = Dispatchers.Default

    /**
     * Variantes (spec 2026-10-14 §11): las familias VÁLIDAS del catálogo completo, una vez por cambio del catálogo y fuera del hilo
     * principal (Codex 6, meta 5,000 variantes). La cuadrícula y la búsqueda las colocan en O(n); el catálogo sigue PLANO (el
     * escáner, promociones y merma buscan por id ahí).
     */
    val familias: StateFlow<Map<String, Familia>> = productsRepository.products
        .map { withContext(calculoDeCatalogo) { familiasDe(it) } }
        // Valor inicial = lo ya cargado: cada mesa y el cobro crean un ViewModel nuevo y el primer cuadro no puede pintar las variantes sueltas.
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), familiasDe(productsRepository.products.value))

    /**
     * Q6: por nombre sale la familia (una vez); sólo por código (SKU, barcode, GTIN), la variante. La lógica vive en `Familias.kt`.
     * Se calcula fuera del hilo principal (5,000 variantes por tecla) y por eso lleva el texto que la produjo: la vista decide
     * «Sin resultados» sólo con los resultados de lo que está escrito AHORA (ver [Busqueda]).
     */
    val busqueda: StateFlow<Busqueda> = combine(
        productsRepository.products,
        familias,
        _searchQuery,
    ) { ps, fs, q -> Busqueda(q, withContext(calculoDeCatalogo) { buscarEnCatalogo(ps, fs, q) }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Busqueda())

    val searchResults: StateFlow<List<ResultadoDeBusqueda>> = busqueda
        .map { it.resultados }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            productsRepository.fetchProducts()
            discountsRepository.fetchDiscounts()
            fetchStaff()
        }
    }

    private fun defaultCartState(): CartState {
        val loggedInStaffName = listOfNotNull(secureStorage.userFirstName, secureStorage.userLastName)
            .joinToString(" ")
            .trim()
            .ifEmpty { secureStorage.userEmail ?: "Staff" }
        val storedStaffId = secureStorage.selectedStaffIdForCurrentVenue
        val storedStaffName = secureStorage.selectedStaffNameForCurrentVenue

        return CartState(
            selectedStaffId = storedStaffId ?: secureStorage.userId.orEmpty(),
            selectedStaffName = storedStaffName ?: loggedInStaffName,
        )
    }

    fun fetchStaff() {
        viewModelScope.launch {
            _isStaffLoading.value = true
            _staffError.value = null
            staffRepository.getActiveStaff().fold(
                onSuccess = { todos ->
                    // Vendedor = roles con la perilla "aparece como vendedor"
                    // prendida (default todos; el venue apaga p.ej. "Investor"
                    // desde el editor de roles). Las pantallas de reservas
                    // siguen usando la lista completa.
                    val staff = todos.soloVendedores()
                    _staffOptions.value = staff
                    val selectedId = _cartState.value.selectedStaffId
                    val selected = staff.firstOrNull { it.id == selectedId }
                    if (selected != null && selected.fullName != _cartState.value.selectedStaffName) {
                        selectStaff(selected.id, selected.fullName)
                    } else if (selectedId.isNotBlank() && selected == null) {
                        secureStorage.clearSelectedStaffForCurrentVenue()
                        _cartState.value = defaultCartState()
                        // El aviso era del carrito que se acaba de vaciar (igual que en `clearCart()`; A3 m1).
                        _avisoDelCarrito.value = null
                        // Segundo reemplazo directo de `_cartState` que no pasa por
                        // `clearCart()`: el vínculo con la venta a medio cobrar se
                        // limpia aquí por la misma razón que en `restoreSavedCart`.
                        _pendingSplitOrder.value = null
                        // 🔴 Y el cliente también: este camino YA se comportaba como
                        // fin de venta (vacía el carrito y suelta el vínculo), sólo le
                        // faltaba soltarlo. Pasa cuando dan de baja al mesero
                        // seleccionado desde el dashboard: el carrito se vacía solo y
                        // el cliente se quedaba en el encabezado, así que la venta
                        // siguiente —de otra persona— nacía con su id.
                        setSelectedCustomer(null)
                    }
                },
                onFailure = { error ->
                    _staffError.value = error.message ?: "No se pudo cargar staff"
                },
            )
            _isStaffLoading.value = false
        }
    }

    fun selectStaff(staffId: String, staffName: String) {
        val normalizedStaffName = staffName.ifBlank { "Staff" }
        secureStorage.saveSelectedStaffForCurrentVenue(staffId, normalizedStaffName)
        _cartState.update { state ->
            state.copy(
                selectedStaffId = staffId,
                selectedStaffName = normalizedStaffName,
            )
        }
    }

    fun refreshProducts() {
        viewModelScope.launch { productsRepository.fetchProducts() }
    }

    fun selectCategory(categoryId: String?) {
        _selectedCategoryId.value = categoryId
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    // MARK: - Walk-in class seed

    /**
     * Consumes a pending walk-in class seed (set by ClassSessionDetailViewModel)
     * and drops the class product into the current sale — mirroring how Square's
     * register loads a service into the open sale.
     *
     * One-shot (the seed clears itself on read), so it's safe to call on every
     * Checkout entry. Adds to the existing cart rather than clearing it, like
     * Square adds a service to the current sale. The reservationId is stored on
     * the cart so the resulting order links back to the reservation.
     */
    /**
     * TABLE_SERVICE — when arriving at Cobrar with a PAYING table session, seed
     * the cart with ONE "Cuenta Mesa N" line for the order total so the NORMAL
     * payment flow (tips, cash/terminal, split) charges the right amount. The
     * PaymentFlowViewModel seam then pays the EXISTING table order instead of
     * creating a new one. Idempotent: re-arriving with the same session doesn't
     * duplicate the line.
     */
    fun consumePendingTableCobrar() {
        val session = tableSession.current() ?: return
        if (session.mode != com.avoqado.pos.tables.data.TableSession.Mode.PAYING) return
        val label = "Cuenta Mesa ${session.tableNumber}"
        val items = _cartState.value.items
        if (items.any { it.name == label }) return
        // Mid-split re-entry: the cart already carries the live remainder line
        // ("Saldo pendiente") — never wipe it and re-seed the original total.
        if (items.any { it.name == "Saldo pendiente" }) return
        clearCart()
        // session.totalCents tracks the REMAINING balance after partial
        // payments (updateRemaining), so an empty-cart re-seed charges exactly
        // what's still owed.
        addCustomAmount(name = label, amountCents = session.totalCents)
    }

    fun consumePendingClassSeed() {
        val seed = classCheckoutSeed.consume() ?: return
        viewModelScope.launch {
            if (productsRepository.products.value.isEmpty()) {
                productsRepository.fetchProducts()
            }
            val product = productsRepository.products.value.firstOrNull { it.id == seed.productId }
            if (product == null) {
                Log.w("🛒", "Class seed product ${seed.productId} not in catalog — cannot seed cart")
                return@launch
            }
            // Don't clobber a different reservation already linked to the cart:
            // seeding class B into a cart that already carries class A's
            // reservationId would leave the sale linked to only one of them.
            val existingResId = _cartState.value.reservationId
            if (existingResId != null && seed.reservationId != null && existingResId != seed.reservationId) {
                Log.w("🛒", "Cart already linked to reservation $existingResId — skipping seed for ${seed.reservationId}")
                _seedConflict.value = true
                return@launch
            }
            addProductWithModifiers(product, quantity = seed.quantity, modifiers = emptyList())
            seed.reservationId?.let { resId ->
                _cartState.update { it.copy(reservationId = resId) }
            }
            Log.d("🛒", "Seeded class ${product.name} x${seed.quantity} from walk-in flow")
        }
    }

    // MARK: - Cart Operations

    private fun immediateServiceCourse() = serviceCoursesRepository?.immediateSnapshot()?.takeIf {
        secureStorage.venueType?.uppercase() in setOf("RESTAURANT", "CAFE", "HOTEL_RESTAURANT")
    }

    private fun freezePreparationForPendingProducts() {
        if (_pendingSplitOrder.value != null) return
        val immediate = immediateServiceCourse() ?: return
        val courses = serviceCoursesRepository?.courses?.value.orEmpty()
        _cartState.update { state -> state.copy(items = state.items.map { item ->
            if (item.type !is CartItemType.ProductItem || item.locked || item.areaTicketLineId != null || item.serviceCourse?.preparationVersion == 1) item
            else item.copy(serviceCourse = item.serviceCourse?.let { course -> course.copy(preparationVersion = 1,
                sortOrder = course.sortOrder ?: courses.indexOfFirst { it.id == course.id }.takeIf { it >= 0 } ?: 31) } ?: immediate)
        }) }
    }

    /**
     * Fusión de dos `catalogAt` (ISO 8601; `a` = la que YA tenía la línea). Una válida le gana a nula o ilegible; entre dos
     * válidas, la más nueva (como `Instant`); si ninguna es válida se queda la que había (`a`), nunca la nueva. Espejo de iOS.
     */
    private fun catalogAtMasNueva(a: String?, b: String?): String? {
        val ia = a?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }
        val ib = b?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }
        return when {
            ia == null && ib == null -> a
            ia == null -> b
            ib == null -> a
            ib.isAfter(ia) -> b
            else -> a
        }
    }

    fun addProduct(product: Product) {
        // Agotado AVISA, nunca bloquea (Square-parity 2026-08-12): el registro
        // del sistema puede estar desfasado y el producto sí existir en el
        // anaquel. El stock queda en negativo como señal de descuadre.
        avisarSiAgotado(product)
        _cartState.update { state ->
            // Check if same product without modifiers already in cart
            // 🔴 Una línea de promoción JAMÁS se fusiona: si el cliente pide una
            // cerveza suelta después de aplicar el 2x1, fusionarla subiría la
            // línea de la PROMOCIÓN a cantidad 3 — se cobraría $75 en vez de
            // $100 (el local pierde $25), la instancia viajaría con
            // `quantity = 3` (que el server rechaza junto a `promotionRef`) y el
            // inventario descontaría 3 unidades bajo un 2x1. Los guards de
            // `esLineaFija` no cubren esta puerta: el merge no pasa por ellos.
            // Tras un refresco del catálogo, otra unidad con OTRO precio es otra línea: la vieja conserva el precio
            // que el cliente ya vio. Con el MISMO precio se fusiona y se queda la hora más nueva (ese precio era
            // real en la lista más nueva); exigir la misma hora partiría «Café ×1, Café ×1» en cada refresco.
            val existingIndex = state.items.indexOfFirst {
                it.type is CartItemType.ProductItem &&
                    (it.type as CartItemType.ProductItem).productId == product.id &&
                    it.selectedModifiers.isEmpty() &&
                    !it.isCortesia &&
                    it.promotionInstanceId == null &&
                    // D7: tampoco con una pesada (es una venta aparte; fusionada quedaría cantidad 2 con el total de una).
                    it.weightKg == null &&
                    it.unitPrice == product.priceInCents
            }

            if (existingIndex >= 0 && !product.hasModifiers) {
                val updated = state.items.toMutableList()
                val existing = updated[existingIndex]
                updated[existingIndex] = existing.copy(
                    quantity = existing.quantity + 1,
                    catalogAt = catalogAtMasNueva(existing.catalogAt, product.catalogAt),
                )
                state.copy(items = updated)
            } else {
                val newItem = CartItem(
                    type = CartItemType.ProductItem(product.id),
                    name = product.name,
                    unitPrice = product.priceInCents,
                    imageUrl = product.imageUrl,
                    colorHex = product.color,
                    categoryId = product.categoryId,
                    serviceCourse = immediateServiceCourse(),
                    catalogAt = product.catalogAt,
                )
                state.copy(items = state.items + newItem)
            }
        }
        Log.d("🛒", "Added product: ${product.name}")
    }

    fun addProductWithModifiers(
        product: Product,
        quantity: Int = 1,
        modifiers: List<SelectedModifier>,
        note: String? = null,
        isCortesia: Boolean = false,
        cortesiaReason: String? = null,
        priceAdjustment: Int? = null,
        /**
         * 🔴 El descuento COMPLETO, no su id. La línea congela `type` y `value`
         * para poder cobrar exactamente lo mismo que el server va a registrar;
         * con sólo el id, el POS cobraba precio de lista y la orden quedaba
         * rebajada — ver [CartItem.itemDiscountType].
         */
        discount: Discount? = null,
    ) {
        // Ver addProduct: agotado avisa, nunca bloquea.
        avisarSiAgotado(product)
        val newItem = CartItem(
            type = CartItemType.ProductItem(product.id),
            name = product.name,
            unitPrice = product.priceInCents,
            quantity = quantity,
            imageUrl = product.imageUrl,
            colorHex = product.color,
            categoryId = product.categoryId,
            selectedModifiers = modifiers,
            itemNote = note,
            isCortesia = isCortesia,
            cortesiaReason = cortesiaReason,
            priceAdjustment = priceAdjustment,
            itemDiscountId = discount?.id,
            itemDiscountType = discount?.type,
            itemDiscountValue = discount?.value,
            itemDiscountName = discount?.name,
            serviceCourse = immediateServiceCourse(),
            catalogAt = product.catalogAt,
        )
        _cartState.update { it.copy(items = it.items + newItem) }
        Log.d("🛒", "Added product with modifiers: ${product.name} x$quantity (${modifiers.size} mods)")
    }

    /**
     * Venta por peso (báscula): agrega una línea pesada. Cada pesada es SIEMPRE una línea NUEVA
     * — jamás se fusiona con otra (D9): dos pesadas del mismo jamón (0.435 y 0.512 kg) son ventas
     * distintas. quantity queda fija en 1; [unitPrice] guarda el precio POR KG y el total de línea
     * lo calcula [CartItem.totalPrice] (round(weightKg × precio/kg)). El aviso de stock lo da el
     * panel de captura (no bloquea aquí — el backend es la autoridad al cobrar).
     */
    fun addProductByWeight(product: Product, weightKg: Double) {
        if (weightKg <= 0) return
        val newItem = CartItem(
            type = CartItemType.ProductItem(product.id),
            name = product.name,
            unitPrice = product.priceInCents, // precio POR KG
            quantity = 1,
            imageUrl = product.imageUrl,
            colorHex = product.color,
            categoryId = product.categoryId,
            weightKg = weightKg,
            serviceCourse = immediateServiceCourse(),
            catalogAt = product.catalogAt,
        )
        _cartState.update { it.copy(items = it.items + newItem) }
        Log.d("🛒", "Added weighted product: ${product.name} (${weightKg} kg)")
    }

    /** Cumplimiento de la venta rápida (selector en el header del carrito). */
    fun setOrderType(orderType: String) {
        _cartState.update { it.copy(orderType = orderType) }
    }

    fun addCustomAmount(name: String, amountCents: Int) {
        val item = CartItem(
            type = CartItemType.CustomAmount,
            name = name,
            unitPrice = amountCents,
        )
        _cartState.update { it.copy(items = it.items + item) }
    }

    /** Add a prepaid credit pack (membresía) to the cart. Charges like a line item; on
     *  payment success the credits are granted to the attached customer. A customer is
     *  required to complete the charge (enforced at checkout). */
    private var lastPackAddId: String? = null
    private var lastPackAddAt: Long = 0

    fun addCreditPack(pack: com.avoqado.pos.articles.data.model.CreditPack) {
        // Debounce accidental double-taps on the grid tile: two identical pack
        // lines within 1.5s is a mis-tap (and would double-charge + double-grant).
        val now = System.currentTimeMillis()
        if (lastPackAddId == pack.id && now - lastPackAddAt < 1500) return
        lastPackAddId = pack.id
        lastPackAddAt = now
        val item = CartItem(
            type = CartItemType.CreditPack(pack.id),
            name = pack.name,
            subtitle = if (pack.creditCount > 0) "${pack.creditCount} créditos" else "Membresía",
            unitPrice = pack.price.aCentavos(),
        )
        _cartState.update { it.copy(items = it.items + item) }
    }

    /** True when the cart contains a credit-pack line (needs a customer to charge). */
    val hasCreditPack: Boolean get() = _cartState.value.items.any { it.type is CartItemType.CreditPack }

    // MARK: - Promociones (combos, paquetes, 2x1)

    /**
     * Mete una promoción al carrito: UNA línea por opción elegida, todas atadas
     * por el mismo `promotionInstanceId`.
     *
     * Reglas que el server impone y que aquí se respetan por construcción:
     * - **Un 2x1 entra como UNA línea de cantidad 2** (la `quantity` de la
     *   opción), porque la deducción de inventario del server multiplica por
     *   ella. No son dos líneas de 1.
     * - 🔴 **3 combos = 3 instancias distintas, NUNCA `quantity: 3`**: el server
     *   responde 400 con `quantity ≠ 1` junto a `promotionRef`. Por eso cada
     *   toque genera su propio UUID y la cantidad de una línea de promoción no
     *   se puede editar desde el carrito.
     * - **Media promoción no entra**: si a un grupo le falta su elección, no se
     *   agrega nada y se devuelve `false`.
     *
     * El precio de las líneas es el estimado local
     * ([preciosUnitariosDePromocion]) — que además es lo que se cobra en la
     * venta rápida. El precio del PEDIDO lo calcula el server al aplicar.
     *
     * @return `true` sólo si de verdad entró — la UI celebra con eso, así que un
     *   "¡Combo agregado!" nunca puede mentir.
     */
    fun aplicarPromocion(promotion: Promotion, selecciones: Map<String, String> = emptyMap(), times: Map<String, com.avoqado.pos.pos.data.model.ServiceCourseSnapshot> = emptyMap()): Boolean {
        val elegidas = opcionesElegidas(promotion, selecciones)
        if (elegidas.isNullOrEmpty()) {
            Log.w("🎁", "Promoción sin elección completa, no se agrega: ${promotion.name}")
            return false
        }
        if (times.isNotEmpty() && elegidas.any { times[it.grupo.id] == null }) return false
        val precios = preciosUnitariosDePromocion(promotion, elegidas)
        val instanceId = UUID.randomUUID().toString()
        val nuevas = elegidas.mapIndexed { index, elegida ->
            CartItem(
                type = CartItemType.ProductItem(elegida.opcion.productId),
                name = elegida.opcion.productName.ifBlank { promotion.name },
                // El carrito ya pinta `subtitle` bajo el nombre: es donde se lee
                // "Combo del día" sin volver al catálogo.
                subtitle = times[elegida.grupo.id]?.let { "${promotion.name} · ${it.label}" } ?: promotion.name,
                unitPrice = precios.getOrElse(index) { elegida.opcion.productPriceCents },
                quantity = elegida.opcion.quantity.coerceAtLeast(1),
                promotionInstanceId = instanceId,
                promotionName = promotion.name,
                promotionId = promotion.id,
                promotionGroupId = elegida.grupo.id,
                promotionOptionId = elegida.opcion.id,
                serviceCourse = times[elegida.grupo.id],
            )
        }
        _cartState.update { it.copy(items = it.items + nuevas) }
        freezePreparationForPendingProducts()
        Log.d("🎁", "Promoción aplicada: ${promotion.name} (${nuevas.size} líneas, instancia $instanceId)")
        return true
    }

    /**
     * Una promoción se quita COMPLETA: quitar una línea quita a todas sus
     * hermanas. Dejar media promoción en el carrito cobraría un combo a medias,
     * y el cajero no tendría cómo notarlo.
     */
    fun quitarPromocion(instanceId: String) {
        _cartState.update { state ->
            val restantes = state.items.filterNot { it.promotionInstanceId == instanceId }
            state.copy(
                items = restantes,
                reservationId = if (restantes.isEmpty()) null else state.reservationId,
            )
        }
        Log.d("🎁", "Promoción quitada: instancia $instanceId")
    }

    fun removeItem(itemId: String) {
        // 🔴 La promoción se quita completa venga de donde venga el borrado
        // (deslizar, anular artículos, panel de detalle). El aviso previo
        // ("Se quitará el combo completo") lo da la UI; esta red de seguridad
        // impide que quede media promoción aunque alguien no lo pregunte.
        val promotionInstanceId = _cartState.value.items.firstOrNull { it.id == itemId }?.promotionInstanceId
        if (promotionInstanceId != null) {
            quitarPromocion(promotionInstanceId)
            return
        }
        val areaTicketId = _cartState.value.items.firstOrNull { it.id == itemId }?.areaTicketId
        if (areaTicketId != null) {
            // Sin apartado no hay nada que soltar en el servidor: el vale se quita aquí. Si no, el borrado fallaba y el
            // renglón se quedaba para siempre después de que el cobro pidiera volver a escanearlo (D1, 20-oct).
            if (areaTicketRepository.session.current() == null) {
                _cartState.update { state -> state.copy(items = state.items.filter { it.areaTicketId != areaTicketId }) }
                return
            }
            val venta = _cartState.value.identidadDeVenta
            viewModelScope.launch {
                runCatching { areaTicketRepository.removeTicket(areaTicketId) }
                    .onSuccess { replaceAreaTicketLines(it, venta) }
                    .onFailure { Log.e("🎟️", "No se pudo quitar el vale: ${it.message}") }
            }
            return
        }
        _cartState.update { state ->
            val updatedItems = state.items.filter { it.id != itemId }
            // When an item removal leaves the cart empty, clear the reservationId.
            // The cart doesn't track which line was the seeded class, so clearing
            // on empty prevents a stale link to an unrelated order; a partially-emptied
            // cart preserves the link.
            state.copy(
                items = updatedItems,
                reservationId = if (updatedItems.isEmpty()) null else state.reservationId
            )
        }
    }

    /**
     * Líneas que no se editan sueltas: las de un vale (precio del server) y las
     * de una promoción. En una promoción la cantidad es parte del contrato —
     * el 2x1 ES una línea de 2— y subirla mandaría `quantity ≠ 1` junto a
     * `promotionRef`, que el server rechaza. Para vender 3 combos se tocan 3
     * veces las tarjetas: 3 instancias.
     */
    private fun esLineaFija(itemId: String): Boolean =
        _cartState.value.items.any { it.id == itemId && (it.locked || it.isPromotionLine) }

    /**
     * Quitar un renglón a mano (deslizar, «Anular artículos», cantidad a cero). 🔴 D5 (Codex r5 #3): en una venta de vales con
     * abono, quitar su «Saldo pendiente» la vaciaba igual que «Vaciar»: no se deja y se dice por qué. Los cobros por artículo
     * quitan lo pagado con [removeItem], sin pasar por aquí.
     */
    fun quitarPorElCajero(itemId: String): Boolean {
        // Lo que se agregó encima sí se quita (es la salida de la regla 11); lo de la venta con abono, no.
        if (vaciarBloqueadoPorAbono() && _pendingSplitOrder.value?.itemIds?.contains(itemId) == true) {
            _avisoDeVales.value = com.avoqado.pos.payment.presentation.VALES_CON_ABONO
            return false
        }
        removeItem(itemId)
        return true
    }

    /**
     * 🔴 D7: un renglón por peso es UNA pesada, cantidad 1 siempre. Su total ya ignora la cantidad y el SERVIDOR rechaza
     * `quantity ≠ 1` junto a `weightQuantity`. Dos pesadas = dos renglones ([addProductByWeight]). La pantalla ya oculta el
     * selector; esto lo cierra para cualquier otro camino. **iOS** pone la misma regla en su `CartViewModel.items`.
     */
    fun updateQuantity(itemId: String, newQuantity: Int) {
        if (esLineaFija(itemId)) return
        if (newQuantity <= 0) {
            quitarPorElCajero(itemId)
            return
        }
        _cartState.update { state ->
            state.copy(
                items = state.items.map {
                    if (it.id == itemId && it.weightKg == null) it.copy(quantity = newQuantity) else it
                },
            )
        }
    }

    fun incrementQuantity(itemId: String) {
        if (esLineaFija(itemId)) return
        _cartState.update { state ->
            state.copy(
                items = state.items.map {
                    // D7: una pesada no sube (ver [updateQuantity]).
                    if (it.id == itemId && it.weightKg == null) it.copy(quantity = it.quantity + 1) else it
                },
            )
        }
    }

    fun decrementQuantity(itemId: String) {
        if (esLineaFija(itemId)) return
        // Bajar de 1 es quitar el renglón: pasa por el mismo candado que deslizar (D5).
        if ((_cartState.value.items.find { it.id == itemId }?.quantity ?: 0) <= 1) {
            quitarPorElCajero(itemId)
            return
        }
        _cartState.update { state ->
            val item = state.items.find { it.id == itemId } ?: return@update state
            if (item.quantity <= 1) {
                val updatedItems = state.items.filter { it.id != itemId }
                // When an item removal leaves the cart empty, clear the reservationId.
                // The cart doesn't track which line was the seeded class, so clearing
                // on empty prevents a stale link to an unrelated order; a partially-emptied
                // cart preserves the link.
                state.copy(
                    items = updatedItems,
                    reservationId = if (updatedItems.isEmpty()) null else state.reservationId
                )
            } else {
                state.copy(
                    items = state.items.map {
                        if (it.id == itemId) it.copy(quantity = it.quantity - 1) else it
                    },
                )
            }
        }
    }

    fun applyOrderDiscount(discount: Discount?) {
        if (_cartState.value.items.any { it.locked }) return
        // Otro descuento es otra cuenta: el monto fijo del anterior ya no aplica.
        _cartState.update { it.copy(orderDiscount = discount, descuentoDeCuentaCongelado = null) }
    }

    fun setOrderNote(note: String?) {
        _cartState.update { it.copy(orderNote = note) }
    }

    fun markItemAsCortesia(itemId: String, reason: String?) {
        // Una promoción no se regala línea por línea: el server cobra la
        // promoción completa, así que poner la línea en $0 aquí cobraría algo
        // distinto de lo que queda en la orden. Se quita el combo y se da el
        // producto suelto de cortesía. (El guard es sólo de promoción: el de
        // `locked` no estaba aquí y no es de esta task cambiarlo.)
        if (_cartState.value.items.any { it.id == itemId && it.isPromotionLine }) return
        _cartState.update { state ->
            state.copy(
                items = state.items.map {
                    if (it.id == itemId) it.copy(isCortesia = true, cortesiaReason = reason)
                    else it
                },
            )
        }
        Log.d("🛒", "Marked item $itemId as cortesia")
    }

    fun updateItemNote(itemId: String, note: String?) {
        if (_cartState.value.items.any { it.id == itemId && it.locked }) return
        _cartState.update { state ->
            state.copy(
                items = state.items.map {
                    if (it.id == itemId) it.copy(itemNote = note) else it
                },
            )
        }
    }

    fun updateItemCortesia(itemId: String, isCortesia: Boolean, reason: String?) {
        if (esLineaFija(itemId)) return
        _cartState.update { state ->
            state.copy(
                items = state.items.map {
                    if (it.id == itemId) it.copy(isCortesia = isCortesia, cortesiaReason = reason)
                    else it
                },
            )
        }
    }

    fun updateItemPriceAdjustment(itemId: String, priceCents: Int?) {
        if (esLineaFija(itemId)) return
        _cartState.update { state ->
            state.copy(
                items = state.items.map {
                    if (it.id == itemId) it.copy(priceAdjustment = priceCents) else it
                },
            )
        }
    }

    fun saveCurrentCart(name: String? = null): Boolean {
        if (detenerPorPagarDespues()) return false
        val state = _cartState.value
        if (state.isEmpty || state.items.any { it.locked }) return false
        // D5 (Codex r3 #5): guardar también vaciaba la venta de vales con abono (y su saldo guardado se cobraba aparte).
        if (vaciarBloqueadoPorAbono()) return false
        // Tablero D3 (Codex r9): guardar soltaba el vínculo y, al restaurarlo, el saldo se cobraba dentro de otra venta.
        if (hayVentaAMedias()) return false

        val cartName = name ?: "Carrito ${
            com.avoqado.pos.core.util.VenueTimeZone.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        }"
        if (!savedCartsRepository.saveCart(comoCarritoGuardado(state, cartName, _selectedCustomer.value?.id))) return false
        // El cliente se fue CON el carrito guardado (`attachedCustomerId` arriba), así
        // que aquí la venta terminó: si no se suelta, se queda en el encabezado y la
        // venta siguiente nace con él.
        finalizarVenta()
        Log.d("🛒", "Cart saved as: $cartName")
        return true
    }

    /** La copia de «Carritos guardados» de [state]. La usan guardar el carrito y «Pagar después» rechazado con otra venta. */
    private fun comoCarritoGuardado(state: CartState, cartName: String, customerId: String?): SavedCart =
        SavedCart(
            id = state.carritoGuardadoId ?: UUID.randomUUID().toString(),
            name = cartName,
            // 🔴 D5 (Codex r6): se guarda con un vale de ESTA mercancía pedido sin respuesta: la copia nace marcada (D1) y
            // restaurarla pregunta; si no, después de «Recuperar» salía como segundo vale.
            cobroIniciadoEn = savedCartsRepository.savedCarts.value.firstOrNull { it.id == state.carritoGuardadoId }?.cobroIniciadoEn
                ?: System.currentTimeMillis().takeIf {
                    secureStorage.venueId?.let { v -> lineasDeValeEnPantalla()?.let { secureStorage.mercanciaDeValeEnDuda(v, it) } } == true
                },
            items = state.items.map { item ->
                SavedCartItem(
                    lineId = item.id,
                    weightKg = item.weightKg,
                    productId = when (val type = item.type) {
                        is CartItemType.ProductItem -> type.productId
                        is CartItemType.CustomAmount -> null
                        is CartItemType.CreditPack -> null
                    },
                    packId = (item.type as? CartItemType.CreditPack)?.packId,
                    name = item.name,
                    unitPrice = item.unitPrice,
                    quantity = item.quantity,
                    modifiers = item.selectedModifiers.map { mod ->
                        SavedModifier(
                            groupId = mod.groupId,
                            groupName = mod.groupName,
                            modifierId = mod.modifierId,
                            modifierName = mod.modifierName,
                            priceInCents = mod.priceInCents,
                        )
                    },
                    note = item.itemNote,
                    isCortesia = item.isCortesia,
                    cortesiaReason = item.cortesiaReason,
                    priceAdjustment = item.priceAdjustment,
                    itemDiscountId = item.itemDiscountId,
                    itemDiscountType = item.itemDiscountType,
                    itemDiscountValue = item.itemDiscountValue,
                    itemDiscountName = item.itemDiscountName,
                    promotionInstanceId = item.promotionInstanceId,
                    promotionName = item.promotionName,
                    promotionId = item.promotionId,
                    promotionGroupId = item.promotionGroupId,
                    promotionOptionId = item.promotionOptionId,
                    serviceCourse = item.serviceCourse,
                    catalogAt = item.catalogAt,
                )
            },
            orderDiscount = state.orderDiscount,
            orderNote = state.orderNote,
            orderTaxPercent = state.orderTaxPercent,
            reservationId = state.reservationId,
            attachedCustomerId = customerId,
            llavePagarDespues = state.llavePagarDespues,
        )

    fun clearCart() {
        if (areaTicketRepository.session.current() != null) {
            viewModelScope.launch {
                runCatching { areaTicketRepository.cancel() }
                    .onFailure { Log.e("🎟️", "No se pudo liberar la sesión al limpiar el carrito: ${it.message}") }
            }
        }
        _cartState.value = defaultCartState()
        _avisoDelCarrito.value = null
        // Referral capture state is per-order, so wiping the cart must wipe
        // the code + validation too. The discount referenced by the validation
        // already lives on cartState.orderDiscount and is dropped above.
        _referralCode.value = ""
        _referralValidation.value = ReferralCaptureUiState.Idle
        // 🔴 DINERO. Vaciar el carrito es el fin de la venta, así que el vínculo
        // con la orden a medio cobrar muere aquí. Es el camino por el que pasa
        // *casi* todo arranque de venta nueva (cobro completo, guardar carrito,
        // pagar después, vaciar, cambio de local).
        //
        // 🔴 Pero NO es el único: `restoreSavedCart` y el descarte de staff en
        // `fetchStaff` reemplazan `_cartState` a pelo sin pasar por aquí, y por
        // eso limpian el vínculo ellos mismos. Si agregas otro reemplazo directo
        // de `_cartState.value`, **límpialo ahí también** — esto no te cubre solo.
        //
        // Sólo el vínculo DURABLE: `chargingAgainstOrderId` (el cobro abierto) no
        // se toca aquí a propósito — ver su doc.
        //
        // El split lo vuelve a marcar DESPUÉS de dejar el saldo en el carrito.
        _pendingSplitOrder.value = null
        Log.d("🛒", "Cart cleared")
    }

    /**
     * 🔴 FIN DE VENTA: vacía el carrito **y suelta al cliente**.
     *
     * Existe porque `clearCart()` significa dos cosas distintas y mezclarlas cuesta
     * dinero: en un split de mostrador se llama **a media venta** para sustituir el
     * carrito por el "Saldo pendiente", y ahí el cliente TIENE que sobrevivir para la
     * parte 2. Por eso el cliente no se suelta dentro de `clearCart()` — se suelta
     * aquí, donde la venta de verdad se acaba.
     *
     * Es el mismo desdoble que ya se hizo con el vínculo de la orden
     * ([chargingAgainstOrderId] vs. el vínculo durable): dos ciclos de vida que se
     * veían iguales.
     *
     * **Úsalo en toda salida que termine la venta** — vaciar a mano, guardar el
     * carrito, cambiar de local, emitir un vale, pagar después. Dejar `clearCart()` a
     * secas en una de ésas deja al cliente pegado y la venta SIGUIENTE nace con él:
     * lealtad, CFDI e historial de quien no compró. En el cobro NO se usa: ese camino
     * pasa por [aplicarCobroConfirmado], que decide si la venta cerró o sigue viva.
     */
    fun finalizarVenta() {
        clearCart()
        setSelectedCustomer(null)
    }

    /**
     * El botón «Vaciar»: termina la venta y, 🔴 D2 (vales-ciclo), suelta la llave de emisión de vales. Es la salida que da el
     * aviso del 409 («… o vacíalo»), y sólo aquí: guardar el carrito o pagar después la conservan, así un vale cuya respuesta
     * se perdió vuelve con el mismo contenido y el servidor devuelve ESE vale en vez de crear otro.
     */
    fun vaciar() {
        if (detenerPorPagarDespues()) return
        secureStorage.venueId?.let { secureStorage.soltarLlaveDeEmisionDeVales(it) }
        finalizarVenta()
    }

    /**
     * J7 (vales-ciclo r0): termina la venta [venta] —la que emitió el vale— sólo si el carrito sigue siendo ella. Reimprimir o
     * guardar el PDF de un vale anterior con otra venta en pantalla no la vacía; sin venta conocida (tras reiniciar), nada.
     */
    fun finalizarVentaSiEs(venta: String?) {
        if (venta != null && venta == _cartState.value.identidadDeVenta) finalizarVenta()
    }

    /** En qué quedó la venta tras un cobro confirmado. Espeja 1:1 los tres caminos. */
    enum class RamaCobro {
        /** Split por producto: se pagaron renglones sueltos. */
        RENGLONES_PAGADOS,

        /** Quedó saldo: el carrito pasa a ser "Saldo pendiente". */
        QUEDA_SALDO,

        /** Pago completo. */
        PAGO_COMPLETO,
    }

    data class CobroAplicado(
        val rama: RamaCobro,
        /**
         * 🔴 La venta se ACABÓ: no queda saldo y el carrito quedó vacío. NO es lo
         * mismo que [RamaCobro.PAGO_COMPLETO]: un split **por producto** que paga
         * los últimos renglones cierra la venta desde [RamaCobro.RENGLONES_PAGADOS].
         */
        val ventaTerminada: Boolean,
        /**
         * 🔴 El referido capturable, **congelado antes de tocar el carrito**. Cerrar
         * la venta borra la validación, el código y el staff, y la captura corre
         * después en un `launch` asíncrono: sin esta foto se perdía en silencio y el
         * que refirió nunca recibía su crédito. `null` = no había referido válido.
         */
        val referralPendiente: ReferralPendiente?,
        val aplicadoAlCarritoActual: Boolean = true,
    )

    /**
     * 🔴 DINERO. Aplica al carrito el resultado de un cobro confirmado y decide si la
     * venta terminó. Vive aquí —y no en el Composable— porque es una decisión de
     * negocio: en la pantalla no se podía probar, y por eso el hueco de abajo pasó
     * desapercibido.
     *
     * **La venta terminó ⇔ no queda saldo Y el carrito quedó vacío.** Las dos
     * condiciones se cubren entre sí: sin la del saldo, quitar los renglones de un
     * combo podría vaciar el carrito con dinero todavía pendiente; sin la del
     * carrito, un split por producto que paga TODO se leería como venta viva.
     *
     * 🔴 El caso que se escapaba: el `when` evalúa `BYPRODUCT` PRIMERO, así que
     * cobrar por producto marcando todos los renglones —incluso sin ninguna parte
     * previa, la venta entera de un tirón— cae en esa rama con saldo 0. Soltar al
     * cliente sólo en la rama de pago completo dejaba al cliente pegado en **toda**
     * venta cobrada por producto que vaciara el carrito, y la venta siguiente nacía
     * con él.
     */
    fun aplicarCobroConfirmado(
        splitType: String?,
        paidItemIds: Set<String>,
        remainingBalanceCents: Int,
        /** Lo que el servidor confirmó del premio (0 = lo rechazó); null = la venta no llevaba premio. */
        premioConfirmadoCents: Int? = null,
        carritoGuardadoDelCobro: String? = _cartState.value.carritoGuardadoId,
        identidadDeVentaDelCobro: String? = null,
        /** Lo que el servidor corrigió en las líneas de esta venta. Vacío = no se toca ni el carrito ni el descuento. */
        cambiosDePrecio: List<CambioDePrecio> = emptyList(),
        /** El descuento de cuenta como quedó en la orden (fijo tras un 201 con cambios; null tras un 409 o sin descuento). */
        descuentoDeCuentaCongelado: DescuentoCongelado? = null,
        /** D7: `remainingBalanceCents` lo confirmó el SERVIDOR (no es la resta local). */
        saldoDelServidor: Boolean = false,
    ): CobroAplicado {
        // 🔴 Se fotografía ANTES de tocar nada: cerrar la venta borra la validación,
        // el código y el staff, y la captura corre después. Ver [ReferralPendiente].
        val identidadOriginal = _cartState.value.identidadDeVenta
        // El recibo retira su copia; una respuesta tardía no puede tocar la venta siguiente.
        carritoGuardadoDelCobro?.let {
            if (!savedCartsRepository.deleteCart(it)) Log.w("🛒", "No se confirmó el retiro de la copia marcada $it")
        }
        val esLaMismaVenta = identidadDeVentaDelCobro?.let { it == identidadOriginal }
            ?: (carritoGuardadoDelCobro == _cartState.value.carritoGuardadoId)
        if (!esLaMismaVenta) {
            return CobroAplicado(RamaCobro.PAGO_COMPLETO, false, null, aplicadoAlCarritoActual = false)
        }
        val referralPendiente = snapshotReferralPendiente()
        _cartState.update { it.copy(carritoGuardadoId = null) }
        // 🔴 «Se cobra el precio que vio el cliente»: el carrito de ESTA venta queda con el precio de la orden, y su descuento de
        // cuenta como quedó en ella. ANTES de quitar lo pagado: la huella es de la venta completa, y lo que quede es un subconjunto.
        if (cambiosDePrecio.isNotEmpty()) {
            _cartState.update {
                it.copy(items = aplicarCambiosDePrecio(it.items, cambiosDePrecio), descuentoDeCuentaCongelado = descuentoDeCuentaCongelado)
            }
        }
        // 🔴 El premio ya se canjeó (o no) sobre la ORDEN entera al crearla. Lo que quede por cobrar
        // descuenta lo que el servidor CONFIRMÓ, nunca un estimado nuevo sobre los renglones que quedan
        // (hallazgo P1 de Codex, 27-sep): un monto fijo con lo confirmado, o nada si lo rechazó.
        if (premioConfirmadoCents != null) {
            _cartState.update { estado ->
                estado.copy(
                    pendingStampReward = estado.pendingStampReward
                        ?.takeIf { premioConfirmadoCents > 0 }
                        ?.copy(tipo = "FIXED_AMOUNT", valor = premioConfirmadoCents / 100.0),
                )
            }
        }
        val esPorProducto = splitType == "BYPRODUCT" && paidItemIds.isNotEmpty()
        val rama = when {
            esPorProducto -> {
                paidItemIds.forEach { removeItem(it) }
                // 🔴 El servidor dice que ya no se debe nada: lo que quede en el carrito ya es parte de esa
                // orden PAGADA (pasa con un premio mayor que la parte cobrada). Dejarlo permitía cobrarlo
                // otra vez en una orden nueva (hallazgo P1 de Codex, ronda 2).
                if (remainingBalanceCents <= 0 && !_cartState.value.isEmpty) clearCart()
                // D7 (27-oct): el SERVIDOR confirmó un saldo que no es lo que valen los renglones que quedan (otra caja
                // abonó, la terminal quitó un descuento) o ya no queda renglón: el carrito enseña ESE saldo, en la misma
                // venta, para que se pueda cobrar. Sin confirmación (resta local) no se toca: es la de siempre.
                // D10 (Codex r1 #1): también con el saldo LOCAL: con la parte proporcional, saldo − parte ya es lo que falta, y
                // un descuento fijo de toda la cuenta se quedaría entero en los renglones que quedan.
                if (remainingBalanceCents > 0 && _cartState.value.totalCents != remainingBalanceCents) {
                    clearCart()
                    _cartState.update { it.copy(identidadDeVenta = identidadOriginal) }
                    addCustomAmount(name = "Saldo pendiente", amountCents = remainingBalanceCents)
                }
                RamaCobro.RENGLONES_PAGADOS
            }
            remainingBalanceCents > 0 -> {
                // El cliente sobrevive: `clearCart()` no lo toca, a propósito.
                clearCart()
                _cartState.update { it.copy(identidadDeVenta = identidadOriginal) }
                addCustomAmount(name = "Saldo pendiente", amountCents = remainingBalanceCents)
                RamaCobro.QUEDA_SALDO
            }
            else -> {
                clearCart()
                RamaCobro.PAGO_COMPLETO
            }
        }

        val ventaTerminada = remainingBalanceCents <= 0 && _cartState.value.isEmpty
        if (ventaTerminada) {
            if (_cartState.value.identidadDeVenta == identidadOriginal) {
                _cartState.update { it.copy(identidadDeVenta = UUID.randomUUID().toString()) }
            }
            // 🔴 Arrastrar al cliente a la venta siguiente es PEOR que perderlo:
            // perderlo deja un dato faltante; arrastrarlo atribuye lealtad, CFDI e
            // historial a quien no compró.
            setSelectedCustomer(null)
            Log.d("🛒", "Venta cerrada ($rama) — se suelta el cliente")
        }
        return CobroAplicado(rama = rama, ventaTerminada = ventaTerminada, referralPendiente = referralPendiente)
    }

    /**
     * Conserva el checkout normal para cualquier SKU/GTIN. Sólo los códigos del
     * namespace de vales se resuelven en servidor para evitar colisiones y dobles claims.
     */
    fun restoreAreaTicketSession() {
        viewModelScope.launch {
            // 🔴 Restaurar es BEST-EFFORT: corre solo al entrar a Cobrar y no
            // puede tumbar la app.
            //
            // `restore()` empieza por `venueId()`, que LANZA si no hay local
            // seleccionado — y eso pasa de verdad: con la sesión vencida el
            // refresh devuelve 401, el venue se limpia, y al abrir Cobrar la
            // excepción subía sin nadie que la atrapara. Crash al arranque en
            // vez de mandar al login. Reproducido en la D3 el 2026-08-10 con la
            // sesión expirada (AreaTicketException VENUE_REQUIRED).
            //
            // iOS ya lo hacía bien con `try?` (CheckoutView.swift): esto es el
            // espejo, con log para que el fallo no quede invisible.
            val venta = _cartState.value.identidadDeVenta
            runCatching { areaTicketRepository.restore(venta) }
                .onSuccess { checkout -> checkout?.let { proyectarOSoltar(it, venta) } }
                .onFailure { Log.w("🎟️", "No se pudo restaurar la sesión de vales: ${it.message}") }
        }
    }

    /**
     * 🔴 DINERO. Un cobro de vales se detuvo porque este carrito no traía los vales de la sesión del servidor
     * (`PaymentFlowViewModel.valesNoCoincidian`): el carrito toma los de la sesión, que el cobro acaba de releer. Sin red.
     * Esa parada sólo ocurre sin abonos en la orden, así que nunca regresa al carrito algo ya pagado.
     */
    fun traerValesDeLaSesion() {
        areaTicketRepository.session.current()?.let { replaceAreaTicketLines(it, _cartState.value.identidadDeVenta) }
    }

    /**
     * 🔴 DINERO (regla 8). Al restaurar: el apartado de ESTA venta (o sin dueño, tras reiniciar) se proyecta; uno sin abono que
     * era de otra venta quedó soltado y se suelta en el servidor en vez de reaparecer aquí. Con abono no se proyecta (D3): lo
     * ofrece el aviso «Cobrar saldo».
     */
    private suspend fun proyectarOSoltar(checkout: AreaTicketCheckout, venta: String) {
        if (checkout.tieneAbono()) return
        // J12 (vales-ciclo, Codex r1): una restauración que tardó compara contra la venta de CUANDO se pidió. Si el carrito ya es
        // otra venta, el apartado que trae puede ser el de esa venta nueva: ni se proyecta ni se suelta.
        if (_cartState.value.identidadDeVenta != venta) {
            // J13 (Codex r2): el que la restauración instaló SIN dueño (venía del disco) es de la venta de cuando se pidió; así la
            // regla 8 lo suelta al siguiente escaneo de la venta nueva, en vez de que ésta lo adopte con sus vales viejos.
            val sesion = areaTicketRepository.session
            if (sesion.ventaDuena == null && sesion.current()?.id == checkout.id) sesion.anotarVentaDuena(venta)
            return
        }
        val duena = areaTicketRepository.session.ventaDuena
        if (duena != null && duena != venta) {
            runCatching { areaTicketRepository.cancel(soloSi = checkout.id) }
                .onFailure {
                    Log.w("🎟️", "No se pudo soltar el apartado de una venta anterior: ${it.message}")
                    releerSiTieneAbono(it)
                }
            return
        }
        replaceAreaTicketLines(checkout, venta)
    }

    /**
     * Renueva la sesión de cobro de vales para que no venza mientras el cajero
     * trabaja. Espejo de `maintainAreaTicketCheckout` en iOS.
     *
     * Lanza si falla: el llamador decide. Un bache de red no debe tumbar el cobro,
     * pero tampoco queremos tragarnos el error en silencio aquí.
     */
    suspend fun heartbeatAreaTicketSession() {
        val venta = _cartState.value.identidadDeVenta
        areaTicketRepository.heartbeat()?.let { replaceAreaTicketLines(it, venta) }
    }

    suspend fun resolveScannedBarcode(rawCode: String): ScannedBarcodeResult {
        // Con el cobro abierto no se consulta nada: ni el servidor se entera de un vale que no se va a cobrar.
        if (cobroAbierto) return ScannedBarcodeResult.Error("Se abrió el cobro: vuelve a escanear al terminar.")
        consultasDeCodigo.update { it + 1 }
        try {
            return resolverCodigo(rawCode)
        } finally {
            consultasDeCodigo.update { it - 1 }
        }
    }

    private suspend fun resolverCodigo(rawCode: String): ScannedBarcodeResult {
        val code = rawCode.trim()
        val localProduct = products.value.firstOrNull {
            it.sku == code || it.barcode == code || it.gtin == code
        }
        if (!com.avoqado.pos.pos.data.isAreaTicketCode(code)) {
            localProduct?.let { return ScannedBarcodeResult.ProductFound(it) }

            if (code.length == 13 && code.all(Char::isDigit)) {
                val barcodeSettings = runCatching { areaTicketRepository.settings().variableWeightBarcode }.getOrNull()
                if (barcodeSettings?.enabled == true && barcodeSettings.entitled) {
                    val decoded = com.avoqado.pos.pos.data.decodeVariableWeightBarcode(code, barcodeSettings.prefix)
                    if (decoded != null) {
                        val exactMatches = products.value.filter { product ->
                            product.sku == decoded.plu || product.barcode == decoded.plu || product.gtin == decoded.plu
                        }
                        val matches = if (exactMatches.isNotEmpty()) {
                            exactMatches
                        } else {
                            val normalizedPlu = decoded.plu.trimStart('0').ifEmpty { "0" }
                            products.value.filter { product ->
                                listOfNotNull(product.sku, product.barcode, product.gtin).any { candidate ->
                                    candidate.all(Char::isDigit) && candidate.trimStart('0').ifEmpty { "0" } == normalizedPlu
                                }
                            }
                        }
                        if (matches.size > 1) {
                            return ScannedBarcodeResult.Error("El PLU ${decoded.plu} coincide con más de un producto. Corrige el catálogo.")
                        }
                        val weightedProduct = matches.singleOrNull()
                            ?: return ScannedBarcodeResult.Error("El PLU ${decoded.plu} de la báscula no existe en el catálogo.")
                        if (!weightedProduct.soldByWeight) {
                            return ScannedBarcodeResult.Error("El producto ${weightedProduct.name} no está configurado para venta por peso.")
                        }
                        return ScannedBarcodeResult.WeightedProductFound(weightedProduct, decoded.weightKg)
                    }
                }
            }
            // 🔴 Último intento antes de rendirse: puede ser la tarjeta digital de un
            // cliente. Se filtra por FORMA primero (`pareceTarjetaDeCliente`) para no
            // hacer un viaje al servidor por cada producto mal dado de alta — en una
            // caja con fila esa latencia se nota.
            if (com.avoqado.pos.loyalty.data.pareceTarjetaDeCliente(code)) {
                walletScanRepository.escanear(code)?.let { return ScannedBarcodeResult.CustomerCardFound(it) }
            }

            return ScannedBarcodeResult.Unknown(code)
        }

        return runCatching {
            // J5 (vales-ciclo r0): la venta y el negocio de ESTE escaneo, antes del primer viaje. Si el cajero vacía y empieza
            // otra venta (o cambia de negocio) mientras se consulta, el vale leído para A nunca cae en B.
            val venta = _cartState.value.identidadDeVenta
            val negocio = secureStorage.venueId
            fun sigueLaVenta() = _cartState.value.identidadDeVenta == venta && secureStorage.venueId == negocio
            val resolved = areaTicketRepository.resolveCheckoutScan(code)
            when (resolved.type) {
                "PRODUCT" -> localProduct?.let(ScannedBarcodeResult::ProductFound)
                    ?: ScannedBarcodeResult.Unknown(code)
                "AREA_TICKET" -> {
                    val settings = areaTicketRepository.settings()
                    val hasNormalItems = _cartState.value.items.any { !it.locked }
                    if (!sigueLaVenta()) {
                        ScannedBarcodeResult.Error(VALES_VENTA_CAMBIO_AL_LEER)
                    } else if (_pendingSplitOrder.value != null) {
                        // 🔴 D4 (20-oct). Con una venta cobrada a medias, el vale armaba una orden NUEVA con lo que faltaba y la
                        // venta de antes se quedaba con su saldo abierto para siempre. Primero se termina esa venta.
                        ScannedBarcodeResult.Error(VALE_CON_VENTA_A_MEDIAS)
                    } else if (!settings.areaTickets.allowMixedCart && hasNormalItems) {
                        ScannedBarcodeResult.Error("Este local cobra los vales en un carrito separado.")
                    } else if (cobroAbierto) {
                        ScannedBarcodeResult.Error("Se abrió el cobro: vuelve a escanear el vale al terminar.")
                    } else if (!soltarApartadoDeOtraVenta(venta)) {
                        ScannedBarcodeResult.Error(VALES_SOLTAR_ANTERIOR)
                    } else if (!sigueLaVenta()) {
                        // Soltar el apartado anterior pudo esperar a la red.
                        ScannedBarcodeResult.Error(VALES_VENTA_CAMBIO_AL_LEER)
                    } else {
                        // Pedir el vale CAMBIA el servidor: una vez pedido se termina aunque la pantalla se vaya (cambiar
                        // de pestaña, girar la tablet). Si no, el servidor quedaba con el vale y el carrito sin él, y el
                        // cobro podía congelar un importe menor (Codex r5). Mientras, el contador sigue y el cobro espera.
                        // Si la pantalla se fue ANTES de pedirlo, no se pide: un paso anterior pudo tragarse la cancelación
                        // (guardar la configuración en disco) y `NonCancellable` la ignoraría (Codex r6).
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val checkout = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                            areaTicketRepository.addTicket(code).also { abierto ->
                                // Regla 8: el apartado es de la venta que escaneó, aunque el carrito haya cambiado mientras;
                                // así nunca aparece en otra venta (queda soltado y se suelta).
                                if (areaTicketRepository.session.current()?.id == abierto.id) {
                                    areaTicketRepository.session.anotarVentaDuena(venta)
                                }
                            }
                        }
                        if (cobroAbierto) return@runCatching ScannedBarcodeResult.Error("Se abrió el cobro: vuelve a escanear el vale al terminar.")
                        if (!sigueLaVenta()) {
                            // El apartado quedó a nombre de A (regla 8): se suelta ya, por el mismo camino que al restaurar,
                            // en vez de esperar al siguiente escaneo. Otro negocio: su sesión ya se olvidó al cambiar.
                            if (secureStorage.venueId == negocio && areaTicketRepository.session.ventaDuena == venta) {
                                proyectarOSoltar(checkout, _cartState.value.identidadDeVenta)
                            }
                            return@runCatching ScannedBarcodeResult.Error(VALES_VENTA_CAMBIO_AL_LEER)
                        }
                        if (!replaceAreaTicketLines(checkout, venta)) {
                            return@runCatching ScannedBarcodeResult.Error("La venta cambió mientras se pedía el vale: vuelve a escanearlo.")
                        }
                        ScannedBarcodeResult.AreaTicketsAdded(checkout.tickets.size)
                    }
                }
                "PAID_AREA_TICKET" -> ScannedBarcodeResult.Error("Ese vale ya está pagado; úsalo en la pantalla de entrega.")
                "AMBIGUOUS" -> ScannedBarcodeResult.Error("El código coincide con un producto y un vale. Revisa la configuración.")
                else -> ScannedBarcodeResult.Error("Vale no encontrado en este local.")
            }
        }.getOrElse { error ->
            // Una cancelación (la pantalla se fue) no es un «Error» del vale: se deja pasar para que nada siga después.
            if (error is kotlinx.coroutines.CancellationException) throw error
            ScannedBarcodeResult.Error(error.message ?: "No se pudo consultar el vale. Revisa la conexión.")
        }
    }

    /**
     * Regla 8 al escanear: un apartado sin abono que era de OTRA venta (el cajero la vació o la cobró y no se pudo soltar)
     * se suelta antes de abrir uno nuevo; si no se puede, no se escanea (el vale nuevo se le colgaría a esa venta).
     */
    private suspend fun soltarApartadoDeOtraVenta(venta: String): Boolean {
        val sesion = areaTicketRepository.session
        val previo = sesion.current()
        val duena = sesion.ventaDuena
        if (previo != null && duena != null && duena != venta && !previo.tieneAbono()) {
            runCatching { areaTicketRepository.cancel(soloSi = previo.id) }
                .onFailure { releerSiTieneAbono(it) }
            // Sigue ahí sin soltar: no se escanea. Si resultó tener abono, el escaneo lo dice (cobra su saldo primero).
            if (sesion.current()?.let { it.id == previo.id && !it.tieneAbono() } == true) return false
        }
        // F2 (vales-ciclo, Codex r3): soltar pudo esperar a la red. Si la venta ya cambió, no se preparan dueño ni llaves para la que
        // se fue (pisaría la creación de la venta nueva): el llamador ve el cambio y lo dice.
        if (_cartState.value.identidadDeVenta != venta) return true
        // El dueño se anota ANTES de pedir: si la respuesta se pierde, el apartado ya sabe de qué venta es (Codex r7 #3).
        sesion.prepararCreacionPara(venta)
        return true
    }

    /**
     * Soltar devolvió 409 porque la venta ya tiene un abono que esta copia no sabía (su relectura tras cobrar falló): se relee
     * para que el aviso «Cobrar saldo» la muestre (Codex r7 #1).
     */
    private suspend fun releerSiTieneAbono(error: Throwable) {
        if ((error as? com.avoqado.pos.areatickets.data.AreaTicketException)?.code == "CHECKOUT_HAS_PAYMENT") {
            runCatching { areaTicketRepository.refresh() }
        }
    }

    /**
     * 🔴 DINERO (regla 8, Codex r6 #5). Sólo se proyecta a la venta [venta] que pidió la respuesta, si el carrito sigue en
     * ella y el apartado es suyo (sin dueño, tras reiniciar, lo adopta). La respuesta atrasada de una venta que ya se fue no
     * mete sus vales en la siguiente.
     */
    private fun replaceAreaTicketLines(checkout: AreaTicketCheckout, venta: String): Boolean {
        val sesion = areaTicketRepository.session
        if (_cartState.value.identidadDeVenta != venta || sesion.current()?.id != checkout.id) return false
        val duena = sesion.ventaDuena ?: venta.also(sesion::anotarVentaDuena)
        if (duena != venta) return false
        // 🔴 DINERO. Una sesión con abonos ya no se proyecta al carrito. Tras el primer abono el carrito quedó en «Saldo
        // pendiente» (`aplicarCobroConfirmado`), y la sesión sigue viva porque el servidor no deja cancelar una venta con
        // abono (409). Restaurarla al volver a la pantalla metía todos los vales encima del saldo y el total se inflaba
        // (D3, Codex r7). La copia del aparato puede no saber del abono si su relectura falló: por eso también cuenta que el
        // carrito continúe ESA orden y que el carrito ya la haya anotado (`marcarConAbono`). iOS no lo necesita: conserva
        // los vales y cobra el saldo del servidor.
        // D4 (20-oct): tampoco sobre una venta cobrada a medias de OTRA orden. Un vale apartado antes (o escaneado con su
        // respuesta perdida) se proyectaba al volver a la pantalla encima del saldo pendiente y mezclaba las dos ventas. Se
        // queda apartado y aparece al terminar esa venta.
        val conAbonos = checkout.status == "PARTIALLY_PAID" || (checkout.order?.paidAmount?.moneyToCents() ?: 0) > 0
        if (conAbonos || _pendingSplitOrder.value != null) return false
        val ticketRows = checkout.tickets.flatMap { ticket ->
            ticket.lines.map { line ->
                val detail = buildList {
                    add("${ticket.fulfillmentArea.name} · Vale ${ticket.code}")
                    line.weightKg?.let { add("$it kg") }
                    if (line.quantity != "1" && line.weightKg == null) add("Cantidad ${line.quantity}")
                }.joinToString(" · ")
                CartItem(
                    id = "area-ticket:${ticket.id}:${line.id}",
                    type = CartItemType.CustomAmount,
                    name = line.productNameSnapshot,
                    subtitle = detail,
                    unitPrice = line.total.moneyToCents(),
                    quantity = 1,
                    areaTicketId = ticket.id,
                    areaTicketLineId = line.id,
                    locked = true,
                )
            }
        }
        _cartState.update { state ->
            state.copy(
                items = state.items.filterNot { it.locked } + ticketRows,
                orderDiscount = null,
                descuentoDeCuentaCongelado = null,
                orderTaxPercent = null,
            )
        }
        return true
    }

    // MARK: - Referral capture (Plan 5B)

    /** Constant tag for the order discount when it came from a referral. */
    private val REFERRAL_DISCOUNT_SOURCE = "REFERRAL_NEW_CUSTOMER"

    /**
     * El cajero eligió (o quitó) al cliente de esta venta. Es el ÚNICO camino
     * para moverlo: [selectedCustomer] es la copia buena, no un espejo de la
     * pantalla.
     *
     * Cambiar de cliente —o quitarlo— invalida la validación de referido en
     * caché: la regla EXISTING_CUSTOMER es por cliente, así que un `Valid` viejo
     * estaría mintiendo sobre otra persona.
     *
     * [customerName] es opcional para el camino que sólo conoce el id (un
     * carrito guardado, que persiste `attachedCustomerId` y nada más). Sin
     * nombre se cae a "Cliente" —el mismo texto de reserva que ya usa
     * `Customer.fullName`— porque un cliente atado NO puede pintarse como
     * "Agregar cliente": diría que no hay nadie cuando la venta sí lo lleva.
     */
    /**
     * Marca (o quita) el premio que se aplicará al cobrar.
     *
     * El carrito resta un ESTIMADO desde ya (como Square y Toast: el premio se ve en la cuenta
     * antes de cobrar), pero el premio NO se canjea aquí: la orden todavía no existe. El
     * servidor lo canjea al crearla y el cobro usa el monto que él confirma.
     */
    fun setPendingStampReward(premio: com.avoqado.pos.loyalty.data.PremioPorAplicar?): Boolean {
        // 🔴 Con una venta a medio cobrar, la orden YA decidió su premio: la parte siguiente cobra contra
        // ella sin volver a crearla, así que un premio nuevo (o quitarlo) cambiaría lo cobrado sin
        // canjearse (hallazgo P1 de Codex, ronda 2).
        if (!puedeCambiarPremio()) return false
        _cartState.update { it.copy(pendingStampReward = premio?.takeIf { p -> p.id.isNotBlank() }) }
        return true
    }

    /** false mientras la venta se cobra por partes contra una orden que ya existe. */
    fun puedeCambiarPremio(): Boolean = _pendingSplitOrder.value == null

    fun setSelectedCustomer(customerId: String?, customerName: String? = null) {
        val previous = _selectedCustomer.value?.id
        val id = customerId?.trim()?.takeIf { it.isNotEmpty() }
        val nuevo = id?.let { SelectedCustomer(it, customerName?.trim()?.takeIf { n -> n.isNotEmpty() } ?: "Cliente") }
        if (_selectedCustomer.value == nuevo) return
        _selectedCustomer.value = nuevo
        if (previous == id) return
        // 🔴 El premio es de UN cliente: con otro, el servidor no lo aplica y el carrito ya
        // habría bajado el total por un descuento que no existe.
        if (_cartState.value.pendingStampReward != null) {
            _cartState.update { it.copy(pendingStampReward = null) }
        }
        if (previous != null) {
            // Customer changed (incl. switch-to-null). Any cached referral
            // state is for the previous customer, drop it.
            clearReferral()
        }
    }

    fun onReferralCodeChange(code: String) {
        _referralCode.value = code
        // Editing the code after a result invalidates the result so the
        // banner doesn't lie about the new (untested) code.
        val current = _referralValidation.value
        if (current !is ReferralCaptureUiState.Idle &&
            current !is ReferralCaptureUiState.Validating
        ) {
            _referralValidation.value = ReferralCaptureUiState.Idle
        }
    }

    /**
     * Validates the cached [referralCode] against the backend for the
     * cached [selectedCustomer]. No-ops when either is missing. On
     * [ReferralValidationResult.Valid] applies the discount to the cart; on
     * [ReferralValidationResult.Invalid] or network failure clears any
     * discount that the previous validation may have applied.
     */
    fun validateReferralCode() {
        val venueId = secureStorage.venueId
        if (venueId.isNullOrBlank()) {
            Log.w("🎁", "validateReferralCode skipped: no venueId")
            return
        }
        val customerId = _selectedCustomer.value?.id
        if (customerId.isNullOrBlank()) {
            Log.w("🎁", "validateReferralCode skipped: no customer selected")
            return
        }
        val code = _referralCode.value.trim()
        if (code.isBlank()) return

        viewModelScope.launch {
            _referralValidation.value = ReferralCaptureUiState.Validating
            val resultado = validateReferralUseCase(
                venueId = venueId,
                referralCode = code,
                newCustomerId = customerId,
            )
            // 🔴 La respuesta es del cliente y del código de ANTES: si mientras tanto quitaron al cliente (aunque lo hayan
            // vuelto a elegir, Codex r2 #1), lo cambiaron o editaron el código, no se aplica. Quitar al cliente vacía el
            // código (`clearReferral()`), así que una respuesta vieja nunca coincide (Codex r1 #2: el descuento volvía a una
            // venta sin cliente).
            if (_selectedCustomer.value?.id != customerId || _referralCode.value.trim() != code) {
                if (_referralValidation.value is ReferralCaptureUiState.Validating) {
                    _referralValidation.value = ReferralCaptureUiState.Idle
                }
                return@launch
            }
            resultado.fold(
                onSuccess = { result ->
                    when (result) {
                        is ReferralValidationResult.Valid -> {
                            _referralValidation.value = ReferralCaptureUiState.Valid(
                                referrerName = result.referrerName,
                                discountPercent = result.discountPercent,
                            )
                            applyReferralDiscount(
                                discountPercent = result.discountPercent,
                                referrerName = result.referrerName,
                            )
                        }
                        is ReferralValidationResult.Invalid -> {
                            _referralValidation.value = ReferralCaptureUiState.Invalid(result.reason)
                            clearReferralDiscountOnly()
                        }
                    }
                },
                onFailure = { e ->
                    Log.e("🎁", "Referral validate request failed", e)
                    _referralValidation.value = ReferralCaptureUiState.Invalid(
                        ReferralValidationResult.Reason.UNKNOWN,
                    )
                    clearReferralDiscountOnly()
                },
            )
        }
    }

    /** Resets referral state and any discount it had applied. */
    fun clearReferral() {
        _referralCode.value = ""
        _referralValidation.value = ReferralCaptureUiState.Idle
        clearReferralDiscountOnly()
    }

    /**
     * Clears the cart's orderDiscount only when it originated from a referral.
     * Leaves a manual/coupon discount alone — the cashier may have applied
     * one independently before the referral validation kicked in.
     */
    private fun clearReferralDiscountOnly() {
        _cartState.update { state ->
            if (state.orderDiscount?.source == REFERRAL_DISCOUNT_SOURCE) {
                state.copy(orderDiscount = null, descuentoDeCuentaCongelado = null)
            } else {
                state
            }
        }
    }

    /**
     * Applies the referral percent discount as an order-level discount
     * tagged with [REFERRAL_DISCOUNT_SOURCE]. The cart's existing
     * [CartState.discountCents] formula computes it against the subtotal
     * automatically — no manual math here.
     */
    private fun applyReferralDiscount(discountPercent: Int, referrerName: String) {
        if (discountPercent <= 0) {
            clearReferralDiscountOnly()
            return
        }
        val discount = Discount(
            id = "referral_$referrerName",
            name = "Referido por $referrerName",
            value = discountPercent.toDouble(),
            type = "PERCENTAGE",
            scope = "ORDER",
            source = REFERRAL_DISCOUNT_SOURCE,
        )
        _cartState.update { it.copy(orderDiscount = discount, descuentoDeCuentaCongelado = null) }
    }

    /**
     * Persists the PENDING Referral row right before the payment is sent.
     * No-op unless [referralValidation] is currently
     * [ReferralCaptureUiState.Valid]. Returns true on a happy-path capture
     * (or no-op), false on transport failure or validation reject — the
     * caller should NOT block the payment on a false result, since the
     * cashier already saw a Valid banner and the discount was visible.
     */
    /**
     * 🔴 Todo lo que hace falta para capturar el referido, **congelado antes de que
     * el cobro toque nada**.
     *
     * Existe porque cerrar la venta destruye las TRES piezas de la captura, y la
     * captura corre después, en un `launch` asíncrono:
     * - `setSelectedCustomer(null)` → `clearReferral()` → la validación cae a `Idle`
     *   (y `captureReferralOnPayment` salía en su primera línea) y el código a `""`.
     * - `clearCart()` → borra también el `selectedStaffId`.
     *
     * Congelar SÓLO el `customerId` no alcanzaba: la función ya había salido antes
     * de llegar a leerlo. El id no era el problema — lo era la validación.
     */
    data class ReferralPendiente(
        val code: String,
        val customerId: String,
        val staffId: String,
    )

    /**
     * Fotografía el referido capturable AHORA, o `null` si no hay uno válido.
     *
     * NOTA: el campo del backend es `capturedByStaffVenueId` y espera un id de
     * StaffVenue. El POS guarda el userId a nivel usuario en SecureStorage y el
     * staff elegido por el cajero en `cartState.selectedStaffId`. Se sigue el patrón
     * del TPV (Plan 5A), que manda `secureStorage.getStaffId()` — ver CONCERNS.
     */
    private fun snapshotReferralPendiente(): ReferralPendiente? {
        if (_referralValidation.value !is ReferralCaptureUiState.Valid) return null
        val code = _referralCode.value.trim().takeIf { it.isNotEmpty() } ?: return null
        val customerId = _selectedCustomer.value?.id ?: return null
        val staffId = _cartState.value.selectedStaffId.takeIf { it.isNotBlank() }
            ?: secureStorage.userId
            ?: return null
        return ReferralPendiente(code = code, customerId = customerId, staffId = staffId)
    }

    suspend fun captureReferralOnPayment(
        orderId: String? = null,
        pendiente: ReferralPendiente? = null,
    ): Boolean {
        // Nada válido que capturar —ni congelado ni vivo— no es un fallo.
        if (pendiente == null && _referralValidation.value !is ReferralCaptureUiState.Valid) return true

        val venueId = secureStorage.venueId ?: return false
        // 🔴 Se prefiere el snapshot CONGELADO: esto corre en un `launch` asíncrono
        // y para entonces el estado vivo puede estar ya borrado. Ver
        // [ReferralPendiente]. Sin snapshot se lee el estado vivo, que es lo
        // correcto para cualquier otro llamador.
        val captura = pendiente ?: snapshotReferralPendiente() ?: return false

        val result = captureReferralUseCase(
            venueId = venueId,
            referralCode = captura.code,
            newCustomerId = captura.customerId,
            capturedByStaffVenueId = captura.staffId,
            intendedOrderId = orderId,
        )

        return result.fold(
            onSuccess = { true },
            onFailure = { e ->
                (e as? ReferralValidationException)?.let { ex ->
                    _referralValidation.value = ReferralCaptureUiState.Invalid(ex.reason)
                    clearReferralDiscountOnly()
                }
                Log.e("🎁", "captureReferralOnPayment failed", e)
                false
            },
        )
    }

    /**
     * D1 (vales-ciclo, 26-oct): el vale de un carrito guardado quedó emitido en el servidor. Se retira la copia de la que vino,
     * como hace el cobro confirmado; si no, restaurarla permitía emitir otro vale por la misma mercancía.
     */
    fun retirarCopiaDelVale(copia: String?): Boolean {
        val id = copia ?: return true
        val retirada = savedCartsRepository.deleteCart(id)
        if (!retirada) Log.w("🛒", "No se confirmó el retiro de la copia $id del vale emitido")
        if (_cartState.value.carritoGuardadoId == id) _cartState.update { it.copy(carritoGuardadoId = null) }
        return retirada
    }

    /**
     * 🔴 D5: «Recuperar» confirmó el vale de [intento]. Se retira su copia (D1), como al emitir, y si el carrito en pantalla es la
     * MISMA mercancía del vale, esa venta se termina: ya quedó en el vale (Codex r6 #2: si no, «Emitir» sacaba otro). Las copias
     * guardadas DESPUÉS de perder la respuesta no se borran (r6 #1: borrar por parecido se llevaba otra mercancía): nacen marcadas
     * ([saveCurrentCart]) y restaurarlas pregunta. `false` si la copia no se pudo retirar (el vale sigue sin confirmar).
     */
    fun alRecuperarVale(intento: com.avoqado.pos.areatickets.data.IntentoDeVale): Boolean {
        // Codex r7 #1: la copia se retira sólo si SIGUE siendo esa mercancía. Si la volvieron a guardar con otra, se queda: trae la
        // marca del D1 (se puso al emitir y guardar la conserva) y restaurarla pregunta.
        val copia = intento.carritoGuardadoId?.let { id -> savedCartsRepository.savedCarts.value.firstOrNull { it.id == id } }
        if (copia != null && lineasDeVale(copia)?.let { intento.mismaMercanciaQue(it) } == true && !retirarCopiaDelVale(copia.id)) {
            return false
        }
        // Codex r7 #2: nunca una venta a medio cobrar (su saldo vive en otra orden), aunque traiga lo mismo.
        if (!hayVentaAMedias()) lineasDeValeEnPantalla()?.takeIf { intento.mismaMercanciaQue(it) }?.let { finalizarVenta() }
        return true
    }

    /** Lo que mandaría «Emitir vale» con una copia guardada, o null si trae algo que un vale no lleva (Codex r8, igual que arriba). */
    private fun lineasDeVale(guardado: SavedCart): List<com.avoqado.pos.areatickets.data.IssueAreaTicketLineRequest>? =
        guardado.items.takeIf { items ->
            guardado.orderDiscount == null && guardado.orderTaxPercent == null && items.isNotEmpty() && items.all {
                it.productId != null && it.packId == null && !it.isCortesia && it.priceAdjustment == null && it.promotionInstanceId == null
            }
        }?.map { item ->
            com.avoqado.pos.areatickets.data.IssueAreaTicketLineRequest(
                clientLineId = item.lineId.orEmpty(),
                productId = item.productId!!,
                quantity = item.quantity.toString(),
                weightKg = item.weightKg?.let { String.format(java.util.Locale.US, "%.3f", it) },
                notes = item.note,
                modifierIds = item.modifiers.map { it.modifierId },
                discountId = item.itemDiscountId,
            )
        }

    /**
     * Lo que mandaría «Emitir vale» con el carrito en pantalla, o null si trae algo que un vale no lleva (no producto, cortesía,
     * precio a mano, promoción, descuento de cuenta…: `areaTicketIssueBlocker`). Codex r8: sin eso, «misma mercancía» confundía
     * una cortesía con el producto a precio normal.
     */
    private fun lineasDeValeEnPantalla(): List<com.avoqado.pos.areatickets.data.IssueAreaTicketLineRequest>? =
        _cartState.value.takeIf { s ->
            s.items.isNotEmpty() && s.items.all { it.type is CartItemType.ProductItem } &&
                com.avoqado.pos.areatickets.presentation.areaTicketIssueBlocker(s) == null
        }?.let { com.avoqado.pos.areatickets.presentation.intentoDeVale(it).lineas }

    fun prepararCobroDelCarritoGuardado(): Boolean =
        _cartState.value.carritoGuardadoId?.let { savedCartsRepository.marcarCobroIniciado(it) } ?: true

    fun restoreSavedCart(savedCart: SavedCart, confirmado: Boolean = false): Restauracion {
        // 27-oct (Codex A-5): la copia de una cuenta de «Pagar después» viva no se vende otra vez, ni confirmando: el cobro
        // normal crearía otra venta por la misma mercancía (la llave sólo protege otro «Pagar después»).
        if (savedCart.pagarDespuesSinDesenlace || payLaterQueue?.copiaLigada(savedCart.id) == true) {
            return Restauracion.YaDejadaParaDespues
        }
        // D5 (Codex r3 #5): restaurar reemplaza el carrito; con una venta de vales con abono la escondía.
        if (vaciarBloqueadoPorAbono()) return Restauracion.BloqueadaPorAbono
        savedCart.cobroIniciadoEn?.let {
            if (!confirmado || !savedCartsRepository.quitarMarca(savedCart.id)) return Restauracion.PideConfirmacion(it)
        }
        val items = savedCart.items.map { savedItem ->
            CartItem(
                id = savedItem.lineId ?: UUID.randomUUID().toString(),
                weightKg = savedItem.weightKg,
                type = when {
                    savedItem.packId != null -> CartItemType.CreditPack(savedItem.packId)
                    savedItem.productId != null -> CartItemType.ProductItem(savedItem.productId)
                    else -> CartItemType.CustomAmount
                },
                name = savedItem.name,
                unitPrice = savedItem.unitPrice,
                quantity = savedItem.quantity,
                selectedModifiers = savedItem.modifiers.map { mod ->
                    SelectedModifier(
                        groupId = mod.groupId,
                        groupName = mod.groupName,
                        modifierId = mod.modifierId,
                        modifierName = mod.modifierName,
                        priceInCents = mod.priceInCents,
                    )
                },
                itemNote = savedItem.note,
                isCortesia = savedItem.isCortesia,
                cortesiaReason = savedItem.cortesiaReason,
                priceAdjustment = savedItem.priceAdjustment,
                itemDiscountId = savedItem.itemDiscountId,
                itemDiscountType = savedItem.itemDiscountType,
                itemDiscountValue = savedItem.itemDiscountValue,
                itemDiscountName = savedItem.itemDiscountName,
                // El combo vuelve como combo: mismo `promotionInstanceId` y las
                // mismas elecciones, o el carrito restaurado cobraría el 2x1
                // como dos productos a precio de lista.
                promotionInstanceId = savedItem.promotionInstanceId,
                promotionName = savedItem.promotionName,
                promotionId = savedItem.promotionId,
                promotionGroupId = savedItem.promotionGroupId,
                promotionOptionId = savedItem.promotionOptionId,
                serviceCourse = savedItem.serviceCourse,
                catalogAt = savedItem.catalogAt,
                subtitle = savedItem.promotionName,
            )
        }
        // Referral-sourced discounts must NOT survive a restore without a live
        // validation — the referrer would never be credited for the new sale.
        val restoredDiscount = savedCart.orderDiscount?.takeIf { it.source != REFERRAL_DISCOUNT_SOURCE }
        clearReferral()
        _cartState.value = CartState(
            items = items,
            orderDiscount = restoredDiscount,
            orderNote = savedCart.orderNote,
            orderTaxPercent = null,
            reservationId = savedCart.reservationId,
            carritoGuardadoId = savedCart.id,
            selectedStaffId = _cartState.value.selectedStaffId,
            selectedStaffName = _cartState.value.selectedStaffName,
            llavePagarDespues = savedCart.llavePagarDespues,
        )
        // D13 (IVA por producto, plan 7a): «Agregar impuesto» ya no existe — en México el precio ya trae el IVA (LFPC 7 Bis).
        // Un carrito guardado con impuesto aparte se restaura SIN él, y se dice; uno sin impuesto limpia el aviso anterior
        // (r1 #15). Lo ya cobrado y lo encolado no se tocan. La copia guardada se conserva hasta el primer cobro confirmado.
        _avisoDelCarrito.value = if ((savedCart.orderTaxPercent ?: 0) > 0) AVISO_CARRITO_SIN_IMPUESTO else null
        // 🔴 DINERO. Este camino reemplaza el carrito SIN pasar por `clearCart()`,
        // así que el vínculo con una venta a medio cobrar se limpia aquí. Hoy los
        // ids restaurados son UUID nuevos y el guard de `resolvePendingSplit…` ya
        // los cazaría, pero el margen de un invariante de dinero no puede ser un
        // solo guard — y sin esto el cajero vería un aviso falso de "venta
        // anterior a medio cobrar" sobre una venta que ya terminó.
        _pendingSplitOrder.value = null
        // El carrito guardado persiste el id del cliente, no su nombre: se cae a
        // "Cliente" (ver [setSelectedCustomer]) para que el encabezado NO diga
        // "Agregar cliente" sobre una venta que sí lo lleva — y para que ese
        // cliente llegue al cobro, que antes se perdía por el mismo motivo que el
        // bug de rotación: la pantalla tenía su propia copia y ésta no la tocaba.
        setSelectedCustomer(savedCart.attachedCustomerId)
        Log.d("🛒", "Restored saved cart: ${savedCart.name}")
        return Restauracion.Hecha
    }

    fun deleteSavedCart(cartId: String) {
        savedCartsRepository.deleteCart(cartId)
        Log.d("🛒", "Deleted saved cart: $cartId")
    }

    // MARK: - Pagar después (27-oct: sin red, una sola cuenta por venta)

    /** La venta que salió del carrito al mandarla (R3-2), con lo que hace falta para devolverla entera si no se crea. */
    private data class VentaSeparada(
        val venta: CartState,
        val cliente: SelectedCustomer,
        val referralCode: String,
        val referralValidation: ReferralCaptureUiState,
    )

    private val _pagarDespuesEnCurso = MutableStateFlow<EnvioPagarDespues?>(null)
    /** No-null mientras se manda: la pantalla de «Pagar después» no se puede cerrar y dice «Guardando la cuenta…». */
    val pagarDespuesEnCurso: StateFlow<EnvioPagarDespues?> = _pagarDespuesEnCurso.asStateFlow()

    private val _resultadoPagarDespues = MutableStateFlow<ResultadoPagarDespues?>(null)
    /** Vive aquí y no en la pantalla: el envío termina aunque la pantalla se recree. */
    val resultadoPagarDespues: StateFlow<ResultadoPagarDespues?> = _resultadoPagarDespues.asStateFlow()

    fun consumirResultadoPagarDespues() {
        _resultadoPagarDespues.value = null
    }

    /**
     * 🔴 Codex iOS r3 #1: mientras «Pagar después» manda una venta (con premio sigue en el carrito), la ENTRADA de cobrar,
     * dividir, guardar, vaciar y buscar se detiene aquí y lo dice — no sólo el botón: F12/F3 de WINDOWS llegan por el mismo
     * camino. true = detenerse.
     */
    fun detenerPorPagarDespues(): Boolean {
        if (_pagarDespuesEnCurso.value == null) return false
        _avisoDeVales.value = PAGAR_DESPUES_EN_CURSO
        return true
    }

    /** El «Continuar» de «Pagar después». Corre en el alcance del ViewModel, no en el de la pantalla (R7). */
    fun pagarDespues(customerId: String) {
        if (_pagarDespuesEnCurso.value != null) return
        viewModelScope.launch { _resultadoPagarDespues.value = createPayLaterOrder(customerId) }
    }

    private fun motivoParaNoDiferir(venta: CartState, customerId: String): String? = when {
        venta.isEmpty -> "No hay artículos en el carrito"
        // D5 (Codex r4 #2): pagar después creaba OTRA cuenta y vaciaba la venta de vales con abono.
        vaciarBloqueadoPorAbono() -> com.avoqado.pos.payment.presentation.VALES_CON_ABONO
        // Tablero D3: la cuenta nueva se llevaba el saldo de la venta a medias y la vieja lo seguía debiendo.
        hayVentaAMedias() -> VENTA_A_MEDIAS_NO_SE_GUARDA
        // D1 por otra puerta: un vale se cobra por su apartado; diferido se iba como importe libre y el vale quedaba sin pagar.
        venta.items.any { it.areaTicketLineId != null } -> VALES_NO_PAGAR_DESPUES
        // Codex r2 (ajeno incluido): el cuerpo pierde el `packId` y el paquete nunca se entregaba al saldar.
        venta.items.any { it.type is CartItemType.CreditPack } -> PAQUETES_NO_PAGAR_DESPUES
        customerId.isBlank() -> "Selecciona un cliente para diferir el pago"
        // Lo mismo que pedía `createOrder` antes de mandar nada.
        venta.selectedStaffId.isBlank() -> "No staff"
        secureStorage.accessToken == null -> "Not authenticated"
        else -> null
    }

    /**
     * 🔴 «Pagar después» (promesa v4, `docs/audit/2026-10-27-pagar-despues-sin-red/`; arreglos de Codex ANDROID r1).
     *
     * La cuenta se escribe en [PayLaterQueue] con la llave de la venta y SÓLO ENTONCES la venta sale del carrito (sus líneas;
     * lo que entró mientras se queda). Sin red queda en la fila y se dice; creada, se compara su total con lo que vio el
     * cliente; rechazada (no se creó) vuelve al carrito, o a «Carritos guardados» si ya hay otra venta, y su fila no se borra
     * hasta que está a salvo. Una venta con premio de sellos nunca entra a la fila para mandarse (R8).
     */
    suspend fun createPayLaterOrder(customerId: String): ResultadoPagarDespues {
        val cliente = _selectedCustomer.value?.takeIf { it.id == customerId } ?: SelectedCustomer(customerId, "Cliente")
        _pagarDespuesEnCurso.value = EnvioPagarDespues(cliente.name, _cartState.value.totalCents)
        try {
            // Como el cobro (`hayConsultasDeCodigo`): lo leído antes del toque entra ANTES de capturar la venta.
            consultasDeCodigo.first { it == 0 }
            val venta = _cartState.value
            motivoParaNoDiferir(venta, customerId)?.let { return ResultadoPagarDespues.Error(it) }
            val venueId = secureStorage.venueId ?: return ResultadoPagarDespues.Error("No venue selected")
            val cola = payLaterQueue ?: return ResultadoPagarDespues.Error("No se pudo registrar la venta como pagar después")

            // El MISMO mapeo que usa el cobro (`PaymentFlowViewModel.buildOrderRequest`): una copia olvidada dejaba «pagar
            // después» cobrando el combo a precio de lista. Descuento: SÓLO el de ORDEN (el server suma los de línea).
            val request = CreateOrderRequest(
                items = buildOrderItemRequests(venta.items),
                subtotal = venta.subtotalCents,
                discount = venta.orderDiscountCents,
                tip = 0,
                total = venta.totalCents,
                paymentMethod = "PAY_LATER",
                note = venta.orderNote,
                splitType = "FULLPAYMENT",
            )
            if (!OrderRepository.hasProductItems(request)) {
                return ResultadoPagarDespues.Error("No hay productos válidos para crear la orden")
            }
            // El carrito ya enseñó el total con el premio: la cuenta que queda abierta tiene que ser ésa (se canjea al crearla).
            val premio = venta.pendingStampRewardId?.takeIf { venta.premioAplica }
            val llave = venta.llavePagarDespues ?: "pay-later:${UUID.randomUUID()}"
            // La llave es de la venta desde ya: cualquier intento siguiente de ESTA venta la reusa (R2-2).
            _cartState.update { if (it.identidadDeVenta == venta.identidadDeVenta) it.copy(llavePagarDespues = llave) else it }
            val capturadaEn = System.currentTimeMillis()
            fun cuerpo(reenvio: Boolean) = OrderRepository.buildCreateOrderPayload(
                request = request,
                staffId = venta.selectedStaffId,
                customerId = customerId,
                orderType = "DINE_IN",
                externalId = llave,
                stampRewardId = premio,
                stampRewardExpectedDiscount = venta.stampRewardCents.takeIf { it > 0 },
                isOfflineReplay = reenvio,
                createdAtLocal = capturadaEn.takeIf { reenvio },
            )
            val s = VentaSeparada(venta.copy(llavePagarDespues = llave), cliente, _referralCode.value, _referralValidation.value)
            val fila = com.avoqado.pos.payment.data.PayLaterRow(
                id = llave,
                venueId = venueId,
                staffId = venta.selectedStaffId,
                customerId = cliente.id,
                customerName = cliente.name,
                totalEsperadoCents = venta.totalCents,
                cuerpo = cuerpo(reenvio = true),
                carritoGuardadoId = venta.carritoGuardadoId,
                creadaEn = capturadaEn,
            )
            _pagarDespuesEnCurso.value = EnvioPagarDespues(cliente.name, venta.totalCents)
            // Codex A-5: la copia guardada lleva la llave y la marca de cobro iniciado ANTES de mandar nada. Con premio (sin fila
            // que la ligue) la marca además es vinculante hasta conocer el desenlace (Codex r2 #5).
            val copia = venta.carritoGuardadoId
            if (copia != null && !savedCartsRepository.prepararPagarDespues(copia, llave, vinculante = premio != null)) {
                return ResultadoPagarDespues.Error(PAGAR_DESPUES_NO_SE_GUARDO)
            }
            return if (premio != null) {
                diferirConPremio(cola, s, venueId, cuerpo(reenvio = false))
            } else {
                diferirConFila(cola, s, fila, venueId, cuerpo(reenvio = false))
            }
        } finally {
            _pagarDespuesEnCurso.value = null
        }
    }

    /**
     * R3-2 con el arreglo de Codex A-3: sale del carrito EXACTAMENTE la venta capturada, por id de línea. Lo que entró mientras
     * se escribía (el lector) se queda como otra venta; si se juntó con una línea suya, se queda la diferencia. El cliente, los
     * descuentos y la nota se sueltan sólo si el carrito sigue siendo esa venta.
     */
    private fun separarDelCarrito(venta: CartState) {
        val actual = _cartState.value
        if (actual.identidadDeVenta != venta.identidadDeVenta) return
        val suyas = venta.items.associateBy { it.id }
        val quedan = actual.items.mapNotNull { linea ->
            val suya = suyas[linea.id] ?: return@mapNotNull linea
            (linea.quantity - suya.quantity).takeIf { it > 0 }?.let { linea.copy(id = UUID.randomUUID().toString(), quantity = it) }
        }
        finalizarVenta()
        if (quedan.isNotEmpty()) _cartState.update { it.copy(items = quedan) }
    }

    private suspend fun diferirConFila(
        cola: com.avoqado.pos.payment.data.PayLaterQueue,
        s: VentaSeparada,
        fila: com.avoqado.pos.payment.data.PayLaterRow,
        venueId: String,
        enLinea: String,
    ): ResultadoPagarDespues {
        val llave = fila.id
        // La marca va ANTES de escribir: el reenvío nunca ve esta fila sin ella mientras el intento vive (R7).
        cola.marcarEnCurso(llave)
        // Codex r2 #3: si la fila rechazada no se pudo sacar del reenvío, la marca se queda: este proceso no la reenvía.
        var retenida = false
        val noSeGuardo = { retenida = true; ResultadoPagarDespues.Error(PAGAR_DESPUES_NO_SE_GUARDO) }
        try {
            // Codex A-2/A-3: si la cuenta no quedó en disco, la venta nunca salió del carrito.
            if (!cola.guardar(fila)) return ResultadoPagarDespues.Error(PAGAR_DESPUES_NO_SE_GUARDO)
            separarDelCarrito(s.venta)
            return when (val d = cola.mandar(venueId, enLinea)) {
                // Aceptada (creada o por subir): su copia guardada se retira. Si el retiro no se escribe, la fila espera a
                // confirmarlo (Codex A-5) y la copia, ligada a ella, no se restaura.
                is DesenlacePagarDespues.Creada -> when (val total = d.totalCents) {
                    s.venta.totalCents -> { cola.cerrarAceptada(llave); ResultadoPagarDespues.Creada }
                    // Existe, pero no se sabe por cuánto: queda por revisar (y nunca se vuelve a crear con otra llave).
                    null -> {
                        cola.cambiar(llave) { it.copy(estado = com.avoqado.pos.payment.data.PayLaterRow.Estado.REVISAR, orderId = d.orderId) }
                        cola.retirarCopia(s.venta.carritoGuardadoId)
                        ResultadoPagarDespues.Aviso(com.avoqado.pos.payment.data.PAGAR_DESPUES_IMPORTE_DESCONOCIDO)
                    }
                    else -> {
                        cola.cerrarAceptada(llave)
                        ResultadoPagarDespues.Aviso(pagarDespuesOtroImporte(s.cliente.name, total, s.venta.totalCents))
                    }
                }
                DesenlacePagarDespues.SinRespuesta, is DesenlacePagarDespues.Pendiente -> {
                    cola.cambiar(llave) {
                        it.copy(estado = com.avoqado.pos.payment.data.PayLaterRow.Estado.PENDIENTE, intentos = it.intentos + 1)
                    }
                    cola.retirarCopia(s.venta.carritoGuardadoId)
                    ResultadoPagarDespues.Aviso(pagarDespuesGuardadaSinRed(s.cliente.name))
                }
                // Un 401 o un 4xx de la API: ESTA única petición (sin reenvíos, R2-2) no creó la cuenta.
                is DesenlacePagarDespues.SesionVencida -> ponerASalvo(cola, s, fila, venueId, d.mensaje) ?: noSeGuardo()
                is DesenlacePagarDespues.Rechazada -> conPreciosDelRechazo(s, d)
                    .let { (corregida, texto) -> ponerASalvo(cola, corregida, fila, venueId, texto) ?: noSeGuardo() }
            }
        } finally {
            if (!retenida) cola.soltar(llave)
        }
    }

    /**
     * Venta con premio (Codex r2 #4/#5): camino de hoy, sin fila. La venta y su premio se quedan en el carrito mientras se manda
     * (la pantalla está bloqueada); sólo si la cuenta se crea sale del carrito lo capturado. Si falla, no se toca nada.
     */
    private suspend fun diferirConPremio(
        cola: com.avoqado.pos.payment.data.PayLaterQueue,
        s: VentaSeparada,
        venueId: String,
        enLinea: String,
    ): ResultadoPagarDespues {
        val copia = s.venta.carritoGuardadoId
        val d = cola.mandar(venueId, enLinea)
        if (d !is DesenlacePagarDespues.Creada) {
            // No se creó (o, sin respuesta, no se sabe: riesgo aceptado de hoy). La copia vuelve a poderse restaurar.
            copia?.let { savedCartsRepository.soltarPagarDespues(it) }
            return ResultadoPagarDespues.Error(
                when (d) {
                    is DesenlacePagarDespues.Rechazada -> {
                        if (d.cambios.isNotEmpty()) {
                            // 🔴 409 de precio: se corrige SIN congelar la venta del carrito (si sigue siendo ella) y se pide el catálogo.
                            _cartState.update {
                                if (it.identidadDeVenta == s.venta.identidadDeVenta) it.conPreciosDelServidor(d.cambios, congelar = false) else it
                            }
                            refreshProducts()
                            avisoDeCambioDePrecio(d.cambios, totalNuevoCents = _cartState.value.totalCents)
                        } else {
                            d.mensaje
                        }
                    }
                    is DesenlacePagarDespues.SesionVencida -> d.mensaje
                    else -> PAGAR_DESPUES_PREMIO_SIN_RED
                },
            )
        }
        // Creada: si el retiro de su copia no se escribe, su marca vinculante la sigue bloqueando (la cuenta ya existe).
        cola.retirarCopia(copia)
        separarDelCarrito(s.venta)
        // Si el servidor no confirmó el premio como lo anunció el carrito, se DICE.
        _avisoDelPremio.value = com.avoqado.pos.payment.data.model.cobroConPremio(
            s.venta.totalCents,
            s.venta.stampRewardCents,
            d.orden?.stampReward,
        ).aviso
        // Codex A-7: el premio puede salir bien y la cuenta quedar por otro importe (un extra que subió): también se dice.
        return when (val total = d.totalCents) {
            s.venta.totalCents -> ResultadoPagarDespues.Creada
            null -> ResultadoPagarDespues.Aviso(com.avoqado.pos.payment.data.PAGAR_DESPUES_IMPORTE_DESCONOCIDO)
            else -> ResultadoPagarDespues.Aviso(pagarDespuesOtroImporte(s.cliente.name, total, s.venta.totalCents))
        }
    }

    /** 🔴 409 de precio: no se creó. Se corrige SIN congelar (el reintento lleva el % recalculado) y se pide el catálogo. */
    private fun conPreciosDelRechazo(s: VentaSeparada, d: DesenlacePagarDespues.Rechazada): Pair<VentaSeparada, String> {
        if (d.cambios.isEmpty()) return s to d.mensaje
        val corregida = s.venta.conPreciosDelServidor(d.cambios, congelar = false)
        refreshProducts()
        return s.copy(venta = corregida) to avisoDeCambioDePrecio(d.cambios, totalNuevoCents = corregida.totalCents)
    }

    /**
     * La venta no se dejó para después: vuelve al carrito o a «Carritos guardados». 🔴 Codex A-4: primero su fila sale del
     * reenvío (RECUPERAR; nunca se manda, como venta sin red se saltaría el rechazo de precio) y sólo se borra cuando la venta
     * ya está en el carrito o escrita. Si no se pudo guardar, la fila se queda a la vista y se dice que NO se guardó.
     *
     * Codex r2: UNA sola captura con id estable (#1) viaja en la fila y es la que se guarda: trasladarla dos veces deja un solo
     * carrito. Si ni RECUPERAR ni el borrado se confirman (#3), la venta NO vuelve: null, y quien llama retiene la marca.
     */
    private suspend fun ponerASalvo(
        cola: com.avoqado.pos.payment.data.PayLaterQueue,
        s: VentaSeparada,
        fila: com.avoqado.pos.payment.data.PayLaterRow,
        venueId: String,
        motivo: String,
    ): ResultadoPagarDespues.Error? {
        val captura = comoCarritoGuardado(s.venta, s.cliente.name, s.cliente.id)
        val porRecuperar: (com.avoqado.pos.payment.data.PayLaterRow) -> com.avoqado.pos.payment.data.PayLaterRow =
            { it.copy(estado = com.avoqado.pos.payment.data.PayLaterRow.Estado.RECUPERAR, motivo = motivo, venta = captura) }
        val recuperable = cola.cambiar(fila.id, porRecuperar)
        if (!recuperable && !cola.borrar(fila.id)) return null
        devolverVenta(s, venueId, motivo, captura)?.let { texto ->
            if (recuperable) cola.borrar(fila.id)
            return ResultadoPagarDespues.Error(texto)
        }
        if (recuperable || cola.guardar(porRecuperar(fila))) return ResultadoPagarDespues.Error(pagarDespuesNiGuardada(s.cliente.name))
        // ponytail: tres escrituras fallidas con una buena en medio; la venta sólo quedó en memoria y así se dice.
        Log.e("🛒", "La venta de ${s.cliente.name} no se pudo guardar en ningún lado: el disco no escribe")
        return ResultadoPagarDespues.Error(PAGAR_DESPUES_NO_SE_GUARDO)
    }

    /**
     * La venta vuelve al carrito si sigue vacío (y en la misma sucursal); si ya hay otra, se guarda [captura] en «Carritos
     * guardados». Nunca se mezcla con la nueva (R3-2). null = no se pudo guardar.
     */
    private fun devolverVenta(s: VentaSeparada, venueId: String, motivo: String, captura: SavedCart): String? {
        if (_cartState.value.isEmpty && _selectedCustomer.value == null && secureStorage.venueId == venueId) {
            // Directo y en este orden: `setSelectedCustomer` le quitaría el premio a la venta que vuelve.
            _selectedCustomer.value = s.cliente
            _cartState.value = s.venta
            _referralCode.value = s.referralCode
            _referralValidation.value = s.referralValidation
            return motivo
        }
        return if (savedCartsRepository.saveCart(captura)) pagarDespuesACarritosGuardados(s.cliente.name) else null
    }

    fun getCartForPayment(): CartState = _cartState.value
}
