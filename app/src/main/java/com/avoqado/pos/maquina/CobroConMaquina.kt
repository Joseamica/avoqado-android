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
    /**
     * Codex código r1 #14 y r2 H11: hasta cuándo vale la cotización para EMPEZAR el cobro, en el reloj MONÓTONO de la PC (nunca la
     * hora de Windows, que alguien puede mover). null = sin cotización.
     */
    val vigenteHasta: Long? = null,
    /** Rediseño del SERVIDOR (r6-6): el emparejamiento vigente al empezar; queda congelado en el intento. */
    val bindingId: String? = null,
    /** Codex código r3 #6: la comanda de cocina congelada; sale de la fila con el PAGADO. */
    val comandaJson: String? = null,
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
    /** Codex código r3 #5: la caja salió de este cobro (Esc, otra venta) antes del `paycash`: no salió nada y no quedó fila. */
    data object Abandonado : ResultadoDeInicio
}

/**
 * Codex código r3 #5: LA puerta de una apertura de «Cobrar»: ¿todavía puede encender la máquina? [siSigueAbierta] corre `enviar` (el
 * `paycash`) BAJO el mismo candado con que la caja cierra la apertura (Esc, salir, otra venta): o sale antes de cerrarla, o no sale.
 */
fun interface PuertaDelCobro {
    /** El resultado de [enviar], o null si la apertura ya se cerró (no se llamó). */
    fun siSigueAbierta(enviar: () -> Boolean): Boolean?

    companion object {
        /** Sin pantalla que cerrar (pruebas, llamadas internas). */
        val SIEMPRE = PuertaDelCobro { it() }
    }
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

/** La fase de un intento vivo de ESTA PC (lo que el intento en memoria sabe y el disco no). */
internal enum class FaseDelIntento { INICIANDO, COBRANDO, SIGUES_AHI, CANCELANDO, RECONECTANDO, CERRADO, ESPERANDO }

/** Lo que un mensaje de la máquina le hace al intento vivo ([decidir]). La evidencia ya está en el diario de su folio. */
internal enum class Paso {
    /** No decide nada (otra conexión, `pong`, una máquina libre, o el intento ya no está vivo): sólo queda en el diario. */
    NADA,
    /** De otra operación (otro folio, otro `Due`) o un cierre sin la propiedad: la nuestra ya no se sabe → «Esperando». */
    DUDA,
    /** El acuse: nuestro folio y `Due`, cobrando, en la conexión donde salió el `paycash`. */
    GANAR,
    /** El cierre de nuestro folio y `Due` en la conexión de la propiedad. */
    CERRAR,
    /** Nuestro folio sigue vivo (o entró dinero de nuestro `Due`): se enseña y reinicia los plazos; nunca adopta ni cierra. */
    NOTICIA,
}

/**
 * Codex código r2, bloque A: LA regla de qué hace un mensaje con el intento vivo, pura (la prueba la matriz de `CobroConMaquinaTest`).
 * - Sólo decide un mensaje de la conexión vigilada ([vigilada]) mientras el intento está vivo. Lo demás es evidencia.
 * - Otra operación —un `status` de otro folio o `Due`, un `updateCredit` o un `sockets` cobrando con otro `Due`— deja la duda (H14).
 * - Un cierre sólo es nuestro en la conexión de la propiedad ([propia]); sin ella, es duda (H2: nunca un desenlace).
 */
internal fun decidir(
    m: MensajeDeLaMaquina,
    generacion: Int,
    referenceCC: String,
    dueCents: Int,
    fase: FaseDelIntento?,
    vigilada: Int?,
    propia: Int?,
): Paso {
    if (fase == null || fase == FaseDelIntento.CERRADO || fase == FaseDelIntento.ESPERANDO || generacion != vigilada) return Paso.NADA
    return when (m) {
        is MensajeDeLaMaquina.Pong, is MensajeDeLaMaquina.Otro -> Paso.NADA
        is MensajeDeLaMaquina.Sockets -> if (m.cobrando && m.dueCents != dueCents) Paso.DUDA else Paso.NADA
        // Codex código r3 #4: un `updateCredit` no trae folio. Es de nuestro intento sólo en la conexión de la propiedad (donde un `status`
        // nuestro lo identificó); antes, o tras reconectar, es dinero de nadie: no se le atribuye ni alarga sus plazos.
        is MensajeDeLaMaquina.UpdateCredit -> when {
            m.dueCents != null && m.dueCents != dueCents -> Paso.DUDA
            propia == generacion -> Paso.NOTICIA
            else -> Paso.NADA
        }
        is MensajeDeLaMaquina.Status -> when {
            m.referenceCC != referenceCC || m.dueCents != dueCents -> Paso.DUDA
            m.cierre() != null -> if (propia == generacion) Paso.CERRAR else Paso.DUDA
            fase == FaseDelIntento.INICIANDO -> Paso.GANAR
            else -> Paso.NOTICIA
        }
    }
}

/**
 * El cobro de una venta en la QuickPay (fase 1a, C3). Cada paso se guarda en `cobros_en_maquina` ANTES de tocar la red, y
 * todo (mensajes, cambios de conexión, relojes y botones) corre bajo un solo candado, en orden.
 *
 * - **El diario (Codex código r2, bloque A):** cada mensaje se anota PRIMERO en el diario de SU folio ([CobroEnMaquinaDao.anotarMensaje]):
 *   un `status` por su `referenceCC`, un `updateCredit` sólo si es del `Due` del intento vivo. Así un intento nuevo nunca se queda con
 *   los mensajes de uno viejo (H4), y lo que llega de una duda es evidencia de ESA fila, también tras reiniciar.
 * - **Propiedad:** se gana sólo con un `status` de nuestro folio y `Due` en la conexión donde salió el `paycash`
 *   ([CobroEnMaquinaEntity.generacionPropia]). Se pierde al caerse esa conexión o al ver otra operación, y no regresa.
 *   Sin ella no corre el abandono, no sale ningún `cancel` y el dinero sólo se enseña. Lo decide [decidir].
 * - **Lo que nunca decide:** `sockets`, `updateCredit`, `pong`, el silencio, una máquina libre y los mensajes de otra
 *   conexión. Un `status` de otra operación (otro folio, `DOTACION`, otro `Due`) deja el intento en duda.
 * - **La duda (`ESPERANDO`)** no la resuelve la PC: lo que la máquina diga después de ese folio, cierre incluido, es evidencia (H2).
 * - **Sin noticias (H3):** un intento vivo sin la propiedad que pasa 30 s sin un mensaje de su folio (reloj monótono, contado desde
 *   el ÚLTIMO) queda «Esperando», aunque el enlace vaya y venga.
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
    /** Codex código r2 H3 y H11: el reloj monótono (ms) de los plazos; la hora de Windows puede moverse. */
    private val monotonico: () -> Long = { System.nanoTime() / 1_000_000 },
    /** Codex código r3 #6, #11: la máquina cerró en vivo y ya está en disco (la comanda y el papel salen de la fila). */
    private val alCerrar: (String, Desenlace) -> Unit = { _, _ -> },
) {
    private val _estado = MutableStateFlow<EstadoDelCobro>(EstadoDelCobro.Libre)
    val estado: StateFlow<EstadoDelCobro> = _estado.asStateFlow()

    private class Intento(val ref: String, val due: Int, var ultimaNoticia: Long) {
        var fase = FaseDelIntento.INICIANDO
        /** La conexión cuyos mensajes cuentan; null mientras se reconecta. */
        var vigilada: Int? = null
        /** La conexión donde se ganó la propiedad. */
        var generacionPropia: Int? = null
        var credit = 0
        var change = 0
        var cancelPedido: String? = null
        var desenlace: Desenlace? = null
        val vivo get() = fase != FaseDelIntento.CERRADO && fase != FaseDelIntento.ESPERANDO
        /** El cobro es nuestro sólo en la conexión de la propiedad y mientras sigue vivo. */
        val propio get() = vivo && generacionPropia != null && generacionPropia == vigilada
    }

    // Todo lo de abajo cambia sólo con el candado.
    private val candado = Mutex()
    private var activo: Intento? = null
    private var temporizador: Job? = null
    private var turno = 0L

    init {
        // UNDISPATCHED: la suscripción queda hecha al construir (`recibidos` no repite lo pasado).
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            cliente.recibidos.collect { r -> candado.withLock { alRecibir(r) } }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            combine(cliente.generacion, cliente.conexion) { _, _ -> }.collect { candado.withLock { revisarConexion() } }
        }
    }

    /**
     * Guarda el intento `EN_CURSO` y DESPUÉS manda el `paycash`, sólo si [puerta] sigue abierta en ese instante (Codex código r3 #5: la
     * comprobación y el envío van juntos, bajo el candado que la cierra).
     */
    suspend fun cobrar(venta: VentaParaLaMaquina, puerta: PuertaDelCobro = PuertaDelCobro.SIEMPRE): ResultadoDeInicio = candado.withLock {
        revisarConexion()
        if (activo?.vivo == true) return@withLock ResultadoDeInicio.Ocupado
        val due = venta.saleCents + venta.tipCents
        if (venta.saleCents < 0 || venta.tipCents < 0 || !cobrableEnMaquina(due)) return@withLock ResultadoDeInicio.NoCobrable
        val ahora = reloj()
        if (venta.vencida()) return@withLock ResultadoDeInicio.CotizacionVencida
        val ref = nuevaReferencia(ahora, aleatorio)
        val fila = CobroEnMaquinaEntity(
            referenceCC = ref, venueId = venta.venueId, llaveDeVenta = venta.llaveDeVenta, pedidoJson = venta.pedidoJson,
            saleCents = venta.saleCents, tipCents = venta.tipCents, dueCents = due, saleShiftId = venta.saleShiftId,
            quoteId = venta.quoteId, creadoEn = ahora, estado = EstadoDelIntento.EN_CURSO, renglonesJson = venta.renglonesJson,
            bindingId = venta.bindingId, comandaJson = venta.comandaJson,
        )
        if (!dao.abrirIntento(fila)) return@withLock ResultadoDeInicio.VentaConIntento
        // Codex código r2 H11: la vigencia se vuelve a ver DESPUÉS de escribir en disco, justo antes del `paycash`.
        if (venta.vencida()) {
            dao.borrarSinEnviar(ref)
            return@withLock ResultadoDeInicio.CotizacionVencida
        }
        val generacion = cliente.generacion.value
        when (puerta.siSigueAbierta { cliente.enviar(pedirCobro(due, ref, fecha(ahora)), generacion) }) {
            true -> Unit
            // No salió nada: no hay evidencia que guardar.
            false -> return@withLock ResultadoDeInicio.SinConexion.also { dao.borrarSinEnviar(ref) }
            null -> return@withLock ResultadoDeInicio.Abandonado.also { dao.borrarSinEnviar(ref) }
        }
        activo = Intento(ref, due, monotonico()).also { it.vigilada = generacion }
        publicar()
        armar(ESPERA_DEL_STATUS_MS, ::aEsperando)   // acuse perdido: nunca se libera por ver la máquina libre (R2#1)
        ResultadoDeInicio.Iniciado(ref)
    }

    private fun VentaParaLaMaquina.vencida() = vigenteHasta != null && monotonico() > vigenteHasta

    /** «Cancelar y devolverme mi dinero»: `cancel "1"`, sólo con la propiedad. */
    fun cancelarPorElCliente() {
        scope.launch {
            candado.withLock {
                val a = activo ?: return@withLock
                if (a.propio && (a.fase == FaseDelIntento.COBRANDO || a.fase == FaseDelIntento.SIGUES_AHI)) mandarCancel(a, CANCEL_DEVOLVER)
            }
        }
    }

    /** El cliente tocó «¿Sigues ahí?». */
    fun sigoAqui() {
        scope.launch {
            candado.withLock {
                val a = activo ?: return@withLock
                if (a.fase == FaseDelIntento.SIGUES_AHI && a.propio) {
                    a.fase = FaseDelIntento.COBRANDO
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
        val m = r.mensaje
        val a = activo?.takeIf { it.vivo }
        val paso = a?.let { decidir(m, r.generacion, it.ref, it.due, it.fase, it.vigilada, it.generacionPropia) } ?: Paso.NADA
        // 1. Al diario de SU folio, en disco, antes de decidir (bloque A). Un `updateCredit` no trae folio: es del intento vivo sólo si
        //    [decidir] lo reconoce como suyo (su `Due`, su conexión).
        when {
            m is MensajeDeLaMaquina.Status && m.referenceCC != null -> {
                val nueva = dao.anotarMensaje(m.referenceCC, r.generacion, r.crudo, m.creditCents, m.dueCents)
                if (nueva && m.referenceCC != a?.ref) alTerminar()   // una duda con evidencia nueva vuelve a subir
            }
            m is MensajeDeLaMaquina.UpdateCredit && a != null && paso == Paso.NOTICIA ->
                dao.anotarMensaje(a.ref, r.generacion, r.crudo, m.creditCents, a.due)
        }
        // La señal de otra operación que deja la duda también es evidencia de ESA duda (sin atribuirle su dinero).
        if (a != null && paso == Paso.DUDA) dao.anotarMensaje(a.ref, r.generacion, r.crudo, null, null)
        // 2. Lo que decide para el intento vivo.
        if (a == null) return
        when (paso) {
            Paso.NADA -> Unit
            Paso.DUDA -> aEsperando(a)
            Paso.GANAR -> ganar(a, (m as MensajeDeLaMaquina.Status).creditCents)
            Paso.CERRAR -> (m as MensajeDeLaMaquina.Status).let { cerrar(a, it, it.cierre()!!) }
            Paso.NOTICIA -> noticia(a, m)
        }
    }

    private suspend fun ganar(a: Intento, creditCents: Int) {
        a.generacionPropia = a.vigilada
        dao.ganarPropiedad(a.ref, a.vigilada!!)
        a.fase = FaseDelIntento.COBRANDO
        a.ultimaNoticia = monotonico()
        a.credit = maxOf(a.credit, creditCents)
        armar(ABANDONO_MS, ::preguntarSiSigue)
        publicar()
    }

    /** Nuestro folio sigue vivo o entró dinero de nuestro `Due`. Sin la propiedad, sólo se enseña y alarga el plazo sin noticias (H3). */
    private fun noticia(a: Intento, m: MensajeDeLaMaquina) {
        a.ultimaNoticia = monotonico()
        val credito = (m as? MensajeDeLaMaquina.Status)?.creditCents ?: (m as MensajeDeLaMaquina.UpdateCredit).creditCents
        val dineroNuevo = credito > a.credit
        if (dineroNuevo) a.credit = credito
        when {
            // Nuestro folio sigue vivo en la conexión nueva, pero no se adopta: sin abandono ni `cancel`.
            a.fase == FaseDelIntento.RECONECTANDO -> {
                a.fase = FaseDelIntento.COBRANDO
                vigia(a)
            }
            a.fase == FaseDelIntento.INICIANDO -> Unit   // el acuse sigue con sus 3 s
            a.fase == FaseDelIntento.CANCELANDO -> if (m is MensajeDeLaMaquina.Status) armar(ESPERA_DEL_CIERRE_MS, ::aEsperando)
            !a.propio -> vigia(a)
            dineroNuevo -> {   // dinero nuevo: el cliente sigue ahí
                a.fase = FaseDelIntento.COBRANDO
                armar(ABANDONO_MS, ::preguntarSiSigue)
            }
        }
        publicar()
    }

    private suspend fun cerrar(a: Intento, m: MensajeDeLaMaquina.Status, cierre: Cierre) {
        val desenlace = desenlaceDelCierre(cierre, a.cancelPedido, a.due, m.creditCents, m.changeDispensedCents)
            ?: return aEsperando(a)   // contradictorio: el crudo ya está en el diario
        // El desenlace y «por subir» en UN `UPDATE`: la fila es la cola (R4-1). Codex código r1 #15: la hora es la que dice la máquina;
        // sin ella, la de la PC sólo para enseñar y el cierre sube sin hora conocida. #13: lo físico que se le debe al cliente.
        val hora = horaDeLaMaquina(m.fecha, zona())
        dao.cerrar(
            a.ref, desenlace.name, m.creditCents, m.changeDispensedCents, hora ?: reloj(), conocido = hora != null,
            aDeber = deudaFisica(desenlace, a.due, m.creditCents, m.changeDispensedCents),
        )
        alTerminar()
        alCerrar(a.ref, desenlace)
        a.fase = FaseDelIntento.CERRADO
        a.desenlace = desenlace
        a.credit = m.creditCents
        a.change = m.changeDispensedCents
        cancelarTemporizador()
        publicar()
    }

    private suspend fun aEsperando(a: Intento) {
        dao.aEsperando(a.ref)
        alTerminar()
        a.fase = FaseDelIntento.ESPERANDO
        cancelarTemporizador()
        publicar()
    }

    // MARK: - Conexión

    /**
     * Lee la conexión del cliente tal como está AHORA (no importa qué flujo avisó primero). Caerse la conexión vigilada quita la
     * propiedad para siempre y arma el plazo sin noticias (30 s desde la ÚLTIMA noticia de nuestro folio: un enlace que parpadea no lo
     * alarga, H3). Al abrir otra, se vigila ésa: si en 3 s (o antes, si el plazo se acaba) no llega un `status` de nuestro folio y
     * `Due`, el intento queda en duda. Sólo arma en las transiciones.
     */
    private fun revisarConexion() {
        val a = activo?.takeIf { it.vivo } ?: return
        val generacion = cliente.generacion.value
        val conectada = cliente.conexion.value == Conexion.CONECTADA
        val vigilada = a.vigilada
        if (vigilada != null && (generacion != vigilada || !conectada)) {
            a.vigilada = null
            a.fase = FaseDelIntento.RECONECTANDO
            vigia(a)
            publicar()
        }
        if (a.vigilada == null && conectada) {
            a.vigilada = generacion
            armar(minOf(ESPERA_DEL_STATUS_MS, restante(a)), ::aEsperando)
        }
    }

    private fun restante(a: Intento) = (a.ultimaNoticia + ESPERA_SIN_NOTICIAS_MS - monotonico()).coerceAtLeast(0)

    /** Codex código r2 H3: sin la propiedad, 30 s desde la última noticia de nuestro folio → «Esperando». */
    private fun vigia(a: Intento) = armar(restante(a), ::aEsperando)

    // MARK: - Abandono y cancel

    private suspend fun preguntarSiSigue(a: Intento) {
        a.fase = FaseDelIntento.SIGUES_AHI
        publicar()
        armar(SIGUES_AHI_MS) { mandarCancel(it, CANCEL_GUARDAR) }   // el abandono siempre guarda (R3#4)
    }

    /**
     * El modo se guarda ANTES del comando. Luego, en un solo paso bajo el candado, se revisa que la conexión siga siendo la
     * de la propiedad y se manda por ESA generación (R3#2): si cambió o no sale, el intento queda en duda, y lo que diga la máquina
     * después es sólo evidencia (Codex código r2 H2: un `cancel` que no salió nunca clasifica un cierre).
     */
    private suspend fun mandarCancel(a: Intento, cancelvalue: String) {
        dao.pedirCancel(a.ref, cancelvalue, reloj())
        revisarConexion()
        val propia = a.generacionPropia
        // Codex código r1 #3 y r2 H14: la relectura de lo último que dijo la máquina y el envío van juntos en el cliente: si mientras se
        // guardaba el modo llegó otro folio, otro `Due` o el cierre del nuestro, el `cancel` sin folio no sale.
        val salio = a.propio && propia != null &&
            cliente.enviarSiSigueSiendoNuestro(cancelar(devolver = cancelvalue == CANCEL_DEVOLVER, fecha = fecha(reloj())), propia, a.ref, a.due)
        if (!salio) return aEsperando(a)
        a.cancelPedido = cancelvalue
        a.fase = FaseDelIntento.CANCELANDO
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
        turno++
        temporizador?.cancel()
        temporizador = null
    }

    // MARK: - Pantalla

    private fun publicar() {
        val a = activo
        _estado.value = when (a?.fase) {
            null -> EstadoDelCobro.Libre
            FaseDelIntento.INICIANDO -> EstadoDelCobro.Iniciando(a.ref, a.due)
            FaseDelIntento.COBRANDO -> EstadoDelCobro.Cobrando(a.ref, a.due, a.credit, a.propio)
            FaseDelIntento.SIGUES_AHI -> EstadoDelCobro.SiguesAhi(a.ref, a.due, a.credit, (SIGUES_AHI_MS / 1_000).toInt())
            FaseDelIntento.CANCELANDO -> EstadoDelCobro.Cancelando(a.ref, a.due, a.credit, a.cancelPedido!!)
            FaseDelIntento.RECONECTANDO -> EstadoDelCobro.Reconectando(a.ref, a.due, a.credit)
            FaseDelIntento.CERRADO -> EstadoDelCobro.Cerrado(a.ref, a.desenlace!!, a.due, a.credit, a.change)
            FaseDelIntento.ESPERANDO -> EstadoDelCobro.Esperando(a.ref, a.due)
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
        /** Codex código r1 #4 y r2 H3. ponytail: plazo propio; diez reconexiones de 3 s. Si la red local del negocio tarda más, ajustar aquí. */
        const val ESPERA_SIN_NOTICIAS_MS = 30_000L
    }
}
