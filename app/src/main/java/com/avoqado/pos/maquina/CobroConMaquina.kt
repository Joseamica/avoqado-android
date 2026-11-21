package com.avoqado.pos.maquina

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneId
import java.util.Random

/** Una venta congelada para cobrarse en la máquina. `Due = sale + tip` (R3#6). */
data class VentaParaLaMaquina(
    val venueId: String,
    val llaveDeVenta: String,
    /** El carrito congelado al cotizar (R5#1): el mismo JSON de creación de orden que sube con el cierre. */
    val pedidoJson: String,
    val saleCents: Int,
    val tipCents: Int,
    val saleShiftId: String? = null,
    val quoteId: String? = null,
    /** Codex código r1 #14: los renglones congelados (los de la cotización, o los del carrito sin red): el ticket sale de aquí. */
    val renglonesJson: String? = null,
    /** Codex código r1 #14: hasta cuándo vale la cotización para EMPEZAR el cobro (reloj de la PC). null = sin cotización. */
    val vigenteHasta: Long? = null,
)

sealed interface ResultadoDeInicio {
    data class Iniciado(val referenceCC: String) : ResultadoDeInicio
    /** `Due` ≤ 0, no múltiplo de $0.50, o importes negativos: no se guardó ni se mandó nada. */
    data object NoCobrable : ResultadoDeInicio
    /** Esta venta ya tiene un intento abierto, en duda o pagado (en esta PC o en otra caja sobre la misma base). */
    data object VentaConIntento : ResultadoDeInicio
    /** Esta PC ya tiene un cobro vivo en la máquina. */
    data object Ocupado : ResultadoDeInicio
    /** El `paycash` no salió (sin conexión): no quedó fila. */
    data object SinConexion : ResultadoDeInicio
    /** Codex código r1 #14: la cotización venció antes del `paycash`: no se guardó ni se mandó nada; hay que volver a cotizar. */
    data object CotizacionVencida : ResultadoDeInicio
}

enum class Desenlace { PAGADO, CANCELADO, GUARDADO }

/** Lo que enseña la pantalla. Los textos fijos («¿Sigues ahí?», «Esperando a la máquina»…) los pone C5. */
sealed interface EstadoDelCobro {
    data object Libre : EstadoDelCobro

    /** El `paycash` salió; la máquina tiene 3 s para aceptarlo con nuestro folio y `Due`. */
    data class Iniciando(val referenceCC: String, val dueCents: Int) : EstadoDelCobro

    /** [propio]: el cobro es nuestro en esta conexión (botón «Cancelar» y abandono). Sin propiedad, el crédito sólo se muestra. */
    data class Cobrando(val referenceCC: String, val dueCents: Int, val creditCents: Int, val propio: Boolean) : EstadoDelCobro

    data class SiguesAhi(val referenceCC: String, val dueCents: Int, val creditCents: Int, val segundos: Int) : EstadoDelCobro

    /** Salió el `cancel` [cancelvalue]; el desenlace lo dice sólo el `status` de cierre. */
    data class Cancelando(val referenceCC: String, val dueCents: Int, val creditCents: Int, val cancelvalue: String) : EstadoDelCobro

    /** Se cayó la conexión con el cobro abierto: se espera, sin adoptarlo, un `status` de nuestro folio y `Due`. */
    data class Reconectando(val referenceCC: String, val dueCents: Int, val creditCents: Int) : EstadoDelCobro

    data class Cerrado(
        val referenceCC: String,
        val desenlace: Desenlace,
        val dueCents: Int,
        val creditCents: Int,
        val changeDispensedCents: Int,
    ) : EstadoDelCobro

    /** «Esperando a la máquina»: la PC no sabe cómo terminó. Sólo lo resuelve un gerente (R4-4), nunca la PC sola. */
    data class Esperando(val referenceCC: String, val dueCents: Int) : EstadoDelCobro
}

/**
 * Codex código r1 #13: lo que la máquina le quedó a deber al cliente por lo FÍSICO, con la fórmula del SERVIDOR (`deudaDelCierre`):
 * PAGADO, el cambio que no dio; CANCELADO, lo que no devolvió; GUARDADO, lo que se quedó (sin comprobante: nadie está para recibirlo).
 */
fun deudaFisica(desenlace: Desenlace, dueCents: Int, creditCents: Int, changeCents: Int): Int = when (desenlace) {
    Desenlace.PAGADO -> creditCents - dueCents - changeCents
    Desenlace.CANCELADO, Desenlace.GUARDADO -> creditCents - changeCents
}.coerceAtLeast(0)

/**
 * El desenlace de un `status` de cierre de NUESTRO folio y `Due`, con las mismas reglas que el SERVIDOR (`validarCierre`):
 * - PAGADO: `credit ≥ due` y `0 ≤ cambio ≤ credit − due`;
 * - «Transacción Cancelada»: `credit < due`; GUARDADO sólo tras nuestro `cancel "2"` y sin cambio; CANCELADO sólo tras
 *   nuestro `cancel "1"` y `cambio ≤ credit`. Cancelada sin un `cancel` nuestro (la pantalla de la máquina) es modo
 *   desconocido (R4-7 #6).
 *
 * null = contradictorio: el intento queda en duda con el mensaje crudo, nunca se descarta.
 */
internal fun desenlaceDelCierre(cierre: Cierre, cancelPedido: String?, dueCents: Int, creditCents: Int, changeCents: Int): Desenlace? {
    if (creditCents < 0 || changeCents < 0) return null
    return when (cierre) {
        Cierre.PAGADO -> Desenlace.PAGADO.takeIf { creditCents >= dueCents && changeCents <= creditCents - dueCents }
        Cierre.CANCELADO -> when {
            creditCents >= dueCents -> null
            cancelPedido == "2" -> Desenlace.GUARDADO.takeIf { changeCents == 0 }
            cancelPedido == "1" -> Desenlace.CANCELADO.takeIf { changeCents <= creditCents }
            else -> null
        }
    }
}

/**
 * El cobro de una venta en la QuickPay (fase 1a, C3). Cada paso se guarda en `cobros_en_maquina` ANTES de tocar la red, y
 * todo (mensajes, cambios de conexión, relojes y botones) corre bajo un solo candado, en orden.
 *
 * - **Propiedad:** se gana sólo con un `status` de nuestro folio y `Due` en la conexión donde salió el `paycash`
 *   ([CobroEnMaquinaEntity.generacionPropia]). Se pierde al caerse esa conexión o al ver otra operación, y no regresa.
 *   Sin ella no corre el abandono, no sale ningún `cancel` y `updateCredit` sólo se muestra.
 * - **Lo que nunca decide:** `sockets`, `updateCredit`, `pong`, el silencio, una máquina libre y los mensajes de otra
 *   conexión. Un `status` de otra operación (otro folio, `DOTACION`, otro `Due`) deja el intento en duda.
 * - **La duda (`ESPERANDO`)** no la resuelve la PC: lo que la máquina diga después de ese folio se guarda como evidencia.
 */
class CobroConMaquina(
    private val cliente: CanalDeLaMaquina,
    private val dao: CobroEnMaquinaDao,
    private val reloj: () -> Long,
    private val scope: CoroutineScope,
    private val aleatorio: Random = SecureRandom(),
    /** C4: un cierre o una duda ya están en disco; la subida ([SubidaDeLaMaquina.disparar]) los manda sin esperar a su reloj. */
    private val alTerminar: () -> Unit = {},
    /** Codex código r1 #15: la zona en que la máquina escribe su `fecha` (la del negocio). */
    private val zona: () -> ZoneId = { ZoneId.systemDefault() },
) {
    private val _estado = MutableStateFlow<EstadoDelCobro>(EstadoDelCobro.Libre)
    val estado: StateFlow<EstadoDelCobro> = _estado.asStateFlow()

    private enum class Fase { INICIANDO, COBRANDO, SIGUES_AHI, CANCELANDO, RECONECTANDO, CERRADO, ESPERANDO }

    private class Intento(val ref: String, val due: Int) {
        var fase = Fase.INICIANDO
        /** La conexión cuyos mensajes cuentan; null mientras se reconecta. */
        var vigilada: Int? = null
        /** La conexión donde se ganó la propiedad; [propio] sólo mientras sigue siendo [vigilada]. */
        var generacionPropia: Int? = null
        var propio = false
        var credit = 0
        var change = 0
        var cancelPedido: String? = null
        var desenlace: Desenlace? = null
        /** Codex código r1 #4: desde cuándo no hay enlace con la máquina (null = hay, o ya se oyó nuestro folio en la nueva). */
        var sinEnlaceDesde: Long? = null
        val crudos = ArrayDeque<String>()
        val vivo get() = fase != Fase.CERRADO && fase != Fase.ESPERANDO
    }

    // Todo lo de abajo cambia sólo con el candado.
    private val candado = Mutex()
    private var activo: Intento? = null
    private var temporizador: Job? = null
    private var turno = 0L
    /** El reloj armado es el de «sin enlace» (#4): no se vuelve a armar en cada revisión, o nunca dispararía. */
    private var relojSinEnlace = false

    init {
        // UNDISPATCHED: la suscripción queda hecha al construir (`recibidos` no repite lo pasado).
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            cliente.recibidos.collect { r -> candado.withLock { alRecibir(r) } }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            combine(cliente.generacion, cliente.conexion) { _, _ -> }.collect { candado.withLock { revisarConexion() } }
        }
    }

    /** Guarda el intento `EN_CURSO` y DESPUÉS manda el `paycash`. */
    suspend fun cobrar(venta: VentaParaLaMaquina): ResultadoDeInicio = candado.withLock {
        revisarConexion()
        if (activo?.vivo == true) return@withLock ResultadoDeInicio.Ocupado
        val due = venta.saleCents + venta.tipCents
        if (venta.saleCents < 0 || venta.tipCents < 0 || !cobrableEnMaquina(due)) return@withLock ResultadoDeInicio.NoCobrable
        val ahora = reloj()
        // Codex código r1 #14: justo antes del `paycash` (con el candado): una cotización vencida no empieza el cobro.
        if (venta.vigenteHasta != null && ahora > venta.vigenteHasta) return@withLock ResultadoDeInicio.CotizacionVencida
        val ref = nuevaReferencia(ahora, aleatorio)
        val fila = CobroEnMaquinaEntity(
            referenceCC = ref, venueId = venta.venueId, llaveDeVenta = venta.llaveDeVenta, pedidoJson = venta.pedidoJson,
            saleCents = venta.saleCents, tipCents = venta.tipCents, dueCents = due, saleShiftId = venta.saleShiftId,
            quoteId = venta.quoteId, creadoEn = ahora, estado = EstadoDelIntento.EN_CURSO, renglonesJson = venta.renglonesJson,
        )
        if (!dao.abrirIntento(fila)) return@withLock ResultadoDeInicio.VentaConIntento
        val generacion = cliente.generacion.value
        if (!cliente.enviar(pedirCobro(due, ref, fecha(ahora)), generacion)) {
            dao.borrarSinEnviar(ref)   // no salió nada: no hay evidencia que guardar
            return@withLock ResultadoDeInicio.SinConexion
        }
        activo = Intento(ref, due).also { it.vigilada = generacion }
        publicar()
        armar(ESPERA_DEL_STATUS_MS, ::aEsperando)   // acuse perdido: nunca se libera por ver la máquina libre (R2#1)
        ResultadoDeInicio.Iniciado(ref)
    }

    /** «Cancelar y devolverme mi dinero»: `cancel "1"`, sólo con la propiedad. */
    fun cancelarPorElCliente() {
        scope.launch {
            candado.withLock {
                val a = activo ?: return@withLock
                if (a.propio && (a.fase == Fase.COBRANDO || a.fase == Fase.SIGUES_AHI)) mandarCancel(a, CANCEL_DEVOLVER)
            }
        }
    }

    /** El cliente tocó «¿Sigues ahí?». */
    fun sigoAqui() {
        scope.launch {
            candado.withLock {
                val a = activo ?: return@withLock
                if (a.fase == Fase.SIGUES_AHI && a.propio) {
                    a.fase = Fase.COBRANDO
                    publicar()
                    armar(ABANDONO_MS, ::preguntarSiSigue)
                }
            }
        }
    }

    /**
     * Al arrancar o reconectar: un `EN_CURSO` que no es el cobro vivo quedó de un proceso que murió y pasa a duda (no se
     * sabe cómo terminó). Devuelve lo que la subida (C4) tiene que mandar: cierres finales sin acuse y dudas sin subir.
     * Nunca resuelve una duda ni le manda nada a la máquina.
     */
    suspend fun recuperar(): List<CobroEnMaquinaEntity> = candado.withLock {
        if (dao.huerfanosAEsperando(salvo = activo?.takeIf { it.vivo }?.ref ?: "") > 0) alTerminar()
        dao.porSubir()
    }

    // MARK: - Lo que llega

    private suspend fun alRecibir(r: Recibido) {
        revisarConexion()
        val a = activo ?: return
        val m = r.mensaje
        if (a.fase == Fase.ESPERANDO) {
            if (m is MensajeDeLaMaquina.Status && m.referenceCC == a.ref) {
                anotar(a, r)
                // Codex código r1 #6: el cierre de NUESTRO folio y `Due` por la conexión de la propiedad llegó tarde: sube como cierre
                // (decide el SERVIDOR; la fila sigue en duda). Cualquier otro mensaje del folio es una observación nueva de la duda.
                val tardio = m.cierre()?.takeIf { m.dueCents == a.due && a.generacionPropia != null && r.generacion == a.generacionPropia }
                    ?.let { desenlaceDelCierre(it, a.cancelPedido, a.due, m.creditCents, m.changeDispensedCents) }
                if (tardio != null) {
                    val hora = horaDeLaMaquina(m.fecha, zona())
                    dao.anotarCierreTardio(a.ref, tardio.name, m.creditCents, m.changeDispensedCents, hora ?: reloj(), hora != null, crudos(a))
                } else {
                    dao.anotarEvidencia(a.ref, crudos(a))
                }
                alTerminar()
            }
            return
        }
        if (a.fase == Fase.CERRADO || r.generacion != a.vigilada) return   // un cierre repetido o un mensaje de otra conexión
        if (m is MensajeDeLaMaquina.Pong || m is MensajeDeLaMaquina.Otro) return
        anotar(a, r)
        when (m) {
            is MensajeDeLaMaquina.Status -> alStatus(a, m)
            is MensajeDeLaMaquina.UpdateCredit -> alCredito(a, m.creditCents)
            else -> Unit   // `sockets` no trae folio: sólo evidencia
        }
    }

    private suspend fun alStatus(a: Intento, m: MensajeDeLaMaquina.Status) {
        if (m.referenceCC != a.ref || m.dueCents != a.due) return aEsperando(a)   // otra operación: la nuestra ya no se sabe
        // Codex código r1 #5: un cierre es nuestro sólo por la conexión en que se ganó la propiedad (`propio` lo dice: se pierde al
        // caerse y no regresa). El de otra conexión deja la duda, con su texto como evidencia.
        m.cierre()?.let { return if (a.propio) cerrar(a, m, it) else aEsperando(a) }
        when (a.fase) {
            Fase.INICIANDO -> {   // el acuse: la máquina aceptó nuestro folio y Due en esta conexión
                a.propio = true
                a.generacionPropia = a.vigilada
                dao.ganarPropiedad(a.ref, a.vigilada!!, crudos(a))
                a.fase = Fase.COBRANDO
                armar(ABANDONO_MS, ::preguntarSiSigue)
            }
            Fase.RECONECTANDO -> {   // nuestro folio sigue vivo, pero no se adopta: sin abandono ni cancel
                a.fase = Fase.COBRANDO
                a.sinEnlaceDesde = null
                cancelarTemporizador()
            }
            Fase.CANCELANDO -> armar(ESPERA_DEL_CIERRE_MS, ::aEsperando)   // sigue trabajando (p. ej. entregando cambio)
            else -> Unit
        }
        alCredito(a, m.creditCents)
        publicar()
    }

    private suspend fun alCredito(a: Intento, creditCents: Int) {
        if (creditCents <= a.credit) return
        a.credit = creditCents
        if (a.propio) {
            dao.anotarCredito(a.ref, creditCents, reloj(), crudos(a))
            if (a.fase == Fase.COBRANDO || a.fase == Fase.SIGUES_AHI) {   // dinero nuevo: el cliente sigue ahí
                a.fase = Fase.COBRANDO
                armar(ABANDONO_MS, ::preguntarSiSigue)
            }
        }
        publicar()
    }

    private suspend fun cerrar(a: Intento, m: MensajeDeLaMaquina.Status, cierre: Cierre) {
        val desenlace = desenlaceDelCierre(cierre, a.cancelPedido, a.due, m.creditCents, m.changeDispensedCents)
            ?: return aEsperando(a)   // contradictorio: el crudo ya va en la evidencia
        // El desenlace y «por subir» en UN `UPDATE`: la fila es la cola (R4-1). Codex código r1 #15: la hora es la que dice la máquina;
        // sin ella, la de la PC sólo para enseñar y el cierre sube sin hora conocida. #13: lo físico que se le debe al cliente.
        val hora = horaDeLaMaquina(m.fecha, zona())
        dao.cerrar(
            a.ref, desenlace.name, m.creditCents, m.changeDispensedCents, hora ?: reloj(), crudos(a), conocido = hora != null,
            aDeber = deudaFisica(desenlace, a.due, m.creditCents, m.changeDispensedCents),
        )
        alTerminar()
        a.fase = Fase.CERRADO
        a.desenlace = desenlace
        a.credit = m.creditCents
        a.change = m.changeDispensedCents
        a.propio = false
        cancelarTemporizador()
        publicar()
    }

    private suspend fun aEsperando(a: Intento) {
        dao.aEsperando(a.ref, crudos(a))
        alTerminar()
        a.fase = Fase.ESPERANDO
        a.propio = false
        cancelarTemporizador()
        publicar()
    }

    // MARK: - Conexión

    /**
     * Lee la conexión del cliente tal como está AHORA (no importa qué flujo avisó primero). Caerse la conexión vigilada
     * quita la propiedad para siempre; al abrir otra, se vigila ésa: si en 3 s no llega un `status` de nuestro folio y
     * `Due`, el intento queda en duda.
     */
    private fun revisarConexion() {
        val a = activo?.takeIf { it.vivo } ?: return
        val generacion = cliente.generacion.value
        val conectada = cliente.conexion.value == Conexion.CONECTADA
        val vigilada = a.vigilada
        if (vigilada != null && (generacion != vigilada || !conectada)) {
            a.vigilada = null
            a.propio = false
            a.fase = Fase.RECONECTANDO
            if (a.sinEnlaceDesde == null) a.sinEnlaceDesde = reloj()
            cancelarTemporizador()
            publicar()
        }
        if (a.vigilada == null && conectada) {
            a.vigilada = generacion
            armar(ESPERA_DEL_STATUS_MS, ::aEsperando)
        } else if (a.vigilada == null && !relojSinEnlace) {
            // Codex código r1 #4: sin enlace, la duda no espera a que vuelva. Con un plazo contado desde la primera caída (un enlace
            // que parpadea no lo alarga), el intento queda «Esperando» y la duda sube mientras haya internet.
            val desde = a.sinEnlaceDesde ?: reloj().also { a.sinEnlaceDesde = it }
            armar((desde + ESPERA_SIN_ENLACE_MS - reloj()).coerceAtLeast(0), ::aEsperando)
            relojSinEnlace = true
        }
    }

    // MARK: - Abandono y cancel

    private suspend fun preguntarSiSigue(a: Intento) {
        a.fase = Fase.SIGUES_AHI
        publicar()
        armar(SIGUES_AHI_MS) { mandarCancel(it, CANCEL_GUARDAR) }   // el abandono siempre guarda (R3#4)
    }

    /**
     * El modo se guarda ANTES del comando. Luego, en un solo paso bajo el candado, se revisa que la conexión siga siendo la
     * de la propiedad y se manda por ESA generación (R3#2): si cambió o no sale, el intento queda en duda. Que el último
     * `status` sea nuestro lo garantiza el candado: uno de otra operación ya habría dejado el intento en duda.
     */
    private suspend fun mandarCancel(a: Intento, cancelvalue: String) {
        a.cancelPedido = cancelvalue
        dao.pedirCancel(a.ref, cancelvalue, reloj())
        revisarConexion()
        val propia = a.generacionPropia
        // Codex código r1 #3: la relectura del último `status` y el envío van juntos en el cliente (con lo que llega): si mientras se
        // guardaba el modo llegó otro folio (o el cierre del nuestro), el `cancel` sin folio no sale.
        val salio = a.propio && propia != null && a.vigilada == propia &&
            cliente.enviarSiSigueSiendoNuestro(cancelar(devolver = cancelvalue == CANCEL_DEVOLVER, fecha = fecha(reloj())), propia, a.ref, a.due)
        if (!salio) return aEsperando(a)
        a.fase = Fase.CANCELANDO
        publicar()
        armar(ESPERA_DEL_CIERRE_MS, ::aEsperando)   // un cancel sin cierre después: duda
    }

    // MARK: - Relojes

    /** Un solo reloj a la vez. El que dispara revisa que siga siendo el vigente y la conexión antes de actuar. */
    private fun armar(ms: Long, accion: suspend (Intento) -> Unit) {
        cancelarTemporizador()
        val a = activo ?: return
        val mio = turno
        temporizador = scope.launch {
            delay(ms)
            candado.withLock {
                if (turno != mio) return@withLock
                temporizador = null   // ya disparó: lo que siga no lo cancela a él mismo
                revisarConexion()
                if (turno == mio) accion(a)
            }
        }
    }

    private fun cancelarTemporizador() {
        relojSinEnlace = false
        turno++
        temporizador?.cancel()
        temporizador = null
    }

    // MARK: - Evidencia y pantalla

    private fun anotar(a: Intento, r: Recibido) {
        a.crudos.addLast("g${r.generacion} t${reloj()} ${r.crudo}")
        while (a.crudos.size > 1 && a.crudos.sumOf { it.length + 1 } > MAX_CRUDOS) a.crudos.removeFirst()
    }

    private fun crudos(a: Intento) = a.crudos.joinToString("\n").takeLast(MAX_CRUDOS)

    private fun publicar() {
        val a = activo
        _estado.value = when (a?.fase) {
            null -> EstadoDelCobro.Libre
            Fase.INICIANDO -> EstadoDelCobro.Iniciando(a.ref, a.due)
            Fase.COBRANDO -> EstadoDelCobro.Cobrando(a.ref, a.due, a.credit, a.propio)
            Fase.SIGUES_AHI -> EstadoDelCobro.SiguesAhi(a.ref, a.due, a.credit, (SIGUES_AHI_MS / 1_000).toInt())
            Fase.CANCELANDO -> EstadoDelCobro.Cancelando(a.ref, a.due, a.credit, a.cancelPedido!!)
            Fase.RECONECTANDO -> EstadoDelCobro.Reconectando(a.ref, a.due, a.credit)
            Fase.CERRADO -> EstadoDelCobro.Cerrado(a.ref, a.desenlace!!, a.due, a.credit, a.change)
            Fase.ESPERANDO -> EstadoDelCobro.Esperando(a.ref, a.due)
        }
    }

    private fun fecha(ms: Long) = Instant.ofEpochMilli(ms).toString()

    private companion object {
        const val CANCEL_DEVOLVER = "1"
        const val CANCEL_GUARDAR = "2"
        /** Acuse inicial y espera tras reconectar. */
        const val ESPERA_DEL_STATUS_MS = 3_000L
        const val ABANDONO_MS = 120_000L
        const val SIGUES_AHI_MS = 30_000L
        /** ponytail: plazo propio, no del manual; 30 s cubren entregar cambio. Si la máquina tarda más, ajustar aquí. */
        const val ESPERA_DEL_CIERRE_MS = 30_000L
        /** Codex código r1 #4. ponytail: plazo propio; diez reconexiones de 3 s. Si la red local del negocio tarda más, ajustar aquí. */
        const val ESPERA_SIN_ENLACE_MS = 30_000L
        const val MAX_CRUDOS = 8_192
    }
}
