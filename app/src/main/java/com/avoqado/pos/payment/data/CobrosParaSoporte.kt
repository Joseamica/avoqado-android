package com.avoqado.pos.payment.data

import android.content.Context
import android.util.Log
import com.avoqado.pos.core.data.local.database.PendingPaymentEntity
import com.avoqado.pos.core.data.local.database.RevisionCanceladoCero
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * D63 (founder 16-nov, «A»): la salida de GERENTE para un cobro retenido que no tiene otra en «Operaciones por revisar».
 *
 * Un cobro «conservado para revisión», uno heredado sin captura o uno que el servidor ya no acepta se quedaba en el aparato frenando
 * «Cerrar sesión» para siempre. Con «Dejar para soporte» el gerente dice si el cliente pagó (Sí · No · No sé) y se guarda quién, cuándo,
 * qué cobro y lo que dijo. Desde ahí ese cobro ya no frena cerrar sesión ni cambiar de sucursal. 🔴 La fila NO se borra, NO se sube por
 * esa respuesta (Q15: una afirmación no es prueba) y sigue a la vista con su nota; si después se resuelve sola, sigue como hoy.
 *
 * La nota vive APARTE de la fila (no en `q15Reviewed*`, que nueve lectores leen como «revisado»; ni en una tabla de Room, que
 * pediría la v20): una llave por cobro en `SharedPreferences` con `commit()`, bajo [candado]. Si se pierde, lo peor que pasa es que el
 * freno vuelve a contar la fila. Promesa: `Avoqado/docs/audit/2026-11-16-salida-de-gerente-cobros-retenidos/promesa.md`.
 */
@Singleton
class CobrosParaSoporte(private val prefs: android.content.SharedPreferences) {
    @Inject constructor(@ApplicationContext context: Context) :
        this(context.getSharedPreferences("cobros_para_soporte", Context.MODE_PRIVATE))

    enum class Declaracion(val texto: String) { SI_PAGO("Sí pagó"), NO_PAGO("No pagó"), NO_SE("No sé") }

    /** El rastro: quién, cuándo, qué cobro (como estaba en ese momento) y lo que dijo. Una vez escrita no cambia. */
    @Serializable
    data class Nota(
        val paymentId: String,
        val venueId: String,
        val declaracion: Declaracion,
        val actorId: String,
        val actorNombre: String? = null,
        val actorRol: String,
        val marcadoEn: Long,
        val estado: String,
        val motivo: String? = null,
        /** Codex r2 #2: el rechazo que vio el gerente (`lastError`); un «Reintentar» después lo borra de la fila. */
        val error: String? = null,
        val importeCents: Int,
        val propinaCents: Int,
    )

    enum class Resultado { GUARDADA, YA_ESTABA, NO_APLICA, SIN_PERMISO, NO_SE_PUDO }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val candado = Mutex()
    private val _notas = MutableStateFlow(leerTodas())

    /** Todas las notas del aparato, por folio del cobro (su llave: un UUID único en `pending_payments`). La hoja las enseña junto a él. */
    val notas: StateFlow<Map<String, Nota>> = _notas.asStateFlow()

    fun nota(paymentId: String): Nota? = _notas.value[paymentId]

    /**
     * ¿Esta fila tiene nota Y sigue calificando? Si dejó de calificar (p. ej. «Reintentar» la puso en la cola), frena como cualquiera.
     * Se mide contra SU sucursal (una fila vieja sin sucursal, `""`, también).
     */
    fun vigente(fila: PendingPaymentEntity): Boolean = nota(fila.id)?.venueId == fila.venueId &&
        aplica(fila, fila.venueId.ifBlank { SIN_SUCURSAL })

    /**
     * Guarda la nota del gerente. [fila] es la fila como está en la base AHORA (quien llama la relee). La primera nota gana: marcar
     * otra vez no la cambia. Sin permiso de gerente o si la fila ya no califica, no escribe nada.
     */
    suspend fun dejar(
        fila: PendingPaymentEntity,
        sucursal: String,
        declaracion: Declaracion,
        actorId: String?,
        actorNombre: String?,
        actorRol: String?,
        ahora: Long = System.currentTimeMillis(),
    ): Resultado = candado.withLock {
        val actor = actorId?.takeIf { it.isNotBlank() } ?: return@withLock Resultado.SIN_PERMISO
        val rol = actorRol?.takeIf { RevisionCanceladoCero.esGerente(it) } ?: return@withLock Resultado.SIN_PERMISO
        if (!aplica(fila, sucursal)) return@withLock Resultado.NO_APLICA
        val clave = fila.id
        if (prefs.getString(clave, null)?.let(::leer) != null) return@withLock Resultado.YA_ESTABA
        val nota = Nota(
            paymentId = fila.id, venueId = fila.venueId, declaracion = declaracion, actorId = actor,
            actorNombre = actorNombre?.takeIf { it.isNotBlank() }, actorRol = rol, marcadoEn = ahora,
            estado = fila.syncStatus, motivo = fila.q15Reason, error = fila.lastError, importeCents = fila.amountCents, propinaCents = fila.tipCents,
        )
        if (!prefs.edit().putString(clave, json.encodeToString(Nota.serializer(), nota)).commit()) {
            // `commit()` cambia la memoria aunque el disco falle: se deshace para no prometer lo que no se guardó.
            prefs.edit().remove(clave).commit()
            _notas.value = leerTodas()
            return@withLock Resultado.NO_SE_PUDO
        }
        _notas.value = leerTodas()
        Log.w("💵", "D63: cobro ${fila.id} (${fila.syncStatus}/${fila.q15Reason}) dejado para soporte por $actor ($rol): ${declaracion.name}")
        Resultado.GUARDADA
    }

    private fun leer(raw: String): Nota? = runCatching { json.decodeFromString(Nota.serializer(), raw) }.getOrNull()

    private fun leerTodas(): Map<String, Nota> = prefs.all.mapNotNull { (k, v) -> (v as? String)?.let(::leer)?.let { k to it } }
        .filter { (k, n) -> k == n.paymentId }.toMap()

    companion object {
        /** Codex r1 #1: una fila vieja sin sucursal (`venueId = ""`) sale con la que esté abierta; aquí, para medir su vigencia. */
        private const val SIN_SUCURSAL = "(sin sucursal)"

        /**
         * ¿Esta fila espera a una persona y puede no tener otra salida? De [sucursal] (o vieja, sin sucursal: el freno la cuenta como de
         * la abierta, Codex r1 #1), sin la revisión Q15 de un gerente y que no sea la
         * cortesía de $0 ya revisada; y además `FAILED` (con o sin motivo: «Reintentar» no sirve si el servidor la rechaza siempre) o
         * `EN_VUELO`/`PENDING` CON motivo Q15 (retenidas: la cola no las manda). Un `EN_VUELO` sin motivo es un cobro vivo o un «Cobro
         * interrumpido» con sus propios botones. Codex r2 #1: un `EN_VUELO` viejo SIN sucursal y sin motivo tampoco tiene salida (la
         * hoja de «Cobros interrumpidos» sólo lee los de la sucursal abierta y el barrido no lo toca), así que también califica. Codex r3
         * #2: un `PENDING` heredado SIN captura nunca sube (la cola lo retiene con `CAPTURA_SIN_EVIDENCIA` en cuanto hay red) y, sin red,
         * no se veía en ningún lado: también califica. PURA.
         */
        fun aplica(fila: PendingPaymentEntity, sucursal: String): Boolean =
            sucursal.isNotBlank() && (fila.venueId == sucursal || fila.venueId.isBlank()) && fila.q15ReviewedAt == null && !RevisionCanceladoCero.revisado(fila) &&
                when (fila.syncStatus) {
                    "FAILED" -> true
                    "EN_VUELO" -> fila.q15Reason != null || fila.venueId.isBlank()
                    "PENDING" -> fila.q15Reason != null ||
                        ConciliacionDelCobro.leerCaptura(fila.q15CaptureJson, fila.venueId, fila.id) == null
                    else -> false
                }
    }
}
