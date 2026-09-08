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
    ): Boolean = false

    /**
     * 🔴 Una LISTA, no una ranura: sin red se puede descartar un conteo y después otro, y con un
     * solo hueco el segundo pisaba al primero — que se quedaba `IN_PROGRESS` para siempre, sin un
     * aviso. En el orden en que se pidieron.
     */
    fun cancelacionesPendientes(): List<String>
    fun agregarCancelacionPendiente(countId: String): Boolean
    fun quitarCancelacionPendiente(countId: String): Boolean

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
        val estampado = borrador.copy(venueId = venueId)
        val ok = prefs.edit()
            .putString(llave("borrador", venueId), ConteoEnCurso.codificar(estampado))
            .commit()
        // 🔴 La caché sólo describe lo que HAY EN DISCO. Actualizarla con la escritura fallida
        // haría que `leer()` devolviera un borrador que no existe, y el siguiente arranque lo
        // encontraría sin la línea que el cajero cree guardada.
        if (ok) cache[venueId] = estampado
        else Log.e("📦", "❌ El borrador NO quedó en disco (commit=false)")
        ok
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
    ): Boolean = synchronized(lock) {
        val actual = leer(venueId) ?: return@synchronized false
        if (actual.countId != countId || actual.revision != expectedRevision) return@synchronized false
        val vigentes = actual.lineas.associate { it.id to (it.counted to it.countedAt) }
        val confirmadas = sellos.filter { (id, sello) -> vigentes[id] == sello }.keys
        guardar(
            venueId,
            actual.copy(
                revision = nuevaRevision,
                pendientesDeEnviar = actual.pendientesDeEnviar - confirmadas,
            ),
        )
    }

    /**
     * El borrador y la cancelación en el MISMO `Editor`, o sea en la misma escritura atómica.
     * Ver la interfaz para el porqué.
     */
    override fun descartarYEncolarCancelacion(countId: String): Boolean {
        val venue = secureStorage.venueId ?: return false
        val ids = cancelacionesPendientes()
        val editor = prefs.edit().remove(llave("borrador", venue))
        if (countId.isNotBlank() && countId !in ids) {
            editor.putString(llave("cancelar", venue), ConteoEnCurso.codificarCancelaciones(ids + countId))
        }
        val ok = editor.commit()
        if (ok) cache.remove(venue) else Log.e("📦", "❌ Descartar NO quedó en disco (commit=false)")
        return ok
    }

    /** Bajo la MISMA llave `cancelar.<venue>`, ahora como lista JSON. */
    override fun cancelacionesPendientes(): List<String> {
        val raw = llave("cancelar")?.let { prefs.getString(it, null) } ?: return emptyList()
        // La versión anterior guardaba el id A PELO. Descartarlo por no ser JSON perdería una
        // cancelación ya encolada en un aparato que se actualiza, y ese conteo se quedaría
        // abierto para siempre.
        return ConteoEnCurso.decodificarCancelaciones(raw) ?: listOf(raw).filter { it.isNotBlank() }
    }

    override fun agregarCancelacionPendiente(countId: String): Boolean {
        val k = llave("cancelar") ?: return false
        val ids = cancelacionesPendientes()
        // Ya encolada (o vacía) = nada que escribir: el disco YA dice lo que tiene que decir.
        if (countId.isBlank() || countId in ids) return true
        val ok = prefs.edit().putString(k, ConteoEnCurso.codificarCancelaciones(ids + countId)).commit()
        if (!ok) Log.e("📦", "❌ La cancelación NO quedó en disco (commit=false): $countId")
        return ok
    }

    override fun quitarCancelacionPendiente(countId: String): Boolean {
        val k = llave("cancelar") ?: return false
        val ids = cancelacionesPendientes()
        if (countId !in ids) return true
        val quedan = ids - countId
        val ok = prefs.edit().apply {
            if (quedan.isEmpty()) remove(k) else putString(k, ConteoEnCurso.codificarCancelaciones(quedan))
        }.commit()
        if (!ok) Log.e("📦", "❌ La cancelación NO se quitó del disco (commit=false): $countId")
        return ok
    }
}
