package com.avoqado.pos.maquina

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ServerErrorText
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.payment.data.model.SincronizacionDelCobro
import com.avoqado.pos.printing.data.PrinterService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom
import java.util.Date
import java.util.Random
import javax.inject.Inject
import javax.inject.Singleton

// MARK: - Textos fijos de la caja con máquina (plan 1a, «Textos fijos»)

const val TEXTO_SIN_CENTAVOS = "La máquina no da centavos: paga con tarjeta"
const val TEXTO_CON_DESCUENTO = "Este carrito tiene descuento: cóbralo con tarjeta o en caja"
const val TEXTO_CON_PREMIO = "Este carrito tiene un premio de lealtad: cóbralo con tarjeta o en caja"
const val TEXTO_PRODUCTO_NO_DISPONIBLE = "Un producto de este carrito ya no está disponible: quítalo o cóbralo con tarjeta"
const val TEXTO_NO_COBRABLE = "Este carrito no se puede cobrar en la máquina: cóbralo con tarjeta"
/** Codex servidor r7 #2: el carrito pasa del tope que la máquina puede subir. */
const val TEXTO_CARRITO_MUY_GRANDE = "Este carrito es demasiado grande para la máquina: divídelo o cóbralo con tarjeta"
const val TEXTO_POCO_CAMBIO = "La máquina tiene poco cambio: paga con el monto exacto o con tarjeta"
const val TEXTO_NO_RESPONDE = "La máquina no responde"
const val TEXTO_OCUPADA = "La máquina está ocupada"
const val TEXTO_VENTA_CON_INTENTO = "Esta venta ya tiene un cobro en la máquina: espera a que termine o a que un gerente lo resuelva en el panel"
const val TEXTO_VENTA_EN_DUDA = "Esta venta espera a la máquina: un gerente la resuelve en el panel"
const val TEXTO_SOLO_VENTA_COMPLETA = "La máquina cobra sólo una venta nueva y completa: cóbrala con tarjeta"
const val TEXTO_SOLO_MAQUINA_O_TARJETA = "En esta PC se cobra con la máquina o con tarjeta"
/** Recorte R2 (21-nov): las dudas se resuelven sólo en el panel; la caja lo dice y espera. */
const val TEXTO_ESPERANDO_AL_PANEL = "Esperando a la máquina: un gerente lo resuelve en el panel"
const val TEXTO_SIGUES_AHI = "¿Sigues ahí? Toca para continuar"
const val TEXTO_AYUDA = "¿Necesitas ayuda? Pídela al personal"
const val TEXTO_COTIZACION_VENCIDA = "No se pudo confirmar el precio a tiempo: inténtalo otra vez"
const val TEXTO_COBRO_ABANDONADO = "Se salió de este cobro antes de mandarlo a la máquina: no se cobró nada"
/** Codex código POS r5 #3: lo que dijo la máquina contradice la resolución del gerente: la deuda la decide el panel. */
const val TEXTO_REVISAR_EN_EL_PANEL = com.avoqado.pos.printing.receiptlayout.ReceiptLabels.REVISAR_EN_EL_PANEL
/** Decisión A (Codex código POS r5 #2 y r6): un carrito traído con «Restaurar» nunca va a la máquina. */
const val TEXTO_VENTA_RESTAURADA = "Esta venta se restauró: cóbrala con tarjeta o empieza un carrito nuevo"
/**
 * Decisión B (Codex código POS r6 y el coordinador, 21-nov): un carrito ligado a una reserva o a una clase NUNCA se cobra en la máquina, con o
 * sin internet: recepción puede abrir y cobrar la misma reserva mientras la máquina recibe el dinero.
 */
const val TEXTO_RESERVA_EN_CAJA = "Las reservas se cobran en caja o con tarjeta"
/**
 * Codex código POS r7 #1 (regla pegajosa del coordinador): esta venta ya mandó un cobro con tarjeta a la terminal. Nunca vence: si esa
 * tarjeta se aprobó tarde, la máquina cobraría la misma compra otra vez.
 */
const val TEXTO_VENTA_CON_TARJETA = "Esta venta ya intentó cobrarse con tarjeta: termínala con tarjeta o empieza un carrito nuevo"

/** Codex código r1 #12: «Hay N tickets sin imprimir». */
fun textoDeTicketsSinImprimir(n: Int): String = if (n == 1) "Hay 1 ticket sin imprimir" else "Hay $n tickets sin imprimir"

/** «Te debemos $X de cambio: pasa a caja a cobrarlo · Folio AQ…» (plan 1a, textos fijos; C7). */
fun textoDeLaDeuda(cents: Long, referenceCC: String): String =
    "Te debemos ${com.avoqado.pos.core.util.formatMoneyFromCents(cents.toInt())} de cambio: pasa a caja a cobrarlo · Folio $referenceCC"

// MARK: - Reglas puras de la caja

/** Lo que la pantalla de cobro sabe de la máquina física, en vivo. */
data class LaMaquinaAhora(
    val conexion: Conexion = Conexion.DESCONECTADA,
    /** Su último `sockets`/`status` dijo `cobrando:true` (alguien la usa, también desde su pantalla). */
    val ocupada: Boolean = false,
    val cambio: NivelDeCambio = NivelDeCambio.DESCONOCIDO,
    /** La última `changeBar` (el aviso de poco cambio la lee). */
    val changeBar: String? = null,
)

/** La fila «Efectivo en la máquina» de la selección de método. [avisoDeCambio]: avisa y deja seguir (Codex código r1 #16). */
data class FilaDeLaMaquina(val visible: Boolean, val habilitada: Boolean, val motivo: String?, val avisoDeCambio: String?)

/**
 * La fila de la máquina. No aparece en una venta sin productos; en gris con la razón si la venta ya tiene un cobro en duda, si
 * no es una venta nueva y completa (la máquina sólo cobra carritos nuevos: la orden nace con el cierre, R4-1), si la máquina no
 * responde o está ocupada. Con poco cambio (o sin saberlo) avisa y deja seguir.
 */
fun filaDeLaMaquina(
    ahora: LaMaquinaAhora,
    conProductos: Boolean,
    ventaNueva: Boolean,
    ventaEnDuda: Boolean,
    cobroVivo: Boolean,
    /** Codex código POS r7 #1: esta venta ya mandó alguna vez un cobro con tarjeta a la terminal (pegajoso, nunca vence). */
    intentoConTarjeta: Boolean = false,
    /** Decisión A (Codex código POS r5 #2 y r6): la trajo «Restaurar» (o volvió ligada a su copia guardada). */
    restaurada: Boolean = false,
    /** Decisión B (Codex código POS r6): va ligada a una reserva o a una clase. */
    conReserva: Boolean = false,
): FilaDeLaMaquina {
    val motivo = when {
        ventaEnDuda -> TEXTO_VENTA_EN_DUDA
        restaurada -> TEXTO_VENTA_RESTAURADA
        intentoConTarjeta -> TEXTO_VENTA_CON_TARJETA
        conReserva -> TEXTO_RESERVA_EN_CAJA
        !ventaNueva -> TEXTO_SOLO_VENTA_COMPLETA
        ahora.conexion != Conexion.CONECTADA -> TEXTO_NO_RESPONDE
        ahora.ocupada || cobroVivo -> TEXTO_OCUPADA
        else -> null
    }
    return FilaDeLaMaquina(
        visible = conProductos,
        habilitada = conProductos && motivo == null,
        motivo = motivo,
        avisoDeCambio = avisoDeCambio(ahora.changeBar),
    )
}

/**
 * R5#4 y la decisión «a»: lo que la máquina NO cobra, leído del MISMO pedido que sube, con la regla de `pedidoConDescuento` del
 * SERVIDOR (premio de sellos, descuento de cuenta o de renglón, cupón, descuento del cliente o del catálogo, cortesía, ajuste de
 * precio, promoción). Se revisa ANTES de la red: sin red tampoco sale nada que al subir sería incidencia. null = se puede.
 */
fun motivoSinMaquinaDelPedido(pedido: JSONObject): String? {
    fun texto(o: JSONObject, llave: String) = !o.isNull(llave) && o.optString(llave).isNotBlank()
    // Decisión B (Codex código POS r6): una reserva o una clase se cobra en caja o con tarjeta, con o sin red. Antes de cotizar.
    if (texto(pedido, "reservationId")) return TEXTO_RESERVA_EN_CAJA
    if (texto(pedido, "stampRewardId")) return TEXTO_CON_PREMIO
    val items = pedido.optJSONArray("items")
    val renglonConDescuento = (0 until (items?.length() ?: 0)).any { i ->
        val r = items!!.optJSONObject(i) ?: return@any false
        texto(r, "discountId") || r.optBoolean("isCortesia") || (r.has("adjustedUnitPrice") && !r.isNull("adjustedUnitPrice")) ||
            (r.has("promotionRef") && !r.isNull("promotionRef"))
    }
    val conDescuento = pedido.optInt("discount", 0) > 0 || renglonConDescuento ||
        listOf("orderDiscountId", "couponCode", "descuentoDeCatalogoId").any { texto(pedido, it) } ||
        (pedido.has("descuentoDelCliente") && !pedido.isNull("descuentoDelCliente"))
    return TEXTO_CON_DESCUENTO.takeIf { conDescuento }
}

/** El `motivo` de la cotización (`cotizacionDeLaMaquina.ts`) en palabras de la pantalla. */
fun textoDelMotivo(motivo: String?): String = when (motivo) {
    "CENTAVOS" -> TEXTO_SIN_CENTAVOS
    "DESCUENTO" -> TEXTO_CON_DESCUENTO
    "PREMIO_PENDIENTE" -> TEXTO_CON_PREMIO
    "PRODUCTO_NO_DISPONIBLE" -> TEXTO_PRODUCTO_NO_DISPONIBLE
    // Decisión B (SERVIDOR 49703c93c, Codex servidor r8): reemplaza a YA_PAGADA y RESERVA_CON_CUENTA. La PC ya no cotiza reservas.
    "RESERVA" -> TEXTO_RESERVA_EN_CAJA
    "CARRITO_MUY_GRANDE" -> TEXTO_CARRITO_MUY_GRANDE
    else -> TEXTO_NO_COBRABLE
}

/**
 * C7 (R3#11, R5#9) y Codex código r1 #13: lo que una fila le debe al cliente ([CobroEnMaquinaEntity.aDeberCents]: el cambio que no dio o
 * lo que no devolvió, y al subir lo que dice el SERVIDOR, que suma la diferencia de precio) y el título de su comprobante. null = nada
 * que comprobar (un GUARDADO nunca: nadie está para recibirlo).
 */
fun deudaDelComprobante(fila: CobroEnMaquinaEntity): Pair<String, Int>? {
    val desenlace = fila.desenlace?.takeIf { it != Desenlace.GUARDADO } ?: return null
    // Codex código POS r5 #3: si lo físico contradice la resolución, ninguna deuda en papel hasta que lo revise el panel.
    if (fila.aDeberCents <= 0 || fila.porRevisarEnElPanel()) return null
    val excedente = fila.respuestaServidor?.let { runCatching { JSONObject(it).optInt("excedenteCents", 0) }.getOrNull() } ?: 0
    return (if (desenlace == Desenlace.PAGADO && excedente > 0) "COMPROBANTE DE DIFERENCIA" else "COMPROBANTE DE LA MÁQUINA") to fila.aDeberCents
}

/**
 * Codex código POS r4 #11: el papel que se congela con el intento, de lo que armó la caja: los renglones (los de la cotización, o los del
 * carrito sin red), el IVA cobrado aparte y quién atendió. Así el ticket armado de la fila sale igual que el de la pantalla. Vive en la
 * columna `renglonesJson` (sin migración); una fila de antes guarda ahí sólo el arreglo de renglones.
 */
fun papelParaCongelar(renglones: String?, impuestoAgregadoCents: Int, atendio: String?): String =
    JSONObject().put("renglones", renglones?.let { runCatching { org.json.JSONArray(it) }.getOrNull() } ?: org.json.JSONArray())
        .put("impuestoAgregadoCents", impuestoAgregadoCents).put("atendio", atendio ?: JSONObject.NULL).toString()

private fun papelCongelado(json: String?): JSONObject? =
    json?.trim()?.takeIf { it.startsWith("{") }?.let { runCatching { JSONObject(it) }.getOrNull() }

/** Codex código r1 #14: los renglones congelados de la fila (`[{name, quantity, unitCents, totalCents, modifiers?}]`), para el ticket. */
fun renglonesDelTicket(json: String?): List<com.avoqado.pos.printing.data.model.ReceiptItem>? {
    val arreglo = papelCongelado(json)?.optJSONArray("renglones") ?: json?.let { runCatching { org.json.JSONArray(it) }.getOrNull() } ?: return null
    return (0 until arreglo.length()).mapNotNull { i ->
        val r = arreglo.optJSONObject(i) ?: return@mapNotNull null
        val modificadores = r.optJSONArray("modifiers")?.let { m -> (0 until m.length()).map { m.optString(it) } }?.ifEmpty { null }
        com.avoqado.pos.printing.data.model.ReceiptItem(
            name = r.optString("name"),
            quantity = r.optInt("quantity", 1),
            unitPrice = r.optInt("unitCents"),
            totalPrice = r.optInt("totalCents"),
            modifiers = modificadores,
        )
    }.takeIf { it.isNotEmpty() }
}

/** Codex código r1 #14: los renglones del ticket de la caja, en el mismo formato que los de la cotización. */
fun renglonesParaCongelar(items: List<com.avoqado.pos.printing.data.model.ReceiptItem>): String =
    org.json.JSONArray(items.map { r ->
        JSONObject().put("name", r.name).put("quantity", r.quantity).put("unitCents", r.unitPrice).put("totalCents", r.totalPrice)
            .apply { r.modifiers?.let { put("modifiers", org.json.JSONArray(it)) } }
    }).toString()

/**
 * Codex código r1 #12: el ticket de una venta pagada de la máquina, armado de su fila (sin el carrito, que ya no está tras reiniciar):
 * los renglones congelados, lo recibido, el cambio, lo que no dio de cambio y «Precio por confirmar» si se cobró sin cotizar. Codex código
 * POS r4 #11: los totales salen con la MISMA regla del ticket de la caja ([com.avoqado.pos.printing.data.model.TotalesDelTicket]) y el IVA
 * aparte y quién atendió que se congelaron al empezar.
 */
fun ticketDeLaFila(fila: CobroEnMaquinaEntity, negocio: String): com.avoqado.pos.printing.data.model.ReceiptData {
    val renglones = renglonesDelTicket(fila.renglonesJson).orEmpty()
    val papel = papelCongelado(fila.renglonesJson)
    val totales = com.avoqado.pos.printing.data.model.TotalesDelTicket.desdeLoCobrado(
        cobrado = fila.saleCents,
        propina = fila.tipCents,
        subtotalBruto = renglones.takeIf { it.isNotEmpty() }?.sumOf { it.totalPrice } ?: fila.saleCents,
        cargo = 0,
        impuestoAgregado = papel?.optInt("impuestoAgregadoCents", 0) ?: 0,
    )
    // Codex código POS r7: «Recibido», «Cambio» y «Te debemos» salen de las columnas físicas ([conLoFisico]), nunca de la fila registrada.
    return conLoFisico(fila, com.avoqado.pos.printing.data.model.ReceiptData(
        orderNumber = fila.referenceCC,
        orderType = "",
        items = renglones,
        subtotal = totales.subtotal,
        taxAmount = totales.impuesto,
        tipAmount = fila.tipCents.takeIf { it > 0 },
        discountAmount = totales.descuento.takeIf { it > 0 },
        total = totales.total,
        paymentMethod = "Efectivo",
        cashierName = papel?.takeIf { !it.isNull("atendio") }?.optString("atendio")?.takeIf { it.isNotBlank() },
        venueName = negocio,
        date = Date(fila.cierreEn ?: fila.creadoEn),
        cashMachine = com.avoqado.pos.printing.receiptlayout.ReceiptCashMachine(fila.referenceCC, priceToConfirm = fila.quoteId == null),
    ))
}

/** C7: el comprobante de [fila] (mismo folio que la venta), o null si no debe nada. */
fun comprobanteDeLaMaquina(fila: CobroEnMaquinaEntity, negocio: String): com.avoqado.pos.printing.data.model.ReceiptData? {
    val (titulo, debe) = deudaDelComprobante(fila) ?: return null
    return comprobante(fila, negocio, titulo, debe)
}

private fun comprobante(fila: CobroEnMaquinaEntity, negocio: String, titulo: String, debe: Int?) =
    com.avoqado.pos.printing.data.model.ReceiptData(
        orderNumber = fila.referenceCC,
        orderType = "",
        items = emptyList(),
        subtotal = 0,
        taxAmount = 0,
        total = debe ?: 0,
        venueName = negocio,
        date = Date(fila.cierreEn ?: fila.creadoEn),
        kind = com.avoqado.pos.printing.receiptlayout.KIND_COMPROBANTE_DE_LA_MAQUINA,
        cashMachine = com.avoqado.pos.printing.receiptlayout.ReceiptCashMachine(
            fila.referenceCC, debe?.toLong(), title = titulo, revisarEnElPanel = fila.porRevisarEnElPanel(),
        ),
    )

/**
 * Codex código r2 H10 y r3 #11, #12: EL papel de un folio, leído de SU fila en el momento de imprimir (nunca de la pantalla). Una venta
 * pagada sin incidencia lleva su ticket ([ticketDeVenta], el que armó la caja, o el de la fila). Si el SERVIDOR la dejó como incidencia
 * (o la rechazó: `conflicto`), NO hay venta: sale el comprobante del dinero con su folio. Un CANCELADO que no devolvió todo, su
 * comprobante. null = no hay papel que dar (un GUARDADO, un CANCELADO sin deuda).
 */
fun papelDeLaFila(
    fila: CobroEnMaquinaEntity,
    negocio: String,
    ticketDeVenta: com.avoqado.pos.printing.data.model.ReceiptData? = null,
): com.avoqado.pos.printing.data.model.ReceiptData? = when {
    // Codex código POS r5 #3 y r6: la deuda, lo recibido y el cambio se vuelven a decidir con la fila AL IMPRIMIR; la pantalla no los impone.
    fila.desenlace == Desenlace.PAGADO && fila.conflicto == null -> conLoFisico(fila, ticketDeVenta ?: ticketDeLaFila(fila, negocio))
    fila.desenlace == Desenlace.PAGADO -> comprobanteDeLaMaquina(fila, negocio) ?: comprobante(fila, negocio, "COMPROBANTE DE LA MÁQUINA", null)
    else -> comprobanteDeLaMaquina(fila, negocio)
}

/**
 * Decisión C (Codex código POS r6) y Codex código POS r7: el ticket de venta de [fila] —el que armó la caja o el de la fila— con «Recibido»,
 * «Cambio» y «Te debemos» de las columnas FÍSICAS ([recibidoYCambioEnPapel]), nunca de lo que tecleó un gerente, de un cierre que el motor
 * descartó ni del diario (que se recorta). Si no constan, esos renglones no salen; si lo físico no respalda lo registrado, ni «Te debemos»:
 * «Revisar en el panel».
 */
private fun conLoFisico(
    fila: CobroEnMaquinaEntity,
    t: com.avoqado.pos.printing.data.model.ReceiptData,
): com.avoqado.pos.printing.data.model.ReceiptData {
    val enPapel = fila.recibidoYCambioEnPapel()
    val maquina = t.cashMachine ?: com.avoqado.pos.printing.receiptlayout.ReceiptCashMachine(fila.referenceCC, priceToConfirm = fila.quoteId == null)
    val revisar = fila.porRevisarEnElPanel()
    // Un comprobante (el de diferencia que pide «Imprimir comprobante») trae su importe del SERVIDOR ([deudaDelComprobante]); el ticket de
    // venta, el cambio físico que no se dio. Los dos sólo si lo físico respalda lo registrado.
    val debe = when {
        revisar -> null
        t.kind == com.avoqado.pos.printing.receiptlayout.KIND_COMPROBANTE_DE_LA_MAQUINA -> maquina.owedCents
        else -> enPapel?.let { (recibido, cambio) -> deudaFisica(Desenlace.PAGADO, fila.dueCents, recibido, cambio).toLong() }
    }
    return t.copy(
        cashTendered = enPapel?.first,
        changeAmount = enPapel?.second,
        cashMachine = maquina.copy(owedCents = debe?.takeIf { it > 0 }, revisarEnElPanel = revisar),
    )
}

/** La venta que la caja manda a la máquina. Los importes son los LOCALES: sin red se cobran ésos (R4-2). */
data class VentaPorCobrar(
    val venueId: String,
    /** La identidad del carrito: a lo más un intento abierto, en duda o pagado por venta. */
    val llaveDeVenta: String,
    /** El cuerpo de `POST /orders` de este carrito: se cotiza y se congela tal cual (R5#1). */
    val pedidoJson: String,
    val saleCents: Int,
    val tipCents: Int,
    /** Codex código r1 #14: los renglones del carrito ([renglonesParaCongelar]); sin red, el ticket sale de éstos. */
    val renglonesLocales: String? = null,
    /** Codex código POS r4 #11: el IVA cobrado aparte del ticket de la caja; se congela con el intento. */
    val impuestoAgregadoCents: Int = 0,
    /** Codex código POS r4 #11: «Atendió» del ticket de la caja; se congela con el intento. */
    val atendio: String? = null,
)

sealed interface InicioEnLaMaquina {
    data class Iniciado(val referenceCC: String) : InicioEnLaMaquina
    /** No se guardó ni se mandó nada: [texto] lo dice la pantalla y ofrece tarjeta. */
    data class NoSeCobra(val texto: String) : InicioEnLaMaquina
}

/**
 * 🔴 La máquina de efectivo de ESTA PC (fase 1a, C5): el único dueño del enlace con la QuickPay ([CobroConMaquina] sobre
 * [ClienteDeLaMaquina]) para toda la app. Cotiza, congela el carrito en `cobros_en_maquina` ANTES del `paycash` (la orden NO se crea
 * en el SERVIDOR: viaja con el cierre, R4-1) y deja que la subida ([SubidaDeLaMaquina]) mande el desenlace.
 *
 * Sólo cobra en WINDOWS con la casilla prendida y la credencial de esta PC ([activa]); sin eso la caja queda como hoy.
 */
@Singleton
class MaquinaDeEfectivo internal constructor(
    private val prefs: MaquinaDeEfectivoPrefs,
    private val boveda: BovedaDeLaMaquina,
    private val dao: CobroEnMaquinaDao,
    private val api: CierresDeLaMaquinaApi,
    private val subida: SubidaDeLaMaquina,
    private val turno: TurnoDelNegocio,
    private val roles: RoleManager,
    private val printerService: PrinterService,
    private val secureStorage: SecureStorage,
    private val cliente: CanalDeLaMaquina,
    private val scope: CoroutineScope,
    private val reloj: () -> Long,
    aleatorio: Random = SecureRandom(),
    /** Codex código r2 H3 y H11: el reloj monótono (ms) de los plazos y de la vigencia de la cotización; la hora de Windows se mueve. */
    private val monotonico: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    /** El enlace a la red local NO usa el cliente de la API: a la máquina no se le manda la sesión. */
    @Inject constructor(
        prefs: MaquinaDeEfectivoPrefs,
        boveda: BovedaDeLaMaquina,
        dao: CobroEnMaquinaDao,
        api: CierresDeLaMaquinaApi,
        subida: SubidaDeLaMaquina,
        turno: TurnoDelNegocio,
        roles: RoleManager,
        printerService: PrinterService,
        secureStorage: SecureStorage,
    ) : this(
        prefs, boveda, dao, api, subida, turno, roles, printerService, secureStorage,
        ClienteDeLaMaquina(OkHttpClient(), CoroutineScope(SupervisorJob() + Dispatchers.IO), System::currentTimeMillis),
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        System::currentTimeMillis,
    )

    /**
     * Codex código r3 #11: los folios que una pantalla de «Cobrar» tiene a la vista. Su ticket lo arma ella (con más datos); el de los
     * demás sale de la fila. Va antes de [cobro]: su PAGADO lo lee.
     */
    private val aLaVista: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

    /** Tras cada cierre o duda, la subida los manda sin esperar a su reloj. La `fecha` de la máquina es hora del negocio (#15). */
    val cobro = CobroConMaquina(
        cliente, dao, reloj, scope, aleatorio, alTerminar = subida::disparar,
        zona = { com.avoqado.pos.core.util.VenueTimeZone.zoneIdFor(secureStorage.venueTimezone) }, monotonico = monotonico,
        alCerrar = ::alCerrarEnLaMaquina,
    )

    /** Codex código r3 #11: CajaConMaquina dice qué folio tiene a la vista (y lo suelta al salir de él). */
    fun aLaVista(referenceCC: String, si: Boolean) {
        if (si) aLaVista.add(referenceCC) else aLaVista.remove(referenceCC)
    }

    /**
     * Codex código r3 #11: el cierre en vivo de la máquina, ya en disco. Si ninguna pantalla tiene el folio a la vista, su papel (el
     * ticket, o el comprobante de un CANCELADO que no devolvió todo) sale de la fila, por el mismo reclamo. Sin comanda a cocina en la 1a
     * (recorte R3, 21-nov).
     */
    @Suppress("UNUSED_PARAMETER")
    private fun alCerrarEnLaMaquina(referenceCC: String, desenlace: Desenlace) {
        if (referenceCC !in aLaVista) scope.launch { imprimirPapelDelFolio(referenceCC) }
    }

    private val _ahora = MutableStateFlow(LaMaquinaAhora())
    val ahora: StateFlow<LaMaquinaAhora> = _ahora.asStateFlow()

    /** Lo que impide subir (sin emparejar, 403). */
    val avisoDeSubida: StateFlow<String?> get() = subida.aviso

    init {
        // UNDISPATCHED: suscritos al construir (`recibidos` no repite lo pasado).
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            cliente.recibidos.collect { r ->
                when (val m = r.mensaje) {
                    is MensajeDeLaMaquina.Sockets -> _ahora.update {
                        it.copy(ocupada = m.cobrando, cambio = nivelDeCambio(m.changeBar), changeBar = m.changeBar)
                    }
                    is MensajeDeLaMaquina.Status -> _ahora.update {
                        it.copy(ocupada = m.cobrando, cambio = m.changeBar?.let(::nivelDeCambio) ?: it.cambio, changeBar = m.changeBar ?: it.changeBar)
                    }
                    else -> Unit
                }
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            cliente.conexion.collect { c -> _ahora.update { it.copy(conexion = c) } }
        }
    }

    /**
     * ¿Esta PC cobra con la máquina? La casilla (sólo se prende en WINDOWS, C6), una credencial que ESTA PC descifra (R5#3) y la sesión en
     * la sucursal con que se emparejó (Codex código r1 #10): en otra, la fila no aparece y Ajustes dice con cuál está emparejada.
     */
    fun activa(): Boolean = prefs.activaEn(secureStorage.venueId) && runCatching { boveda.leer() }.getOrNull() != null

    /** Codex código r2 H1: esta PC tuvo una máquina (la IP se queda al desemparejar): sus ventas se revisan en disco aunque esté apagada. */
    fun tuvoMaquina(): Boolean = prefs.activa || prefs.ip != null

    private var ipConectada: String? = null
    private var recuperada = false

    /**
     * Al iniciar sesión y al abrir cada cobro. La primera vez rescata lo que dejó un proceso que murió (un `EN_CURSO` pasa a duda,
     * nunca se libera); siempre deja el enlace con la IP de hoy, o lo corta si la casilla se apagó.
     */
    @Synchronized
    fun arrancar() {
        if (!recuperada) {
            recuperada = true
            scope.launch {
                try {
                    cobro.recuperar()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    recuperada = false
                }
            }
        }
        val ip = prefs.ip?.takeIf { activa() }
        if (ip == ipConectada) return
        ipConectada = ip
        if (ip == null) cliente.desconectar() else runCatching { cliente.conectar(ip) }.onFailure { ipConectada = null }
    }

    suspend fun fila(referenceCC: String): CobroEnMaquinaEntity? = dao.porReferencia(referenceCC)

    /** Recorte R2: la fila en vivo (la pantalla de una duda se entera de cómo la resolvió el panel cuando la subida lo trae). */
    fun filaEnVivo(referenceCC: String): Flow<CobroEnMaquinaEntity?> = dao.observar(referenceCC)

    /** Codex código r1 #7: el intento abierto o en duda de esta venta (la pantalla de la máquina lo recupera tras reiniciar). */
    suspend fun intentoDeLaVenta(llaveDeVenta: String): CobroEnMaquinaEntity? = dao.intentoDeLaVenta(llaveDeVenta)

    /** Las ventas apartadas para la máquina que todavía no tienen su fila (se cotizan o piden el turno), con la ficha de quien las apartó. */
    private val apartadas = java.util.concurrent.ConcurrentHashMap<String, Any>()

    /**
     * Codex código r1 #1: «Efectivo en la máquina» APARTA la venta en el mismo toque, antes de pedir el turno o cotizar (la red tarda):
     * desde aquí la tarjeta no sale para esta venta. Se suelta en [cobrar] o al salir de la apertura (Codex código r3 #5): si el
     * `paycash` salió ya frena su fila; si no, nada quedó en la máquina. Devuelve la ficha del apartado (con ella se suelta, para que
     * soltar uno viejo nunca suelte el de otra apertura), o null si ya estaba apartada.
     */
    fun apartar(llaveDeVenta: String): Any? = Any().takeIf { apartadas.putIfAbsent(llaveDeVenta, it) == null }

    /** Suelta el apartado [ficha] de [llaveDeVenta] (sólo si sigue siendo ése). */
    fun soltar(llaveDeVenta: String, ficha: Any) {
        apartadas.remove(llaveDeVenta, ficha)
    }

    /** Síncrono: lo revisa la tarjeta DESPUÉS de leer el disco, en el mismo paso en que sale. */
    fun apartada(llaveDeVenta: String): Boolean = apartadas.containsKey(llaveDeVenta)

    /** R2#1: mientras esta venta esté apartada o tenga un intento abierto, en duda, pagado o resuelto, no se cobra por otro medio. */
    suspend fun ventaBloqueada(llaveDeVenta: String): Boolean = apartada(llaveDeVenta) || dao.bloqueanLaVenta(llaveDeVenta) > 0

    /**
     * Cotiza y, si se puede, guarda el intento y manda el `paycash` ([CobroConMaquina.cobrar]: la fila ANTES de la red). Lo que
     * no se puede cobrar en la máquina se dice y no deja nada en disco ni en la máquina. [apartado]: la ficha con que la caja ya apartó
     * la venta (si no, se aparta aquí).
     */
    suspend fun cobrar(v: VentaPorCobrar, puerta: PuertaDelCobro = PuertaDelCobro.SIEMPRE, apartado: Any? = null): InicioEnLaMaquina {
        val ficha = apartado ?: apartar(v.llaveDeVenta)
        // Iniciado: su fila ya frena la venta. Cualquier otro final: no quedó nada en la máquina y la tarjeta vuelve a poder.
        try {
            return cotizarYCobrar(v, puerta)
        } finally {
            ficha?.let { soltar(v.llaveDeVenta, it) }
        }
    }

    /**
     * [puerta] (Codex código r2 H5 y r3 #5): si mientras se cotizaba la caja salió de este cobro, el `paycash` ya no sale: nadie está
     * viendo esta venta. La comprobación definitiva va junto con el envío, bajo el candado con que la caja cierra la apertura.
     */
    private suspend fun cotizarYCobrar(v: VentaPorCobrar, puerta: PuertaDelCobro): InicioEnLaMaquina {
        val pedido = runCatching { JSONObject(v.pedidoJson) }.getOrNull() ?: return InicioEnLaMaquina.NoSeCobra(TEXTO_NO_COBRABLE)
        motivoSinMaquinaDelPedido(pedido)?.let { return InicioEnLaMaquina.NoSeCobra(it) }
        try {
            turno.refrescar()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Unit   // sin red se queda el turno guardado
        }
        // Codex código r1 #14: si la cotización vence antes del `paycash` (la PC se pausó), se vuelve a cotizar; dos veces y se dice.
        repeat(2) {
            var sale = v.saleCents
            var tip = v.tipCents
            var quoteId: String? = null
            var renglones = v.renglonesLocales
            var vigenteHasta: Long? = null
            when (val c = cotizar(v.venueId, pedido)) {
                is Cotizacion.Lista -> {
                    sale = c.saleCents; tip = c.tipCents; quoteId = c.quoteId; vigenteHasta = c.vigenteHasta
                    renglones = c.renglones ?: renglones
                }
                is Cotizacion.NoSe -> return InicioEnLaMaquina.NoSeCobra(c.texto)
                Cotizacion.SinRed -> Unit
            }
            if (!cobrableEnMaquina(sale + tip)) return InicioEnLaMaquina.NoSeCobra(TEXTO_SIN_CENTAVOS)
            // Antes de escribir en disco: si ya se salió de este cobro, ni se guarda.
            if (puerta.siSigueAbierta { true } == null) return InicioEnLaMaquina.NoSeCobra(TEXTO_COBRO_ABANDONADO)
            val venta = VentaParaLaMaquina(
                v.venueId, v.llaveDeVenta, v.pedidoJson, sale, tip, turno.turnoActual(), quoteId,
                papelParaCongelar(renglones, v.impuestoAgregadoCents, v.atendio), vigenteHasta, prefs.bindingId,
            )
            return when (val r = cobro.cobrar(venta, puerta)) {
                is ResultadoDeInicio.Iniciado -> InicioEnLaMaquina.Iniciado(r.referenceCC)
                ResultadoDeInicio.NoCobrable -> InicioEnLaMaquina.NoSeCobra(TEXTO_SIN_CENTAVOS)
                ResultadoDeInicio.VentaConIntento -> InicioEnLaMaquina.NoSeCobra(TEXTO_VENTA_CON_INTENTO)
                ResultadoDeInicio.Ocupado -> InicioEnLaMaquina.NoSeCobra(TEXTO_OCUPADA)
                ResultadoDeInicio.SinConexion -> InicioEnLaMaquina.NoSeCobra(TEXTO_NO_RESPONDE)
                ResultadoDeInicio.Abandonado -> InicioEnLaMaquina.NoSeCobra(TEXTO_COBRO_ABANDONADO)
                ResultadoDeInicio.CotizacionVencida -> return@repeat
            }
        }
        return InicioEnLaMaquina.NoSeCobra(TEXTO_COTIZACION_VENCIDA)
    }

    private sealed interface Cotizacion {
        /** [renglones]: los que confirmó el SERVIDOR (#14). [vigenteHasta]: hasta cuándo EMPIEZA un cobro (reloj MONÓTONO de la PC). */
        data class Lista(val saleCents: Int, val tipCents: Int, val quoteId: String?, val renglones: String?, val vigenteHasta: Long?) : Cotizacion
        data class NoSe(val texto: String) : Cotizacion
        /** Sin respuesta útil: se cobra el total local y el excedente cubre un precio que baje (R4-2). */
        data object SinRed : Cotizacion
    }

    private suspend fun cotizar(venueId: String, pedido: JSONObject): Cotizacion {
        // Codex código r1 #14 y r2 H11: la vigencia se cuenta con el reloj MONÓTONO desde ANTES de pedirla. El SERVIDOR firma su `expiresAt`
        // DESPUÉS de recibir la petición, así que este plazo nunca pasa del suyo, y mover la hora de Windows no lo alarga. ponytail: los
        // 10 min del SERVIDOR menos un margen; si cambia allá, cambiar aquí.
        val pedidaEn = monotonico()
        val r = try {
            api.cotizar(venueId, pedido)
        } catch (e: IOException) {
            return Cotizacion.SinRed
        }
        // Caído, sesión vencida (la venta sube al volver a entrar) o muchas peticiones: como sin red.
        if (r.codigo >= 500 || r.codigo == 401 || r.codigo == 408 || r.codigo == 429) return Cotizacion.SinRed
        if (r.codigo != 200) return Cotizacion.NoSe(ServerErrorText.fromResponseBody(r.cuerpo, TEXTO_NO_COBRABLE))
        val j = runCatching { JSONObject(r.cuerpo) }.getOrNull() ?: return Cotizacion.SinRed
        if (!j.optBoolean("cobrableEnMaquina", false)) return Cotizacion.NoSe(textoDelMotivo(j.optString("motivo")))
        val sale = (j.opt("saleCents") as? Number)?.toInt()
        val tip = (j.opt("tipCents") as? Number)?.toInt()
        if (sale == null || tip == null) return Cotizacion.SinRed
        val quoteId = j.optString("quoteId").takeIf { !j.isNull("quoteId") && it.isNotBlank() }
        val renglones = j.optJSONArray("renglones")?.takeIf { it.length() > 0 }?.toString()
        return Cotizacion.Lista(sale, tip, quoteId, renglones, (pedidaEn + VIGENCIA_DE_LA_COTIZACION_MS).takeIf { quoteId != null })
    }

    // MARK: - Ticket y comprobantes (C7)

    /** Codex código r2 H9: los folios que ESTE proceso imprime ahora. Un «imprimiéndose» que no está aquí lo dejó una PC que murió. */
    private val imprimiendo = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Codex código r2 H9 y r3 #11, #12: EL camino del papel de un folio. La impresión automática, «Imprimir recibo» de la pantalla de
     * éxito, «Tickets sin imprimir» y el comprobante pasan TODOS por aquí:
     * - el ORIGINAL se reclama en la fila (`ticketImpreso` 0 → 2, un solo ganador) y sale en UNA impresora de recibos
     *   ([PrinterService.imprimirOriginal]); la marca es el resultado REAL de la impresora;
     * - el papel se decide al imprimir, con lo último que dijo el SERVIDOR en la fila ([papelDeLaFila]): con incidencia, el comprobante
     *   del dinero aunque la pantalla haya armado un ticket de venta;
     * - lo demás es COPIA («REIMPRESIÓN»): el 2 que dejó una PC que murió imprimiendo, o lo que se pide ([aPedido]) cuando el original
     *   ya salió.
     * null = otro lo está imprimiendo ahora, ya salió y nadie pidió copia, o no hay papel que dar.
     */
    suspend fun imprimirPapelDelFolio(
        referenceCC: String,
        ticketDeVenta: com.avoqado.pos.printing.data.model.ReceiptData? = null,
        aPedido: Boolean = false,
    ): PrinterService.PrintOutcome? {
        val negocio = secureStorage.venueName ?: "Avoqado"
        var ya = false
        imprimiendo.update { if (referenceCC in it) { ya = true; it } else it + referenceCC }
        if (ya) return null
        try {
            val gano = dao.reclamarTicket(referenceCC) == 1
            // Se lee DESPUÉS de reclamar: el acuse del SERVIDOR que llegó hasta este instante decide el papel (Codex código r3 #12).
            val fila = dao.porReferencia(referenceCC) ?: return null
            val copia = when {
                gano -> false
                fila.ticketImpreso == TicketDelCobro.IMPRIMIENDO -> true   // lo dejó una PC que murió imprimiéndolo
                aPedido && fila.ticketImpreso == TicketDelCobro.IMPRESO -> true
                else -> return null
            }
            val papel = papelDeLaFila(fila, negocio, ticketDeVenta)
            if (papel == null) {
                if (gano) dao.terminarTicket(referenceCC, TicketDelCobro.PENDIENTE)
                return null
            }
            val r = if (copia) printerService.manualPrintReceipt(papel.copy(reprintedAt = Date(reloj())))
            else printerService.imprimirOriginal(papel)
            // Sólo cambia un reclamado (2): el original que salió (1), el que no salió (0, otra vez pendiente) o el que pudo salir (2).
            dao.terminarTicket(
                referenceCC,
                when {
                    r is PrinterService.PrintOutcome.Printed -> TicketDelCobro.IMPRESO
                    copia -> TicketDelCobro.IMPRIMIENDO   // pudo haber salido antes: se sigue ofreciendo como copia
                    // Codex código POS r5 #1: sólo «sin papel» y «sin impresora» dicen que no salió nada. Cualquier otra falla (Windows
                    // aceptó los bytes y falló al cerrar el trabajo) pudo sacar el original: lo que sigue de este folio es «REIMPRESIÓN».
                    r is PrinterService.PrintOutcome.NoPrinter || r is PrinterService.PrintOutcome.OutOfPaper -> TicketDelCobro.PENDIENTE
                    else -> TicketDelCobro.IMPRIMIENDO
                },
            )
            return r
        } finally {
            imprimiendo.update { it - referenceCC }
        }
    }

    /**
     * El comprobante de [referenceCC] desde la lista de lo que se debe (Ajustes). TODO papel del folio pasa por [imprimirPapelDelFolio] y
     * su reclamo (Codex código POS r4 #10): un CANCELADO o una venta que quedó como incidencia (su comprobante ES el papel del folio), y
     * también el comprobante de diferencia de una venta (el precio bajó al subir). El primero que sale del folio es el original; los
     * demás, «REIMPRESIÓN»: una deuda nunca se presenta dos veces como original. null = esa fila no le debe nada.
     */
    suspend fun imprimirComprobante(referenceCC: String): PrinterService.PrintOutcome? {
        val fila = dao.porReferencia(referenceCC) ?: return null
        val diferencia = if (fila.desenlace == Desenlace.PAGADO && fila.conflicto == null) {
            comprobanteDeLaMaquina(fila, secureStorage.venueName ?: "Avoqado") ?: return null
        } else {
            null
        }
        return imprimirPapelDelFolio(referenceCC, diferencia, aPedido = true)
    }

    /**
     * Lo que la máquina les debe a clientes en la sucursal de la sesión, con su comprobante reimprimible, los más nuevos primero. Codex
     * código r1 #13: la consulta ya sólo trae los que deben; [limite] crece con «Ver más» ([cuantosComprobantes] dice cuántos hay).
     * Codex código POS r5 #3: null = lo físico contradice la resolución: «Revisar en el panel», sin comprobante.
     */
    suspend fun comprobantes(limite: Int = COMPROBANTES_POR_PAGINA): List<Pair<CobroEnMaquinaEntity, Int?>> {
        val venueId = secureStorage.venueId ?: return emptyList()
        return dao.conComprobante(venueId, limite).mapNotNull { f ->
            if (f.porRevisarEnElPanel()) f to null else deudaDelComprobante(f)?.let { f to it.second }
        }
    }

    suspend fun cuantosComprobantes(): Int = secureStorage.venueId?.let { dao.contarComprobantes(it) } ?: 0

    /** Codex código r1 #12: los cobros pagados de la sucursal cuyo papel no salió, en vivo (sin los que este proceso imprime ahora). */
    fun ticketsSinImprimir(): Flow<Int> =
        (secureStorage.venueId?.let(dao::observarTicketsSinImprimir) ?: kotlinx.coroutines.flow.flowOf(emptyList()))
            .combine(imprimiendo) { filas, enCurso -> filas.count { it.referenceCC !in enCurso } }

    /** Lo mismo, una lectura (Ajustes › Máquina de efectivo: se llega con la máquina apagada, Codex código r2 #12). */
    suspend fun cuantosTicketsSinImprimir(): Int =
        secureStorage.venueId?.let { v -> dao.ticketsSinImprimir(v).count { it.referenceCC !in imprimiendo.value } } ?: 0

    /**
     * Codex código r1 #12 y r2 H9/H10: imprime (con el botón, nunca solo) los papeles pendientes, cada uno reclamado y marcado con el
     * resultado REAL de la impresora ([imprimirPapel]); con incidencia sale el comprobante del dinero ([papelDeLaFila]). Devuelve
     * cuántos salieron.
     */
    suspend fun imprimirTicketsPendientes(): Int {
        val venueId = secureStorage.venueId ?: return 0
        return dao.ticketsSinImprimir(venueId).count { fila ->
            imprimirPapelDelFolio(fila.referenceCC) is PrinterService.PrintOutcome.Printed
        }
    }

    /** Codex código r3 #7: ¿el permiso de configurar la máquina está en la sesión? (sólo la parte de IP y emparejar lo pide). */
    fun puedeConfigurar(): Boolean = roles.canConfigureCashMachine

    /** Lo que la pantalla de resultado dice de un cobro de la máquina: su fila, en vivo (Room avisa cuando cambia). */
    fun sincronizacion(referenceCC: String): Flow<SincronizacionDelCobro> = dao.observar(referenceCC).map { f ->
        when {
            f == null -> SincronizacionDelCobro.PENDIENTE
            f.conflicto != null -> SincronizacionDelCobro.RECHAZADA
            f.subido -> SincronizacionDelCobro.SINCRONIZADA
            else -> SincronizacionDelCobro.PENDIENTE
        }
    }.distinctUntilChanged()

    companion object {
        private const val COMPROBANTES_POR_PAGINA = 50
        /** ponytail: la vigencia del SERVIDOR (`VIGENCIA_DE_LA_COTIZACION_MS`, 10 min) menos 30 s de margen. */
        private const val VIGENCIA_DE_LA_COTIZACION_MS = 10 * 60_000L - 30_000L
    }
}
