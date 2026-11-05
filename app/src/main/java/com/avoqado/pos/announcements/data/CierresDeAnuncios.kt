package com.avoqado.pos.announcements.data

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * «Al cerrarla no vuelve a salir», también sin red (codex r1 #12).
 *
 * El cierre se guarda en el aparato ANTES de tocar la red, por persona (codex r2 #8): la ventana no enseña lo que esa
 * persona ya cerró aunque el servidor todavía no se entere. Con un 2xx queda `sincronizado` y la marca SE CONSERVA
 * (codex r2 #9): si el servidor tarda en reflejarlo, la ventana sigue sin salir. Tope: los últimos [TOPE].
 */
@Serializable
data class Cierre(val id: String, val sincronizado: Boolean)

/** ¿La respuesta pedida con la sesión [pedida] sigue siendo de quien está adentro ([actual])? (codex r5 #3, r6 #1) */
fun respuestaVigente(pedida: Long, actual: Long): Boolean = pedida == actual

fun ventanaVisible(pendienteId: String?, cerrados: Set<String>): Boolean = pendienteId != null && pendienteId !in cerrados

/** Los cierres de [staffId] que el servidor no ha confirmado, en el orden en que se cerraron. Nunca los de otra persona. */
fun pendientesPorMandar(staffId: String, cierres: Map<String, List<Cierre>>): List<String> =
    cierres[staffId].orEmpty().filterNot { it.sincronizado }.map { it.id }

/** Dónde viven los cierres, por persona. En pruebas, uno en memoria. */
interface AlmacenDeCierres {
    fun todos(): Map<String, List<Cierre>>
    /** Escribe en disco antes de volver (`commit`): lo que sigue puede ser la red. */
    fun escribir(staffId: String, cierres: List<Cierre>): Boolean
}

private class AlmacenEnPreferencias(private val prefs: SharedPreferences) : AlmacenDeCierres {
    private val serializer = ListSerializer(Cierre.serializer())

    override fun todos(): Map<String, List<Cierre>> {
        val resultado = HashMap<String, List<Cierre>>()
        for ((clave, valor) in prefs.all) {
            if (!clave.startsWith(PREFIJO)) continue
            // Un JSON roto se lee como lista vacía: nunca tumba la app por un anuncio.
            resultado[clave.removePrefix(PREFIJO)] =
                runCatching { Json.decodeFromString(serializer, valor as String) }.getOrDefault(emptyList())
        }
        return resultado
    }

    override fun escribir(staffId: String, cierres: List<Cierre>): Boolean =
        prefs.edit().putString(PREFIJO + staffId, Json.encodeToString(serializer, cierres)).commit()

    companion object { const val PREFIJO = "cerrados:" }
}

@Singleton
class CierresDeAnuncios(private val almacen: AlmacenDeCierres) {

    @Inject
    constructor(@ApplicationContext context: Context) :
        this(AlmacenEnPreferencias(context.getSharedPreferences("avoqado_anuncios_cerrados", Context.MODE_PRIVATE)))

    @Synchronized
    fun cerrados(staffId: String): Set<String> = almacen.todos()[staffId].orEmpty().mapTo(HashSet()) { it.id }

    @Synchronized
    fun pendientes(staffId: String): List<String> = pendientesPorMandar(staffId, almacen.todos())

    /** Guarda el cierre como pendiente. Si ya estaba, no cambia (un sincronizado no vuelve a pendiente). */
    @Synchronized
    fun marcar(staffId: String, id: String) {
        val actuales = almacen.todos()[staffId].orEmpty()
        if (actuales.any { it.id == id }) return
        almacen.escribir(staffId, (actuales + Cierre(id, sincronizado = false)).takeLast(TOPE))
    }

    @Synchronized
    fun marcarSincronizado(staffId: String, id: String) {
        val actuales = almacen.todos()[staffId].orEmpty()
        almacen.escribir(staffId, actuales.map { if (it.id == id) it.copy(sincronizado = true) else it })
    }

    private companion object { const val TOPE = 100 }
}
