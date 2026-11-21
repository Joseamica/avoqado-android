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
const val TEXTO_CARRITO_ILEGIBLE = "El carrito guardado de este cobro no se puede leer: pide a un gerente que lo revise"

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
 *   gerente decide, y la fila pasa a `RESUELTO` con lo que tecleó (R4-4).
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
            val esDuda = fila.estado == EstadoDelIntento.ESPERANDO
            // Un carrito ilegible no puede tapar la cola: se ve y espera a un gerente, y las demás siguen.
            val cuerpo = runCatching { if (esDuda) cuerpoDeLaDuda(fila) else cuerpoDelCierre(fila) }.getOrNull()
            if (cuerpo == null) {
                dao.anotarConflicto(fila.referenceCC, TEXTO_CARRITO_ILEGIBLE)
                continue
            }
            val r = try {
                if (esDuda) api.subirDuda(venueId, credencial, cuerpo) else api.subirCierre(venueId, credencial, cuerpo)
            } catch (e: IOException) {
                return@withContext true   // sin red: la fila se queda tal cual
            }
            if (!aplicar(fila, r, esDuda)) return@withContext true
        }
        // Codex código r1 #6: el cierre tardío de una duda que el SERVIDOR ya tiene sube como cierre (después de la duda: ella congela
        // el carrito). La fila sigue en duda hasta su TERMINADO.
        for (fila in dao.tardiosParaSubir(venueId)) {
            val cuerpo = runCatching { cuerpoDelCierre(fila) }.getOrNull() ?: continue
            val r = try {
                api.subirCierre(venueId, credencial, cuerpo)
            } catch (e: IOException) {
                return@withContext true
            }
            if (!aplicar(fila, r, esDuda = false)) return@withContext true
        }
        for (fila in esperan) {
            val r = try {
                api.consultarDuda(venueId, credencial, fila.referenceCC)
            } catch (e: IOException) {
                return@withContext true
            }
            if (r.codigo == 404) dao.marcarDudaSubida(fila.referenceCC, false)   // el SERVIDOR no la tiene: se sube otra vez
            else if (!aplicar(fila, r, esDuda = true)) return@withContext true
        }
        hayTrabajo(venueId)
    }

    private suspend fun hayTrabajo(venueId: String) =
        dao.paraSubir(venueId).isNotEmpty() || dao.tardiosParaSubir(venueId).isNotEmpty() || dao.esperanAlGerente(venueId).isNotEmpty()

    /** Aplica una respuesta. false = la pasada se corta (sesión vencida, sin emparejar o sin permiso: lo demás fallaría igual). */
    private suspend fun aplicar(fila: CobroEnMaquinaEntity, r: RespuestaDelServidor, esDuda: Boolean): Boolean {
        when {
            r.codigo == 401 -> return false
            r.codigo == 403 -> {
                _aviso.value = if ("CASH_MACHINE_NOT_PAIRED" in r.cuerpo) TEXTO_PC_SIN_EMPAREJAR
                else ServerErrorText.fromResponseBody(r.cuerpo, TEXTO_PC_SIN_EMPAREJAR)
                return false
            }
            // Sin respuesta útil (caído, ruta que todavía no existe, muchas peticiones): se reintenta.
            r.codigo >= 500 || r.codigo == 404 || r.codigo == 408 || r.codigo == 429 -> Unit
            // «Otro registro de este folio estaba en curso»: el SERVIDOR revirtió todo y pide repetir el MISMO folio.
            r.codigo == 409 && codigoDe(r.cuerpo) == "CASH_MACHINE_CLOSE_RETRY" -> Unit
            r.codigo in 400..499 -> {
                val texto = ServerErrorText.fromResponseBody(r.cuerpo, "El servidor rechazó este cobro (${r.codigo})")
                // Un cierre con su incidencia DURABLE en el SERVIDOR (409/422) ya no es de la PC: queda visible y deja de frenar
                // cerrar sesión. Sin incidencia (un 400) el dinero sólo está aquí: visible y sin acuse.
                when {
                    incidenciaDe(r.cuerpo) == null -> dao.anotarConflicto(fila.referenceCC, texto)
                    // Codex código r1 #9: la duda en conflicto ya es durable en el SERVIDOR: no frena y se sigue consultando por su folio.
                    esDuda -> dao.marcarDudaEnConflicto(fila.referenceCC, texto, r.cuerpo)
                    fila.estado == EstadoDelIntento.ESPERANDO -> dao.marcarTardioSubido(fila.referenceCC, texto, r.cuerpo)
                    else -> dao.marcarSubido(fila.referenceCC, texto, r.cuerpo)
                }
            }
            r.codigo == 200 || r.codigo == 202 -> {
                val json = runCatching { JSONObject(r.cuerpo) }.getOrNull() ?: return true   // ilegible: se reintenta (es idempotente)
                if (esDuda) aplicarDuda(fila, json, r.cuerpo) else aplicarCierre(fila, json, r.codigo, r.cuerpo)
            }
        }
        _aviso.value = null
        return true
    }

    private suspend fun aplicarCierre(fila: CobroEnMaquinaEntity, json: JSONObject, codigo: Int, cuerpo: String) {
        // Un 202 sólo libera la fila con su incidencia DURABLE (`incidentId`, contrato del SERVIDOR); sin ella se repite el folio.
        if (codigo == 202 && incidenciaDe(cuerpo) == null) return
        val incidencia = if (codigo == 202) textoDeIncidencia(json.optString("motivo")) else null
        val tardio = fila.estado == EstadoDelIntento.ESPERANDO
        when {
            !repite(fila, json, desenlaceQueSube(fila), conLoQueEntro = true) -> dao.anotarConflicto(fila.referenceCC, TEXTO_RESPUESTA_DISTINTA)
            // El cierre tardío terminó el folio en el SERVIDOR: la duda queda resuelta con lo que él dice.
            tardio -> dao.resolver(fila.referenceCC, json.optString("resolvedBy").ifBlank { "MAQUINA" }, fila.creditCents,
                fila.changeDispensedCents, incidencia, cuerpo, json.optString("outcome"), json.centavos("owedCents"))
            else -> dao.marcarSubido(fila.referenceCC, incidencia, cuerpo, json.centavos("owedCents"))
        }
    }

    /** El desenlace que sube esta fila: el suyo, o el de su cierre tardío (Codex código r1 #6). */
    private fun desenlaceQueSube(fila: CobroEnMaquinaEntity): String =
        if (fila.estado == EstadoDelIntento.ESPERANDO) fila.cierreTardio.orEmpty() else fila.estado

    /** `ABIERTO`: el SERVIDOR ya la tiene. `RESUELTO`: la decisión del gerente (o su incidencia) con lo que tecleó. */
    private suspend fun aplicarDuda(fila: CobroEnMaquinaEntity, json: JSONObject, cuerpo: String) {
        when {
            json.optString("referenceCC") != fila.referenceCC -> dao.anotarConflicto(fila.referenceCC, TEXTO_RESPUESTA_DISTINTA)
            json.optString("status") == "ABIERTO" -> dao.marcarDudaSubida(fila.referenceCC, true)
            json.optString("status") != "RESUELTO" -> Unit   // no se entiende: se pregunta otra vez
            !dao.resolverConElEco(fila, json, cuerpo) -> dao.anotarConflicto(fila.referenceCC, TEXTO_RESPUESTA_DISTINTA)
        }
    }

    /** R5#7: el mismo folio, desenlace e importes. De una duda, lo que entró y el cambio los decide el gerente. */
    private fun repite(fila: CobroEnMaquinaEntity, json: JSONObject, outcome: String, conLoQueEntro: Boolean): Boolean =
        json.optString("referenceCC") == fila.referenceCC && json.optString("outcome") == outcome &&
            json.centavos("dueCents") == fila.dueCents && json.centavos("saleCents") == fila.saleCents &&
            json.centavos("tipCents") == fila.tipCents &&
            (!conLoQueEntro || (json.centavos("creditCents") == fila.creditCents &&
                json.centavos("changeDispensedCents") == fila.changeDispensedCents))

    private fun cuerpoDelCierre(fila: CobroEnMaquinaEntity): JSONObject {
        val outcome = desenlaceQueSube(fila)
        val cierre = JSONObject()
            .put("outcome", outcome)
            .put("dueCents", fila.dueCents).put("saleCents", fila.saleCents).put("tipCents", fila.tipCents)
            .put("creditCents", fila.creditCents).put("changeDispensedCents", fila.changeDispensedCents)
            // Codex código r1 #15: la hora sólo sube si la dijo la máquina; la de la PC nunca pasa por «conocida».
            .put("occurredAtKnown", fila.cierreConocido && fila.cierreEn != null)
            .put("occurredAt", fila.cierreEn?.takeIf { fila.cierreConocido }?.let(::iso) ?: JSONObject.NULL)
            .put("saleShiftId", fila.saleShiftId ?: JSONObject.NULL)
        val cuerpo = JSONObject().put("referenceCC", fila.referenceCC).put("cierre", cierre)
        if (outcome == EstadoDelIntento.PAGADO) cuerpo.put("pedido", JSONObject(fila.pedidoJson))
        else cierre.put("cancelMode", fila.cancelPedido ?: JSONObject.NULL)
        return cuerpo
    }

    private fun cuerpoDeLaDuda(fila: CobroEnMaquinaEntity): JSONObject = JSONObject()
        .put("referenceCC", fila.referenceCC)
        .put("dueCents", fila.dueCents).put("saleCents", fila.saleCents).put("tipCents", fila.tipCents)
        .put("pedido", JSONObject(fila.pedidoJson))
        .put("startedAt", iso(fila.creadoEn))
        .put("saleShiftId", fila.saleShiftId ?: JSONObject.NULL)
        .put("lastSeen", loUltimoQueVio(fila))

    /**
     * Codex código r1 #6: lo ÚLTIMO que quedó en disco de este folio (lo que entró, cuándo, y los mensajes de la máquina tal cual). Una
     * observación nueva del mismo folio cambia este texto y el SERVIDOR la agrega a la duda; una retransmisión idéntica no.
     */
    private fun loUltimoQueVio(fila: CobroEnMaquinaEntity): String =
        "Entró ${com.avoqado.pos.core.util.formatMoneyFromCents(fila.creditCents)}" +
            (fila.ultimoCreditoEn?.let { " (${iso(it)})" } ?: "") + "\n" + fila.mensajesCrudos

    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()

    private fun codigoDe(cuerpo: String): String? = runCatching { JSONObject(cuerpo).optString("code") }.getOrNull()

    /** La incidencia que el SERVIDOR guardó con un 409/422: arriba o en `details` (así salen los `AppError`). */
    private fun incidenciaDe(cuerpo: String): String? = runCatching {
        val json = JSONObject(cuerpo)
        listOf(json, json.optJSONObject("details")).firstNotNullOfOrNull { o ->
            o?.optString("incidentId")?.takeIf { it.isNotBlank() && it != "null" }
        }
    }.getOrNull()

    private companion object {
        const val TAG = "💵SubidaMaquina"
        const val CADA_MS = 60_000L
    }
}

private val DESENLACES = setOf("PAGADO", "CANCELADO", "GUARDADO")

private fun JSONObject.centavos(campo: String): Int? = (opt(campo) as? Number)?.toInt()

/** Codex código r1 #11: el folio quedó como incidencia (el dinero sin venta): su texto, o null si es venta o cierre. */
fun incidenciaDelEco(json: JSONObject): String? =
    json.optString("incident").takeIf { it.isNotBlank() && it != "null" }?.let { textoDeIncidencia(json.optString("motivo")) }

/**
 * La evidencia TERMINADA de una duda (la decisión del gerente, o su incidencia) en la fila: UN solo lugar que la lee, para la subida y
 * para la respuesta que recibe el propio gerente al resolver (Codex código r1 #11). false = no es de este folio o no cuadra.
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
