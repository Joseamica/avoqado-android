package com.avoqado.pos.printing.data

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * La comanda que no salió, guardada en el APARATO.
 *
 * 🔴 Existe por el P1 #7 de la auditoría de Codex (2026-09-07): el reintento vivía SÓLO en
 * memoria (variables locales de una corrutina en `viewModelScope`). Si la app moría a media
 * espera —se cierra, la mata el sistema, se reinicia la tablet— el trabajo desaparecía sin
 * dejar rastro, y la única recuperación (abrir el pedido en Pedidos y reimprimir) depende de un
 * GET al servidor: justo lo que NO hay cuando el local se queda sin internet.
 *
 * 🔴 **No reimprime sola al arrancar, y es deliberado.** Si la app murió, lo más probable es que
 * alguien ya le cantara el pedido a la cocina o lo escribiera a mano; sacar el papel horas
 * después sería el ticket duplicado que toda esta ronda existe para evitar. Lo que hace es
 * DEVOLVER EL AVISO: al abrir, el cajero ve «no salió la comanda del pedido X» con su botón, y
 * decide él. Es la misma frontera que en el resto del trabajo — la máquina insiste, la persona
 * duplica.
 */
@Singleton
class ComandasPendientesStore @Inject constructor(
    /**
     * 🔴 La dependencia es el ALMACÉN, no Android. Así el almacén entero se prueba en la JVM
     * —guardar, releer, vencer, descartar basura— con una implementación en memoria.
     * `SharedPreferences` es de Android y este repo no tiene Robolectric: si el `Context`
     * estuviera aquí, lo único comprobable sería la aritmética de la vigencia, y lo que de
     * verdad importa (que un aviso guardado vuelva COMPLETO, con su trabajo reenviable) se
     * quedaría sin una sola prueba.
     */
    private val almacen: AlmacenDeTexto,
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Guardada(
        val estaciones: List<String>,
        val causa: String?,
        val orderNumber: String,
        val trabajo: TrabajoPendiente?,
        val guardadaEn: Long,
    )

    fun guardar(estado: EstadoDeComanda.NoSalio, ahora: Long = System.currentTimeMillis()) {
        runCatching {
            val texto = json.encodeToString(
                Guardada(estado.estaciones, estado.causa, estado.orderNumber, estado.trabajo, ahora),
            )
            almacen.escribir(texto)
        }.onFailure { Log.w(TAG, "No se pudo guardar la comanda pendiente: ${it.message}") }
    }

    /**
     * Devuelve el aviso guardado, o `null` si no hay o si ya VENCIÓ.
     *
     * La vigencia no es cosmética: una comanda de ayer ya se resolvió de alguna forma —la
     * cocina la preparó, el cliente se fue, alguien la cantó— y ofrecer reimprimirla invita a
     * mandar comida que nadie pidió. [VIGENCIA_MS] cubre una jornada de mostrador sin llegar al
     * día siguiente.
     */
    fun leer(
        venueIdActual: String?,
        ahora: Long = System.currentTimeMillis(),
    ): EstadoDeComanda.NoSalio? {
        val texto = almacen.leer() ?: return null
        val guardada = runCatching { json.decodeFromString<Guardada>(texto) }.getOrElse {
            // Un formato viejo o corrupto no puede dejar la app atorada arrastrándolo para
            // siempre: se descarta y se sigue.
            Log.w(TAG, "Comanda pendiente ilegible, se descarta: ${it.message}")
            limpiar()
            return null
        }
        if (ahora - guardada.guardadaEn > VIGENCIA_MS || ahora < guardada.guardadaEn) {
            limpiar()
            return null
        }
        // 🔴 Sólo vuelve si es de ESTE negocio. La app cambia de sucursal y de sesión sin
        // reinstalarse, y un aviso guardado en la sucursal A reaparecía en la B: si las dos
        // redes usan la misma IP privada —lo normal en un 192.168.1.x— el papel podía salir
        // FÍSICAMENTE en el local equivocado (P1 #5 de la 2ª auditoría de Codex, 2026-09-07).
        // Sin venue no se arriesga: se descarta.
        if (guardada.trabajo?.venueId == null || guardada.trabajo.venueId != venueIdActual) {
            limpiar()
            return null
        }
        return EstadoDeComanda.NoSalio(
            estaciones = guardada.estaciones,
            causa = guardada.causa,
            orderNumber = guardada.orderNumber,
            trabajo = guardada.trabajo,
        )
    }

    fun limpiar() {
        runCatching { almacen.borrar() }
    }

    private companion object {
        const val TAG = "ComandasPendientes"
        /** 8 horas: cubre un turno de mostrador y nunca llega al día siguiente. */
        const val VIGENCIA_MS = 8L * 60 * 60 * 1000
    }
}

/** Dónde se guarda el texto. Ver el constructor de pruebas de [ComandasPendientesStore]. */
interface AlmacenDeTexto {
    fun leer(): String?
    fun escribir(texto: String)
    fun borrar()
}

class PrefsComoAlmacen(context: Context) : AlmacenDeTexto {
    private val prefs = context.getSharedPreferences("comandas_pendientes", Context.MODE_PRIVATE)
    override fun leer(): String? = prefs.getString("ultima_no_salio", null)
    override fun escribir(texto: String) = prefs.edit().putString("ultima_no_salio", texto).apply()
    override fun borrar() = prefs.edit().remove("ultima_no_salio").apply()
}
