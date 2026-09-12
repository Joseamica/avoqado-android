package com.avoqado.pos.payment.data

import android.content.Context
import android.util.Log
import com.avoqado.pos.printing.data.AlmacenDeTexto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** En qué paso va una cancelación. El orden NO es libre: el borrado de la orden es el último. */
enum class FaseDeCancelacion {
    /** Pedirle a la terminal que se detenga. */
    PEDIR_CANCEL,

    /** Esperar a que CONSTE si cobró o no. */
    ESPERAR_DESENLACE,

    /** Consta que no se cobró: recién ahora se cancela la orden que creó este flujo. */
    BORRAR_ORDEN,

    /** La terminal sí cobró: queda el aviso hasta que alguien lo vea. */
    SE_COBRO,
    ;

    companion object {
        /**
         * 🔴 Una fase que este APK no conoce (la escribió una versión más nueva) se lee como
         * ESPERAR_DESENLACE: seguir preguntando es lo único que no puede borrar una cuenta ni
         * dar por terminado un cobro que quizá siga vivo.
         */
        fun de(texto: String?): FaseDeCancelacion =
            entries.firstOrNull { it.name == texto } ?: ESPERAR_DESENLACE
    }
}

/**
 * La intención de cancelar un cobro (y, si la creó este mismo flujo, su orden).
 *
 * Se escribe en DISCO antes de tocar la red: es lo que hace que sobreviva a que el proceso muera
 * entre el toque de «Cancelar» y el POST — el hueco por el que se quedaban órdenes abiertas con la
 * terminal quizá cobrando y nadie volviendo a intentarlo.
 */
@Serializable
data class IntencionDeCancelarCobro(
    /** Llave única: el `requestId` del cobro, o `orden:<orderId>` cuando nunca hubo cobro. */
    val id: String,
    val requestId: String? = null,
    val venueId: String,
    val terminalId: String? = null,
    val orderId: String? = null,
    /** La orden la creó ESTE flujo. Sin esto, cancelar el cobro de una mesa borraría su cuenta. */
    val borrarOrden: Boolean = false,
    val actorStaffId: String? = null,
    val montoCents: Int? = null,
    val orderNumber: String? = null,
    val creadaEn: Long,
    val fase: String = FaseDeCancelacion.PEDIR_CANCEL.name,
    val intentos: Int = 0,
    /** El último intento murió sin respuesta del servidor: la pantalla lo DICE. */
    val sinRed: Boolean = false,
    val proximoIntentoEn: Long = 0,
    val paymentId: String? = null,
    /** Lo que dijo el servidor la última vez, para la hoja de pendientes. */
    val motivo: String? = null,
) {
    val faseActual: FaseDeCancelacion get() = FaseDeCancelacion.de(fase)
}

/** Dónde viven las intenciones de cancelar entre arranques de la app. */
interface CancelacionesDeCobroStore {
    fun todas(): List<IntencionDeCancelarCobro>
    fun leer(id: String): IntencionDeCancelarCobro?

    /**
     * Registra una intención NUEVA.
     *
     * 🔴 Si ya existe una con el mismo id NO la toca (y devuelve `true`): un doble toque en
     * «Cancelar» no puede devolver a PEDIR_CANCEL una cancelación que ya está esperando desenlace.
     *
     * @return si de verdad quedó en disco. Un `false` NO se puede tratar como éxito.
     */
    fun registrar(intencion: IntencionDeCancelarCobro): Boolean

    /** Reemplaza una intención existente. `false` si no existe o si el disco no aceptó. */
    fun actualizar(intencion: IntencionDeCancelarCobro): Boolean

    fun quitar(id: String): Boolean
}

/**
 * Implementación sobre un [AlmacenDeTexto] (JSON en una sola llave, `commit()` síncrono).
 *
 * Va sobre SharedPreferences y no sobre Room a propósito: no necesita migración de esquema, y la
 * durabilidad que hace falta —que lo escrito esté en disco ANTES de que salga una petición— la da
 * `commit()`. Mismo patrón que `BorradorDeConteoPrefs` y `ComandasPendientesStore`.
 */
class CancelacionesDeCobroEnTexto(private val almacen: AlmacenDeTexto) : CancelacionesDeCobroStore {

    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    private data class Archivo(val v: Int = 1, val intenciones: List<IntencionDeCancelarCobro> = emptyList())

    private fun leerTodo(): List<IntencionDeCancelarCobro> {
        val texto = almacen.leer()?.takeIf { it.isNotBlank() } ?: return emptyList()
        return runCatching { json.decodeFromString(Archivo.serializer(), texto).intenciones }
            .getOrElse {
                // Un archivo ilegible no puede dejar la app atorada: se ignora y la siguiente
                // escritura lo reemplaza. Se registra porque perder una intención es perder una
                // cancelación pendiente.
                Log.w(TAG, "Intenciones de cancelación ilegibles, se ignoran: ${it.message}")
                emptyList()
            }
            .sortedBy { it.creadaEn }
    }

    private fun escribirTodo(lista: List<IntencionDeCancelarCobro>): Boolean =
        runCatching { almacen.escribir(json.encodeToString(Archivo.serializer(), Archivo(intenciones = lista))) }
            .onFailure { Log.w(TAG, "No se pudo guardar la cancelación: ${it.message}") }
            .getOrDefault(false)

    override fun todas(): List<IntencionDeCancelarCobro> = synchronized(lock) { leerTodo() }

    override fun leer(id: String): IntencionDeCancelarCobro? = synchronized(lock) { leerTodo().firstOrNull { it.id == id } }

    override fun registrar(intencion: IntencionDeCancelarCobro): Boolean = synchronized(lock) {
        val actuales = leerTodo()
        if (actuales.any { it.id == intencion.id }) return true
        escribirTodo(actuales + intencion)
    }

    override fun actualizar(intencion: IntencionDeCancelarCobro): Boolean = synchronized(lock) {
        val actuales = leerTodo()
        if (actuales.none { it.id == intencion.id }) return false
        escribirTodo(actuales.map { if (it.id == intencion.id) intencion else it })
    }

    override fun quitar(id: String): Boolean = synchronized(lock) {
        val actuales = leerTodo()
        if (actuales.none { it.id == id }) return true
        escribirTodo(actuales.filterNot { it.id == id })
    }

    private companion object {
        const val TAG = "CancelacionDeCobro"
    }
}

/** `SharedPreferences` con `commit()`: lo guardado está en disco antes de que salga una petición. */
class AlmacenEnPreferencias(
    context: Context,
    nombre: String,
    private val llave: String,
) : AlmacenDeTexto {
    private val prefs = context.getSharedPreferences(nombre, Context.MODE_PRIVATE)
    override fun leer(): String? = prefs.getString(llave, null)
    override fun escribir(texto: String): Boolean = prefs.edit().putString(llave, texto).commit()
    override fun borrar() { prefs.edit().remove(llave).commit() }
}
