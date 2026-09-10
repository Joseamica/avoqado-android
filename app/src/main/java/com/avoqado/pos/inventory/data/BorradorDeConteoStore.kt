package com.avoqado.pos.inventory.data

import android.content.Context
import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Un borrador por venue (un conteo en curso a la vez por aparato) + las cancelaciones que no
 * pudieron salir.
 *
 * 🔴 Toda mutación devuelve **si de verdad quedó en disco**. Antes devolvían `Unit` y quien
 * llamaba seguía adelante como si el dato estuviera a salvo; un `commit()` en `false` (disco
 * lleno, almacenamiento en modo lectura) dejaba la línea sólo en RAM y el aparato siguiendo su
 * camino normal hasta que el proceso muriera. Un almacén durable que no puede DECIR que falló no
 * es durable: es optimista.
 */
interface BorradorDeConteoStore {
    fun leer(): BorradorDeConteo?
    fun leer(venueId: String): BorradorDeConteo? = leer()?.takeIf { it.venueId == venueId }
    fun venuesConTrabajo(): List<String> = listOfNotNull(leer()?.venueId)
    fun guardar(borrador: BorradorDeConteo): Boolean
    fun guardar(venueId: String, borrador: BorradorDeConteo): Boolean =
        if (borrador.venueId.isBlank() || borrador.venueId == venueId) guardar(borrador.copy(venueId = venueId)) else false
    /** Aplica una edición de UI sobre la revisión viva bajo el mismo lock del store. */
    fun guardarEdicion(venueId: String, borrador: BorradorDeConteo): Boolean = guardar(venueId, borrador)
    fun borrar(): Boolean
    fun borrar(venueId: String): Boolean = if (leer()?.venueId == venueId) borrar() else false

    /**
     * Reconoce un PUT sobre la foto vigente en una sola mutación durable. La revisión avanza aunque
     * el cajero haya editado después; sólo se quitan los ids cuyo sello sigue siendo el enviado.
     */
    fun reconocerPut(
        venueId: String,
        countId: String,
        expectedRevision: Int,
        nuevaRevision: Int,
        sellos: Map<String, Pair<Double, String?>>,
        notaEnviada: String? = null,
        esFinal: Boolean = false,
    ): Boolean = false

    /**
     * Reconoce un PUT incremental cuya foto sólo alcanzó a vivir en RAM porque el guardado previo
     * falló. La revisión viene de un 2xx real; nunca se usa para final/confirm. La implementación
     * debe conservar una edición de disco posterior a [snapshot] y jamás retroceder una revisión.
     */
    fun reconocerPutDesdeMemoria(
        venueId: String,
        countId: String,
        expectedRevision: Int,
        nuevaRevision: Int,
        snapshot: BorradorDeConteo,
        durableAlSeleccionar: BorradorDeConteo?,
        sellos: Map<String, Pair<Double, String?>>,
    ): Boolean = false

    /** Reintenta persistir una revisión ya reconocida por el coordinador y conservada en RAM. */
    fun guardarSnapshotReconocido(
        venueId: String,
        countId: String,
        revisionReconocida: Int,
        borrador: BorradorDeConteo,
        durableAlSeleccionar: BorradorDeConteo?,
    ): Boolean = false

    fun guardarConflicto(venueId: String, countId: String, conflicto: ConflictoRevision): Boolean {
        val actual = leer(venueId) ?: return false
        if (actual.countId != countId) return false
        return guardar(venueId, actual.copy(conflictoRevision = conflicto))
    }

    /**
     * 🔴 Una LISTA, no una ranura: sin red se puede descartar un conteo y después otro, y con un
     * solo hueco el segundo pisaba al primero — que se quedaba `IN_PROGRESS` para siempre, sin un
     * aviso. En el orden en que se pidieron.
     */
    fun cancelacionesPendientes(): List<String>
    fun agregarCancelacionPendiente(countId: String): Boolean
    fun quitarCancelacionPendiente(countId: String): Boolean

    fun cancelacionesPendientes(venueId: String): List<CancelacionPendienteDeConteo> = emptyList()
    fun agregarCancelacionPendiente(cancelacion: CancelacionPendienteDeConteo): Boolean =
        agregarCancelacionPendiente(cancelacion.countId)
    fun quitarCancelacionPendiente(venueId: String, countId: String): Boolean =
        quitarCancelacionPendiente(countId)

    /**
     * Descartar, en UNA sola escritura: quita el borrador Y encola su cancelación.
     *
     * 🔴 Eran dos `commit()` seguidos, y entre uno y otro cabe la muerte del proceso: el borrador
     * ya borrado y la cancelación sin encolar dejaban el conteo `IN_PROGRESS` en el servidor sin
     * nada en el aparato que volviera a intentarlo — invisible para siempre, que es exactamente el
     * defecto que este proyecto está arreglando. Un `Editor` es atómico: o quedan los dos cambios
     * o no queda ninguno.
     *
     * @param countId el conteo a cancelar allá; vacío = el conteo no existe en el servidor (un
     *   cíclico sin crear) y sólo se quita el borrador.
     */
    fun descartarYEncolarCancelacion(countId: String): Boolean
    fun descartarYEncolarCancelacion(
        venueId: String,
        countId: String,
        expectedRevision: Int?,
    ): Boolean = descartarYEncolarCancelacion(countId)
}

/**
 * SharedPreferences planas, JSON, llaveadas por venue.
 *
 * 🔴 `commit()`, NO `apply()`: `apply()` escribe a disco de forma asíncrona y si el proceso
 * muere entre teclear y el flush, la línea se pierde — que es justo lo que este almacén
 * existe para impedir. Mismo criterio que `SecureStorage.setPendingDrawerOpsJson`.
 */
@Singleton
class BorradorDeConteoPrefs @Inject constructor(
    @ApplicationContext context: Context,
    private val secureStorage: SecureStorage,
) : BorradorDeConteoStore {
    private val prefs = context.getSharedPreferences("conteo_en_curso", Context.MODE_PRIVATE)

    /**
     * El último borrador escrito o leído. `guardar()` se llama después de CADA cantidad que
     * teclea el cajero y el consumidor relee justo después: sin esta copia, cada tecla pagaría
     * además un `getString` y un decode del conteo ENTERO, encima del `commit()` con fsync que
     * la durabilidad sí exige. No toca ninguna garantía: el disco se escribe igual, síncrono.
     *
     * La llave es el `venueId` DENTRO del borrador (`guardar` lo estampa), así que al cambiar
     * de sucursal la caché deja de encontrarse sola y se vuelve al disco.
     * `@Volatile` porque leer y guardar pueden venir de hilos distintos.
     */
    private val lock = Any()
    private val cache = mutableMapOf<String, BorradorDeConteo>()

    // Un solo sitio construye la llave. La variante con `venue` explícito es la que usan las
    // ESCRITURAS: garantiza que la llave y el venue estampado dentro del JSON salen del mismo
    // valor, aunque `secureStorage.venueId` cambie entre dos lecturas.
    private fun llave(prefijo: String, venue: String): String = "$prefijo.$venue"

    private fun llave(prefijo: String): String? = secureStorage.venueId?.let { llave(prefijo, it) }

    override fun leer(): BorradorDeConteo? = secureStorage.venueId?.let(::leer)

    override fun leer(venueId: String): BorradorDeConteo? = synchronized(lock) {
        cache[venueId]?.let { return@synchronized it }
        val b = ConteoEnCurso.decodificar(
            prefs.getString(llave("borrador", venueId), null),
        ) ?: return@synchronized null
        // Un borrador de otro venue bajo esta llave sería un error de programación: no se enseña.
        b.takeIf { it.venueId == venueId }?.also { cache[venueId] = it }
    }

    override fun venuesConTrabajo(): List<String> = synchronized(lock) {
        val desdeDisco = prefs.all.keys.mapNotNull { key ->
            when {
                key.startsWith("borrador.") -> key.removePrefix("borrador.")
                key.startsWith("cancelar.") -> key.removePrefix("cancelar.")
                else -> null
            }
        }
        (desdeDisco + cache.keys).filter { it.isNotBlank() }.distinct().sorted()
    }

    /**
     * El venue lo pone el ALMACÉN, no quien llama: el ViewModel puede no conocerlo todavía y
     * manda `""`. Guardar ese `""` dentro del JSON dejaría un borrador que `leer()` descarta
     * para siempre — trabajo perdido con el archivo intacto en disco, que es peor que no
     * guardarlo. Sin venue no se escribe nada.
     *
     * 🔴 Pero estampar el venue actual sobre un borrador que YA dice ser de OTRA sucursal sería
     * adoptar trabajo ajeno: quien cambia de sucursal a media captura acabaría con lo contado
     * en A guardado como conteo de B. Ese caso tampoco se escribe.
     */
    override fun guardar(borrador: BorradorDeConteo): Boolean {
        val venue = secureStorage.venueId ?: return false
        if (borrador.venueId.isNotBlank() && borrador.venueId != venue) {
            Log.w("📦", "⚠️ Borrador de otra sucursal (${borrador.venueId} != $venue): no se guarda")
            return false
        }
        return guardar(venue, borrador)
    }

    override fun guardar(venueId: String, borrador: BorradorDeConteo): Boolean = synchronized(lock) {
        if (venueId.isBlank() || (borrador.venueId.isNotBlank() && borrador.venueId != venueId)) {
            Log.w("📦", "⚠️ Borrador de otra sucursal (${borrador.venueId} != $venueId): no se guarda")
            return@synchronized false
        }
        val anterior = leer(venueId)
        val mismoConteo = anterior?.countId == borrador.countId
        if (mismoConteo && anterior?.revision != null && borrador.revision != null &&
            anterior.revision != borrador.revision
        ) {
            Log.w("📦", "⚠️ Revisión stale ${borrador.revision}; vigente ${anterior.revision}: no se guarda")
            return@synchronized false
        }
        val huboEdicion = anterior?.revisionConPutFinalConfirmado != null && (
            anterior.countId != borrador.countId ||
                anterior.lineas != borrador.lineas ||
                anterior.nota != borrador.nota ||
                borrador.pendientesDeEnviar.isNotEmpty() ||
                ConteoEnCurso.notaPendienteDeEnviar(borrador)
            )
        val estampado = borrador.copy(
            venueId = venueId,
            // Los escritores viejos del VM no pueden volver atrás un ACK que llegó durante un await.
            revision = borrador.revision ?: anterior?.revision?.takeIf { mismoConteo },
            conflictoRevision = borrador.conflictoRevision
                ?: anterior?.conflictoRevision?.takeIf { mismoConteo },
            revisionConPutFinalConfirmado = when {
                huboEdicion -> null
                borrador.revisionConPutFinalConfirmado != null -> borrador.revisionConPutFinalConfirmado
                mismoConteo -> anterior?.revisionConPutFinalConfirmado
                else -> null
            },
        )
        escribir(venueId, estampado)
    }

    override fun guardarEdicion(venueId: String, borrador: BorradorDeConteo): Boolean = synchronized(lock) {
        val actual = leer(venueId)
        if (actual == null || actual.countId != borrador.countId) return@synchronized guardar(venueId, borrador)
        val anteriores = actual.lineas.associateBy { it.id }
        val cambiadas = borrador.lineas.filter { linea ->
            val previa = anteriores[linea.id]
            previa == null || previa.counted != linea.counted || previa.countedAt != linea.countedAt
        }.map { it.id }.filter { it.isNotBlank() }.toSet()
        val estructuraCambio = actual.lineas.map { it.id } != borrador.lineas.map { it.id }
        val notaCambio = actual.nota != borrador.nota
        val huboEdicion = cambiadas.isNotEmpty() || estructuraCambio || notaCambio
        val pendientes = if (borrador.countId == null) emptySet()
        else actual.pendientesDeEnviar + cambiadas
        escribir(
            venueId,
            borrador.copy(
                venueId = venueId,
                revision = actual.revision,
                pendientesDeEnviar = pendientes,
                notaPendienteDeEnviar = if (notaCambio) true else actual.notaPendienteDeEnviar,
                conflictoRevision = actual.conflictoRevision,
                revisionConPutFinalConfirmado = actual.revisionConPutFinalConfirmado.takeUnless { huboEdicion },
            ),
        )
    }

    private fun escribir(venueId: String, borrador: BorradorDeConteo): Boolean {
        val ok = prefs.edit()
            .putString(llave("borrador", venueId), ConteoEnCurso.codificar(borrador))
            .commit()
        if (ok) cache[venueId] = borrador
        else Log.e("📦", "❌ El borrador NO quedó en disco (commit=false)")
        return ok
    }

    override fun borrar(): Boolean = secureStorage.venueId?.let(::borrar) ?: false

    override fun borrar(venueId: String): Boolean = synchronized(lock) {
        val k = llave("borrador", venueId)
        val ok = prefs.edit().remove(k).commit()
        // Después del disco: si la escritura revienta, la caché sigue describiendo lo que hay.
        if (ok) cache.remove(venueId) else Log.e("📦", "❌ El borrador NO se borró del disco (commit=false)")
        ok
    }

    override fun reconocerPut(
        venueId: String,
        countId: String,
        expectedRevision: Int,
        nuevaRevision: Int,
        sellos: Map<String, Pair<Double, String?>>,
        notaEnviada: String?,
        esFinal: Boolean,
    ): Boolean = synchronized(lock) {
        val actual = leer(venueId) ?: return@synchronized false
        if (actual.countId != countId || actual.revision != expectedRevision) return@synchronized false
        val vigentes = actual.lineas.associate { it.id to (it.counted to it.countedAt) }
        val confirmadas = sellos.filter { (id, sello) -> vigentes[id] == sello }.keys
        val pendientes = actual.pendientesDeEnviar - confirmadas
        val notaReconocida = notaEnviada != null &&
            ConteoEnCurso.notaPendienteDeEnviar(actual) &&
            actual.nota == notaEnviada
        val notaPendiente = if (notaReconocida) false else ConteoEnCurso.notaPendienteDeEnviar(actual)
        escribir(
            venueId,
            actual.copy(
                revision = nuevaRevision,
                pendientesDeEnviar = pendientes,
                notaPendienteDeEnviar = notaPendiente,
                conflictoRevision = null,
                revisionConPutFinalConfirmado = nuevaRevision.takeIf {
                    esFinal && pendientes.isEmpty() && !notaPendiente
                },
            ),
        )
    }

    override fun reconocerPutDesdeMemoria(
        venueId: String,
        countId: String,
        expectedRevision: Int,
        nuevaRevision: Int,
        snapshot: BorradorDeConteo,
        durableAlSeleccionar: BorradorDeConteo?,
        sellos: Map<String, Pair<Double, String?>>,
    ): Boolean = synchronized(lock) {
        if (snapshot.venueId != venueId || snapshot.countId != countId || snapshot.revision != expectedRevision) {
            return@synchronized false
        }
        val disco = leer(venueId)
        if (disco != null && disco.countId != countId) return@synchronized false
        val revisionDisco = disco?.revision
        if (disco != null && (revisionDisco == null || revisionDisco > nuevaRevision)) return@synchronized false

        // La igualdad con la foto durable capturada antes del PUT distingue el disco viejo de una
        // edición hecha durante el viaje incluso si ambas escrituras caen en el mismo milisegundo.
        // Si no cambió, gana RAM: contiene justo la edición aceptada por el servidor.
        val base = if (disco != durableAlSeleccionar && disco != null) disco else snapshot
        val vigentes = base.lineas.associate { it.id to (it.counted to it.countedAt) }
        val confirmadas = sellos.filter { (id, sello) -> vigentes[id] == sello }.keys
        escribir(
            venueId,
            base.copy(
                venueId = venueId,
                countId = countId,
                revision = nuevaRevision,
                pendientesDeEnviar = base.pendientesDeEnviar - confirmadas,
                conflictoRevision = null,
                revisionConPutFinalConfirmado = null,
            ),
        )
    }

    override fun guardarSnapshotReconocido(
        venueId: String,
        countId: String,
        revisionReconocida: Int,
        borrador: BorradorDeConteo,
        durableAlSeleccionar: BorradorDeConteo?,
    ): Boolean = synchronized(lock) {
        if (borrador.venueId != venueId || borrador.countId != countId || borrador.revision != revisionReconocida) {
            return@synchronized false
        }
        val disco = leer(venueId)
        if (disco != null && disco.countId != countId) return@synchronized false
        if (disco?.revision == null && disco != null) return@synchronized false
        if ((disco?.revision ?: Int.MIN_VALUE) > revisionReconocida) return@synchronized false
        val base = if (disco != durableAlSeleccionar && disco != null) {
            disco.copy(revision = revisionReconocida)
        } else {
            borrador
        }
        escribir(
            venueId,
            base.copy(
                venueId = venueId,
                countId = countId,
                revision = revisionReconocida,
                conflictoRevision = borrador.conflictoRevision,
                // El snapshot reconocido manda sobre un stage viejo: cualquier incremental lo
                // invalida, incluso si una edición concurrente de disco aportó el contenido base.
                revisionConPutFinalConfirmado = borrador.revisionConPutFinalConfirmado,
            ),
        )
    }

    /**
     * El borrador y la cancelación en el MISMO `Editor`, o sea en la misma escritura atómica.
     * Ver la interfaz para el porqué.
     */
    override fun descartarYEncolarCancelacion(countId: String): Boolean {
        val venue = secureStorage.venueId ?: return false
        return descartarYEncolarCancelacion(venue, countId, leer(venue)?.revision)
    }

    override fun descartarYEncolarCancelacion(
        venueId: String,
        countId: String,
        expectedRevision: Int?,
    ): Boolean = synchronized(lock) {
        if (venueId.isBlank()) return@synchronized false
        val actuales = cancelacionesPendientes(venueId)
        val editor = prefs.edit().remove(llave("borrador", venueId))
        if (countId.isNotBlank() && actuales.none { it.countId == countId }) {
            val conflicto = if (expectedRevision == null) conflictoSinRevision(venueId, countId) else null
            editor.putString(
                llave("cancelar", venueId),
                ConteoEnCurso.codificarCancelacionesConRevision(
                    actuales + CancelacionPendienteDeConteo(
                        venueId = venueId,
                        countId = countId,
                        expectedRevision = expectedRevision,
                        conflictoRevision = conflicto,
                    ),
                ),
            )
        }
        val ok = editor.commit()
        if (ok) cache.remove(venueId) else Log.e("📦", "❌ Descartar NO quedó en disco (commit=false)")
        ok
    }

    override fun cancelacionesPendientes(): List<String> =
        secureStorage.venueId?.let { cancelacionesPendientes(it).map(CancelacionPendienteDeConteo::countId) }
            .orEmpty()

    override fun cancelacionesPendientes(venueId: String): List<CancelacionPendienteDeConteo> = synchronized(lock) {
        val raw = prefs.getString(llave("cancelar", venueId), null) ?: return@synchronized emptyList()
        ConteoEnCurso.decodificarCancelacionesConRevision(raw) ?: run {
            val ids = ConteoEnCurso.decodificarCancelaciones(raw)
                ?: listOf(raw).filter { it.isNotBlank() }
            ids.map { id ->
                CancelacionPendienteDeConteo(
                    venueId = venueId,
                    countId = id,
                    conflictoRevision = conflictoSinRevision(venueId, id),
                )
            }
        }
    }

    override fun agregarCancelacionPendiente(countId: String): Boolean {
        val venue = secureStorage.venueId ?: return false
        return agregarCancelacionPendiente(
            CancelacionPendienteDeConteo(
                venueId = venue,
                countId = countId,
                conflictoRevision = conflictoSinRevision(venue, countId),
            ),
        )
    }

    override fun agregarCancelacionPendiente(cancelacion: CancelacionPendienteDeConteo): Boolean = synchronized(lock) {
        if (cancelacion.venueId.isBlank() || cancelacion.countId.isBlank()) return@synchronized true
        val actuales = cancelacionesPendientes(cancelacion.venueId)
        val existente = actuales.firstOrNull { it.countId == cancelacion.countId }
        if (existente == cancelacion) return@synchronized true
        val nuevos = actuales.filterNot { it.countId == cancelacion.countId } + cancelacion
        val ok = prefs.edit().putString(
            llave("cancelar", cancelacion.venueId),
            ConteoEnCurso.codificarCancelacionesConRevision(nuevos),
        ).commit()
        if (!ok) Log.e("📦", "❌ La cancelación NO quedó en disco (commit=false): ${cancelacion.countId}")
        ok
    }

    override fun quitarCancelacionPendiente(countId: String): Boolean {
        val venue = secureStorage.venueId ?: return false
        return quitarCancelacionPendiente(venue, countId)
    }

    override fun quitarCancelacionPendiente(venueId: String, countId: String): Boolean = synchronized(lock) {
        val k = llave("cancelar", venueId)
        val actuales = cancelacionesPendientes(venueId)
        if (actuales.none { it.countId == countId }) return@synchronized true
        val quedan = actuales.filterNot { it.countId == countId }
        val ok = prefs.edit().apply {
            if (quedan.isEmpty()) remove(k)
            else putString(k, ConteoEnCurso.codificarCancelacionesConRevision(quedan))
        }.commit()
        if (!ok) Log.e("📦", "❌ La cancelación NO se quitó del disco (commit=false): $countId")
        ok
    }

    private fun conflictoSinRevision(venueId: String, countId: String) = ConflictoRevision(
        code = ConteoEnCurso.CODIGO_REVISION_DESCONOCIDA,
        message = ConteoEnCurso.REVISION_DESCONOCIDA,
        venueId = venueId,
        countId = countId,
    )
}
