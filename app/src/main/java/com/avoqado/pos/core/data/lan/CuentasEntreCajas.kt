package com.avoqado.pos.core.data.lan

import android.os.SystemClock
import android.util.Log
import com.avoqado.pos.core.data.local.PayloadCache
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * D12 · Opción D — una caja frente a las DEMÁS por las cuentas abiertas de Mostrador: pide permiso a la que la abrió, la contesta cuando
 * es ella ([DuenoDeCuentas] por [CuentasPorLaRed]) y le avisa «cobrada» o «suelta» hasta su acuse (H4). Vive con el hub Premium
 * (`LanHubService`); sin él no pregunta nada. Mesas no pasa por aquí.
 *
 * - **Quién la abrió** (regla 2): se pregunta en paralelo a las cajas con `d=1` de la sucursal; contesta sólo la dueña, y su id y nombre
 *   se guardan en disco ([DuenoConocido]) para nombrarla aunque ya no se vea.
 * - **Apartado** (30 s): se renueva cada 10 s mientras la cajera teclea; lo que la dueña ya no confirme se suelta.
 * - **Avisos durables:** al conceder COMPROMETER se guarda uno ANTES de reservar el efectivo. Mientras el intento vive en este proceso no
 *   se manda nada; después, su fila `pending_payments` decide: existe → «cobrada» (con `subida` = SYNCED) hasta el acuse de la dueña,
 *   que se repite cuando `subida` cambia; no existe → «suelta». Una fila que se borra después de verse no cambia el aviso.
 */
@Singleton
class CuentasEntreCajas internal constructor(
    private val red: LanDiscoveryPort,
    private val dueno: DuenoDeCuentas,
    private val cache: PayloadCache,
    /** El `syncStatus` de la fila `pending_payments` de esa llave, o null si no existe. */
    private val estadoDeLaFila: suspend (llave: String) -> String?,
) {
    @Inject constructor(
        transporte: TransporteLan,
        dueno: DuenoDeCuentas,
        cache: PayloadCache,
        pendientes: com.avoqado.pos.core.data.local.database.PendingPaymentDao,
        monitor: com.avoqado.pos.core.util.ConnectivityMonitor,
    ) : this(transporte, dueno, cache, { pendientes.syncStatusOf(it) }) {
        servidorContesta = { monitor.elServidorContestaEn(PLAZO_DE_LA_SALUD_MS) }
    }

    internal var client = LeaseClient()

    /** Reloj monotónico de ESTA caja (R1: la autorización de la dueña vale [RegistroDeCuentas.AUTORIZACION_MS] desde el envío). */
    internal var reloj: () -> Long = { SystemClock.elapsedRealtime() }
    internal var horaDePared: () -> Long = System::currentTimeMillis

    /**
     * Familia C (Codex r4 #2): ¿el SERVIDOR contesta AHORA, con su base? La salud profunda con plazo TOTAL ([PLAZO_DE_LA_SALUD_MS]); «no» al
     * instante si ya se sabe sin red. Un 5xx no cuenta: su POST caería a la cola. La pregunta [puerta] sólo cuando cambia la respuesta. En
     * producción la pone el constructor de Hilt; el de pruebas la deja en «sí» (las pruebas eligen con `enLinea`).
     */
    internal var servidorContesta: suspend () -> Boolean = { true }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var trabajos: Job? = null
    @Volatile private var venueActivo: String? = null

    /** Apartados vivos de esta pantalla (también el local de la dueña): orderId → a quién y con qué intento. */
    private val apartados = ConcurrentHashMap<String, Apartado>()

    /** Intentos en curso en este proceso (entre comprometer y el fin del flujo): sus avisos esperan. */
    private val vivos = ConcurrentHashMap.newKeySet<String>()

    /** [desde]: reloj monotónico de esta caja al conceder (la red de seguridad de [APARTADO_SIN_COMPROMETER_MS]). */
    private data class Apartado(val venueId: String, val dueno: String, val intento: String, val quien: String, val enLinea: Boolean, val desde: Long)

    /** Con el hub Premium encendido en esta caja. */
    val encendida: Boolean get() = venueActivo != null

    fun encender(venueId: String) {
        if (venueActivo == venueId && trabajos?.isActive == true) return
        apagar()
        venueActivo = venueId
        red.conectarCuentas({ linea -> CuentasPorLaRed.contestar(linea, venueActivo, dueno) }, dueno.nombreCaja)
        trabajos = scope.launch {
            while (isActive) {
                delay(RegistroDeCuentas.RENOVAR_MS)
                runCatching { unaVuelta() }.onFailure { Log.w(TAG, "⚠️ vuelta de cuentas: ${it.message}") }
            }
        }
    }

    fun apagar() {
        trabajos?.cancel()
        trabajos = null
        red.desconectarCuentas()
        apartados.clear()
        venueActivo = null
    }

    /** Las otras cajas de MI sucursal que saben de D (R5). */
    fun cajasConD(): List<LanPeer> = red.peers.value.filter { it.deviceId != dueno.deviceId && it.participaEnD }

    fun hayOtraCajaConD(): Boolean = encendida && cajasConD().isNotEmpty()

    /**
     * [respuesta] null = nadie contestó por ella. [deviceId] y [caja] = la dueña si se sabe (contestó o se guardó antes). [enviadoEn] = reloj
     * monotónico de esta caja al ENVIAR (la latencia no alarga la autorización).
     */
    data class DelDueno(
        val respuesta: Respuesta?, val deviceId: String?, val caja: String?, val enviadoEn: Long,
        /** Revisión final I-1: alguna caja preguntada se quedó callada (sin respuesta o con error; «no es mía» no cuenta): quizá era la dueña. */
        val algunaCallo: Boolean = false,
    )

    /** El nombre de la caja que abrió [orderId], si alguna vez contestó. */
    suspend fun cajaQueLaAbrio(venueId: String, orderId: String): String? = leer(venueId).duenos[orderId]?.caja

    // MARK: - Apartar (al tocar «Cobrar»)

    /** La dueña la aparta en local; otra caja le pregunta. Concedido ⇒ se renueva cada 10 s hasta comprometer o soltar. */
    suspend fun apartar(venueId: String, orderId: String, intento: String, quien: String, enLinea: Boolean): DelDueno {
        val enviado = reloj()
        dueno.apartar(venueId, orderId, Solicitud(dueno.deviceId, dueno.nombreCaja, quien, intento, enLinea))?.let { r ->
            if (r == Respuesta.Concedida) apartados[orderId] = Apartado(venueId, dueno.deviceId, intento, quien, enLinea, reloj())
            return DelDueno(r, dueno.deviceId, dueno.nombreCaja, enviado)
        }
        val r = preguntar(venueId, orderId, LeaseProtocol.OP_CUENTA_APARTAR, intento, quien, enLinea)
        if (r.respuesta == Respuesta.Concedida && r.deviceId != null) apartados[orderId] = Apartado(venueId, r.deviceId, intento, quien, enLinea, reloj())
        return r
    }

    /** ¿Esta pantalla tiene un apartado vivo de esa cuenta? */
    fun tieneApartado(orderId: String): Boolean = apartados.containsKey(orderId)

    /** Suelta un apartado que no llegó a comprometerse (salir, cancelar). Nunca espera a la red. */
    fun soltarApartado(orderId: String) {
        val a = apartados.remove(orderId) ?: return
        soltar(orderId, a)
    }

    private fun soltar(orderId: String, a: Apartado) {
        scope.launch {
            if (a.dueno == dueno.deviceId) runCatching { dueno.soltar(a.venueId, orderId, a.intento) }
            else peerDe(a.dueno)?.let { p -> client.preguntarCuenta(p, pedido(LeaseProtocol.OP_CUENTA_SOLTAR, orderId, a.intento, a.quien, venueId = a.venueId)) }
        }
    }

    // MARK: - La puerta: justo antes del cajón (o de la tarjeta de la dueña)

    sealed interface Puerta {
        /** Adelante. [esDueno] = esta caja la abrió (su compromiso es local); si no, [autorizadoEn] es el envío confirmado (R1). */
        data class Pasa(val intento: String, val esDueno: Boolean, val autorizadoEn: Long?) : Puerta

        /** No se cobra: [texto] es lo que ve la cajera. */
        data class NoPasa(val texto: String) : Puerta

        /** No es una cuenta que coordinar (no la abrió nadie que conteste, tarjeta ajena, sin hub con internet): como siempre. */
        data object Libre : Puerta
    }

    /**
     * La ÚNICA puerta de un cobro NUEVO sobre una cuenta de Pedidos (R4). La dueña compromete en local y lo deja en disco ANTES de
     * reservar (R2). Otra caja compromete EN VIVO; concedido, guarda su aviso durable ANTES de devolver. [enLinea] = quien llama cobraría
     * con internet y el saldo vivo del servidor (falso con la copia). Que el SERVIDOR conteste AHORA (Codex r3 #1) lo confirma la puerta
     * con [servidorContesta], y sólo cuando cambia la respuesta (familia C): una CERRADA, la dueña que no contesta (regla 8), una caja sin
     * hub, el registro ilegible; nunca en lo que no es de Pedidos, ni con tarjeta (sin red no sale). [esTarjeta]: la tarjeta de otra caja no
     * entra en D. [esCuentaDePedidos]: la cuenta está en la copia como Mostrador (sólo entonces otra caja pregunta).
     */
    suspend fun puerta(
        venueId: String, orderId: String, llave: String, quien: String, enLinea: Boolean, esTarjeta: Boolean, esCuentaDePedidos: Boolean,
        /** R1: al volver a comprometer porque la autorización venció, el MISMO intento (si la dueña lo canceló, ya no pasa). */
        intentoPrevio: String? = null,
    ): Puerta {
        // M4: no se corta a la mitad: lo que la dueña concedió queda con su aviso aunque quien preguntaba ya se haya ido.
        val r = withContext(NonCancellable) { puertaSinCortar(venueId, orderId, llave, quien, enLinea, esTarjeta, esCuentaDePedidos, intentoPrevio) }
        // `withContext(NonCancellable)` no lanza al salir: si quien preguntaba se fue, lo concedido no se usa y su aviso lo suelta.
        if (r is Puerta.Pasa && !currentCoroutineContext().isActive) terminarEnFondo(venueId, orderId, r, quien, cobrado = false, subida = false)
        currentCoroutineContext().ensureActive()
        return r
    }

    private suspend fun puertaSinCortar(
        venueId: String, orderId: String, llave: String, quien: String, enLinea: Boolean, esTarjeta: Boolean, esCuentaDePedidos: Boolean,
        intentoPrevio: String?,
    ): Puerta {
        // La llave del cobro (o el intento que ya se comprometió en esta venta): reemplaza el apartado de ESTA caja (misma caja).
        val intento = intentoPrevio ?: llave
        // Familia C: «en línea» de verdad se pregunta UNA vez y sólo si cambia la respuesta. Primero todo va como sin red (lo más estricto);
        // con la tarjeta (sin red no sale) no se mide, y fuera de Pedidos (la bandeja, Rappi 2b) otra caja nunca la pregunta.
        var salud: Boolean? = null
        suspend fun saludYa(): Boolean = salud ?: servidorContestaSinLanzar().also { salud = it }
        // La dueña (Menor 2 de la re-revisión): una cuenta «abierta aquí» nunca es de la bandeja ni de Rappi 2b (sólo la marcan los caminos
        // que la CREAN en este aparato), así que no necesita estar en la copia para preguntar.
        suspend fun enLineaPropia(): Boolean = enLinea && (esTarjeta || saludYa())
        suspend fun enLineaYa(): Boolean = enLinea && (esTarjeta || (esCuentaDePedidos && saludYa()))
        val propia = try {
            val sinRed = dueno.comprometerPropio(venueId, orderId, intento, quien, enLinea = false)
            // Una CERRADA (o dinero propio que el SERVIDOR ya contestó, I4) sólo se abre con internet de verdad.
            if (sinRed is Respuesta.Cerrada && enLineaPropia()) dueno.comprometerPropio(venueId, orderId, intento, quien, enLinea = true) else sinRed
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: DuenoDeCuentas.RegistroIlegible) {
            // M-3: el registro de ESTA caja no se deja leer (no se escribe encima). Con red VIVA el SERVIDOR valida su propio cobro en vivo,
            // salvo que esta caja tenga dinero de la cuenta que aún no sube (R4). Sin red, no. A otra caja nunca le concede (`pedir` calla).
            Log.e(TAG, "🚨 ${e.message}")
            return when {
                !enLineaPropia() -> Puerta.NoPasa(NO_SE_GUARDO)
                dueno.tieneDineroDe(venueId, orderId) ->
                    Puerta.NoPasa(com.avoqado.pos.payment.domain.TextosDeCuentaSinRed.cobradaEn(dueno.nombreCaja, faltaSubirla = true))
                else -> Puerta.Libre
            }
        } catch (e: Exception) {
            return Puerta.NoPasa(NO_SE_GUARDO)
        }
        if (propia != null) {
            apartados.remove(orderId)
            if (propia == Respuesta.Concedida) return Puerta.Pasa(intento, esDueno = true, autorizadoEn = null)
            // I2: con o sin red, a los 2 min de que otra caja la comprometió sin confirmar, su «no confirmó» (la salida vive en la cuenta).
            val e = if (propia is Respuesta.Ocupada) dueno.estadoDe(venueId, orderId) else null
            if (e != null && dueno.puedeDeclararNoSeCobro(e)) return Puerta.NoPasa(com.avoqado.pos.payment.domain.TextosDeCuentaSinRed.noConfirmo(e.quien, e.caja))
            return Puerta.NoPasa(textoDe(propia, dueno.nombreCaja))
        }
        if (esTarjeta) return Puerta.Libre
        // Codex r3 #1: una pantalla abierta con internet puede preguntar sin red: lo que no es de Pedidos (la bandeja, un pedido web, Rappi 2b)
        // sigue fuera de D, con o sin red. Desde la copia siempre es de Pedidos.
        if (!esCuentaDePedidos) return Puerta.Libre
        if (!encendida) return if (enLineaYa()) Puerta.Libre else Puerta.NoPasa(com.avoqado.pos.payment.domain.TextosDeCuentaSinRed.SE_ABRIO_EN_OTRA_CAJA)
        var r = preguntar(venueId, orderId, LeaseProtocol.OP_CUENTA_COMPROMETER, intento, quien, enLinea = false)
        // Una CERRADA en la dueña sólo se abre con internet de verdad (Ruling duda 2: el saldo VIVO del servidor): se le vuelve a preguntar.
        if (r.respuesta is Respuesta.Cerrada && enLineaYa()) r = preguntar(venueId, orderId, LeaseProtocol.OP_CUENTA_COMPROMETER, intento, quien, enLinea = true)
        apartados.remove(orderId)
        val respuesta = r.respuesta
        if (respuesta == null) {
            // Quizá la dueña sí lo anotó y su respuesta se perdió: «suelta» durable hasta su acuse. I2: aunque no se sepa cuál caja es la
            // dueña (dueña vacía = se le pide a todas las cajas con D; sólo la dueña acusa). I-1: sólo con dueña conocida o si alguna caja
            // se quedó callada; si todas dijeron «no es mía» (o no hubo a quién preguntar), nadie anotó nada.
            if (r.deviceId != null || r.algunaCallo) runCatching { anotarAviso(venueId, orderId, intento, llave, quien, r.deviceId.orEmpty()) }
            return if (enLineaYa()) Puerta.Libre
            else Puerta.NoPasa(r.caja?.let { com.avoqado.pos.payment.domain.TextosDeCuentaSinRed.sePerdioElContacto(it) } ?: com.avoqado.pos.payment.domain.TextosDeCuentaSinRed.SE_ABRIO_EN_OTRA_CAJA)
        }
        if (respuesta != Respuesta.Concedida) return Puerta.NoPasa(textoDe(respuesta, r.caja))
        vivos += intento
        return try {
            anotarAviso(venueId, orderId, intento, llave, quien, requireNotNull(r.deviceId))
            Puerta.Pasa(intento, esDueno = false, autorizadoEn = r.enviadoEn)
        } catch (e: Exception) {
            vivos -= intento
            r.deviceId?.let { soltarAhora(it, venueId, orderId, intento, quien) }
            Puerta.NoPasa(NO_SE_GUARDO)
        }
    }

    /** Familia C: la salud nunca truena la puerta; si falla, cuenta como sin red (falla cerrado). */
    private suspend fun servidorContestaSinLanzar(): Boolean = try {
        servidorContesta()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    /** R1: ¿la autorización de otra caja sigue valiendo para guardar el efectivo? (la dueña: siempre). */
    fun autorizacionVigente(p: Puerta.Pasa): Boolean = p.autorizadoEn == null || reloj() - p.autorizadoEn < RegistroDeCuentas.AUTORIZACION_MS

    /**
     * El cobro de esta caja sobre ESE intento terminó (con o sin dinero). La dueña cierra o reconcilia en local; otra caja suelta el
     * intento a su aviso, que decide por la fila si dice «cobrada» o «suelta». Nunca lanza.
     */
    suspend fun terminar(
        venueId: String, orderId: String, p: Puerta.Pasa, quien: String, cobrado: Boolean, subida: Boolean,
        /** Revisión final M-1: el cobro dejó saldo (una parte, un saldo de regalo que no alcanzó): la dueña la deja «con abono». */
        conAbono: Boolean = false,
    ) {
        try {
            when {
                p.esDueno && cobrado -> dueno.cerrarPropio(venueId, orderId, p.intento, quien, subida, conAbono)
                p.esDueno -> dueno.terminarPropio(venueId, orderId, p.intento)
                else -> {
                    // El flujo SABE que cobró: el aviso queda «cobrada» aunque su fila se limpie antes de la siguiente vuelta (H4). W2: y sabe si
                    // quedó saldo; ese dato viaja con el «cobrada» (un aviso que se rearma desde su fila no lo sabe: null, abono en la dueña).
                    if (cobrado) runCatching {
                        cambiar(venueId) { e ->
                            e.copy(avisos = e.avisos.map {
                                if (it.intento == p.intento) it.copy(cobradaVista = true, subida = it.subida || subida, conAbono = conAbono) else it
                            })
                        }
                    }.onFailure { Log.w(TAG, "⚠️ no se pudo marcar cobrado el aviso ${p.intento}; decide su fila: ${it.message}") }
                    vivos -= p.intento
                    scope.launch { runCatching { unaVuelta() } }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ no se pudo cerrar el intento ${p.intento}: ${e.message}")
        }
    }

    /**
     * C1: «Reintentar» con otra llave, con el efectivo de esta venta ya apartado (la puerta no se vuelve a preguntar). La dueña pasa su
     * compromiso a la llave nueva; otra caja, la llave de su aviso. Va ANTES de guardar el efectivo nuevo. Nunca lanza.
     */
    suspend fun cambiarLlave(venueId: String, orderId: String, p: Puerta.Pasa, nuevaLlave: String): Puerta.Pasa = withContext(NonCancellable) {
        if (p.esDueno) {
            if (dueno.renombrarIntentoPropio(venueId, orderId, p.intento, nuevaLlave)) p.copy(intento = nuevaLlave) else p
        } else {
            runCatching { cambiar(venueId) { e -> e.copy(avisos = e.avisos.map { if (it.intento == p.intento) it.copy(llave = nuevaLlave) else it }) } }
                .onFailure { Log.w(TAG, "⚠️ no se pudo pasar el aviso ${p.intento} a la llave nueva: ${it.message}") }
            p
        }
    }

    /** [terminar] sin esperar (desde un ViewModel, también al destruirse). */
    fun terminarEnFondo(venueId: String, orderId: String, p: Puerta.Pasa, quien: String, cobrado: Boolean, subida: Boolean, conAbono: Boolean = false) {
        scope.launch(NonCancellable) { terminar(venueId, orderId, p, quien, cobrado, subida, conAbono) }
    }

    // MARK: - La red

    private fun peerDe(deviceId: String): LanPeer? = cajasConD().firstOrNull { it.deviceId == deviceId }

    /** Ronda 1 de la tubería (Menor 2): con la sucursal de la cuenta; una caja que hoy está en otra contesta error, nunca «no es mía». */
    private fun pedido(
        op: String, orderId: String, intento: String, quien: String, enLinea: Boolean = false, subida: Boolean = false, venueId: String,
        conAbono: Boolean? = null,
    ) = CuentasPorLaRed.pedido(op, orderId, dueno.deviceId, dueno.nombreCaja, quien, intento, enLinea, subida, venueId, conAbono)

    /**
     * Le pregunta a la dueña conocida, o a todas las cajas con D en paralelo (≤ 5 s cada una). Gana la primera que contesta por la cuenta;
     * las demás terminan solas en el fondo. La dueña que contesta queda guardada.
     */
    private suspend fun preguntar(venueId: String, orderId: String, op: String, intento: String, quien: String, enLinea: Boolean): DelDueno {
        val enviado = reloj()
        val conocida = leer(venueId).duenos[orderId]
        val candidatos = cajasConD().filter { conocida == null || it.deviceId == conocida.deviceId }
        if (!encendida || candidatos.isEmpty()) return DelDueno(null, conocida?.deviceId, conocida?.caja, enviado)
        val primera = CompletableDeferred<Pair<LanPeer, Respuesta>?>()
        val req = pedido(op, orderId, intento, quien, enLinea, venueId = venueId)
        val callo = java.util.concurrent.atomic.AtomicBoolean(false)
        val todas = candidatos.map { p ->
            scope.launch {
                val cable = client.preguntarCuenta(p, req)
                val r = CuentasPorLaRed.desdeCable(cable)
                if (r != null) primera.complete(p to r) else if (cable?.status != LeaseProtocol.STATUS_NO_ES_MIA) callo.set(true)
            }
        }
        scope.launch { todas.joinAll(); primera.complete(null) }
        val (peer, r) = primera.await() ?: return DelDueno(null, conocida?.deviceId, conocida?.caja, enviado, algunaCallo = callo.get())
        val caja = peer.nombreCaja ?: conocida?.caja ?: DuenoDeCuentas.nombreDeCaja(null)
        if (conocida?.deviceId != peer.deviceId || conocida.caja != caja) {
            runCatching { cambiar(venueId) { it.copy(duenos = it.duenos + (orderId to DuenoConocido(peer.deviceId, caja, horaDePared()))) } }
        }
        return DelDueno(r, peer.deviceId, caja, enviado)
    }

    private fun soltarAhora(duena: String, venueId: String, orderId: String, intento: String, quien: String) {
        scope.launch { peerDe(duena)?.let { client.preguntarCuenta(it, pedido(LeaseProtocol.OP_CUENTA_SOLTAR, orderId, intento, quien, venueId = venueId)) } }
    }

    /** M2: una vuelta a la vez (el lazo de 10 s y el empujón de [terminar] no se pisan). */
    private val vuelta = Mutex()

    /** Una vuelta del lazo: renovar apartados y mandar los avisos pendientes. */
    internal suspend fun unaVuelta() = vuelta.withLock { unaVueltaSola() }

    private suspend fun unaVueltaSola() {
        for ((orden, a) in apartados.toMap()) {
            // Ninguna renovación huérfana: lo que nadie comprometió en 2 min ya no se renueva y se suelta (la puerta, si llega, compromete
            // desde LIBRE). Las salidas normales ya lo sueltan; esto cubre la que no avisó.
            if (reloj() - a.desde >= APARTADO_SIN_COMPROMETER_MS) {
                if (apartados.remove(orden, a)) soltar(orden, a)
                continue
            }
            val r = if (a.dueno == dueno.deviceId) dueno.apartar(a.venueId, orden, Solicitud(a.dueno, dueno.nombreCaja, a.quien, a.intento, a.enLinea))
            else peerDe(a.dueno)?.let { CuentasPorLaRed.desdeCable(client.preguntarCuenta(it, pedido(LeaseProtocol.OP_CUENTA_APARTAR, orden, a.intento, a.quien, a.enLinea, venueId = a.venueId))) }
            // Sin respuesta se conserva (la dueña lo vence sola a los 30 s); una negativa explícita lo suelta.
            if (r != null && r != Respuesta.Concedida) apartados.remove(orden, a)
        }
        val v = venueActivo ?: return
        mandarAvisos(v)
    }

    private suspend fun mandarAvisos(venueId: String) {
        val estado = leer(venueId)
        if (estado.avisos.isEmpty()) return
        val vigentes = estado.avisos.filter { horaDePared() - it.creado < PODA_MS }
        val quedan = LinkedHashMap<String, Aviso>()
        for (a in vigentes) {
            if (a.intento in vivos) { quedan[a.intento] = a; continue }
            val fila = estadoDeLaFila(a.llave)
            // C1: «cobrada» sólo de un cobro que SÍ ocurrió (la cola ya tiene el efectivo, o el flujo lo cobró: `terminar`). Una EN_VUELO
            // está en duda: no se manda nada y la dueña ofrece «No se cobró» a los 2 min; si se descarta, se vuelve «suelta».
            val visto = a.copy(
                cobradaVista = a.cobradaVista || fila in DuenoDeCuentas.CON_DINERO,
                subida = a.subida || fila in DuenoDeCuentas.CONTESTADA,
            )
            if (!visto.cobradaVista && fila == DuenoDeCuentas.EN_VUELO) { quedan[a.intento] = visto; continue }
            val destinos = if (visto.dueno.isBlank()) cajasConD() else listOfNotNull(peerDe(visto.dueno))
            if (destinos.isEmpty()) { quedan[a.intento] = visto; continue }
            val op = if (visto.cobradaVista) LeaseProtocol.OP_CUENTA_COBRADA else LeaseProtocol.OP_CUENTA_SOLTAR
            if (visto.cobradaVista && visto.acusado == visto.subida) { quedan[a.intento] = visto; continue }
            var acuso: String? = null
            var todasNoEsMia = true
            for (p in destinos) {
                val r = client.preguntarCuenta(p, pedido(op, a.orderId, a.intento, a.quien, subida = visto.subida, venueId = venueId,
                    conAbono = visto.conAbono))
                if (CuentasPorLaRed.acuso(r)) { acuso = p.deviceId; break }
                if (r?.status != LeaseProtocol.STATUS_NO_ES_MIA) todasNoEsMia = false
            }
            when {
                // La dueña conocida dice que no es suya (marca podada): no hay a quién avisar. Con dueña DESCONOCIDA (I2) un «no es mía»
                // de las demás no prueba nada (la dueña puede no verse en esta vuelta): sólo la borra su acuse o la poda de 7 días.
                acuso == null && todasNoEsMia && visto.dueno.isNotBlank() -> Unit
                acuso == null -> quedan[a.intento] = visto
                op == LeaseProtocol.OP_CUENTA_SOLTAR -> Unit
                visto.subida -> Unit // la dueña sabe que se cobró y que ya subió
                else -> quedan[a.intento] = visto.copy(acusado = false, dueno = visto.dueno.ifBlank { acuso })
            }
        }
        // M2: lo que otro cambió en el disco mientras se mandaba (`terminar` lo marcó cobrado, `cambiarLlave`, un aviso nuevo) manda sobre
        // lo que esta vuelta leyó antes de la red: se queda como está y la siguiente vuelta lo vuelve a decidir.
        val leidos = estado.avisos.associateBy { it.intento }
        cambiar(venueId) { actual ->
            actual.copy(avisos = actual.avisos.mapNotNull { act ->
                val leido = leidos[act.intento]
                if (leido == null || act != leido) act else quedan[act.intento]
            })
        }
    }

    /** Por intento: volver a comprometer (R1, o un reintento con llave nueva) sólo cambia la llave cuya fila decide. */
    private suspend fun anotarAviso(venueId: String, orderId: String, intento: String, llave: String, quien: String, duena: String) {
        cambiar(venueId) { e ->
            // I2: si el aviso nació sin dueña (se perdió la respuesta) y ahora se sabe cuál es, queda a su nombre.
            if (e.avisos.any { it.intento == intento }) e.copy(avisos = e.avisos.map { if (it.intento == intento) it.copy(llave = llave, dueno = it.dueno.ifBlank { duena }) else it })
            else e.copy(avisos = e.avisos + Aviso(orderId, intento, llave, duena, quien, horaDePared()))
        }
    }

    // MARK: - Disco

    @Serializable
    data class DuenoConocido(val deviceId: String, val caja: String, val vistoEn: Long)

    @Serializable
    data class Aviso(
        val orderId: String,
        val intento: String,
        /** La llave del cobro: su fila `pending_payments` decide «cobrada» o «suelta». */
        val llave: String,
        val dueno: String,
        val quien: String,
        val creado: Long,
        val cobradaVista: Boolean = false,
        val subida: Boolean = false,
        /** El `subida` que la dueña ya acusó (null = nada acusado). */
        val acusado: Boolean? = null,
        /** W2: el cobro dejó saldo (true) o no (false), si el flujo lo supo; null = no se sabe (el aviso se rearmó desde su fila). */
        val conAbono: Boolean? = null,
    )

    @Serializable
    data class Estado(val duenos: Map<String, DuenoConocido> = emptyMap(), val avisos: List<Aviso> = emptyList())

    private val json = Json { ignoreUnknownKeys = true }

    /** LANZA si el disco falla; un JSON ilegible cuenta como vacío (no hay dinero en esto: los avisos se rehacen desde la fila). */
    private suspend fun leer(venueId: String): Estado {
        val guardado = cache.loadDurable(PayloadCache.TYPE_CUENTAS_ENTRE_CAJAS, venueId) ?: return Estado()
        return runCatching { json.decodeFromString(Estado.serializer(), guardado.json) }.getOrElse { Estado() }
    }

    private suspend fun cambiar(venueId: String, f: (Estado) -> Estado) = withContext(NonCancellable) {
        mutex.withLock {
            val antes = leer(venueId)
            val podado = antes.copy(duenos = antes.duenos.filterValues { horaDePared() - it.vistoEn < PODA_MS })
            val nuevo = f(podado)
            if (nuevo != antes) cache.saveDurable(PayloadCache.TYPE_CUENTAS_ENTRE_CAJAS, venueId, json.encodeToString(Estado.serializer(), nuevo))
        }
    }

    companion object {
        private const val TAG = "CuentasEntreCajas"
        private const val PODA_MS = 7 * 24 * 60 * 60 * 1000L

        /** Un apartado (la cajera teclea lo que recibe) que nadie compromete en este tiempo deja de renovarse. */
        const val APARTADO_SIN_COMPROMETER_MS = 2 * 60_000L
        const val NO_SE_GUARDO = "No se pudo guardar el cobro. Intenta de nuevo antes de recibir el pago."

        /** Familia C: plazo TOTAL de la salud que pregunta la puerta; al vencer cuenta como sin red. */
        const val PLAZO_DE_LA_SALUD_MS = 3_000L

        fun textoDe(r: Respuesta, caja: String?): String = when (r) {
            is Respuesta.Ocupada -> com.avoqado.pos.payment.domain.TextosDeCuentaSinRed.laEstaCobrandoEn(r.quien, r.caja.ifBlank { caja ?: DuenoDeCuentas.nombreDeCaja(null) })
            // M1: lo que cerró el SERVIDOR no lleva caja: su propio chip, nunca el nombre de una caja. M-1: un abono no es «Cobrada»: con
            // internet se cobra el resto; si el abono aún no sube, se espera a que suba.
            is Respuesta.Cerrada -> when {
                r.conAbono && r.faltaSubirla -> com.avoqado.pos.giftcards.domain.TextosDeTarjetas.COBRO_SIN_SINCRONIZAR
                r.conAbono -> com.avoqado.pos.payment.domain.TextosDeCuentaSinRed.CON_ABONO
                r.caja.isBlank() -> com.avoqado.pos.payment.domain.TextosDeCuentaSinRed.YA_SE_COBRO
                else -> com.avoqado.pos.payment.domain.TextosDeCuentaSinRed.cobradaEn(r.caja, r.faltaSubirla)
            }
            Respuesta.Rechazada -> caja?.let { com.avoqado.pos.payment.domain.TextosDeCuentaSinRed.sePerdioElContacto(it) } ?: com.avoqado.pos.payment.domain.TextosDeCuentaSinRed.SE_ABRIO_EN_OTRA_CAJA
            Respuesta.Concedida -> ""
        }
    }
}
