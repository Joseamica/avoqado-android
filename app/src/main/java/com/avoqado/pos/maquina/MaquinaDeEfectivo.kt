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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom
import java.time.Instant
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
const val TEXTO_POCO_CAMBIO = "La máquina tiene poco cambio: paga con el monto exacto o con tarjeta"
const val TEXTO_NO_RESPONDE = "La máquina no responde"
const val TEXTO_OCUPADA = "La máquina está ocupada"
const val TEXTO_VENTA_CON_INTENTO = "Esta venta ya tiene un cobro en la máquina: espera a que termine o pide a un gerente que lo resuelva"
const val TEXTO_VENTA_EN_DUDA = "Esta venta espera a la máquina: pide a un gerente que la resuelva"
const val TEXTO_SOLO_VENTA_COMPLETA = "La máquina cobra sólo una venta nueva y completa: cóbrala con tarjeta"
const val TEXTO_SOLO_MAQUINA_O_TARJETA = "En esta PC se cobra con la máquina o con tarjeta"
const val TEXTO_ESPERANDO = "Esperando a la máquina"
const val TEXTO_SIGUES_AHI = "¿Sigues ahí? Toca para continuar"
const val TEXTO_AYUDA = "¿Necesitas ayuda? Pídela al personal"
const val TEXTO_SIN_PERMISO_RESOLVER = "Pide a un gerente que entre con su PIN (cambio de usuario) para resolver este cobro"
const val TEXTO_SIN_RED_RESOLVER = "Se necesita internet para resolver: el cobro sigue esperando"
const val TEXTO_NO_SE_SABE_EL_MODO = "Sin saber si la máquina devolvió o guardó el dinero, el cobro sigue esperando"
const val TEXTO_DUDA_SIN_SUBIR = "Este cobro todavía no llega al servidor: inténtalo en un momento"
const val TEXTO_ESCRIBE_LO_QUE_VISTE = "Escribe lo que viste en la máquina"
const val TEXTO_ES_LA_DE_ESTA_VENTA = "Ese cobro es el de esta venta: resuélvelo en su pantalla"
const val TEXTO_COBROS_EN_ESPERA = "Cobros esperando a la máquina"
const val TEXTO_COTIZACION_VENCIDA = "No se pudo confirmar el precio a tiempo: inténtalo otra vez"

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
    /** La última `changeBar` (Codex código r1 #16: el aviso de los 50 centavos la lee). */
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
    /** El total local de la venta (sin cotizar): el aviso de los 50 centavos. */
    dueCents: Int = 0,
): FilaDeLaMaquina {
    val motivo = when {
        ventaEnDuda -> TEXTO_VENTA_EN_DUDA
        !ventaNueva -> TEXTO_SOLO_VENTA_COMPLETA
        ahora.conexion != Conexion.CONECTADA -> TEXTO_NO_RESPONDE
        ahora.ocupada || cobroVivo -> TEXTO_OCUPADA
        else -> null
    }
    return FilaDeLaMaquina(
        visible = conProductos,
        habilitada = conProductos && motivo == null,
        motivo = motivo,
        avisoDeCambio = avisoDeCambio(ahora.changeBar, dueCents),
    )
}

/**
 * R5#4 y la decisión «a»: lo que la máquina NO cobra, leído del MISMO pedido que sube, con la regla de `pedidoConDescuento` del
 * SERVIDOR (premio de sellos, descuento de cuenta o de renglón, cupón, descuento del cliente o del catálogo, cortesía, ajuste de
 * precio, promoción). Se revisa ANTES de la red: sin red tampoco sale nada que al subir sería incidencia. null = se puede.
 */
fun motivoSinMaquinaDelPedido(pedido: JSONObject): String? {
    fun texto(o: JSONObject, llave: String) = !o.isNull(llave) && o.optString(llave).isNotBlank()
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
    else -> TEXTO_NO_COBRABLE
}

/**
 * C7 (R3#11, R5#9) y Codex código r1 #13: lo que una fila le debe al cliente ([CobroEnMaquinaEntity.aDeberCents]: el cambio que no dio o
 * lo que no devolvió, y al subir lo que dice el SERVIDOR, que suma la diferencia de precio) y el título de su comprobante. null = nada
 * que comprobar (un GUARDADO nunca: nadie está para recibirlo).
 */
fun deudaDelComprobante(fila: CobroEnMaquinaEntity): Pair<String, Int>? {
    val desenlace = fila.desenlace?.takeIf { it != Desenlace.GUARDADO } ?: return null
    if (fila.aDeberCents <= 0) return null
    val excedente = fila.respuestaServidor?.let { runCatching { JSONObject(it).optInt("excedenteCents", 0) }.getOrNull() } ?: 0
    return (if (desenlace == Desenlace.PAGADO && excedente > 0) "COMPROBANTE DE DIFERENCIA" else "COMPROBANTE DE LA MÁQUINA") to fila.aDeberCents
}

/** Codex código r1 #14: los renglones congelados de la fila (`[{name, quantity, unitCents, totalCents, modifiers?}]`), para el ticket. */
fun renglonesDelTicket(json: String?): List<com.avoqado.pos.printing.data.model.ReceiptItem>? {
    val arreglo = json?.let { runCatching { org.json.JSONArray(it) }.getOrNull() } ?: return null
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
 * los renglones congelados, lo recibido, el cambio, lo que no dio de cambio y «Precio por confirmar» si se cobró sin cotizar.
 */
fun ticketDeLaFila(fila: CobroEnMaquinaEntity, negocio: String): com.avoqado.pos.printing.data.model.ReceiptData {
    val renglones = renglonesDelTicket(fila.renglonesJson).orEmpty()
    val debe = deudaFisica(Desenlace.PAGADO, fila.dueCents, fila.creditCents, fila.changeDispensedCents)
    return com.avoqado.pos.printing.data.model.ReceiptData(
        orderNumber = fila.referenceCC,
        orderType = "",
        items = renglones,
        subtotal = fila.saleCents,
        taxAmount = 0,
        tipAmount = fila.tipCents.takeIf { it > 0 },
        total = fila.dueCents,
        paymentMethod = "Efectivo",
        venueName = negocio,
        date = Date(fila.cierreEn ?: fila.creadoEn),
        cashTendered = fila.creditCents,
        changeAmount = fila.changeDispensedCents,
        cashMachine = com.avoqado.pos.printing.receiptlayout.ReceiptCashMachine(
            referenceCC = fila.referenceCC,
            owedCents = debe.takeIf { it > 0 }?.toLong(),
            priceToConfirm = fila.quoteId == null,
        ),
    )
}

/** C7: el comprobante de [fila] (mismo folio que la venta), o null si no debe nada. */
fun comprobanteDeLaMaquina(fila: CobroEnMaquinaEntity, negocio: String): com.avoqado.pos.printing.data.model.ReceiptData? {
    val (titulo, debe) = deudaDelComprobante(fila) ?: return null
    return com.avoqado.pos.printing.data.model.ReceiptData(
        orderNumber = fila.referenceCC,
        orderType = "",
        items = emptyList(),
        subtotal = 0,
        taxAmount = 0,
        total = debe,
        venueName = negocio,
        date = Date(fila.cierreEn ?: fila.creadoEn),
        kind = com.avoqado.pos.printing.receiptlayout.KIND_COMPROBANTE_DE_LA_MAQUINA,
        cashMachine = com.avoqado.pos.printing.receiptlayout.ReceiptCashMachine(fila.referenceCC, debe.toLong(), title = titulo),
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
)

sealed interface InicioEnLaMaquina {
    data class Iniciado(val referenceCC: String) : InicioEnLaMaquina
    /** No se guardó ni se mandó nada: [texto] lo dice la pantalla y ofrece tarjeta. */
    data class NoSeCobra(val texto: String) : InicioEnLaMaquina
}

/** Lo que el gerente lee en la máquina (R4-4). */
data class ResolucionDelGerente(
    val desenlace: Desenlace,
    val creditCents: Int,
    val changeDispensedCents: Int,
    /** La hora del comprobante de la máquina (R5#8). null = «hora y turno por conciliar», nunca la de inicio ni la de ahora. */
    val horaDelComprobante: Instant?,
    /** Con CANCELADO o GUARDADO: `"1"` la máquina devolvió, `"2"` guardó. null («No sé») = el cobro sigue esperando. */
    val cancelMode: String?,
    val evidencia: String,
)

sealed interface ResultadoDeResolver {
    data class Resuelto(val desenlace: Desenlace, val creditCents: Int, val changeDispensedCents: Int) : ResultadoDeResolver
    data class NoSe(val texto: String) : ResultadoDeResolver
    /**
     * Codex código r1 #11: el SERVIDOR guardó el dinero como incidencia (202): NO hay venta, ni su ticket ni su comanda. [texto] dice el
     * motivo; [debeCents], lo que se le debe al cliente (su comprobante con folio).
     */
    data class Incidencia(val texto: String, val debeCents: Int) : ResultadoDeResolver
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

    /** Tras cada cierre o duda, la subida los manda sin esperar a su reloj. La `fecha` de la máquina es hora del negocio (#15). */
    val cobro = CobroConMaquina(cliente, dao, reloj, scope, aleatorio, alTerminar = subida::disparar) {
        com.avoqado.pos.core.util.VenueTimeZone.zoneIdFor(secureStorage.venueTimezone)
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

    /** Codex código r1 #7: el intento abierto o en duda de esta venta (la pantalla de la máquina lo recupera tras reiniciar). */
    suspend fun intentoDeLaVenta(llaveDeVenta: String): CobroEnMaquinaEntity? = dao.intentoDeLaVenta(llaveDeVenta)

    /** Codex código r1 #7: las dudas de ESTA PC en la sucursal de la sesión, en vivo (la franja de Cobrar). */
    fun enEspera(): Flow<List<CobroEnMaquinaEntity>> =
        secureStorage.venueId?.let(dao::observarEnEspera) ?: kotlinx.coroutines.flow.flowOf(emptyList())

    /** Lo mismo, una lectura (Ajustes › Máquina de efectivo). */
    suspend fun dudasEnEspera(): List<CobroEnMaquinaEntity> = secureStorage.venueId?.let { dao.enEspera(it) }.orEmpty()

    /** Las ventas apartadas para la máquina que todavía no tienen su fila (se cotizan o piden el turno). */
    private val apartadas: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

    /**
     * Codex código r1 #1: «Efectivo en la máquina» APARTA la venta en el mismo toque, antes de pedir el turno o cotizar (la red tarda):
     * desde aquí la tarjeta no sale para esta venta. Se suelta en [cobrar]: si el `paycash` salió ya frena su fila; si la cotización
     * (o lo de antes del `paycash`) no pasó, nada quedó en la máquina. false = ya estaba apartada.
     */
    fun apartar(llaveDeVenta: String): Boolean = apartadas.add(llaveDeVenta)

    /** Síncrono: lo revisa la tarjeta DESPUÉS de leer el disco, en el mismo paso en que sale. */
    fun apartada(llaveDeVenta: String): Boolean = llaveDeVenta in apartadas

    /** R2#1: mientras esta venta esté apartada o tenga un intento abierto, en duda, pagado o resuelto, no se cobra por otro medio. */
    suspend fun ventaBloqueada(llaveDeVenta: String): Boolean = apartada(llaveDeVenta) || dao.bloqueanLaVenta(llaveDeVenta) > 0

    /**
     * Cotiza y, si se puede, guarda el intento y manda el `paycash` ([CobroConMaquina.cobrar]: la fila ANTES de la red). Lo que
     * no se puede cobrar en la máquina se dice y no deja nada en disco ni en la máquina.
     */
    suspend fun cobrar(v: VentaPorCobrar): InicioEnLaMaquina {
        apartar(v.llaveDeVenta)
        // Iniciado: su fila ya frena la venta. Cualquier otro final: no quedó nada en la máquina y la tarjeta vuelve a poder.
        try {
            return cotizarYCobrar(v)
        } finally {
            apartadas.remove(v.llaveDeVenta)
        }
    }

    private suspend fun cotizarYCobrar(v: VentaPorCobrar): InicioEnLaMaquina {
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
            val venta = VentaParaLaMaquina(v.venueId, v.llaveDeVenta, v.pedidoJson, sale, tip, turno.turnoActual(), quoteId, renglones, vigenteHasta)
            return when (val r = cobro.cobrar(venta)) {
                is ResultadoDeInicio.Iniciado -> InicioEnLaMaquina.Iniciado(r.referenceCC)
                ResultadoDeInicio.NoCobrable -> InicioEnLaMaquina.NoSeCobra(TEXTO_SIN_CENTAVOS)
                ResultadoDeInicio.VentaConIntento -> InicioEnLaMaquina.NoSeCobra(TEXTO_VENTA_CON_INTENTO)
                ResultadoDeInicio.Ocupado -> InicioEnLaMaquina.NoSeCobra(TEXTO_OCUPADA)
                ResultadoDeInicio.SinConexion -> InicioEnLaMaquina.NoSeCobra(TEXTO_NO_RESPONDE)
                ResultadoDeInicio.CotizacionVencida -> return@repeat
            }
        }
        return InicioEnLaMaquina.NoSeCobra(TEXTO_COTIZACION_VENCIDA)
    }

    private sealed interface Cotizacion {
        /** [renglones]: los que confirmó el SERVIDOR (#14). [vigenteHasta]: hasta cuándo EMPIEZA un cobro (reloj de la PC). */
        data class Lista(val saleCents: Int, val tipCents: Int, val quoteId: String?, val renglones: String?, val vigenteHasta: Long?) : Cotizacion
        data class NoSe(val texto: String) : Cotizacion
        /** Sin respuesta útil: se cobra el total local y el excedente cubre un precio que baje (R4-2). */
        data object SinRed : Cotizacion
    }

    private suspend fun cotizar(venueId: String, pedido: JSONObject): Cotizacion {
        // Codex código r1 #14: la vigencia se cuenta con el reloj de la PC desde ANTES de pedirla (un reloj adelantado o atrasado no la
        // alarga). ponytail: los 10 min del SERVIDOR menos un margen; si cambia allá, cambiar aquí.
        val pedidaEn = reloj()
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

    /**
     * El gerente teclea lo que dice la máquina, con SU sesión (`cash-machine:resolve`, R4-4): el SERVIDOR escribe la venta o el
     * cierre y la PC se entera en su siguiente subida (se dispara aquí). Sin permiso, sin modo («No sé») o sin red, el cobro sigue
     * «Esperando a la máquina» y no sale nada.
     */
    suspend fun resolver(referenceCC: String, r: ResolucionDelGerente): ResultadoDeResolver {
        if (!roles.canResolveCashMachine) return ResultadoDeResolver.NoSe(TEXTO_SIN_PERMISO_RESOLVER)
        if (r.desenlace != Desenlace.PAGADO && r.cancelMode !in MODOS) return ResultadoDeResolver.NoSe(TEXTO_NO_SE_SABE_EL_MODO)
        if (r.evidencia.isBlank()) return ResultadoDeResolver.NoSe(TEXTO_ESCRIBE_LO_QUE_VISTE)
        val fila = dao.porReferencia(referenceCC) ?: return ResultadoDeResolver.NoSe(TEXTO_DUDA_SIN_SUBIR)
        val cuerpo = JSONObject()
            .put("outcome", r.desenlace.name)
            .put("creditCents", r.creditCents)
            .put("changeDispensedCents", r.changeDispensedCents)
            .put("occurredAt", r.horaDelComprobante?.toString() ?: JSONObject.NULL)
            .put("evidence", r.evidencia.trim())
            .put("cancelMode", if (r.desenlace == Desenlace.PAGADO) JSONObject.NULL else r.cancelMode)
        val respuesta = try {
            api.resolverDuda(fila.venueId, referenceCC, cuerpo)
        } catch (e: IOException) {
            return ResultadoDeResolver.NoSe(TEXTO_SIN_RED_RESOLVER)
        }
        return when {
            respuesta.codigo == 200 || respuesta.codigo == 202 -> {
                // Codex código r1 #11: lo que contestó se aplica a la fila con la MISMA lectura que la subida; un 202 con incidencia no es
                // venta (sin ticket ni comanda), sólo el comprobante de lo que se le debe al cliente.
                val eco = runCatching { JSONObject(respuesta.cuerpo) }.getOrNull()
                if (eco != null && !dao.resolverConElEco(fila, eco, respuesta.cuerpo)) {
                    return ResultadoDeResolver.NoSe(TEXTO_RESPUESTA_DISTINTA)
                }
                subida.disparar()
                val incidencia = eco?.let(::incidenciaDelEco)
                if (incidencia != null) ResultadoDeResolver.Incidencia(incidencia, eco.optInt("owedCents", 0))
                else ResultadoDeResolver.Resuelto(r.desenlace, r.creditCents, r.changeDispensedCents)
            }
            respuesta.codigo == 404 -> {
                subida.disparar()
                ResultadoDeResolver.NoSe(TEXTO_DUDA_SIN_SUBIR)
            }
            respuesta.codigo == 403 -> ResultadoDeResolver.NoSe(TEXTO_SIN_PERMISO_RESOLVER)
            respuesta.codigo >= 500 -> ResultadoDeResolver.NoSe(TEXTO_SIN_RED_RESOLVER)
            else -> ResultadoDeResolver.NoSe(ServerErrorText.fromResponseBody(respuesta.cuerpo, TEXTO_SIN_RED_RESOLVER))
        }
    }

    // MARK: - Ticket y comprobantes (C7)

    /**
     * El ticket de una venta de la máquina, UNA vez por folio: sale aunque la impresora no tenga la impresión automática (es la
     * prueba del cliente, sobre todo con deuda) y `ticketImpreso` se marca sólo con el resultado REAL de la impresora.
     */
    suspend fun imprimirTicketUnaVez(referenceCC: String, ticket: com.avoqado.pos.printing.data.model.ReceiptData): PrinterService.PrintOutcome? {
        if (dao.porReferencia(referenceCC)?.ticketImpreso != false) return null
        val r = printerService.manualPrintReceipt(ticket)
        if (r is PrinterService.PrintOutcome.Printed) dao.marcarTicketImpreso(referenceCC)
        return r
    }

    /** El comprobante de [referenceCC] (reimprimible). null = esa fila no le debe nada al cliente. */
    suspend fun imprimirComprobante(referenceCC: String): PrinterService.PrintOutcome? {
        val fila = dao.porReferencia(referenceCC) ?: return null
        val c = comprobanteDeLaMaquina(fila, secureStorage.venueName ?: "Avoqado") ?: return null
        return printerService.manualPrintReceipt(c)
    }

    /** El de un CANCELADO que no devolvió todo, UNA vez al cerrarse (después se reimprime desde Ajustes de la máquina). */
    suspend fun imprimirComprobanteUnaVez(referenceCC: String) {
        if (dao.porReferencia(referenceCC)?.ticketImpreso != false) return
        if (imprimirComprobante(referenceCC) is PrinterService.PrintOutcome.Printed) dao.marcarTicketImpreso(referenceCC)
    }

    /**
     * Lo que la máquina les debe a clientes en la sucursal de la sesión, con su comprobante reimprimible, los más nuevos primero. Codex
     * código r1 #13: la consulta ya sólo trae los que deben; [limite] crece con «Ver más» ([cuantosComprobantes] dice cuántos hay).
     */
    suspend fun comprobantes(limite: Int = COMPROBANTES_POR_PAGINA): List<Pair<CobroEnMaquinaEntity, Int>> {
        val venueId = secureStorage.venueId ?: return emptyList()
        return dao.conComprobante(venueId, limite).mapNotNull { f -> deudaDelComprobante(f)?.let { f to it.second } }
    }

    suspend fun cuantosComprobantes(): Int = secureStorage.venueId?.let { dao.contarComprobantes(it) } ?: 0

    /** Codex código r1 #12: las ventas pagadas de la sucursal cuyo ticket no salió, en vivo. */
    fun ticketsSinImprimir(): Flow<Int> =
        (secureStorage.venueId?.let(dao::observarTicketsSinImprimir) ?: kotlinx.coroutines.flow.flowOf(emptyList())).map { it.size }

    /**
     * Codex código r1 #12: imprime (con el botón, nunca solo) los tickets pendientes; cada uno se marca con el resultado REAL de la
     * impresora, y el que no sale sigue pendiente. Devuelve cuántos salieron.
     */
    suspend fun imprimirTicketsPendientes(): Int {
        val venueId = secureStorage.venueId ?: return 0
        val negocio = secureStorage.venueName ?: "Avoqado"
        return dao.ticketsSinImprimir(venueId).count { fila ->
            val r = printerService.manualPrintReceipt(ticketDeLaFila(fila, negocio))
            (r is PrinterService.PrintOutcome.Printed).also { if (it) dao.marcarTicketImpreso(fila.referenceCC) }
        }
    }

    /** ¿El permiso de resolver está en la sesión? (la pantalla lo explica sin mandar nada). */
    fun puedeResolver(): Boolean = roles.canResolveCashMachine

    /** Lo que la pantalla de resultado dice de un cobro de la máquina: su fila, en vivo (Room avisa cuando cambia). */
    fun sincronizacion(referenceCC: String): Flow<SincronizacionDelCobro> = dao.observar(referenceCC).map { f ->
        when {
            f == null -> SincronizacionDelCobro.PENDIENTE
            f.conflicto != null -> SincronizacionDelCobro.RECHAZADA
            f.subido -> SincronizacionDelCobro.SINCRONIZADA
            else -> SincronizacionDelCobro.PENDIENTE
        }
    }.distinctUntilChanged()

    private companion object {
        val MODOS = setOf("1", "2")
        const val COMPROBANTES_POR_PAGINA = 50
        /** ponytail: la vigencia del SERVIDOR (`VIGENCIA_DE_LA_COTIZACION_MS`, 10 min) menos 30 s de margen. */
        const val VIGENCIA_DE_LA_COTIZACION_MS = 10 * 60_000L - 30_000L
    }
}
