package com.avoqado.pos.maquina

import android.content.Context
import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ServerErrorText
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.payment.data.PaymentSyncService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

const val TEXTO_PC_SIN_EMPAREJAR = "Esta PC no está emparejada con la máquina: emparéjala en Ajustes para subir sus cobros"
const val TEXTO_RESPUESTA_DISTINTA = "El servidor respondió otros importes para este folio: pide a un gerente que lo revise"

/** R4-1: el dinero de un folio que el SERVIDOR no pudo volver venta queda como incidencia (202); la PC lo dice así. */
fun textoDeIncidencia(motivo: String): String = "Registrado como incidencia: " + when (motivo) {
    "DESCUENTO" -> "la venta traía un descuento"
    "PC_REVOCADA" -> "esta PC ya no estaba emparejada"
    "SIN_SALDAR" -> "lo que entró no alcanzaba para la cuenta"
    "SIN_CARRITO" -> "faltaba el carrito de la venta"
    "VENTA_RECHAZADA" -> "la venta ya no se pudo armar"
    else -> motivo
}

/**
 * 🔴 La subida de la máquina de efectivo (fase 1a, C4: R4-1, R5#6, R5#7, R3#12). La tabla `cobros_en_maquina` ES su cola:
 * no toca `pending_payments`, ni su replay, ni el cajón (la PC de la máquina no tiene).
 *
 * - **Cierres finales** (`PAGADO`, `CANCELADO`, `GUARDADO` sin acuse) → `POST …/cash-machine/sales`. `subido` sólo si el
 *   SERVIDOR repite el folio, el desenlace y los importes (200), si guardó el dinero como incidencia (202 con `incidentId`), o si lo rechazó
 *   DEJANDO su incidencia durable (409/422 con `incidentId`): las tres quedan en `respuestaServidor` y lo raro, visible en
 *   `conflicto`. Un 4xx sin incidencia o una respuesta con otros importes quedan visibles, sin acuse y sin reintento: esperan
 *   a un gerente (y siguen frenando cerrar sesión, R5#2). El 409 `CASH_MACHINE_CLOSE_RETRY` se reintenta.
 * - **Dudas** (`ESPERANDO`) → `POST …/doubts` con el carrito congelado; después se consultan cada 60 s hasta que el
 *   gerente decide EN EL PANEL (recorte R2), y la fila pasa a `RESUELTO` con lo que tecleó (R4-4).
 * - **Sin red, 401, 5xx o sin emparejar** no se marca nada: se reintenta. Sin emparejar (o 403) se dice en [aviso].
 * - **Cuándo:** al arrancar, al volver la red (el mismo disparador que `PaymentSyncService`), con [disparar] (tras cada
 *   cierre) y cada 60 s mientras quede trabajo. Sin trabajo no hay reloj.
 *
 * Sólo sube lo de la sucursal de la sesión: la credencial y la sesión son de ésa.
 */
@Singleton
class SubidaDeLaMaquina @Inject constructor(
    private val dao: CobroEnMaquinaDao,
    private val api: CierresDeLaMaquinaApi,
    private val boveda: BovedaDeLaMaquina,
    private val secureStorage: SecureStorage,
    private val turno: TurnoDelNegocio,
    private val prefs: MaquinaDeEfectivoPrefs,
    private val connectivityMonitor: ConnectivityMonitor,
) {
    /** Seams de pruebas. */
    internal var io: CoroutineDispatcher = Dispatchers.IO
    internal var cadaMs: Long = CADA_MS
    internal var esperaDeReconexionMs: Long = PaymentSyncService.ESPERA_DE_ESTABILIZACION_MS

    private val _aviso = MutableStateFlow<String?>(null)
    /** Lo que impide subir ahora mismo (sin emparejar, 403); null cuando el SERVIDOR contesta. */
    val aviso: StateFlow<String?> = _aviso.asStateFlow()

    private val senal = Channel<Unit>(Channel.CONFLATED)
    private var trabajo: Job? = null

    /** Un solo trabajador: las pasadas nunca se enciman. Si ya corre, sólo pide otra pasada (entró otra persona). */
    @Synchronized
    fun start() {
        if (trabajo != null) return disparar()
        trabajo = CoroutineScope(SupervisorJob() + io).launch {
            launch {
                PaymentSyncService.alReconectar(
                    combine(connectivityMonitor.isConnected, connectivityMonitor.isServerReachable) { red, servidor -> red && servidor },
                    esperaDeReconexionMs,
                ) { disparar() }
            }
            while (isActive) {
                if (prefs.activa) refrescarTurno()
                val queda = pasada()
                // Con trabajo: otra pasada a los 60 s o antes si alguien la pide. Sin trabajo: sólo cuando alguien la pida.
                if (queda) withTimeoutOrNull(cadaMs) { senal.receive() } else senal.receive()
            }
        }
    }

    @Synchronized
    fun stop() {
        trabajo?.cancel()
        trabajo = null
    }

    /** Tras cada cierre o duda, al cambiar de sucursal y al volver la red. */
    fun disparar() {
        senal.trySend(Unit)
    }

    private suspend fun refrescarTurno() {
        try {
            turno.refrescar()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo leer el turno del negocio", e)
        }
    }

    private suspend fun pasada(): Boolean = try {
        subir()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "La subida de la máquina falló; se reintenta", e)
        true
    }

    /** Una pasada. Devuelve si queda trabajo (algo por subir o una duda esperando al gerente). */
    internal suspend fun subir(): Boolean = withContext(io) {
        val venueId = secureStorage.venueId ?: return@withContext false
        val credencial = boveda.leer()
        if (credencial == null) {
            _aviso.value = TEXTO_PC_SIN_EMPAREJAR
            return@withContext hayTrabajo(venueId)
        }
        // Las que ya estaban subidas: la que se sube en esta pasada no se consulta en el mismo instante.
        val esperan = dao.esperanAlGerente(venueId)
        for (fila in dao.paraSubir(venueId)) {
            // Codex código r3 #1 y POS r7: lo que llegó después de la resolución de un gerente, o de un cierre que el SERVIDOR ya tiene, sube
            // como duda (el SERVIDOR abre su revisión).
            val esDuda = fila.estado == EstadoDelIntento.ESPERANDO || fila.subeTrasTerminar()
            // Codex código r3 #14: el cuerpo siempre se arma; un carrito ilegible sube SIN carrito y el SERVIDOR guarda primero el dinero.
            val r = try {
                subirFolio(venueId, credencial, fila, esDuda)
            } catch (e: IOException) {
                return@withContext true   // sin red: la fila se queda tal cual
            }
            if (!aplicar(fila, leerAcuse(r), esDuda, porPost = true)) return@withContext true
        }
        for (fila in esperan.filter(::consultable)) {
            // Rediseño del SERVIDOR (r6-1): una duda ABIERTA puede tener ya su desenlace sin terminar, y sólo REENVIARLA lo reanuda (y sube
            // lo nuevo que vio la PC, que el SERVIDOR agrega). La que quedó en conflicto ya tiene su incidencia: reenviarla daría el mismo 409,
            // así que sólo se consulta por su folio (Codex código r1 #9).
            val enConflicto = fila.conflicto != null
            val r = try {
                if (enConflicto) api.consultarDuda(venueId, credencial, fila.referenceCC)
                else subirFolio(venueId, credencial, fila, esDuda = true)
            } catch (e: IOException) {
                return@withContext true
            }
            val acuse = leerAcuse(r)
            if (acuse == Acuse.NoExiste) dao.marcarDudaSubida(fila.referenceCC, false)   // el SERVIDOR no la tiene: se sube otra vez
            else if (!aplicar(fila, acuse, esDuda = true, porPost = !enConflicto)) return@withContext true
        }
        hayTrabajo(venueId)
    }

    /**
     * Codex código POS r5 #4: un folio nunca se queda fuera de la cola ni del panel por pesar de más. Lo esencial (importes, `creditCents`
     * como número y el diario recortado, [textoDeLoQueVio]) va siempre. Si el cuerpo con el carrito no cabe en [TOPE_DEL_CUERPO], o el
     * SERVIDOR contesta 413, sale al momento SIN el carrito: el SERVIDOR guarda primero el dinero (D1) y anota lo que falta. Un 413 que
     * quede nunca es rechazo: [leerAcuse] lo reintenta.
     */
    private suspend fun subirFolio(venueId: String, credencial: String, fila: CobroEnMaquinaEntity, esDuda: Boolean): RespuestaDelServidor {
        suspend fun enviar(cuerpo: JSONObject) =
            if (esDuda) api.subirDuda(venueId, credencial, cuerpo) else api.subirCierre(venueId, credencial, cuerpo)
        fun cuerpo(conCarrito: Boolean) = if (esDuda) cuerpoDeLaDuda(fila, conCarrito) else cuerpoDelCierre(fila, conCarrito)
        val completo = cuerpo(conCarrito = true)
        if (completo.toString().toByteArray(Charsets.UTF_8).size > TOPE_DEL_CUERPO) return enviar(cuerpo(conCarrito = false))
        val r = enviar(completo)
        return if (r.codigo == 413) enviar(cuerpo(conCarrito = false)) else r
    }

    private suspend fun hayTrabajo(venueId: String) =
        dao.paraSubir(venueId).isNotEmpty() || dao.esperanAlGerente(venueId).any(::consultable)

    /**
     * Codex código r2 H8: una duda cuyo folio el SERVIDOR atribuye a OTRA PC ya no se consulta (su decisión nunca le llega a ésta). Codex
     * código POS r4 #5: tampoco la que él guardó sólo como incidencia (422): no hay duda que consultar, y preguntar daba 404 y la volvía a
     * subir sin fin. Las dos sí vuelven a subir cuando su diario trae algo nuevo: el SERVIDOR lo agrega a la incidencia.
     */
    private fun consultable(fila: CobroEnMaquinaEntity) = !fila.esDeOtraPc() && !fila.esIncidenciaSinDuda()

    /**
     * Aplica el acuse (leído en UN solo lugar: [leerAcuse]). false = la pasada se corta (sesión vencida, sin emparejar o sin permiso: lo
     * demás fallaría igual). Rediseño del SERVIDOR (r3 #7, r6-1): el pendiente sólo se retira con TERMINADO de ESTA PC, o con la
     * incidencia durable de un 409/422; RECIBIDO se conserva y se reenvía el MISMO mensaje, que lo reanuda.
     */
    private suspend fun aplicar(fila: CobroEnMaquinaEntity, acuse: Acuse, esDuda: Boolean, porPost: Boolean): Boolean {
        if (fila.subeTrasTerminar()) return entregarTrasResolver(fila, acuse, porPost)
        when (acuse) {
            Acuse.SinSesion -> return false
            is Acuse.SinEmparejar -> {
                _aviso.value = acuse.texto
                return false
            }
            Acuse.Reintentar, Acuse.NoExiste -> Unit
            // Sin incidencia (un 400) el dinero sólo está aquí: visible y sin acuse.
            is Acuse.Rechazo -> dao.anotarConflicto(fila.referenceCC, acuse.texto)
            // Con su incidencia DURABLE en el SERVIDOR (409/422) ya no es de la PC: queda visible y deja de frenar cerrar sesión.
            is Acuse.ConIncidencia -> when {
                // Codex código r1 #9: la duda en conflicto no frena (se consulta por su folio salvo que sea de otra PC, H8).
                esDuda -> {
                    if (porPost) acuse.deviceIdDeEstaPc?.let(prefs::recordarDispositivo)
                    dao.marcarDudaEnConflicto(fila.referenceCC, acuse.texto, acuse.cuerpo, fila.mensajesCrudos, fila.creditCents)
                }
                // Codex código r2 H8: el folio es de OTRA PC: lo de esta PC nunca queda como subido; su incidencia sí se ve.
                acuse.motivo == MOTIVO_OTRA_PC && acuse.duenoDelFolio.let { it == null || it != prefs.dispositivo } ->
                    dao.marcarDeOtraPc(fila.referenceCC, acuse.texto, acuse.cuerpo)
                else -> dao.marcarSubido(fila.referenceCC, acuse.texto, acuse.cuerpo)
            }
            // La duda ABIERTA: el SERVIDOR ya la tiene; su `version` es la que mandará el gerente al resolverla (r6-4). Codex código r2
            // H1: el acuse es de la evidencia que se mandó; si llegó otra mientras tanto, sigue por subir.
            is Acuse.Recibido -> if (esDuda && esDeEstaPc(acuse.deviceId, porPost)) {
                dao.marcarDudaAbierta(fila.referenceCC, acuse.json.centavos("version"), fila.mensajesCrudos, fila.creditCents)
            }
            is Acuse.Terminado -> when {
                !esDeEstaPc(acuse.deviceId, porPost) -> Unit   // el folio es de otra PC (o el acuse no dice de quién): no se retira nada
                esDuda -> if (!dao.resolverConElEco(fila, acuse.json, acuse.cuerpo)) {
                    dao.anotarConflicto(fila.referenceCC, TEXTO_RESPUESTA_DISTINTA)
                } else if (porPost) {
                    // Codex código r3 #1: este POST llevó la evidencia de la fila: si quedó pendiente tras la resolución, ya está allá.
                    dao.marcarEvidenciaEntregada(fila.referenceCC, fila.mensajesCrudos, fila.creditCents)
                }
                else -> aplicarCierre(fila, acuse)
            }
        }
        _aviso.value = null
        return true
    }

    /**
     * Codex código r3 #1 y Codex código POS r4 #3: la evidencia que llegó DESPUÉS de la resolución de un gerente. Sólo la entrega una
     * constancia de que el SERVIDOR la guardó: su incidencia durable (`incidentId`) o el TERMINADO de ESTA PC. Un rechazo sin incidencia
     * (400, 413…), un RECIBIDO o un TERMINADO sin dueño la dejan pendiente: sigue frenando salir y vuelve a subir. La resolución guardada
     * no se toca (si no cubre lo que entró, la venta sigue frenada: [bloqueaLaVenta]).
     */
    private suspend fun entregarTrasResolver(fila: CobroEnMaquinaEntity, acuse: Acuse, porPost: Boolean): Boolean {
        when (acuse) {
            Acuse.SinSesion -> return false
            is Acuse.SinEmparejar -> {
                _aviso.value = acuse.texto
                return false
            }
            is Acuse.ConIncidencia -> dao.marcarEvidenciaEntregada(fila.referenceCC, fila.mensajesCrudos, fila.creditCents)
            is Acuse.Terminado -> if (esDeEstaPc(acuse.deviceId, porPost)) {
                dao.marcarEvidenciaEntregada(fila.referenceCC, fila.mensajesCrudos, fila.creditCents)
            }
            is Acuse.Rechazo, is Acuse.Recibido, Acuse.Reintentar, Acuse.NoExiste -> Unit
        }
        _aviso.value = null
        return true
    }

    /**
     * r3 #7: ¿el acuse es de un folio de ESTA PC? Un POST con la credencial de esta PC sólo lo contesta así el dueño (otra PC recibe 409
     * `OTRA_PC`): su `deviceId` es el de esta PC y se recuerda. Un GET (la duda en conflicto) lo contesta a cualquiera: tiene que ser el
     * recordado.
     */
    private fun esDeEstaPc(deviceId: String?, porPost: Boolean): Boolean {
        if (deviceId == null) return false
        if (porPost) prefs.recordarDispositivo(deviceId)
        return porPost || prefs.esDeEstaPc(deviceId)
    }

    private suspend fun aplicarCierre(fila: CobroEnMaquinaEntity, acuse: Acuse.Terminado) {
        val json = acuse.json
        if (!repite(fila, json, fila.estado, conLoQueEntro = true)) dao.anotarConflicto(fila.referenceCC, TEXTO_RESPUESTA_DISTINTA)
        else dao.marcarSubido(fila.referenceCC, acuse.incidencia, acuse.cuerpo, json.centavos("owedCents"))
    }

    /** R5#7: el mismo folio, desenlace e importes. De una duda, lo que entró y el cambio los decide el gerente. */
    private fun repite(fila: CobroEnMaquinaEntity, json: JSONObject, outcome: String, conLoQueEntro: Boolean): Boolean =
        json.optString("referenceCC") == fila.referenceCC && json.optString("outcome") == outcome &&
            json.centavos("dueCents") == fila.dueCents && json.centavos("saleCents") == fila.saleCents &&
            json.centavos("tipCents") == fila.tipCents &&
            (!conLoQueEntro || (json.centavos("creditCents") == fila.creditCents &&
                json.centavos("changeDispensedCents") == fila.changeDispensedCents))

    private fun cuerpoDelCierre(fila: CobroEnMaquinaEntity, conCarrito: Boolean = true): JSONObject {
        val outcome = fila.estado
        val cierre = JSONObject()
            .put("outcome", outcome)
            .put("dueCents", fila.dueCents).put("saleCents", fila.saleCents).put("tipCents", fila.tipCents)
            .put("creditCents", fila.creditCents).put("changeDispensedCents", fila.changeDispensedCents)
            // Codex código r1 #15: la hora sólo sube si la dijo la máquina; la de la PC nunca pasa por «conocida».
            .put("occurredAtKnown", fila.cierreConocido && fila.cierreEn != null)
            .put("occurredAt", fila.cierreEn?.takeIf { fila.cierreConocido }?.let(::iso) ?: JSONObject.NULL)
            .put("saleShiftId", fila.saleShiftId ?: JSONObject.NULL)
        val cuerpo = JSONObject().put("referenceCC", fila.referenceCC).put("cierre", cierre)
        // r6-6: el emparejamiento con que ESTA PC empezó el cobro.
        cuerpo.put("bindingId", fila.bindingId ?: JSONObject.NULL)
        // Codex código r2 H15: el carrito viaja con todo desenlace: el primer mensaje del folio lo congela en el SERVIDOR (D2), también
        // un CANCELADO o un GUARDADO (el gerente ve qué partidas y qué cliente tenía el dinero). Codex código r3 #14: si el carrito de
        // disco no se puede leer, el cierre sube SIN él: el SERVIDOR guarda primero el dinero (D1) y deja la incidencia.
        if (conCarrito) pedidoLegible(fila)?.let { cuerpo.put("pedido", it) }
        if (outcome != EstadoDelIntento.PAGADO) cierre.put("cancelMode", fila.cancelPedido ?: JSONObject.NULL)
        return cuerpo
    }

    private fun pedidoLegible(fila: CobroEnMaquinaEntity): JSONObject? = runCatching { JSONObject(fila.pedidoJson) }.getOrNull()

    private fun cuerpoDeLaDuda(fila: CobroEnMaquinaEntity, conCarrito: Boolean = true): JSONObject = JSONObject()
        .put("referenceCC", fila.referenceCC)
        .put("dueCents", fila.dueCents).put("saleCents", fila.saleCents).put("tipCents", fila.tipCents)
        .apply { if (conCarrito) pedidoLegible(fila)?.let { put("pedido", it) } }   // Codex código r3 #14: ilegible, sube sin él (r6-2)
        .put("startedAt", iso(fila.creadoEn))
        .put("saleShiftId", fila.saleShiftId ?: JSONObject.NULL)
        .put("lastSeen", loUltimoQueVio(fila))
        // Codex código r3 #9: lo que entró, como NÚMERO: el SERVIDOR conserva el mayor que vio la PC con este campo. Codex código POS r7:
        // con lo que la máquina contó DESPUÉS de un cierre (columna física), que lo registrado de ese cierre ya no recoge.
        .put("creditCents", loQueEntro(fila))
        .put("bindingId", fila.bindingId ?: JSONObject.NULL)

    /**
     * Codex código r1 #6 y r2 H13: lo ÚLTIMO que quedó en disco de este folio: lo que entró y su diario, sin horas de la PC. Una
     * observación nueva del mismo folio cambia este texto y el SERVIDOR la agrega a la duda; un mensaje físico repetido no (el diario no
     * lo vuelve a anotar), así que tampoco sube la versión que revisa el gerente.
     */
    private fun loUltimoQueVio(fila: CobroEnMaquinaEntity): String = textoDeLoQueVio(fila)

    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()


    private companion object {
        const val TAG = "💵SubidaMaquina"
        const val CADA_MS = 60_000L
    }
}

private val DESENLACES = setOf("PAGADO", "CANCELADO", "GUARDADO")

private fun JSONObject.centavos(campo: String): Int? = (opt(campo) as? Number)?.toInt()

/**
 * Lo que contestó el SERVIDOR a un mensaje de un folio (cierre o duda), leído en UN solo lugar ([leerAcuse]).
 * Rediseño del SERVIDOR (r6-1): todo acuse trae `ack` (`RECIBIDO` | `TERMINADO`) y el `deviceId` dueño del folio.
 */
sealed interface Acuse {
    /** El folio terminó: venta o cierre (200), o el dinero sin venta con su incidencia (202). [incidencia]: su texto, o null. */
    data class Terminado(val json: JSONObject, val cuerpo: String, val deviceId: String?, val incidencia: String?) : Acuse
    /** La evidencia quedó y la venta todavía no (202 RECIBIDO), o la duda sigue ABIERTA: se conserva y se reenvía el MISMO mensaje. */
    data class Recibido(val json: JSONObject, val deviceId: String?) : Acuse
    /**
     * 409/422 con su incidencia durable en el SERVIDOR. [deviceIdDeEstaPc]: en un conflicto `OTRA_EVIDENCIA` el dueño del folio es quien
     * lo mandó (con `OTRA_PC` sería otro), así que su `deviceId` es el de esta PC (para reconocer después el acuse de su consulta).
     * Codex código r2 H8: [motivo] (`OTRA_PC`, `OTRA_EVIDENCIA`…) y [duenoDelFolio], el `deviceId` que el SERVIDOR dice dueño.
     */
    data class ConIncidencia(
        val texto: String,
        val cuerpo: String,
        val deviceIdDeEstaPc: String? = null,
        val motivo: String? = null,
        val duenoDelFolio: String? = null,
    ) : Acuse
    /** Un rechazo sin incidencia (un 400): sólo está en esta PC. */
    data class Rechazo(val texto: String) : Acuse
    data class SinEmparejar(val texto: String) : Acuse
    data object SinSesion : Acuse
    data object NoExiste : Acuse
    /** Caído, muchas peticiones, «otro registro del folio estaba en curso» (sin escribir nada) o una respuesta ilegible. */
    data object Reintentar : Acuse
}

/** El ÚNICO lugar que interpreta lo que contesta el SERVIDOR a la máquina (la subida y la consulta de una duda). */
fun leerAcuse(r: RespuestaDelServidor): Acuse {
    val json = runCatching { JSONObject(r.cuerpo) }.getOrNull()
    val codigo = json?.optString("code")
    return when {
        r.codigo == 401 -> Acuse.SinSesion
        r.codigo == 403 -> Acuse.SinEmparejar(
            if ("CASH_MACHINE_NOT_PAIRED" in r.cuerpo) TEXTO_PC_SIN_EMPAREJAR else ServerErrorText.fromResponseBody(r.cuerpo, TEXTO_PC_SIN_EMPAREJAR),
        )
        r.codigo == 404 -> Acuse.NoExiste
        // Codex código POS r5 #4: 413 = «demasiado grande», nunca un rechazo: el folio sigue en la cola (la subida ya mandó lo más chico).
        r.codigo >= 500 || r.codigo == 408 || r.codigo == 429 || r.codigo == 413 -> Acuse.Reintentar
        // «Otro registro de este folio estaba en curso»: el SERVIDOR no escribió nada y pide repetir el MISMO folio.
        r.codigo == 409 && codigo == "CASH_MACHINE_CLOSE_RETRY" -> Acuse.Reintentar
        r.codigo in 400..499 -> {
            val texto = ServerErrorText.fromResponseBody(r.cuerpo, "El servidor rechazó este cobro (${r.codigo})")
            val detalles = json?.optJSONObject("details")
            val motivo = detalles?.optString("motivo")?.takeIf { it.isNotBlank() && it != "null" }
            val dueno = detalles?.takeIf { !it.isNull("deviceId") }?.optString("deviceId")?.takeIf { it.isNotBlank() }
            val delDueno = dueno.takeIf { motivo == "OTRA_EVIDENCIA" }
            if (incidenciaDe(r.cuerpo) != null) Acuse.ConIncidencia(texto, r.cuerpo, delDueno, motivo, dueno) else Acuse.Rechazo(texto)
        }
        (r.codigo == 200 || r.codigo == 202) && json != null -> {
            val deviceId = json.optString("deviceId").takeIf { !json.isNull("deviceId") && it.isNotBlank() }
            val terminado = when (json.optString("ack")) {
                "TERMINADO" -> true
                "RECIBIDO" -> false
                // Un acuse sin `ack` (un servidor de antes): la duda dice su estado; un 202 sin incidencia no terminó.
                else -> json.optString("status") != "ABIERTO" && (r.codigo == 200 || incidenciaDe(r.cuerpo) != null)
            }
            when {
                !terminado -> Acuse.Recibido(json, deviceId)
                // Un 202 sólo libera la fila con su incidencia DURABLE (`incidentId`); sin ella se repite el folio.
                r.codigo == 202 && incidenciaDe(r.cuerpo) == null -> Acuse.Reintentar
                else -> Acuse.Terminado(json, r.cuerpo, deviceId, incidenciaDelEco(json))
            }
        }
        else -> Acuse.Reintentar
    }
}

/** La incidencia que el SERVIDOR guardó: arriba o en `details` (así salen los `AppError`). */
private fun incidenciaDe(cuerpo: String): String? = runCatching {
    val json = JSONObject(cuerpo)
    listOf(json, json.optJSONObject("details")).firstNotNullOfOrNull { o ->
        o?.optString("incidentId")?.takeIf { it.isNotBlank() && it != "null" }
    }
}.getOrNull()

/**
 * Lo que la PC vio de un folio en duda (lo que sube y lo que lee el gerente): lo que entró y el diario. Codex código POS r5 #4: a lo más
 * [tope] bytes, los mensajes más NUEVOS primero (el SERVIDOR guarda el principio si le sobra); el diario completo se queda en la PC.
 */
fun textoDeLoQueVio(fila: CobroEnMaquinaEntity, tope: Int = TOPE_DEL_DIARIO_QUE_SUBE): String {
    val texto = StringBuilder("Entró ${com.avoqado.pos.core.util.formatMoneyFromCents(loQueEntro(fila))}")
    val lineas = fila.mensajesCrudos.lines().filter { it.isNotBlank() }.asReversed()
    var bytes = texto.toString().toByteArray(Charsets.UTF_8).size + RESERVA_DEL_AVISO
    var van = 0
    for (l in lineas) {
        bytes += l.toByteArray(Charsets.UTF_8).size + 1
        if (bytes > tope) break
        texto.append('\n').append(l)
        van++
    }
    if (van < lineas.size) texto.append("\n… y ${lineas.size - van} mensajes más viejos, sólo en esta PC")
    return texto.toString()
}

/** Lo más que la PC vio entrar de un folio: lo de la fila o, si la máquina contó más después de cerrar, lo físico. */
private fun loQueEntro(fila: CobroEnMaquinaEntity) = maxOf(fila.creditCents, fila.creditoFisicoCents)

/** Codex código POS r5 #4: lo más que sube del diario de un folio (64 KiB); lo demás se queda en la PC. */
internal const val TOPE_DEL_DIARIO_QUE_SUBE = 64 * 1024
private const val RESERVA_DEL_AVISO = 80

/** Codex código POS r5 #4: el límite de siempre del SERVIDOR (1 MiB) con margen; un cuerpo más grande sale sin el carrito. */
internal const val TOPE_DEL_CUERPO = 1_000_000

/** Codex código r1 #11: el folio quedó como incidencia (el dinero sin venta): su texto, o null si es venta o cierre. */
fun incidenciaDelEco(json: JSONObject): String? =
    json.optString("incident").takeIf { it.isNotBlank() && it != "null" }?.let { textoDeIncidencia(json.optString("motivo")) }

/**
 * La evidencia TERMINADA de una duda (la decisión del gerente en el panel, o su incidencia) en la fila: UN solo lugar que la lee
 * (Codex código r1 #11). false = no es de este folio o no cuadra.
 */
internal suspend fun CobroEnMaquinaDao.resolverConElEco(fila: CobroEnMaquinaEntity, json: JSONObject, cuerpo: String): Boolean {
    val outcome = json.optString("outcome")
    val credit = json.centavos("creditCents") ?: return false
    val change = json.centavos("changeDispensedCents") ?: return false
    if (outcome !in DESENLACES || json.optString("referenceCC") != fila.referenceCC) return false
    if (json.centavos("dueCents") != fila.dueCents || json.centavos("saleCents") != fila.saleCents || json.centavos("tipCents") != fila.tipCents) return false
    resolver(
        referenceCC = fila.referenceCC,
        resueltoPor = json.optString("resolvedBy").ifBlank { "GERENTE" },
        creditCents = credit,
        changeDispensedCents = change,
        conflicto = incidenciaDelEco(json),
        // El desenlace del gerente vive aquí ([desenlace]); `cancelPedido` sigue siendo lo que pidió la PC.
        respuesta = cuerpo,
        desenlace = outcome,
        aDeber = json.centavos("owedCents"),
    )
    return true
}

/**
 * El turno vivo del NEGOCIO para la PC de la máquina, que no tiene gaveta (R3#8): con red se pregunta y se guarda; sin red
 * queda lo último que se supo. C5 lo pone en `VentaParaLaMaquina.saleShiftId`.
 */
@Singleton
class TurnoDelNegocio @Inject constructor(
    @ApplicationContext context: Context,
    private val api: CierresDeLaMaquinaApi,
    private val secureStorage: SecureStorage,
) {
    private val prefs = context.getSharedPreferences(MaquinaDeEfectivoPrefs.NOMBRE, Context.MODE_PRIVATE)

    /** El de la sucursal de la sesión, según la última consulta con red; null si no había turno abierto o es de otra. */
    fun turnoActual(): String? = prefs.getString(TURNO, null)?.takeIf { prefs.getString(SUCURSAL, null) == secureStorage.venueId }

    /** Con red, lo pregunta y lo guarda (`null` del SERVIDOR = no hay turno abierto). Sin respuesta útil, se queda lo guardado. */
    suspend fun refrescar(): String? {
        val venueId = secureStorage.venueId ?: return null
        val r = try {
            api.turnoDelNegocio(venueId)
        } catch (e: IOException) {
            return turnoActual()
        }
        if (r.codigo != 200) return turnoActual()
        val json = if (r.cuerpo.trim() == "null") null else runCatching { JSONObject(r.cuerpo) }.getOrNull() ?: return turnoActual()
        val guardado = prefs.edit().putString(SUCURSAL, venueId)
            .putString(TURNO, json?.optString("shiftId")?.takeIf { it.isNotBlank() })
            .putString(INICIO, json?.optString("startTime")?.takeIf { it.isNotBlank() })
            .commit()
        check(guardado) { "No se pudo guardar el turno del negocio" }
        return turnoActual()
    }

    private companion object {
        const val SUCURSAL = "turno_sucursal"
        const val TURNO = "turno_id"
        const val INICIO = "turno_inicio"
    }
}
