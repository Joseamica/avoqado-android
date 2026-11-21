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
)

/** La fila «Efectivo en la máquina» de la selección de método. */
data class FilaDeLaMaquina(val visible: Boolean, val habilitada: Boolean, val motivo: String?, val pocoCambio: Boolean)

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
        pocoCambio = ahora.cambio != NivelDeCambio.SUFICIENTE,
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

/** La venta que la caja manda a la máquina. Los importes son los LOCALES: sin red se cobran ésos (R4-2). */
data class VentaPorCobrar(
    val venueId: String,
    /** La identidad del carrito: a lo más un intento abierto, en duda o pagado por venta. */
    val llaveDeVenta: String,
    /** El cuerpo de `POST /orders` de este carrito: se cotiza y se congela tal cual (R5#1). */
    val pedidoJson: String,
    val saleCents: Int,
    val tipCents: Int,
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

    /** Tras cada cierre o duda, la subida los manda sin esperar a su reloj. */
    val cobro = CobroConMaquina(cliente, dao, reloj, scope, aleatorio, alTerminar = subida::disparar)

    private val _ahora = MutableStateFlow(LaMaquinaAhora())
    val ahora: StateFlow<LaMaquinaAhora> = _ahora.asStateFlow()

    /** Lo que impide subir (sin emparejar, 403). */
    val avisoDeSubida: StateFlow<String?> get() = subida.aviso

    init {
        // UNDISPATCHED: suscritos al construir (`recibidos` no repite lo pasado).
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            cliente.recibidos.collect { r ->
                when (val m = r.mensaje) {
                    is MensajeDeLaMaquina.Sockets -> _ahora.update { it.copy(ocupada = m.cobrando, cambio = nivelDeCambio(m.changeBar)) }
                    is MensajeDeLaMaquina.Status -> _ahora.update {
                        it.copy(ocupada = m.cobrando, cambio = m.changeBar?.let(::nivelDeCambio) ?: it.cambio)
                    }
                    else -> Unit
                }
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            cliente.conexion.collect { c -> _ahora.update { it.copy(conexion = c) } }
        }
    }

    /** ¿Esta PC cobra con la máquina? La casilla (sólo se prende en WINDOWS, C6) y una credencial que ESTA PC descifra (R5#3). */
    fun activa(): Boolean = prefs.activa && runCatching { boveda.leer() }.getOrNull() != null

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

    /** R2#1: mientras esta venta tenga un intento abierto, en duda, pagado o resuelto, no se cobra por otro medio. */
    suspend fun ventaBloqueada(llaveDeVenta: String): Boolean = dao.bloqueanLaVenta(llaveDeVenta) > 0

    /**
     * Cotiza y, si se puede, guarda el intento y manda el `paycash` ([CobroConMaquina.cobrar]: la fila ANTES de la red). Lo que
     * no se puede cobrar en la máquina se dice y no deja nada en disco ni en la máquina.
     */
    suspend fun cobrar(v: VentaPorCobrar): InicioEnLaMaquina {
        val pedido = runCatching { JSONObject(v.pedidoJson) }.getOrNull() ?: return InicioEnLaMaquina.NoSeCobra(TEXTO_NO_COBRABLE)
        motivoSinMaquinaDelPedido(pedido)?.let { return InicioEnLaMaquina.NoSeCobra(it) }
        try {
            turno.refrescar()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Unit   // sin red se queda el turno guardado
        }
        var sale = v.saleCents
        var tip = v.tipCents
        var quoteId: String? = null
        when (val c = cotizar(v.venueId, pedido)) {
            is Cotizacion.Lista -> { sale = c.saleCents; tip = c.tipCents; quoteId = c.quoteId }
            is Cotizacion.NoSe -> return InicioEnLaMaquina.NoSeCobra(c.texto)
            Cotizacion.SinRed -> Unit
        }
        if (!cobrableEnMaquina(sale + tip)) return InicioEnLaMaquina.NoSeCobra(TEXTO_SIN_CENTAVOS)
        val venta = VentaParaLaMaquina(v.venueId, v.llaveDeVenta, v.pedidoJson, sale, tip, turno.turnoActual(), quoteId)
        return when (val r = cobro.cobrar(venta)) {
            is ResultadoDeInicio.Iniciado -> InicioEnLaMaquina.Iniciado(r.referenceCC)
            ResultadoDeInicio.NoCobrable -> InicioEnLaMaquina.NoSeCobra(TEXTO_SIN_CENTAVOS)
            ResultadoDeInicio.VentaConIntento -> InicioEnLaMaquina.NoSeCobra(TEXTO_VENTA_CON_INTENTO)
            ResultadoDeInicio.Ocupado -> InicioEnLaMaquina.NoSeCobra(TEXTO_OCUPADA)
            ResultadoDeInicio.SinConexion -> InicioEnLaMaquina.NoSeCobra(TEXTO_NO_RESPONDE)
        }
    }

    private sealed interface Cotizacion {
        data class Lista(val saleCents: Int, val tipCents: Int, val quoteId: String?) : Cotizacion
        data class NoSe(val texto: String) : Cotizacion
        /** Sin respuesta útil: se cobra el total local y el excedente cubre un precio que baje (R4-2). */
        data object SinRed : Cotizacion
    }

    private suspend fun cotizar(venueId: String, pedido: JSONObject): Cotizacion {
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
        return Cotizacion.Lista(sale, tip, j.optString("quoteId").takeIf { !j.isNull("quoteId") && it.isNotBlank() })
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
                subida.disparar()
                ResultadoDeResolver.Resuelto(r.desenlace, r.creditCents, r.changeDispensedCents)
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
    }
}
