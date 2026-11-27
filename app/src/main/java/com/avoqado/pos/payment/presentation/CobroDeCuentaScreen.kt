package com.avoqado.pos.payment.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.currentStateAsState
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.designsystem.components.*
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.giftcards.data.SolicitudesDeTarjetaStore
import com.avoqado.pos.giftcards.domain.SolicitudDeTarjeta
import com.avoqado.pos.giftcards.domain.TextosDeTarjetas
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.presentation.cart.CartState
import com.avoqado.pos.tables.data.OrderDetail
import com.avoqado.pos.tables.data.TableServiceRepository
import com.avoqado.pos.tables.data.comoEvidencia
import com.avoqado.pos.tables.data.paraLaCuenta
import com.avoqado.pos.tables.presentation.RewardsDialog
import com.avoqado.pos.tables.data.restanteCents
import com.avoqado.pos.orders.data.CuentaGuardada
import com.avoqado.pos.payment.domain.DatosDelCobroSinRed
import com.avoqado.pos.payment.domain.DecisionSinRed
import com.avoqado.pos.payment.domain.TextosDeCuentaSinRed
import com.avoqado.pos.payment.domain.decidirCobroSinRed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * D39 (Codex r3 #2): el canje que se quedó sin respuesta, tal cual se mandó (con la versión de entonces). D39 r10: con su llave
 * ([operationId], una por intento): repetirlo manda la MISMA, y el SERVIDOR contesta el desenlace del primero.
 */
internal data class CanjeSinRespuesta(
    val orderId: String, val customerId: String, val points: Int, val monto: Double, val version: Int?,
    val operationId: String = java.util.UUID.randomUUID().toString(),
)

/**
 * D39 C1 (founder, «memoria propia», 26-nov): el canje en vuelo y el que se quedó sin respuesta son de la CAJA, no de una pantalla. La
 * cuenta del atajo (encima de Cobrar) y Pedidos tienen cada una su ViewModel; con esto las dos ven el mismo «Reintentar el canje» y el
 * mismo cobro bloqueado (Codex v3-r2: antes cada pantalla perdía el de la otra).
 */
@javax.inject.Singleton
class CanjesDeLaCaja @Inject constructor() {
    /** El canje de UNA cuenta (Codex C1-r1: uno sin respuesta en la cuenta A no frena el de la cuenta B). */
    internal class DeLaCuenta {
        var enVuelo = false
            private set
        var sinRespuesta: CanjeSinRespuesta? = null
        var enDuda: String? = null
            private set
        // Avisos directos, sin corrutinas: los ViewModels que miran esta cuenta se ponen al día en el mismo hilo que escribe.
        private val oyentes = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
        fun ponerEnVuelo(v: Boolean) { enVuelo = v; oyentes.forEach { it() } }
        fun ponerEnDuda(v: String?) { enDuda = v; oyentes.forEach { it() } }
        fun escuchar(o: () -> Unit) { oyentes += o }
        fun dejarDeEscuchar(o: () -> Unit) { oyentes -= o }
    }
    private val porCuenta = java.util.concurrent.ConcurrentHashMap<String, DeLaCuenta>()
    internal fun de(orderId: String): DeLaCuenta = porCuenta.getOrPut(orderId) { DeLaCuenta() }
}

@HiltViewModel
class CobroDeCuentaViewModel @Inject constructor(
    private val repo: TableServiceRepository,
    private val storage: SecureStorage,
    private val red: ConnectivityMonitor,
    private val roles: RoleManager,
    private val store: SolicitudesDeTarjetaStore,
    private val cobrosLocales: com.avoqado.pos.giftcards.data.CobrosLocalesDeLaCuenta? = null,
    private val copiaDeCuentas: com.avoqado.pos.orders.data.CopiaDeCuentas? = null,
    private val cuentasAbiertasAqui: com.avoqado.pos.orders.data.CuentasAbiertasAqui? = null,
    private val cashPaymentRepository: com.avoqado.pos.payment.data.CashPaymentRepository? = null,
    private val planManager: com.avoqado.pos.core.domain.PlanManager? = null,
    /** D12 · Opción D: pedirle permiso a la caja que abrió la cuenta (o apartarla en local si es ésta). */
    private val cuentasEntreCajas: com.avoqado.pos.core.data.lan.CuentasEntreCajas? = null,
    /** D12 · Opción D: el registro de esta caja como dueña de las cuentas que abrió. */
    private val duenoDeCuentas: com.avoqado.pos.core.data.lan.DuenoDeCuentas? = null,
    private val payLaterQueue: com.avoqado.pos.payment.data.PayLaterQueue? = null,
    /** D12 (final fix ronda 2, R2-1): la lista durable de cobros con tarjeta de esta caja (una tarjeta en duda o cobrada da gris). */
    private val terminalPaymentService: com.avoqado.pos.payment.data.TerminalPaymentService? = null,
    /** D39 (atajo «Usar puntos»): la cuenta que Cobrar pidió abrir con «Recompensas». Default para las pruebas. */
    private val puertaDeRecompensas: com.avoqado.pos.pos.presentation.cart.PuertaDeRecompensas? = null,
    /** D39 C1: el canje en vuelo o sin respuesta es de la CAJA (lo ven Pedidos y la cuenta del atajo). Default por ViewModel en las pruebas. */
    canjesDeLaCaja: CanjesDeLaCaja? = null,
) : ViewModel() {
    private val canjes = canjesDeLaCaja ?: CanjesDeLaCaja()
    /**
     * La cuenta de este ViewModel (uno por cuenta: `hiltViewModel(key = "cuenta-$orderId")`). Sus [canjeEnVuelo] y [canjeEnDuda] son el
     * espejo del canje de ESA cuenta en [CanjesDeLaCaja]; se liga con la primera cuenta que toca.
     */
    private var cuentaLigada: String? = null
    private var oyente: (() -> Unit)? = null
    private val vueloDeLaCuenta = MutableStateFlow(false)
    private val dudaDeLaCuenta = MutableStateFlow<String?>(null)
    private fun canjeDe(orderId: String): CanjesDeLaCaja.DeLaCuenta {
        val de = canjes.de(orderId)
        if (cuentaLigada != orderId) {
            soltarElCanje()
            cuentaLigada = orderId
            val o = { vueloDeLaCuenta.value = de.enVuelo; dudaDeLaCuenta.value = de.enDuda }
            oyente = o
            de.escuchar(o)
            o()
        }
        return de
    }
    private fun soltarElCanje() {
        val c = cuentaLigada ?: return
        oyente?.let { canjes.de(c).dejarDeEscuchar(it) }
        oyente = null
    }
    /** D39 (atajo): ¿el atajo pidió abrir ESTA cuenta, con esta sesión y esta sucursal? */
    fun aperturaPedida(orderId: String): Boolean =
        puertaDeRecompensas?.porAbrir(storage.venueId, storage.selloYAccess().first, orderId) == true
    fun aperturaAtendida(orderId: String) { puertaDeRecompensas?.atendida(orderId) }
    private val foto = MutableStateFlow<OrderDetail?>(null)
    val detalle = foto.asStateFlow()
    val conectado get() = red.isConnected
    val servidor get() = red.isServerReachable
    private val aviso = MutableStateFlow<String?>(null)
    val mensaje = aviso.asStateFlow()
    val solicitudes = store.solicitudes
    val venueActual: String? get() = storage.venueId
    val puedeCobrar: Boolean get() = roles.hasVenuePermission("payments:create")
    val puedeCancelar: Boolean get() = roles.hasVenuePermission("orders:cancel-unpaid")
    private var venueDeLaCuenta: String? = null
    private val ofertaActual = MutableStateFlow(com.avoqado.pos.giftcards.domain.OfertaDeLaCuenta.NADA)
    val oferta = ofertaActual.asStateFlow()
    private val enVuelo = MutableStateFlow(0)
    /** Consultas de la cuenta que aún no vuelven. Es contador y no booleano porque pueden correr dos a la vez. */
    val consultasEnCurso = enVuelo.asStateFlow()
    private val turnos = java.util.concurrent.atomic.AtomicInteger()
    /** Devuelve su turno: si después salió otra consulta, la que manda es ésa. */
    suspend fun cargar(orderId: String): Int {
        enVuelo.update { it + 1 } // Antes de vaciar la foto: «sin foto y sin consulta» significa que ya falló.
        val turno = turnos.incrementAndGet()
        try { consultar(orderId, turno) } finally { enVuelo.update { it - 1 } }
        return turno
    }
    private suspend fun consultar(orderId: String, turno: Int) {
        foto.value = null
        aviso.value = null
        val venue = storage.venueId
        venueDeLaCuenta = venue
        if (venue == null) { aviso.value = TextosDeTarjetas.CUENTA_NO_ENCONTRADA; return }
        val turnoCopia = copiaDeCuentas?.abrirLectura()
        val resultado = repo.getOrderDetail(venue, orderId)
        // D12 (Codex r3 H1): lo que dice este detalle (o su 404) también pone al día la copia de «Pedidos». Final fix (F1): también si
        // otra consulta ya lo superó en pantalla: la copia ordena con sus propios turnos, y si la nueva no trae nada, ésta es lo más nuevo.
        val evidencia = resultado.fold(
            onSuccess = { d -> d.takeIf { it.id == orderId }?.comoEvidencia() },
            onFailure = { e -> com.avoqado.pos.orders.data.EvidenciaDelDetalle.NoExiste.takeIf { e is retrofit2.HttpException && e.code() == 404 } },
        )
        if (evidencia != null && turnoCopia != null) copiaDeCuentas?.alLeerDetalle(venue, orderId, evidencia, turnoCopia)
        // Si salió otra consulta después, manda ella: una respuesta atrasada no pisa su saldo ni su aviso (Codex D14 r1 P1).
        if (turno != turnos.get() && resultado.exceptionOrNull() !is kotlinx.coroutines.CancellationException) return
        if (storage.venueId != venue) { aviso.value = TextosDeTarjetas.CUENTA_NO_ENCONTRADA; return }
        resultado.fold(onSuccess = {
            if (it.id != orderId) aviso.value = TextosDeTarjetas.CUENTA_NO_ENCONTRADA
            else if (it.paymentStatus == "PAID" || it.status in listOf("CANCELLED", "DELETED"))
                aviso.value = "Esta cuenta no tiene saldo pendiente."
            else foto.value = it
        }, onFailure = {
            if (it is kotlinx.coroutines.CancellationException) throw it // `getOrderDetail` la atrapa con runCatching.
            aviso.value = com.avoqado.pos.core.data.network.ServerErrorText.humanize(it)
        })
    }
    /** 🔴 D13: la foto de la puerta del efectivo de la última oferta ([ofertaParaCobrar]); viaja al cobro que abre «Cobrar». */
    var dineroConocido: com.avoqado.pos.payment.domain.DineroConocido = com.avoqado.pos.payment.domain.DineroConocido.NADA
        private set

    suspend fun ofertaParaCobrar(orderId: String, laTraeUnCarrito: Boolean = false): com.avoqado.pos.giftcards.domain.OfertaDeLaCuenta {
        // D39 C1: al abrir la cuenta, su «Reintentar el canje» y su cobro bloqueado (aunque el canje se haya hecho en otra pantalla).
        canjeDe(orderId)
        // D13: ANTES de leer la cuenta: lo subido después pudo no contarse. Si no se puede leer, vacía (frena de más, nunca de menos).
        val fotoVenue = storage.venueId
        val fotoDelDinero = try {
            fotoVenue?.let { cobrosLocales?.dineroConocido(it, orderId) } ?: com.avoqado.pos.payment.domain.DineroConocido.NADA
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { com.avoqado.pos.payment.domain.DineroConocido.NADA }
        val turno = cargar(orderId)
        val cuenta = foto.value
        val venue = venueDeLaCuenta ?: return com.avoqado.pos.giftcards.domain.OfertaDeLaCuenta.NADA
        val pendiente = try { cobrosLocales?.hayPendiente(venue, orderId) ?: true }
            catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { true }
        // Una consulta superada no publica oferta: decidiría con la foto de otra (Codex D14 r2 P2).
        if (turno != turnos.get() || storage.venueId != venue || foto.value?.id != orderId) return com.avoqado.pos.giftcards.domain.OfertaDeLaCuenta.NADA
        val resultado = com.avoqado.pos.giftcards.domain.ofertaDeLaCuenta(cuenta?.let(::restanteCents),
            cuenta == null || cuenta.esDeMesa != false && !cuenta.esCuentaDeTarjetas, laTraeUnCarrito, pendiente,
            cierraEnCero = cuenta?.let(::cierraEnCero) == true)
        ofertaActual.value = resultado
        dineroConocido = fotoDelDinero
        return resultado
    }
    // MARK: - D12: cobrar sin red con la copia de «Pedidos»

    private val decisionSinRed = MutableStateFlow<DecisionSinRed?>(null)
    /** Lo que la cuenta ofrece sin red (null = aún no se decide). Lo publican las cuatro funciones de abajo. */
    val sinRed: StateFlow<DecisionSinRed?> = decisionSinRed.asStateFlow()
    /** La cuenta cuyo apartado sostiene esta pantalla (Opción D), o null. */
    private var apartadoDe: String? = null
    private val avisando = MutableStateFlow<String?>(null)
    /** Mientras se le pregunta a la caja que la abrió: su nombre («Avisándole a <caja>…»). null = no se está preguntando a otra caja. */
    val avisandoA: StateFlow<String?> = avisando.asStateFlow()
    /** P11: el toque en curso; un segundo toque espera ESTE resultado en vez de pedir otro candado. */
    private var pedidoEnVuelo: Pair<String, Deferred<DecisionSinRed>>? = null
    /** La revalidación previa al efectivo dijo que no: al volver, la cuenta se queda con ese gris (no se vuelve a ofrecer en el acto). */
    private var rechazadoAntesDelEfectivo = false
    /**
     * D5 (hz:619db22d, medido en la SAMSUNG el 12-nov): el gris que dio la puerta o la revalidación justo antes del efectivo («Se perdió el
     * contacto… No recibas el efectivo»). Se queda mientras la causa siga siendo la misma (la caja que la abrió no se ve): la vuelta de cada
     * 10 s ([consultarSinRed]) lo tapaba con «no la encuentro». Con cualquier otra decisión (se puede cobrar, o un motivo nuevo) se olvida.
     */
    private var grisDeLaPuerta: String? = null

    /** Lo que decide: la copia VIGENTE de la sucursal ACTUAL (vencida u otra sucursal = sin cuenta) y lo local; leer mal = gris. */
    private suspend fun datosSinRed(orderId: String): DatosDelCobroSinRed {
        val venue = storage.venueId
        suspend fun leer(siFalla: Boolean, bloque: suspend () -> Boolean?): Boolean = try {
            bloque() ?: siFalla
        } catch (e: CancellationException) { throw e } catch (_: Exception) { siFalla }
        // Final fix ronda 2 (R2-1): una tarjeta de ESTA caja en duda o ya cobrada para esta cuenta (su lista durable; un contexto sin
        // negocio también cuenta). Se lee ANTES que la copia: un positivo que vence al leerla deja su marca y la copia la aplica.
        val tarjetaDeEstaCaja = venue != null && leer(true) {
            terminalPaymentService?.let { s -> s.cobrosConTarjetaDelNegocio(venue).any { it.orderId == orderId } } ?: false
        } || venue != null && leer(true) {
            // Merge #199 (D14): el positivo de una tarjeta de «Pedidos → detalle» deja la marca de la ORDEN, no la de la copia.
            com.avoqado.pos.onlineorders.data.FoliosCobradosConTarjeta(storage).marcasDe(venue).any { it.orderId == orderId }
        }
        val abiertaAqui = venue != null && leer(false) { cuentasAbiertasAqui?.laAbrioAqui(venue, orderId) }
        val registro = if (abiertaAqui && venue != null) duenoDeCuentas?.estadoDe(venue, orderId) else null
        return DatosDelCobroSinRed(
            cuenta = venue?.let { copiaDeCuentas?.cuenta(it, orderId) },
            puedeCobrar = puedeCobrar,
            abiertaAqui = abiertaAqui,
            // Un cobro local pendiente (también una tarjeta de esta caja), O una cortesía sin confirmar de «Pagar después» (fila REVISAR).
            cobroLocalPendiente = venue == null || tarjetaDeEstaCaja || leer(true) { cobrosLocales?.hayPendiente(venue, orderId) } || leer(true) {
                payLaterQueue?.filas?.value?.any { it.venueId == venue && it.orderId == orderId && it.estado == com.avoqado.pos.payment.data.PayLaterRow.Estado.REVISAR }
            },
            huboEfectivoSubido = leer(true) { cashPaymentRepository?.huboEfectivoSubido(orderId) },
            premiumAcreditado = leer(false) { planManager?.tieneAcreditada("OFFLINE_LAN_HUB") },
            hayOtraCajaDelNegocio = leer(false) { cuentasEntreCajas?.hayOtraCajaConD() },
            registro = registro,
            puedeDeclararNoSeCobro = duenoDeCuentas?.puedeDeclararNoSeCobro(registro) == true,
            miDeviceId = duenoDeCuentas?.deviceId.orEmpty(),
            cajaQueLaAbrio = if (!abiertaAqui && venue != null) runCatching { cuentasEntreCajas?.cajaQueLaAbrio(venue, orderId) }.getOrNull() else null,
        )
    }

    /** Sin permiso todavía: lo que la cuenta ofrece antes del toque. Con un toque en curso no se pinta encima (lo publica él). */
    suspend fun consultarSinRed(orderId: String) {
        if (pedidoEnVuelo?.second?.isActive == true) return
        // D39 (Codex r4 #1 y r5 #1): con un canje de puntos en vuelo, sin respuesta o ya mandado desde aquí, la copia no sabe cuánto falta.
        grisSinRedPorCanje(orderId)?.let { decisionSinRed.value = DecisionSinRed.Gris(it); return }
        val datos = datosSinRed(orderId)
        val nueva = decidirCobroSinRed(datos, permiso = null)
        // Sólo tapa la MISMA causa: el aviso guardado es de contacto perdido Y la caja que la abrió sigue sin verse. Un motivo nuevo, o un
        // aviso guardado por otra causa (el saldo cambió), deja ver lo vigente (Codex r1 y r2 sobre la D5).
        val avisosDeContacto = setOfNotNull(datos.cajaQueLaAbrio?.let { TextosDeCuentaSinRed.sePerdioElContacto(it) }, TextosDeCuentaSinRed.SE_ABRIO_EN_OTRA_CAJA)
        val mismaCausa = grisDeLaPuerta in avisosDeContacto &&
            nueva == DecisionSinRed.Gris(TextosDeCuentaSinRed.noLaEncuentro(datos.cajaQueLaAbrio))
        val d = grisDeLaPuerta?.takeIf { mismaCausa }?.let { DecisionSinRed.Gris(it) } ?: nueva.also { grisDeLaPuerta = null }
        if (pedidoEnVuelo?.second?.isActive != true) decisionSinRed.value = d
    }

    /** El toque en «Cobrar $X en efectivo»: aparta con la caja que la abrió (en local si es ésta), en 5 s o menos. */
    suspend fun pedirCobroSinRed(orderId: String): DecisionSinRed {
        pedidoEnVuelo?.let { (id, enCurso) -> if (id == orderId && enCurso.isActive) return enCurso.await() }
        val propio = CompletableDeferred<DecisionSinRed>()
        pedidoEnVuelo = orderId to propio
        try {
            return pedirUnaVez(orderId).also { propio.complete(it) }
        } catch (e: Throwable) {
            propio.completeExceptionally(e)
            throw e
        } finally {
            if (pedidoEnVuelo?.second === propio) pedidoEnVuelo = null
        }
    }

    private suspend fun pedirUnaVez(orderId: String): DecisionSinRed {
        grisSinRedPorCanje(orderId)?.let { return DecisionSinRed.Gris(it).also { d -> decisionSinRed.value = d } }
        val datos = datosSinRed(orderId)
        val antes = decidirCobroSinRed(datos, permiso = null)
        val cuentas = cuentasEntreCajas
        val venue = storage.venueId
        if (antes !is DecisionSinRed.Cobrar || cuentas == null || venue == null) return antes.also { decisionSinRed.value = it }
        val quien = storage.selectedStaffNameForCurrentVenue ?: storage.userFirstName.orEmpty()
        val intento = java.util.UUID.randomUUID().toString()
        if (!datos.abiertaAqui) avisando.value = datos.cajaQueLaAbrio ?: TextosDeCuentaSinRed.LAS_OTRAS_CAJAS
        // Lo concedido se guarda aunque cancelen el toque: si no, se renovaría para siempre a nombre de esta caja.
        val permiso = try {
            withContext(NonCancellable) {
                val r = try { cuentas.apartar(venue, orderId, intento, quien, enLinea = false) }
                    catch (_: Exception) { com.avoqado.pos.core.data.lan.CuentasEntreCajas.DelDueno(null, null, datos.cajaQueLaAbrio, 0) }
                if (r.respuesta == com.avoqado.pos.core.data.lan.Respuesta.Concedida) apartadoDe = orderId
                r
            }.also { currentCoroutineContext().ensureActive() } // `withContext(NonCancellable)` no lanza al salir: aquí se ve la cancelación
        } catch (e: CancellationException) {
            soltarCobroSinRed()
            throw e
        } finally {
            avisando.value = null
        }
        return decidirCobroSinRed(datos, permiso).also { decisionSinRed.value = it }
    }

    /**
     * «No se cobró: cobrar aquí» en la caja que la abrió (2 min después de que otra la comprometió sin confirmar). Lo declara una persona:
     * el intento de la otra caja queda cancelado y la cuenta se aparta aquí. false = todavía no se puede o el disco falló.
     */
    suspend fun noSeCobroYCobrarAqui(orderId: String): DecisionSinRed {
        declararNoSeCobro(orderId)
        return pedirCobroSinRed(orderId)
    }

    /** Suelta el apartado que sostiene esta pantalla (si sostiene uno). Nunca espera a la red. */
    fun soltarCobroSinRed() {
        val orden = apartadoDe ?: return
        apartadoDe = null
        cuentasEntreCajas?.soltarApartado(orden)
    }

    /**
     * Justo antes de recibir el efectivo, lo que sólo sabe la copia: su gris y si su saldo cambió (T12b, O3). null = sigue; un texto = no se
     * cobra (y la cuenta lo enseña). El permiso de la caja que la abrió lo pide la puerta del cobro (`PaymentFlowViewModel`, Opción D).
     */
    suspend fun revalidarCobroSinRed(orderId: String, saldoCents: Int): String? {
        grisSinRedPorCanje(orderId)?.let { alNoPasarLaPuerta(it); return it }
        val datos = datosSinRed(orderId)
        val decision = decidirCobroSinRed(datos, permiso = null)
        // Caso 4 (aparatos, 12-nov): la caja que la abrió dejó de verse con la cuenta YA apartada con ella: la cajera ya tecleó el
        // efectivo, así que es el «No recibas el efectivo» de la puerta, no el «no la encuentro» de antes del toque. Se corta igual.
        val sinContactoCon = datos.cajaQueLaAbrio?.takeIf {
            apartadoDe == orderId && decision == DecisionSinRed.Gris(TextosDeCuentaSinRed.noLaEncuentro(it))
        }
        val texto = when {
            sinContactoCon != null -> TextosDeCuentaSinRed.sePerdioElContacto(sinContactoCon)
            decision is DecisionSinRed.Gris -> decision.texto
            decision is DecisionSinRed.NoConfirmo -> decision.texto
            decision is DecisionSinRed.Cobrar && decision.saldoCents != saldoCents -> TextosDeCuentaSinRed.saldoCambio(decision.saldoCents)
            else -> null
        } ?: return null
        alNoPasarLaPuerta(texto)
        return texto
    }

    /**
     * Opción D, en la lista de Pedidos: «Cobrada en <caja>» (y «· falta subirla») si ESTA caja la abrió y su registro la da cobrada
     * ([com.avoqado.pos.payment.domain.textoDeLaListaDe]). null = nada que decir (o no la abrió aquí, o no se pudo leer).
     */
    /** La versión del registro de la dueña: cambia cada vez que se guarda (para volver a calcular la etiqueta de la lista). */
    val versionDelRegistro: kotlinx.coroutines.flow.StateFlow<Long> =
        duenoDeCuentas?.version ?: kotlinx.coroutines.flow.MutableStateFlow(0L)

    suspend fun textoDeLaLista(orderId: String, saldoPendienteCents: Int?, lecturaDelServidorEn: Long?): String? {
        val venue = storage.venueId ?: return null
        return com.avoqado.pos.payment.domain.textoDeLaListaDe(duenoDeCuentas?.estadoDe(venue, orderId), saldoPendienteCents, lecturaDelServidorEn)
    }

    /**
     * I2: con internet, en la caja que la abrió: el «<quién> empezó a cobrarla en <caja> y no confirmó…» de otra caja, a los 2 min. null =
     * nada que decir. Su botón es «No se cobró: cobrar aquí» ([declararNoSeCobro]).
     */
    suspend fun noConfirmoEnLinea(orderId: String): String? {
        val venue = storage.venueId ?: return null
        val d = duenoDeCuentas ?: return null
        val r = d.estadoDe(venue, orderId) ?: return null
        return if (d.puedeDeclararNoSeCobro(r)) TextosDeCuentaSinRed.noConfirmo(r.quien, r.caja) else null
    }

    /** «No se cobró» (lo declara una persona en la caja que la abrió). false = todavía no se puede, no es suya o el disco falló. */
    suspend fun declararNoSeCobro(orderId: String): Boolean {
        val venue = storage.venueId ?: return false
        val quien = storage.selectedStaffNameForCurrentVenue ?: storage.userFirstName.orEmpty()
        return duenoDeCuentas?.noSeCobro(venue, orderId, quien) == true
    }

    /** La puerta del cobro dijo que no (Opción D): la cuenta se queda con ese gris al volver. */
    fun alNoPasarLaPuerta(texto: String) {
        rechazadoAntesDelEfectivo = true
        grisDeLaPuerta = texto
        decisionSinRed.value = DecisionSinRed.Gris(texto)
    }

    /** Al volver del cobro con la copia. false = la revalidación o la puerta dijeron que no: su gris se queda y la cuenta no se refresca. */
    suspend fun alVolverDelCobroSinRed(orderId: String): Boolean {
        soltarCobroSinRed()
        if (rechazadoAntesDelEfectivo) {
            rechazadoAntesDelEfectivo = false
            return false
        }
        consultarSinRed(orderId)
        return true
    }

    override fun onCleared() {
        soltarCobroSinRed()
        soltarElCanje()
    }

    suspend fun cancelar(reason: String): Boolean {
        val cuenta = foto.value ?: return false
        val venue = venueDeLaCuenta
        if (venue == null || venue != storage.venueId) { aviso.value = TextosDeTarjetas.CUENTA_NO_ENCONTRADA; return false }
        if (!cuenta.esCuentaDeTarjetas || !puedeCancelar || reason.isBlank()) return false
        if (!red.isConnected.value || !red.isServerReachable.value) {
            aviso.value = TextosDeTarjetas.CUENTA_NO_SE_CANCELO_SIN_RED
            return false
        }
        val resultado = repo.cancelarEnLinea(venue, cuenta.id, reason)
        if (resultado.isSuccess) {
            foto.value = null
            val ventas = store.delNegocio(venue).filter { it.tipo == "GIFT_CARD_SALE" && it.orderId == cuenta.id }
            if (ventas.any { !store.quitar(it.llave) }) {
                aviso.value = "La cuenta se canceló. No se pudo actualizar el registro del aparato; vuelve a consultarla."
                return false
            }
            aviso.value = null
            return true
        }
        val error = resultado.exceptionOrNull()
        aviso.value = if (error is java.io.IOException || (error is retrofit2.HttpException && error.code() in 502..504))
            TextosDeTarjetas.CUENTA_NO_SE_CANCELO_SIN_RED
        else com.avoqado.pos.core.data.network.ServerErrorText.humanizeCancelacionDeCuenta(error, "No se pudo cancelar la cuenta.")
        return false
    }

    // MARK: - D39 fase 1: «Recompensas» en las cuentas de «Pagar después» de Pedidos, con el canje de Mesas

    private val lecturaDeLealtad = MutableStateFlow<com.avoqado.pos.tables.data.LealtadLeida?>(null)
    /** La lectura más nueva del saldo, etiquetada con la cuenta (orden, clienta y versión) para la que se pidió. */
    val lealtadLeida: StateFlow<com.avoqado.pos.tables.data.LealtadLeida?> = lecturaDeLealtad.asStateFlow()
    private val lecturaProhibida = MutableStateFlow(false)
    /** La última lectura del saldo fue un 403: el plan del negocio o los permisos de la sesión no incluyen la lealtad. */
    val lealtadProhibida: StateFlow<Boolean> = lecturaProhibida.asStateFlow()
    private var turnoDeLealtad = 0

    /** Sólo la lectura de la cuenta que se ve: otra versión, otra clienta u otra cuenta no se ofrece (la regla de Mesas). */
    fun lealtadDeLaCuenta(): com.avoqado.pos.tables.data.LealtadLeida? = lecturaDeLealtad.value.paraLaCuenta(foto.value)

    /** Los permisos del SERVIDOR: leer el saldo (`loyalty:read`) y canjear (`orders:update`). */
    val puedeCanjearPuntos: Boolean get() = roles.hasVenuePermission("loyalty:read") && roles.hasVenuePermission("orders:update")

    private val dialogoDeRecompensas = MutableStateFlow(false)
    val recompensasAbiertas: StateFlow<Boolean> = dialogoDeRecompensas.asStateFlow()
    private val avisoDeCanje = MutableStateFlow<String?>(null)
    /** «La cuenta cambió: revisa los puntos.» tras un 409 del candado de la oferta. */
    val avisoDelCanje: StateFlow<String?> = avisoDeCanje.asStateFlow()
    private val avisoDeLasRecompensas = MutableStateFlow<String?>(null)
    /** Lo que pasó con el último canje (aplicado o por qué no). Aparte de [mensaje]: la relectura de la cuenta lo borraría. */
    val avisoDeRecompensas: StateFlow<String?> = avisoDeLasRecompensas.asStateFlow()
    val canjeEnVuelo: StateFlow<Boolean> = vueloDeLaCuenta.asStateFlow()

    /** Lee el saldo de la clienta de la cuenta que se ve, contra ESA cuenta (`?orderId=`: el máximo es de ella). */
    suspend fun leerLealtad(orderId: String) {
        val cuenta = foto.value?.takeIf { it.id == orderId }
        val cliente = cuenta?.customerId?.takeIf { it.isNotBlank() }
        val venue = venueDeLaCuenta
        val turno = ++turnoDeLealtad
        lecturaDeLealtad.value = null
        lecturaProhibida.value = false
        if (cuenta == null || cliente == null || venue == null || venue != storage.venueId || cuenta.payLater != true) return
        val leido = repo.getCustomerLoyalty(venue, cliente, orderId)
        if (turno != turnoDeLealtad) return
        lecturaProhibida.value = (leido.exceptionOrNull() as? retrofit2.HttpException)?.code() == 403
        lecturaDeLealtad.value = leido.fold(
            onSuccess = { com.avoqado.pos.tables.data.LealtadLeida(orderId, cliente, cuenta.version, it) },
            onFailure = {
                if (it is CancellationException) throw it
                com.avoqado.pos.tables.data.LealtadLeida(orderId, cliente, cuenta.version, null, com.avoqado.pos.core.data.network.FalloDeLectura.de(it))
            },
        )
    }

    /**
     * Tocar «Recompensas»: abre el diálogo y relee cuenta → saldo; el diálogo sólo pinta esa lectura fresca (como Mesas). La relectura
     * corre en el ViewModel, como en Mesas: vacía la cuenta un momento, el botón sale de la pantalla y su scope se cancelaba con él (D3, 26-nov).
     */
    fun abrirRecompensas(orderId: String, laTraeUnCarrito: Boolean = false) {
        avisoDeCanje.value = null
        avisoDeLasRecompensas.value = null
        dialogoDeRecompensas.value = true
        viewModelScope.launch { releerConSaldo(orderId, laTraeUnCarrito) }
    }

    fun cerrarRecompensas() {
        dialogoDeRecompensas.value = false
        avisoDeCanje.value = null
    }

    private suspend fun releerConSaldo(orderId: String, laTraeUnCarrito: Boolean) {
        lecturaDeLealtad.value = null
        turnoDeLealtad++
        ofertaParaCobrar(orderId, laTraeUnCarrito)
        leerLealtad(orderId)
    }

    /**
     * D39 (Codex r3 #2): el canje que se quedó sin respuesta, tal cual se mandó (con la versión de entonces). null = ninguno. D39 r10: con
     * su llave ([operationId], una por intento): repetirlo manda la MISMA, y el SERVIDOR contesta el desenlace del primero.
     */
    /** La cuenta con un canje sin respuesta: su botón es «Reintentar el canje», que manda EXACTAMENTE lo mismo. null = ninguna. */
    val canjeEnDuda: StateFlow<String?> = dudaDeLaCuenta.asStateFlow()

    /**
     * D39 (Codex r2 #2, r3 #1, r4 #1, pasada completa de la familia «cobro durante el canje»): ninguna entrada de cobro de esta cuenta
     * se abre mientras un canje viaja (su relectura vaciaría la foto del cobro) ni mientras uno se quedó sin respuesta (no se sabe cuánto
     * falta: si entró, el saldo es otro; si no, un «Reintentar» después de cobrar bajaría una cuenta ya cobrada). Las entradas: los dos
     * «Cobrar $X», «No se cobró: cobrar aquí» y el efectivo desde la copia sin red. Se revisa otra vez después de cada espera.
     */
    fun cobroBloqueadoPorCanje(orderId: String): Boolean = canjeDe(orderId).let { it.enVuelo || it.sinRespuesta != null }

    /**
     * D39 r10 (Codex r9 #1): ¿esta caja canjeó (o intentó canjear) puntos en la cuenta? Entonces su cobro va «sólo con conexión»: si la red se
     * cae a media petición el efectivo no se guarda en el aparato. Sin sucursal o sin el registro (pruebas), [CuentasAbiertasAqui.tieneCanje]
     * decide; si el disco falla dice que sí.
     */
    suspend fun tieneCanjeAqui(orderId: String): Boolean = storage.venueId?.let { v -> cuentasAbiertasAqui?.tieneCanje(v, orderId) } == true

    /**
     * D39 r11 (Codex r10 #6): justo antes de recibir el efectivo de un cobro «sólo con conexión», ¿hay red? Sin ella no se recibe el billete:
     * el cobro se cierra y la cuenta queda gris con su texto (sin red no se guarda en el aparato). null = sigue.
     */
    fun sinConexionParaElEfectivo(): String? =
        if (red.isConnected.value && red.isServerReachable.value) null else RECOMPENSAS_SIN_RED_CON_PUNTOS

    /** Lo que frena el efectivo desde la copia sin red por un canje: el de [cobroBloqueadoPorCanje] o un canje ya mandado desde aquí. */
    private suspend fun grisSinRedPorCanje(orderId: String): String? = when {
        cobroBloqueadoPorCanje(orderId) -> RECOMPENSAS_COBRO_ESPERA_AL_CANJE
        // D39 (Codex r5 #1): la copia pudo guardarse antes del canje; con el canje hecho aquí, sin red no se cobra (como Toast).
        storage.venueId?.let { v -> cuentasAbiertasAqui?.tieneCanje(v, orderId) } == true -> RECOMPENSAS_SIN_RED_CON_PUNTOS
        else -> null
    }

    /**
     * Canjea [points] mandando lo que enseñó el diálogo ([montoMostrado]) y la versión de la cuenta que se ve: el SERVIDOR sólo canjea
     * exactamente eso sobre esa misma versión (candado de la oferta, D18; versión, Codex D39 r1 #2), y quema los puntos y pone el
     * descuento en una sola transacción. Corre en el alcance del ViewModel: cerrar el diálogo no lo corta. Al terminar relee la cuenta —el
     * «Cobrar $X» cobra lo nuevo— y su saldo, y avisa a Pedidos ([alTerminar]); `canjeEnVuelo` sigue prendido hasta que la relectura
     * termina, así ningún cobro se abre sobre la foto que se está releyendo (Codex r3 #1).
     */
    fun canjear(orderId: String, points: Int, montoMostrado: Double, laTraeUnCarrito: Boolean = false, alTerminar: () -> Unit) {
        val canje = canjeDe(orderId)
        if (canje.enVuelo || canje.sinRespuesta != null) return
        if (!red.isConnected.value || !red.isServerReachable.value) {
            avisoDeLasRecompensas.value = com.avoqado.pos.tables.data.RECOMPENSAS_SIN_RED
            return
        }
        val cuenta = foto.value?.takeIf { it.id == orderId } ?: return
        venueDeLaCuenta?.takeIf { it == storage.venueId } ?: return
        val cliente = cuenta.customerId?.takeIf { it.isNotBlank() } ?: return
        val lealtad = lealtadDeLaCuenta()?.lealtad ?: return
        if (motivoSinRecompensasDeCuenta(cuenta, lecturaDeLealtad.value, enLinea = true, consultando = false, oferta = ofertaActual.value,
                puedeCanjear = puedeCanjearPuntos, prohibida = lecturaProhibida.value, laTraeUnCarrito = laTraeUnCarrito) != null) return
        // D39 r10: los puntos pueden cubrir toda la cuenta (Pedidos la cierra con «Cerrar cuenta ($0.00)», como Mesas). El tope es el del SERVIDOR.
        if (!lealtad.puedeCanjear(points)) return
        mandarCanje(CanjeSinRespuesta(orderId, cliente, points, montoMostrado, cuenta.version), laTraeUnCarrito, alTerminar)
    }

    /**
     * D39 (Codex r2 #3, r3 #2): repite el canje que se quedó sin respuesta, IDÉNTICO (la misma versión de la cuenta y, D39 r10, la misma
     * llave). Con la llave, el SERVIDOR espera al primer envío y contesta SU desenlace (entró, no entró o «sigue en proceso»); sin ella (un
     * aparato sin identidad UUID), si el primero entró el canje ya subió la versión y contesta 409 sin quemar nada. Sobrevive a cerrar el
     * diálogo, no a cerrar la app (al volver, sin red la marca no deja cobrar desde aquí y con red el efectivo no se guarda en el aparato).
     */
    fun reintentarCanje(orderId: String, laTraeUnCarrito: Boolean = false, alTerminar: () -> Unit) {
        val canje = canjeDe(orderId)
        val intento = canje.sinRespuesta ?: return
        if (canje.enVuelo) return
        if (!red.isConnected.value || !red.isServerReachable.value) {
            avisoDeLasRecompensas.value = com.avoqado.pos.tables.data.RECOMPENSAS_SIN_RED
            return
        }
        venueDeLaCuenta?.takeIf { it == storage.venueId } ?: return
        viewModelScope.launch {
            // Codex r4 #2: lo que frena un canje nuevo también frena repetirlo (un cobro de esta caja sin subir, la cuenta en el carrito).
            // Codex r5 #2: con la oferta RELEÍDA aquí, no la guardada (un disco que falló un instante dejaba un «sin subir» viejo).
            val oferta = if (puedeCanjearPuntos && !laTraeUnCarrito) ofertaParaCobrar(orderId, laTraeUnCarrito) else ofertaActual.value
            // Un segundo toque mientras se releía: ése ya lo manda el primero.
            if (canje.enVuelo || canje.sinRespuesta != intento) return@launch
            if (oferta != com.avoqado.pos.giftcards.domain.OfertaDeLaCuenta.OFRECER || laTraeUnCarrito || !puedeCanjearPuntos) {
                avisoDeLasRecompensas.value = when {
                    !puedeCanjearPuntos -> RECOMPENSAS_SIN_PERMISO
                    oferta == com.avoqado.pos.giftcards.domain.OfertaDeLaCuenta.COBRO_SIN_SINCRONIZAR -> RECOMPENSAS_CON_COBRO_SIN_SUBIR
                    laTraeUnCarrito -> RECOMPENSAS_EN_EL_CARRITO
                    else -> RECOMPENSAS_REINTENTO_NO_SE_PUEDE
                }
                return@launch
            }
            mandarCanje(intento, laTraeUnCarrito, alTerminar)
        }
    }

    private fun mandarCanje(intento: CanjeSinRespuesta, laTraeUnCarrito: Boolean, alTerminar: () -> Unit) {
        val venue = venueDeLaCuenta ?: return
        val canje = canjeDe(intento.orderId)
        val esReintento = canje.sinRespuesta == intento
        canje.ponerEnVuelo(true)
        dialogoDeRecompensas.value = false
        avisoDeCanje.value = null
        viewModelScope.launch {
            try {
                // D39 (Codex r5 #1): la marca «canje mandado desde aquí» va ANTES de la red; sin ella no se manda nada. Nunca se quita
                // (Codex r6 #2, r7 #1, r8 #2 y #3). Límite declarado: si esta caja intentó canjear en una cuenta, desde esta caja esa cuenta
                // sólo se cobra con conexión, aunque el canje no haya entrado (founder, «Sí, como Toast»).
                if (cuentasAbiertasAqui?.marcarCanje(venue, intento.orderId) == false) {
                    avisoDeLasRecompensas.value = RECOMPENSAS_NO_SE_GUARDO
                    return@launch
                }
                repo.redeemLoyaltyPoints(venue, intento.orderId, intento.customerId, intento.points, intento.monto, intento.version, intento.operationId).fold(
                    onSuccess = { aplicado ->
                        canje.sinRespuesta = null
                        avisoDeLasRecompensas.value = "Recompensa aplicada — ${aplicado.pointsRedeemed} puntos por " +
                            com.avoqado.pos.core.util.formatMoney(aplicado.discountAmount)
                    },
                    onFailure = { e ->
                        if (e is CancellationException) throw e
                        when {
                            // D39 r10 (Codex r9 #1): con llave, un 4xx sin el recibo del SERVIDOR (la puerta rebotó un reenvío de OkHttp antes
                            // de llegar a la llave) no dice nada del primer envío, que pudo entrar: queda en duda, con el cobro bloqueado, hasta
                            // que el reintento (la misma llave) traiga el desenlace.
                            e is com.avoqado.pos.tables.data.CanjeRechazado && !e.definitivo -> {
                                canje.sinRespuesta = intento
                                avisoDeLasRecompensas.value = RECOMPENSAS_EN_DUDA
                            }
                            // Un rechazo definitivo del SERVIDOR: el canje no se hizo con ESTE intento. Sin llave (un aparato sin identidad
                            // UUID), el 409 de un reintento puede ser el canje anterior que sí entró: se dice. Con llave el recibo ya lo descarta.
                            e is com.avoqado.pos.tables.data.CanjeRechazado && esReintento && !e.conLlave -> {
                                canje.sinRespuesta = null
                                // La cuenta ya no es la de entonces: o el canje anterior sí entró (subió la versión), u otra cosa la cambió.
                                avisoDeLasRecompensas.value = if (e.ofertaCambio) RECOMPENSAS_REINTENTO_YA_CAMBIO else
                                    com.avoqado.pos.core.data.network.ServerErrorText.humanize(e, "No se pudo canjear los puntos")
                            }
                            e is com.avoqado.pos.tables.data.CanjeRechazado && e.ofertaCambio -> {
                                canje.sinRespuesta = null
                                avisoDeCanje.value = "La cuenta cambió: revisa los puntos."
                                dialogoDeRecompensas.value = true
                            }
                            e is com.avoqado.pos.tables.data.CanjeRechazado -> {
                                canje.sinRespuesta = null
                                avisoDeLasRecompensas.value = com.avoqado.pos.core.data.network.ServerErrorText.humanize(e, "No se pudo canjear los puntos")
                            }
                            // Sin respuesta (red, tiempo agotado, 5xx): no se sabe si entró. Lo único que se ofrece es repetirlo idéntico.
                            else -> {
                                canje.sinRespuesta = intento
                                avisoDeLasRecompensas.value = RECOMPENSAS_EN_DUDA
                            }
                        }
                    },
                )
                canje.ponerEnDuda(canje.sinRespuesta?.orderId)
                releerConSaldo(intento.orderId, laTraeUnCarrito)
            } finally {
                canje.ponerEnVuelo(false)
            }
            alTerminar()
        }
    }
}

const val RECOMPENSAS_EN_DUDA =
    "No se sabe si el canje entró. Toca «Reintentar el canje»: manda lo mismo y, si ya había entrado, no se canjea otra vez."
const val RECOMPENSAS_REINTENTO_YA_CAMBIO =
    "No se volvió a canjear: la cuenta ya cambió. Si el canje anterior entró, la cuenta ya trae su descuento «Recompensas»; revísalo antes de canjear otra vez."
const val TEXTO_REINTENTAR_CANJE = "Reintentar el canje"
const val RECOMPENSAS_SIN_RED_CON_PUNTOS = "Esta cuenta tiene puntos canjeados: se cobra con conexión. Espera a que vuelva la red."
const val RECOMPENSAS_NO_SE_GUARDO = "No se pudo guardar el canje en el aparato: no se canjeó nada. Intenta otra vez."
const val RECOMPENSAS_REINTENTO_NO_SE_PUEDE = "No se puede repetir el canje ahora: la cuenta ya no se puede cobrar desde aquí. Vuelve a abrirla."
const val RECOMPENSAS_COBRO_ESPERA_AL_CANJE =
    "Hay un canje de puntos sin terminar en esta cuenta: no se sabe cuánto falta. Espera a que termine o reinténtalo con red antes de cobrar."
const val RECOMPENSAS_SIN_CLIENTE = "Esta cuenta no tiene cliente: no hay puntos que canjear."
const val RECOMPENSAS_CON_COBRO_SIN_SUBIR = "Esta cuenta tiene un cobro de esta caja que todavía no sube: canjea cuando suba."
const val RECOMPENSAS_SIN_PERMISO = "No tienes permiso para canjear puntos. Pídeselo a tu gerente."
const val RECOMPENSAS_SIN_ACCESO = "No se pueden leer los puntos: el plan del negocio o tus permisos no incluyen la lealtad."
const val RECOMPENSAS_EN_EL_CARRITO = "Esta cuenta está en el carrito: cóbrala desde ahí."
const val RECOMPENSAS_LEYENDO_LA_CUENTA = "Leyendo la cuenta…"
/**
 * D39 fase 1: por qué «Recompensas» de una cuenta de «Pagar después» está gris (null = se puede). Cada causa con su texto: sin red, sin
 * permiso, sin cliente, un cobro local sin subir, el plan, la cuenta en el carrito; lo demás es la misma regla de Mesas
 * ([com.avoqado.pos.tables.data.motivoSinRecompensas]). Los puntos pueden cubrir toda la cuenta (D39 r10): se cierra con «Cerrar cuenta ($0.00)».
 */
fun motivoSinRecompensasDeCuenta(
    cuenta: com.avoqado.pos.tables.data.OrderDetail?,
    leida: com.avoqado.pos.tables.data.LealtadLeida?,
    enLinea: Boolean,
    consultando: Boolean,
    oferta: com.avoqado.pos.giftcards.domain.OfertaDeLaCuenta,
    puedeCanjear: Boolean = true,
    prohibida: Boolean = false,
    laTraeUnCarrito: Boolean = false,
): String? {
    val vigente = leida.paraLaCuenta(cuenta)
    val lealtad = vigente?.lealtad
    return when {
        !enLinea -> com.avoqado.pos.tables.data.RECOMPENSAS_SIN_RED
        !puedeCanjear -> RECOMPENSAS_SIN_PERMISO
        cuenta != null && !consultando && cuenta.customerId.isNullOrBlank() -> RECOMPENSAS_SIN_CLIENTE
        oferta == com.avoqado.pos.giftcards.domain.OfertaDeLaCuenta.COBRO_SIN_SINCRONIZAR -> RECOMPENSAS_CON_COBRO_SIN_SUBIR
        laTraeUnCarrito -> RECOMPENSAS_EN_EL_CARRITO
        prohibida && lealtad == null -> RECOMPENSAS_SIN_ACCESO
        else -> com.avoqado.pos.tables.data.motivoSinRecompensas(
            tieneCliente = if (cuenta == null || consultando) null else !cuenta.customerId.isNullOrBlank(),
            lealtad = lealtad,
            fallo = vigente?.fallo,
            enLinea = enLinea,
            actualizandoCuenta = consultando,
            cuentaCancelada = cuenta?.status == "CANCELLED" || cuenta?.status == "DELETED",
            cuentaCambioAlLeer = lealtad?.let { l -> cuenta?.let { com.avoqado.pos.tables.data.maximoCeroContradiceLaCuenta(l, it) } } == true,
        ) ?: when {
            oferta != com.avoqado.pos.giftcards.domain.OfertaDeLaCuenta.OFRECER -> RECOMPENSAS_LEYENDO_LA_CUENTA
            else -> null
        }
    }
}

enum class CierreDeCuenta { ABIERTA, PAGADA, CANCELADA }

/** D39 r10 (founder, «Sí, de raíz»): el botón de una cuenta de Pedidos que quedó en $0.00 (puntos, cortesía o descuento que la cubren). */
const val TEXTO_CERRAR_EN_CERO = "Cerrar cuenta (\$0.00)"

/**
 * D39 r10: una cuenta abierta con productos que ya no debe nada (los puntos, una cortesía o un descuento la cubren) se cierra con un cobro
 * de $0.00, igual que Mesas cierra una cortesía (`TableOrderViewModel`, «cobrar $0 es justo lo que la cierra»; el SERVIDOR sólo acepta
 * $0 si el saldo de verdad es 0). Sin productos, ya pagada, cancelada, de tarjetas de regalo, o sin saber cuánto falta: no.
 */
fun cierraEnCero(cuenta: com.avoqado.pos.tables.data.OrderDetail): Boolean =
    restanteCents(cuenta) == 0 && cuenta.items.isNotEmpty() && cuenta.paymentStatus != "PAID" &&
        cuenta.status !in listOf("CANCELLED", "DELETED") && !cuenta.esCuentaDeTarjetas

/**
 * hz:bdd815fd:D5: lo que faltaba de [cuenta] según el SERVIDOR (`preBillBalance.remainingBalanceCents`), si es exactamente lo que cobra
 * esta pantalla ([cents]). null si la cuenta no lo trajo (SERVIDOR viejo: el importe salió de `total`) o no coincide: entonces el cobro
 * sale como siempre, sin el dato.
 */
internal fun saldoLeidoDelServidor(cuenta: com.avoqado.pos.tables.data.OrderDetail, cents: Int): Int? =
    cuenta.preBillBalance?.remainingBalanceCents?.takeIf { it == cents && it >= 0 }

@Composable
fun CobroDeCuentaScreen(
    orderId: String,
    onSalir: (CierreDeCuenta) -> Unit,
    viewModel: CobroDeCuentaViewModel = hiltViewModel(key = "cuenta-$orderId"),
    /**
     * D12: sin red, el cobro en efectivo de la cuenta de la copia de «Pedidos» por el saldo YA decidido (`DecisionSinRed.Cobrar.saldoCents`,
     * el mismo número del botón). No consulta la red. null = como siempre.
     */
    saldoSinRed: Int? = null,
) {
    if (saldoSinRed != null) { CobroDesdeLaCopia(orderId, saldoSinRed, onSalir, viewModel); return }
    val conectado by viewModel.conectado.collectAsState()
    val servidor by viewModel.servidor.collectAsState()
    val enLinea = controlDeRedDeCuenta(conectado, servidor, true)
    val detalle by viewModel.detalle.collectAsState()
    val oferta by viewModel.oferta.collectAsState()
    val cuentaDelCarrito = LocalCuentaDelCarrito.current
    val cerrarResultadoDeSaldo = LocalCerrarCuentaDeSaldo.current
    fun salir(cierre: CierreDeCuenta) {
        if (cierre != CierreDeCuenta.ABIERTA) cerrarResultadoDeSaldo(orderId)
        onSalir(cierre)
    }
    val mensaje by viewModel.mensaje.collectAsState()
    val canjeEnVuelo by viewModel.canjeEnVuelo.collectAsState()
    val canjeEnDuda by viewModel.canjeEnDuda.collectAsState()
    val scope = rememberCoroutineScope()
    var cobrar by rememberSaveable(orderId) { mutableStateOf(false) }
    var avisoLocal by rememberSaveable(orderId) { mutableStateOf<String?>(null) }
    var restanteTrasCobrar by rememberSaveable(orderId) { mutableStateOf<Int?>(null) }
    // D39 r10: se decide al tocar «Cobrar»; mientras, lo estricto.
    var soloConConexion by rememberSaveable(orderId) { mutableStateOf(true) }
    LaunchedEffect(orderId) { if (enLinea) viewModel.ofertaParaCobrar(orderId, cuentaDelCarrito() == orderId) }
    Dialog(onDismissRequest = { onSalir(CierreDeCuenta.ABIERTA) }, properties = DialogProperties(
        usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false)) {
        ImmersiveWindow()
        DisposableEffect(Unit) {
            com.avoqado.pos.payment.domain.PaymentOverlayState.setPaying(true)
            onDispose { com.avoqado.pos.payment.domain.PaymentOverlayState.setPaying(false) }
        }
        val cuenta = detalle
        val cents = cuenta?.let(::restanteCents)
        if (cobrar && cuenta != null && cents != null && (cents > 0 || cierraEnCero(cuenta))) {
            val cart = remember(cuenta.id, cents) { CartState(items = listOf(CartItem(
                type = CartItemType.CustomAmount, name = if (cuenta.esCuentaDeTarjetas) TextosDeTarjetas.NOMBRE_EN_CUENTAS else "Saldo pendiente", unitPrice = cents))) }
            PaymentFlowScreen(cartState = cart, resumeOrderId = cuenta.id,
                cuentaDeTarjetas = cuenta.esCuentaDeTarjetas, desdeCuentasAbiertas = true,
                // D13: la foto de antes de leer esta cuenta, con los pagos que trajo ESTA misma cuenta (de donde sale el saldo que se cobra);
                // lo que llegue después lo frena la puerta del efectivo.
                dineroConocido = viewModel.dineroConocido.copy(pagosContados = cuenta.payments.filter { it.status == "COMPLETED" }.map { it.id }.toSet()),
                // hz:bdd815fd:D5: lo que faltaba según ESTA pantalla, sólo si lo dijo el SERVIDOR (`preBillBalance`); si un abono entra
                // antes de que la terminal reciba el cobro, el SERVIDOR no cobra y lo dice.
                saldoLeidoDeLaCuenta = saldoLeidoDelServidor(cuenta, cents),
                soloConConexion = soloConConexion,
                antesDeRecibirEfectivo = if (soloConConexion) suspend { viewModel.sinConexionParaElEfectivo() } else null,
                onCuentaConSaldo = { cobrar = false; scope.launch { if (enLinea) viewModel.ofertaParaCobrar(orderId, cuentaDelCarrito() == orderId) } },
                onPaymentCommitted = { restanteTrasCobrar = it.remainingBalanceCents },
                onDone = {
                    if (restanteTrasCobrar == 0) salir(CierreDeCuenta.PAGADA)
                    else {
                        cobrar = false
                        restanteTrasCobrar = null
                        scope.launch { if (enLinea) viewModel.ofertaParaCobrar(orderId, cuentaDelCarrito() == orderId) }
                    }
                },
                onCancel = { onSalir(CierreDeCuenta.ABIERTA) },
                onPreviousChargeResolved = { texto -> cobrar = false; avisoLocal = texto; scope.launch { if (enLinea) viewModel.ofertaParaCobrar(orderId, cuentaDelCarrito() == orderId) } },
            )
        } else {
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                AvoqadoFullscreenHeader(title = if (cuenta?.esCuentaDeTarjetas == true) TextosDeTarjetas.NOMBRE_EN_CUENTAS else "Cuenta abierta",
                    onNav = { onSalir(CierreDeCuenta.ABIERTA) }, showDivider = true)
                Column(Modifier.padding(AvoqadoTheme.spacing.lg), verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.lg)) {
                    (if (enLinea) avisoLocal ?: mensaje else com.avoqado.pos.giftcards.domain.avisoDeCuentaSinRed(
                        cuenta?.esCuentaDeTarjetas != false, cuenta?.cobrosConSaldo, sePuedeCobrar = (cents ?: 0) > 0))?.let { Text(it) }
                    when {
                        cents == null && cuenta != null -> Text("No se pudo consultar lo que falta. Vuelve a abrir la cuenta.")
                        cents == 0 && cuenta?.let(::cierraEnCero) != true -> Text("Esta cuenta no tiene saldo pendiente.")
                        cents != null && cents >= 0 -> PrimaryButton(text = if (cents == 0) TEXTO_CERRAR_EN_CERO else TextosDeTarjetas.cobrar(cents), enabled = controlDeRedDeCuenta(conectado, servidor, viewModel.puedeCobrar && oferta == com.avoqado.pos.giftcards.domain.OfertaDeLaCuenta.OFRECER && !canjeEnVuelo && canjeEnDuda != orderId),
                            onClick = { scope.launch { if (enLinea && viewModel.puedeCobrar && !viewModel.cobroBloqueadoPorCanje(orderId) && viewModel.ofertaParaCobrar(orderId, cuentaDelCarrito() == orderId) == com.avoqado.pos.giftcards.domain.OfertaDeLaCuenta.OFRECER && !viewModel.cobroBloqueadoPorCanje(orderId)) {
                                soloConConexion = viewModel.tieneCanjeAqui(orderId)
                                cobrar = true
                            } } })
                    }
                    if (oferta == com.avoqado.pos.giftcards.domain.OfertaDeLaCuenta.COBRO_SIN_SINCRONIZAR) Text(TextosDeTarjetas.COBRO_SIN_SINCRONIZAR)
                    if (!viewModel.puedeCobrar) Text("No tienes permiso para cobrar. Pídeselo a tu gerente.")
                    TextButton(enabled = enLinea, onClick = { scope.launch { avisoLocal = null; if (enLinea) viewModel.ofertaParaCobrar(orderId, cuentaDelCarrito() == orderId) } }) { Text("Volver a consultar") }
                    if (cuenta != null && !cuenta.esCuentaDeTarjetas) RegresarSaldoDeCuenta(cuenta.id, cuenta.cobrosConSaldo ?: 0,
                        cuenta.paymentStatus == "PAID" || cuenta.status in listOf("CANCELLED", "DELETED"),
                        onCancelada = { salir(CierreDeCuenta.CANCELADA) })
                    if (cuenta?.esCuentaDeTarjetas == true && cuenta.paymentStatus != "PAID" &&
                        cuenta.status !in listOf("CANCELLED", "DELETED")) CancelarCuenta(viewModel) { salir(CierreDeCuenta.CANCELADA) }
                }
            }
        }
    }
}

/**
 * D12: el cobro en efectivo de una cuenta con la copia de «Pedidos». Sólo efectivo, contra SU orden y sin dividir (el saldo esperado
 * tras cobrar es 0); la revalidación corre justo antes de recibir el efectivo y, si dice que no, el flujo sale solo (`debeSalir` →
 * `onCancel`) y la cuenta enseña su gris. Final fix (M-a): SIEMPRE vuelve ABIERTA, nunca «Pagada». El anfitrión no lee un cierre: al
 * volver decide otra vez con la copia y recarga la cuenta, y eso es lo que se ve (un cobro encolado sale «sin sincronizar»; uno que
 * subió ya sacó la cuenta de la copia, final fix I-2, y la recarga con red la trae pagada).
 */
@Composable
private fun CobroDesdeLaCopia(orderId: String, cents: Int, onSalir: (CierreDeCuenta) -> Unit, viewModel: CobroDeCuentaViewModel) {
    var avisoLocal by rememberSaveable(orderId) { mutableStateOf<String?>(null) }
    fun salir() {
        viewModel.soltarCobroSinRed()
        onSalir(CierreDeCuenta.ABIERTA)
    }
    Dialog(onDismissRequest = { salir() }, properties = DialogProperties(
        usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false)) {
        ImmersiveWindow()
        DisposableEffect(Unit) {
            com.avoqado.pos.payment.domain.PaymentOverlayState.setPaying(true)
            onDispose { com.avoqado.pos.payment.domain.PaymentOverlayState.setPaying(false) }
        }
        val cart = remember(orderId, cents) { CartState(items = listOf(CartItem(
            type = CartItemType.CustomAmount, name = "Saldo pendiente", unitPrice = cents))) }
        // Como el cobro en línea: si se resolvió un cobro anterior de OTRA venta, este cobro se cierra y su aviso se queda a la vista.
        avisoLocal?.let { texto ->
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                AvoqadoFullscreenHeader(title = "Cuenta abierta", onNav = { salir() }, showDivider = true)
                Text(texto, Modifier.padding(AvoqadoTheme.spacing.lg))
            }
        } ?: PaymentFlowScreen(cartState = cart, resumeOrderId = orderId, desdeCuentasAbiertas = true,
            origen = com.avoqado.pos.payment.domain.OrigenDelCobro.CUENTA_ABIERTA_DESDE_LA_COPIA,
            antesDeRecibirEfectivo = { viewModel.revalidarCobroSinRed(orderId, cents) },
            alNoPasarLaPuerta = viewModel::alNoPasarLaPuerta,
            onPaymentCommitted = {},
            // D39 C1: cobrado desde la copia (se sube solo): la cuenta del atajo se cierra sin decir «quedó por cobrar».
            onDone = { viewModel.soltarCobroSinRed(); onSalir(CierreDeCuenta.PAGADA) },
            onCancel = { salir() },
            onPreviousChargeResolved = { texto -> avisoLocal = texto },
        )
    }
}

/**
 * D12: el botón sin red de la cuenta de la copia: «Cobrar $X en efectivo», «Avisándole a <caja>…» mientras le pregunta a la caja que la
 * abrió (5 s o menos), o el gris con su razón. En la caja que la abrió, si otra la comprometió y no confirmó en 2 min: el aviso y «No se
 * cobró: cobrar aquí» (Opción D, regla 7). Se vuelve a decidir cada 10 s: los 2 min pasan con la cuenta a la vista.
 */
@Composable
private fun CobrarCuentaSinRed(orderId: String, copia: CuentaGuardada, viewModel: CobroDeCuentaViewModel, onCobrar: (saldoCents: Int) -> Unit) {
    val decision by viewModel.sinRed.collectAsState()
    val avisandoA by viewModel.avisandoA.collectAsState()
    // Si este botón sale de la pantalla (atrás, o volvió la red) a media consulta, su alcance se cancela y el VM suelta lo concedido.
    val scope = rememberCoroutineScope()
    var revisando by remember(orderId) { mutableStateOf(false) }
    LaunchedEffect(copia) {
        while (true) {
            viewModel.consultarSinRed(orderId)
            kotlinx.coroutines.delay(10_000)
        }
    }
    fun pedir(accion: suspend () -> DecisionSinRed) {
        revisando = true
        scope.launch {
            try {
                val r = accion()
                if (r is DecisionSinRed.Cobrar) onCobrar(r.saldoCents)
            } finally { revisando = false }
        }
    }
    val textoRevisando = avisandoA?.let { TextosDeCuentaSinRed.avisandoA(it) } ?: TextosDeCuentaSinRed.REVISANDO
    when (val d = decision) {
        is DecisionSinRed.Cobrar -> PrimaryButton(
            text = if (revisando) textoRevisando else TextosDeCuentaSinRed.cobrarEnEfectivo(d.saldoCents),
            enabled = !revisando,
            onClick = { pedir { viewModel.pedirCobroSinRed(orderId) } })
        is DecisionSinRed.NoConfirmo -> {
            Text(d.texto)
            PrimaryButton(text = if (revisando) textoRevisando else TextosDeCuentaSinRed.NO_SE_COBRO_COBRAR_AQUI, enabled = !revisando,
                onClick = { pedir { viewModel.noSeCobroYCobrarAqui(orderId) } })
        }
        is DecisionSinRed.Gris -> Text(d.texto)
        null -> PrimaryButton(text = TextosDeTarjetas.CONSULTANDO, enabled = false, onClick = {})
    }
}

/** I2: con internet, el «no confirmó» de otra caja y su salida, en la caja que abrió la cuenta. Se vuelve a mirar cada 10 s. */
@Composable
private fun NoConfirmoEnLinea(orderId: String, viewModel: CobroDeCuentaViewModel, onDeclarado: () -> Unit) {
    val canjeEnVuelo by viewModel.canjeEnVuelo.collectAsState()
    val canjeEnDuda by viewModel.canjeEnDuda.collectAsState()
    var texto by remember(orderId) { mutableStateOf<String?>(null) }
    var declarando by remember(orderId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(orderId) {
        while (true) {
            texto = viewModel.noConfirmoEnLinea(orderId)
            kotlinx.coroutines.delay(10_000)
        }
    }
    texto?.let { t ->
        Text(t)
        PrimaryButton(text = TextosDeCuentaSinRed.NO_SE_COBRO_COBRAR_AQUI, enabled = !declarando && !canjeEnVuelo && canjeEnDuda != orderId, onClick = {
            // D39 (Codex r4 #1): mientras hay un canje no se declara nada: la declaración va antes de abrir el cobro.
            if (viewModel.cobroBloqueadoPorCanje(orderId)) return@PrimaryButton
            declarando = true
            scope.launch {
                try {
                    if (viewModel.declararNoSeCobro(orderId)) { texto = null; onDeclarado() }
                } finally { declarando = false }
            }
        })
    }
}

@Composable
private fun CancelarCuenta(viewModel: CobroDeCuentaViewModel, onCancelada: () -> Unit) {
    val conectado by viewModel.conectado.collectAsState()
    val servidor by viewModel.servidor.collectAsState()
    val enLinea = controlDeRedDeCuenta(conectado, servidor, true)
    val scope = rememberCoroutineScope()
    var confirmar by rememberSaveable { mutableStateOf(false) }
    var motivo by rememberSaveable { mutableStateOf("") }
    var enviando by remember { mutableStateOf(false) }
    TextButton(enabled = controlDeRedDeCuenta(conectado, servidor, viewModel.puedeCancelar && !enviando),
        onClick = { confirmar = true }) { Text(TextosDeTarjetas.CANCELAR_CUENTA) }
    if (!viewModel.puedeCancelar) Text("No tienes permiso para cancelar cuentas. Pídeselo a tu gerente.")
    if (confirmar) AvoqadoDialog(onDismiss = { if (!enviando) confirmar = false },
        title = TextosDeTarjetas.CANCELAR_CUENTA, dismissOnClickOutside = !enviando) {
        if (!enLinea) Text(TextosDeTarjetas.SIN_RED)
        AvoqadoPillTextField(value = motivo, onValueChange = { motivo = it }, placeholder = "Motivo")
        PrimaryButton(text = TextosDeTarjetas.CANCELAR_CUENTA, enabled = controlDeRedDeCuenta(conectado, servidor, viewModel.puedeCancelar && motivo.isNotBlank() && !enviando), onClick = {
            if (!viewModel.conectado.value || !viewModel.servidor.value) return@PrimaryButton
            confirmar = false
            enviando = true
            scope.launch {
                try { cancelarCuentaDesdeDialogo(motivo, viewModel::cancelar, onCancelada) }
                finally { enviando = false }
            }
        })
    }
}

val LocalCuentaDelCarrito = staticCompositionLocalOf<() -> String?> { { null } }
internal val LocalSolicitudesDeSaldo = compositionLocalOf<List<SolicitudDeTarjeta>> { emptyList() }
internal val LocalCuentasDeSaldoCerradas = compositionLocalOf<List<String>> { emptyList() }
internal val LocalCerrarCuentaDeSaldo = compositionLocalOf<(String) -> Unit> { {} }

/**
 * D39 C1 (Codex v3-r2 #5): cómo terminó el cobro de una cuenta (pagada, cancelada o la cajera salió sin cobrar), al cerrarse su pantalla
 * (después de «Listo»). La cuenta del atajo encima de Cobrar se cierra con esto, sin esperar a releer la cuenta. Fuera de ella, nada.
 */
val LocalAlSalirDelCobroDeCuenta = staticCompositionLocalOf<(orderId: String, cierre: CierreDeCuenta) -> Unit> { { _, _ -> } }

val LocalResultadoDeTarjetas = staticCompositionLocalOf<(String) -> Unit> {
    { error("Falta el anfitrión del resultado de tarjetas") }
}

internal fun alSalirDeCuentaAbierta(cierre: CierreDeCuenta, esCuentaDeTarjetas: Boolean, orderId: String,
    onActualizar: () -> Unit, onCancelada: () -> Unit, onResultadoDeTarjetas: (String) -> Unit) {
    if (cierre == CierreDeCuenta.PAGADA && esCuentaDeTarjetas) onResultadoDeTarjetas(orderId)
    if (cierre == CierreDeCuenta.CANCELADA) onCancelada()
    onActualizar()
}

/**
 * I1: qué pinta [AccionesDeCuentaAbierta]. Un cobro abierto (en línea, o sin red con su saldo ya decidido) se pinta SIEMPRE, aunque la
 * cuenta, recargada a medio cobro, ya salga PAGADA o CANCELADA: el cobro termina y la cajera ve su resultado y su cambio. Las acciones
 * (botón, avisos) sólo con una cuenta abierta de Mostrador o de tarjetas.
 */
internal data class LoQueSePinta(val acciones: Boolean, val cobroEnLinea: Boolean, val cobroSinRed: Int?)

internal fun loQueSePinta(order: com.avoqado.pos.orders.data.model.OrderDetail, abierta: Boolean, cobroSinRed: Int?) = LoQueSePinta(
    acciones = order.status !in listOf("CANCELLED", "DELETED") &&
        order.orderPaymentStatus != com.avoqado.pos.orders.data.model.PaymentStatus.PAID && (order.esDeMesa == false || order.esCuentaDeTarjetas),
    cobroEnLinea = abierta,
    cobroSinRed = cobroSinRed,
)

/**
 * Final fix ronda 2 (menor 1): el pedido armado con la copia, ya con red, no ofrece cobrar (F2) ni el cobro desde la copia (F3): sin esto
 * quedaba sin salida tras una reapertura con 5xx. «Reintentar» lo vuelve a pedir sin desmontar lo que se ve (`refrescarDetalle`).
 */
internal fun ofreceReintentarDetalle(order: com.avoqado.pos.orders.data.model.OrderDetail, enLinea: Boolean): Boolean = order.deLaCopia && enLinea

/** I1: recargar la cuenta vuelve a pedir la oferta sólo con red y sin un cobro abierto (pedirla vacía la foto del cobro en línea). */
internal fun pideOfertaAlRecargar(enLinea: Boolean, cobroAbierto: Boolean): Boolean = enLinea && !cobroAbierto

/** «Reintentar» es para una consulta que ya falló; antes de que arranque la primera también se está consultando. */
internal fun cuentaEnConsulta(consultasEnCurso: Int, enLinea: Boolean, consultaPedida: Boolean) =
    consultasEnCurso > 0 || (enLinea && !consultaPedida)

@Composable
fun AccionesDeCuentaAbierta(
    order: com.avoqado.pos.orders.data.model.OrderDetail,
    onActualizar: () -> Unit,
    onCancelada: () -> Unit,
    onSalioSinCobrar: () -> Unit,
    /** D12: la cuenta de la copia que respalda este detalle sin red (`OrdersRepository.detalleDeLaCopia`), o null. */
    copia: CuentaGuardada? = null,
    /** Final fix ronda 2 (menor 1): vuelve a pedir el pedido armado con la copia (`OrdersViewModel.refrescarDetalle`). */
    onReintentar: () -> Unit = {},
    viewModel: CobroDeCuentaViewModel = hiltViewModel(key = "cuenta-${order.id}"),
) {
    val onResultadoDeTarjetas = LocalResultadoDeTarjetas.current
    val conectado by viewModel.conectado.collectAsState()
    val servidor by viewModel.servidor.collectAsState()
    val enLinea = controlDeRedDeCuenta(conectado, servidor, true)
    val scope = rememberCoroutineScope()
    var abierta by rememberSaveable(order.id) { mutableStateOf(false) }
    var aviso by rememberSaveable(order.id) { mutableStateOf<String?>(null) }
    // D12: el saldo YA decidido del cobro sin red abierto con la copia (null = ninguno). No depende de `conCopia`: sigue abierto
    // aunque vuelva la red a medio cobro. T12b (O2): sobrevive a reconstruir la Activity, como `abierta`.
    var cobroSinRed by rememberSaveable(order.id) { mutableStateOf<Int?>(null) }
    val cuentaDelCarrito = LocalCuentaDelCarrito.current
    val alSalirDelCobro = LocalAlSalirDelCobroDeCuenta.current
    DisposableEffect(viewModel) { onDispose { viewModel.soltarCobroSinRed() } }
    // I1: los cobros abiertos van ANTES de la salida temprana (ver `loQueSePinta`).
    val pinta = loQueSePinta(order, abierta, cobroSinRed)
    if (pinta.cobroEnLinea) CobroDeCuentaScreen(order.id, onSalir = { cierre ->
        abierta = false
        if (cierre == CierreDeCuenta.ABIERTA) aviso = "La venta quedó en Cuentas abiertas."
        alSalirDeCuentaAbierta(cierre, order.esCuentaDeTarjetas, order.id,
            if (cierre == CierreDeCuenta.ABIERTA) onSalioSinCobrar else onActualizar, onCancelada, onResultadoDeTarjetas)
        // T12b (M-n2): la oferta también (recarga la cuenta): si la recarga trae la misma cuenta, el efecto de abajo no corre.
        scope.launch { if (enLinea) viewModel.ofertaParaCobrar(order.id, cuentaDelCarrito() == order.id) }
        alSalirDelCobro(order.id, cierre)
    }, viewModel = viewModel)
    // Al volver, sin «La venta quedó en Cuentas abiertas» (un cobro guardado no es salir sin cobrar). Si la revalidación dijo que no,
    // la cuenta se queda con ese gris; si no, se vuelve a decidir y se refresca (un cobro encolado se ve «sin sincronizar»).
    pinta.cobroSinRed?.let { saldo ->
        CobroDeCuentaScreen(order.id, onSalir = { cierre ->
            cobroSinRed = null
            scope.launch { if (viewModel.alVolverDelCobroSinRed(order.id)) onActualizar() }
            alSalirDelCobro(order.id, cierre)
        }, viewModel = viewModel, saldoSinRed = saldo)
    }
    if (!pinta.acciones) {
        // D39 (atajo, Codex r2/r3 #5): la cuenta ya no está abierta (pagada o cancelada): se dice con su folio y se da por atendida.
        var avisoDelAtajo by rememberSaveable(order.id) { mutableStateOf<String?>(null) }
        if (viewModel.aperturaPedida(order.id)) LaunchedEffect(order.id) {
            avisoDelAtajo = com.avoqado.pos.pos.presentation.cart.avisoCuentaYaCerrada("#" + order.orderNumber)
            viewModel.aperturaAtendida(order.id)
        }
        avisoDelAtajo?.let { Text(it) }
        return
    }
    val solicitudes by viewModel.solicitudes.collectAsState()
    val oferta by viewModel.oferta.collectAsState()
    val detalle by viewModel.detalle.collectAsState()
    val consultasEnCurso by viewModel.consultasEnCurso.collectAsState()
    val mensaje by viewModel.mensaje.collectAsState()
    val canjeEnVuelo by viewModel.canjeEnVuelo.collectAsState()
    val canjeEnDuda by viewModel.canjeEnDuda.collectAsState()
    val solicitudesPropias = solicitudesDeLaCuenta(solicitudes, viewModel.venueActual, order.id)
    val porTerminar = solicitudesPropias.any { it.porTerminar }
    val elegible = com.avoqado.pos.orders.data.model.puedeCobrarseDesdeLaLista(order.cobrosConSaldo, order, porTerminar)
    var consultaPedida by remember(order.id) { mutableStateOf(false) }
    LaunchedEffect(order, solicitudesPropias) {
        consultaPedida = true
        if (pideOfertaAlRecargar(enLinea, cobroAbierto = abierta || cobroSinRed != null)) viewModel.ofertaParaCobrar(order.id, cuentaDelCarrito() == order.id)
    }
    val cents = detalle?.let(::restanteCents)
    // D12: sin red, la cuenta que respalda la copia se decide con la FILA de la copia, no con este detalle reconstruido (`payments`
    // null abriría la compuerta de abonos por accidente). Con red, todo como siempre.
    val conCopia = !enLinea && copia != null && copia.cuenta.id == order.id
    // I2: con internet, la caja que la abrió también ofrece «No se cobró: cobrar aquí» cuando otra la comprometió y no confirmó.
    if (enLinea && elegible) NoConfirmoEnLinea(order.id, viewModel) {
        scope.launch {
            // D39 (Codex r3 #1): tampoco abre el cobro mientras viaja un canje (su relectura vaciaría la foto del cobro).
            if (viewModel.puedeCobrar && !viewModel.cobroBloqueadoPorCanje(order.id) && viewModel.ofertaParaCobrar(order.id, cuentaDelCarrito() == order.id) == com.avoqado.pos.giftcards.domain.OfertaDeLaCuenta.OFRECER && !viewModel.cobroBloqueadoPorCanje(order.id)) abierta = true
        }
    }
    if (conCopia) CobrarCuentaSinRed(order.id, copia!!, viewModel) { saldo -> cobroSinRed = saldo }
    else if (elegible) {
        if (detalle == null && cuentaEnConsulta(consultasEnCurso, enLinea, consultaPedida)) PrimaryButton(text = TextosDeTarjetas.CONSULTANDO, enabled = false, onClick = {})
        else if (detalle == null) TextButton(enabled = enLinea, onClick = {
            scope.launch { if (enLinea) viewModel.ofertaParaCobrar(order.id, cuentaDelCarrito() == order.id) }
        }) { Text(TextosDeTarjetas.REINTENTAR) }
        else if (cents == null && detalle != null) Text("No se pudo consultar lo que falta. Vuelve a abrir la cuenta.")
        // D39 (Codex r2 #2): mientras viaja un canje no se abre el cobro: su relectura vaciaría la foto y reiniciaría el cobro abierto.
        // D39 r10: la que quedó en $0.00 (los puntos la cubren) se cierra con el mismo cobro, en $0.00.
        else if (cents != null && (cents > 0 || detalle?.let(::cierraEnCero) == true)) PrimaryButton(text = if (cents == 0) TEXTO_CERRAR_EN_CERO else TextosDeTarjetas.cobrar(cents), enabled = controlDeRedDeCuenta(conectado, servidor, viewModel.puedeCobrar && oferta == com.avoqado.pos.giftcards.domain.OfertaDeLaCuenta.OFRECER && !canjeEnVuelo && canjeEnDuda != order.id),
            onClick = { scope.launch { if (enLinea && viewModel.puedeCobrar && !viewModel.cobroBloqueadoPorCanje(order.id) && viewModel.ofertaParaCobrar(order.id, cuentaDelCarrito() == order.id) == com.avoqado.pos.giftcards.domain.OfertaDeLaCuenta.OFRECER && !viewModel.cobroBloqueadoPorCanje(order.id)) abierta = true } })
        if (!viewModel.puedeCobrar) Text("No tienes permiso para cobrar. Pídeselo a tu gerente.")
    } else if (ofreceReintentarDetalle(order, enLinea)) TextButton(onClick = onReintentar) { Text(TextosDeTarjetas.REINTENTAR) }
    // D39 fase 1: el canje de Mesas en una cuenta abierta de Mostrador con cliente (las de «Pagar después»). Sin red, gris con su texto.
    // D39 r11 (Codex r10 #2): con un canje en duda, su «Reintentar el canje» sigue a la vista aunque la relectura traiga la cuenta en $0.
    val ofrece = ofreceRecompensas(order, detalle, conCopia, elegible, cents) || canjeEnDuda == order.id
    if (ofrece) RecompensasDeLaCuenta(order.id, enLinea, viewModel, onActualizar)
    // D39 (atajo): la cuenta que pidió el atajo ya no admite puntos (pagada, cancelada, sin saldo): se dice con su folio (Codex r1 #6).
    LaunchedEffect(order.id, ofrece, detalle?.version, consultasEnCurso) {
        if (ofrece || !viewModel.aperturaPedida(order.id) || detalle?.id != order.id) return@LaunchedEffect
        val r = com.avoqado.pos.pos.presentation.cart.aperturaDeRecompensas(false, consultasEnCurso > 0, null, "#" + order.orderNumber)
        if (r is com.avoqado.pos.pos.presentation.cart.AperturaDeRecompensas.Avisar) {
            aviso = r.texto
            viewModel.aperturaAtendida(order.id)
        }
    }
    if (!order.esCuentaDeTarjetas) RegresarSaldoDeCuenta(order.id, order.cobrosConSaldo ?: 0,
        order.orderPaymentStatus == com.avoqado.pos.orders.data.model.PaymentStatus.PAID || order.status in listOf("CANCELLED", "DELETED"),
        onCancelada = { onCancelada(); onActualizar() })
    if (detalle?.esCuentaDeTarjetas == true) CancelarCuenta(viewModel) { onCancelada(); onActualizar() }
    if (!conCopia && oferta == com.avoqado.pos.giftcards.domain.OfertaDeLaCuenta.COBRO_SIN_SINCRONIZAR) Text(TextosDeTarjetas.COBRO_SIN_SINCRONIZAR)
    if (!conCopia) (if (enLinea) mensaje ?: aviso else com.avoqado.pos.giftcards.domain.avisoDeCuentaSinRed(
        order.esCuentaDeTarjetas, detalle?.cobrosConSaldo ?: order.cobrosConSaldo,
        sePuedeCobrar = elegible && (detalle == null || (cents ?: 0) > 0)))?.let { Text(it) }
}

/**
 * D39 fase 1: dónde sale «Recompensas» en Pedidos. Sólo en una cuenta de «Pagar después» (`payLater` del SERVIDOR: sin mesa no basta,
 * una importada o de plataforma tampoco tiene, Codex r1 #1), no de tarjetas de regalo, con cliente, y donde se ofrece cobrarla: con
 * red, la cuenta con saldo que el botón «Cobrar» cobra; sin red, la de la copia (gris con su texto). Lo demás lo decide
 * [motivoSinRecompensasDeCuenta].
 */
internal fun ofreceRecompensas(
    order: com.avoqado.pos.orders.data.model.OrderDetail,
    detalle: com.avoqado.pos.tables.data.OrderDetail?,
    conCopia: Boolean,
    elegible: Boolean,
    cents: Int?,
): Boolean = when {
    order.esCuentaDeTarjetas -> false
    // Con red: el detalle fresco del SERVIDOR, con su cliente.
    !conCopia -> detalle?.id == order.id && detalle.payLater == true && detalle.esDeMesa == false && !detalle.esCuentaDeTarjetas &&
        !detalle.customerId.isNullOrBlank() && elegible && (cents ?: 0) > 0
    // Sin red (Codex r2 #5): la fila de la copia también dice `payLater`; se ve gris con «Sin conexión…», también abierta por primera vez.
    else -> order.payLater == true && order.esDeMesa == false
}

/** El botón «Recompensas» de una cuenta de Pedidos y su diálogo (el de Mesas). */
@Composable
private fun RecompensasDeLaCuenta(orderId: String, enLinea: Boolean, viewModel: CobroDeCuentaViewModel, onActualizar: () -> Unit) {
    val detalle by viewModel.detalle.collectAsState()
    val leida by viewModel.lealtadLeida.collectAsState()
    val prohibida by viewModel.lealtadProhibida.collectAsState()
    val oferta by viewModel.oferta.collectAsState()
    val consultas by viewModel.consultasEnCurso.collectAsState()
    val abiertas by viewModel.recompensasAbiertas.collectAsState()
    val avisoDelCanje by viewModel.avisoDelCanje.collectAsState()
    val resultado by viewModel.avisoDeRecompensas.collectAsState()
    val enVuelo by viewModel.canjeEnVuelo.collectAsState()
    val cuentaDelCarrito = LocalCuentaDelCarrito.current
    val cuenta = detalle?.takeIf { it.id == orderId }
    // Cada cuenta nueva (otra versión, otra clienta) o la red que vuelve relee el saldo contra ella.
    LaunchedEffect(cuenta?.id, cuenta?.version, cuenta?.customerId, enLinea) { if (enLinea && cuenta != null) viewModel.leerLealtad(orderId) }
    val enCarrito = cuentaDelCarrito() == orderId
    val enDuda by viewModel.canjeEnDuda.collectAsState()
    val motivo = motivoSinRecompensasDeCuenta(cuenta, leida, enLinea, consultas > 0, oferta, viewModel.puedeCanjearPuntos, prohibida, enCarrito)
    // D39 (atajo «Usar puntos»): la cuenta que pidió el atajo abre su diálogo sola al quedar lista; con una razón final, la razón ya se
    // ve aquí abajo (con el folio arriba) y la solicitud se da por atendida (Codex r1 #6). Nunca canjea: eso es «Canjear».
    // Codex r5: al salir por la barra, Pedidos y la pantalla nueva conviven ~700 ms (como el lector de Mesas, Codex d2 #1): sólo se
    // abre con esta pantalla RESUMED; si se va, `OrdersScreen` suelta la cuenta al desmontarse.
    val activa = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle.currentStateAsState().value
        .isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
    LaunchedEffect(orderId, motivo, consultas, cuenta?.version, activa) {
        if (!activa || !viewModel.aperturaPedida(orderId)) return@LaunchedEffect
        when (com.avoqado.pos.pos.presentation.cart.aperturaDeRecompensas(true, consultas > 0, motivo, orderId)) {
            com.avoqado.pos.pos.presentation.cart.AperturaDeRecompensas.Abrir -> {
                viewModel.aperturaAtendida(orderId)
                viewModel.abrirRecompensas(orderId, enCarrito)
            }
            is com.avoqado.pos.pos.presentation.cart.AperturaDeRecompensas.Avisar -> viewModel.aperturaAtendida(orderId)
            com.avoqado.pos.pos.presentation.cart.AperturaDeRecompensas.Esperar -> Unit
        }
    }
    if (enDuda == orderId) {
        // Codex r3 #2: con un canje sin respuesta no se ofrece uno nuevo; sólo repetir ESE, idéntico (no se canjea dos veces).
        TextButton(enabled = !enVuelo && enLinea, onClick = { viewModel.reintentarCanje(orderId, enCarrito, onActualizar) }) {
            Text(TEXTO_REINTENTAR_CANJE)
        }
    } else TextButton(enabled = !enVuelo && motivo == null, onClick = { viewModel.abrirRecompensas(orderId, enCarrito) }) {
        Text("Recompensas")
    }
    // Sin red es un estado normal: el mismo color de las notas, nunca rojo.
    (if (enVuelo) TEXTO_CANJEANDO else if (enDuda == orderId) resultado ?: RECOMPENSAS_EN_DUDA else motivo ?: resultado)?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val lealtad = leida.paraLaCuenta(cuenta)?.lealtad
    if (abiertas) RewardsDialog(
        loyalty = lealtad,
        motivo = motivo,
        aviso = avisoDelCanje,
        enLinea = enLinea,
        onDismiss = viewModel::cerrarRecompensas,
        onRedeem = { pts, monto -> viewModel.canjear(orderId, pts, monto, enCarrito, onActualizar) },
        // En Pedidos no hay «quitar la recompensa»: los puntos regresan si se cancela la venta.
        nota = { "Mínimo ${it.minPointsRedeem} puntos. Si se cancela la venta, los puntos regresan." },
    )
}

const val TEXTO_CANJEANDO = "Canjeando los puntos…"

@Composable
fun RegresarSaldoDeCuenta(orderId: String, cobrosConSaldo: Int, pagada: Boolean, onCancelada: () -> Unit,
    viewModel: PaymentFlowViewModel = hiltViewModel(key = "regreso-$orderId")) {
    val conectado by viewModel.conexionDelSaldo.collectAsState()
    val servidor by viewModel.servidorDelSaldo.collectAsState()
    val enLinea = controlDeRedDeCuenta(conectado, servidor, true)
    var abierta by remember(orderId) { mutableStateOf(false) }
    if (viewModel.ofreceRegresarSaldo(cobrosConSaldo, pagada)) TextButton(enabled = enLinea, onClick = { abierta = true }) {
        Text(TextosDeTarjetas.REGRESAR_SALDO_Y_CANCELAR)
    }
    if (abierta) DialogoDeRegresoDeSaldo(orderId, cobrosConSaldo, pagada,
        onDismiss = { abierta = false }, onCancelada = { abierta = false; onCancelada() }, viewModel = viewModel)
}

@Composable
fun DialogoDeRegresoDeSaldo(orderId: String, cobrosConSaldo: Int, pagada: Boolean, onDismiss: () -> Unit,
    onCancelada: () -> Unit, viewModel: PaymentFlowViewModel = hiltViewModel(key = "regreso-$orderId")) {
    val cerrarResultadoDeSaldo = LocalCerrarCuentaDeSaldo.current
    val conectado by viewModel.conexionDelSaldo.collectAsState()
    val servidor by viewModel.servidorDelSaldo.collectAsState()
    val enLinea = controlDeRedDeCuenta(conectado, servidor, true)
    val scope = rememberCoroutineScope()
    var preparada by remember(orderId) { mutableStateOf(false) }
    var ocupada by remember { mutableStateOf(false) }
    val mensaje by viewModel.cancelFailure.collectAsState()
    LaunchedEffect(orderId) { preparada = viewModel.prepararRegresoDeSaldo(orderId, cobrosConSaldo, pagada) }
    AvoqadoDialog(title = TextosDeTarjetas.REGRESAR_SALDO_Y_CANCELAR,
        description = TextosDeTarjetas.REGRESAR_SALDO_CUERPO,
        onDismiss = { if (!ocupada) onDismiss() }, dismissOnClickOutside = !ocupada) {
        if (!enLinea) Text(TextosDeTarjetas.SIN_RED) else mensaje?.let { Text(it) }
        PrimaryButton(text = TextosDeTarjetas.REGRESAR_SALDO_Y_CANCELAR, enabled = controlDeRedDeCuenta(conectado, servidor, preparada && !ocupada),
            onClick = {
                ocupada = true
                scope.launch { try { if (viewModel.regresarSaldoYCancelar()) { cerrarResultadoDeSaldo(orderId); onCancelada() } } finally { ocupada = false } }
            })
    }
}

internal fun controlDeRedDeCuenta(conectado: Boolean, servidor: Boolean, disponible: Boolean): Boolean = conectado && servidor && disponible

internal suspend fun cancelarCuentaDesdeDialogo(motivo: String, cancelar: suspend (String) -> Boolean,
    onCancelada: () -> Unit): Boolean {
    val cancelada = cancelar(motivo)
    if (cancelada) onCancelada()
    return !cancelada
}

internal fun solicitudesDeLaCuenta(solicitudes: List<SolicitudDeTarjeta>, venueId: String?, orderId: String): Set<SolicitudDeTarjeta> =
    solicitudes.filter { it.venueId == venueId && it.orderId == orderId }.toSet()
