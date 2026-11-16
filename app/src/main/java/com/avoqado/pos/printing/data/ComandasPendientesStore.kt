package com.avoqado.pos.printing.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    /**
     * Etapa 3 del KDS (3.5), ronda 3 de la Task 6: con qué se cierran las entregas por WiFi de una comanda que una
     * persona dio por resuelta ([yaLaCante]). Hilt SIEMPRE la inyecta. ponytail: opcional porque ~20 pruebas construyen
     * el almacén con un solo argumento; sin ella «Ya la canté» sólo suelta la libreta, como antes.
     */
    private val entregaPorWifi: EntregaPorWifi? = null,
) {
    /** Donde corre el cierre de [yaLaCante]: el toque es de la pantalla, el borrado es de la base. */
    private val fondo = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    private val _pendiente = MutableStateFlow<EstadoDeComanda.NoSalio?>(null)

    /**
     * La comanda que no salió — **UNA sola en toda la app**.
     *
     * 🔴 Antes cada `PaymentFlowViewModel` cargaba su propia copia del disco. Checkout y el cobro
     * de una mesa son destinos de navegación distintos, así que convivían dos: reimprimir desde
     * uno dejaba al otro ofreciendo imprimirla otra vez (P1 #6 de la 2ª auditoría de Codex).
     * Ahora todos observan esto y nadie guarda una copia.
     */
    val pendiente: StateFlow<EstadoDeComanda.NoSalio?> = _pendiente.asStateFlow()

    /** El venue del último [cargar]; `null` = nunca se cargó (sin venue conocido). Lo usa la guarda de [guardar]. */
    @Volatile private var venueCargado: String? = null

    /**
     * Cuándo falló por PRIMERA vez la comanda que está en [pendiente] (`null` = no se sabe o no hay). Con esto [vigente]
     * aplica las 8 h también con la app abierta, no sólo al leer del disco (Codex D11 r1 #3).
     */
    @Volatile private var desde: Long? = null

    /**
     * 🔴 Un solo candado para todo lo que lee-y-escribe la libreta (Codex D11 r1 #1 y #2). Sin él, el reintento comprobaba
     * «sigue siendo mi comanda» y, antes de escribir, una ronda de mesa (otro hilo) guardaba la suya: el reintento la
     * pisaba y esa comanda desaparecía. Es de UNA ranura y escribe un JSON chico: el candado dura milisegundos.
     */
    private val cerrojo = Any()

    /** Se llama UNA vez al abrir sesión en un venue — ver `AppState.startOfflineOutbox`. */
    fun cargar(venueIdActual: String?, ahora: Long = System.currentTimeMillis()) = synchronized(cerrojo) {
        venueCargado = venueIdActual
        val leida = leerConFecha(venueIdActual, ahora)
        _pendiente.value = leida?.first
        desde = leida?.second
    }

    /**
     * La comanda pendiente si sigue VIGENTE (8 h desde su primer fallo); si ya venció, se suelta y devuelve `null`.
     *
     * 🔴 Es lo que lee el reintento. `leer` sólo aplicaba la vigencia al abrir la app: con la tablet prendida nueve horas,
     * el reloj imprimía una comanda de ayer en cuanto volvía la impresora (Codex D11 r1 #3).
     */
    fun vigente(ahora: Long = System.currentTimeMillis()): EstadoDeComanda.NoSalio? = synchronized(cerrojo) {
        val actual = _pendiente.value ?: return null
        val d = desde
        if (d != null && (ahora - d > VIGENCIA_MS || ahora < d)) {
            limpiar()
            return null
        }
        actual
    }

    @Serializable
    private data class Guardada(
        val estaciones: List<String>,
        val causa: String?,
        val orderNumber: String,
        val trabajo: TrabajoPendiente?,
        val guardadaEn: Long,
    )

    /**
     * La ÚNICA guarda de lo que entra aquí (revisión final de la 3.4, I-1 y m-8): los llamadores —mostrador y ronda—
     * pueden conservar la suya, pero la verdad vive en este almacén.
     *
     * 1. **Sin trabajo, nada.** Un `NoSalio` sin trabajo (todas las estaciones saltadas, o el camino legado) no se
     *    puede reimprimir; guardarlo PISABA el pendiente recuperable de otra venta —su reintento y su «Volver a
     *    imprimir»— en memoria y en disco. Devuelve `false` sin tocar ninguno de los dos.
     * 2. **Venue ajeno, se rechaza entero.** Si `trabajo.venueId` no es el del último [cargar], NO se publica en
     *    memoria —el reloj de reintento y Cobrar, que leen [pendiente], lo mandarían a las impresoras de la sucursal
     *    equivocada— 🔴 **y tampoco se escribe a disco** (igual que iOS): el almacén es de UNA ranura
     *    (`PrefsComoAlmacen`, una sola clave `ultima_no_salio`, sin ranuras por venue), así que escribirlo pisaría el
     *    pendiente recuperable del venue actual en disco. Lo único que se pierde es el reintento automático del
     *    trabajo viejo; su aviso ya se mostró. Devuelve `false`.
     * 3. **Nunca cargado** (sin venue conocido): como antes, se escribe y se publica.
     *
     * @return `true` si el aviso quedó de verdad en el aparato. 🔴 Un `false` NO se puede tratar
     * como éxito: significa que si la app muere ahora, esa comanda desaparece.
     */
    fun guardar(estado: EstadoDeComanda.NoSalio, ahora: Long = System.currentTimeMillis()): Boolean = synchronized(cerrojo) {
        val trabajo = estado.trabajo ?: return false
        val cargado = venueCargado
        if (cargado != null && trabajo.venueId != cargado) return false
        return runCatching {
            val texto = json.encodeToString(
                Guardada(estado.estaciones, estado.causa, estado.orderNumber, trabajo, ahora),
            )
            almacen.escribir(texto).also {
                if (it) {
                    _pendiente.value = estado
                    desde = ahora
                }
            }
        }.onFailure { Log.w(TAG, "No se pudo guardar la comanda pendiente: ${it.message}") }
            .getOrDefault(false)
    }

    /**
     * Lo que dejó un reintento de [anterior] — el reloj o «Volver a imprimir» (D11, 16-nov-2026).
     *
     * 🔴 Existe porque el reintento sólo BORRABA cuando todo salía. Si Cocina salía y Barra volvía a fallar, el
     * `NoSalio` traía la lista ya reducida (sólo Barra), pero aquí seguía la vieja (Cocina + Barra): el siguiente tic
     * del reloj, o el siguiente toque, le mandaba a Cocina el mismo ticket otra vez — la cocina podía preparar dos
     * veces el mismo plato.
     *
     * - **Sólo si el pendiente sigue siendo [anterior]**, comprobado y escrito bajo el mismo candado que [guardar]. La
     *   libreta es de UNA ranura: si mientras se imprimía entró el fallo de OTRA venta, ése manda y no se pisa.
     * - **Con trabajo reenviable**, se guarda la lista reducida, en memoria y en disco, **conservando la hora del
     *   primer fallo**: la vigencia de 8 h cuenta desde que la comanda no salió, no desde el último intento (si no, el
     *   reloj la renovaría cada minuto y una comanda de ayer seguiría viva).
     * - **Sin nada reenviable** (lo que faltaba salió, pero lo que queda ya no tiene impresora): el aviso se sigue
     *   VIENDO —la pantalla lo pinta sin «Volver a imprimir» y el cajero lo cierra con «Ya la canté»— pero se borra
     *   del disco y el reloj ya no tiene qué mandar. Reenviar el trabajo viejo sólo repetiría lo que ya salió.
     * - Si el disco no acepta la escritura, se BORRA el viejo del disco en vez de dejarlo: tras reiniciar, su «Volver a
     *   imprimir» repetiría lo que ya salió. Un ticket duplicado en la cocina es peor que el aviso que se pierde.
     *
     * @return `false` si no cambió nada (otra venta en la ranura) o si el disco no quedó al día.
     */
    fun reemplazar(
        anterior: TrabajoPendiente,
        resultado: EstadoDeComanda.NoSalio,
        ahora: Long = System.currentTimeMillis(),
    ): Boolean = synchronized(cerrojo) {
        if (_pendiente.value?.trabajo != anterior) return false
        val trabajo = resultado.trabajo
        if (trabajo == null) {
            runCatching { almacen.borrar() }
            _pendiente.value = resultado
            return true
        }
        val primerFallo = desde ?: ahora
        val escrito = runCatching {
            almacen.escribir(
                json.encodeToString(Guardada(resultado.estaciones, resultado.causa, resultado.orderNumber, trabajo, primerFallo)),
            )
        }.onFailure { Log.w(TAG, "No se pudo guardar lo que falta de la comanda: ${it.message}") }.getOrDefault(false)
        if (!escrito) runCatching { almacen.borrar() }
        _pendiente.value = resultado
        desde = primerFallo
        return escrito
    }

    /**
     * El reintento de [anterior] terminó sin nada pendiente (salió, o ya no lleva comanda): se suelta la libreta, pero
     * SÓLO si sigue siendo [anterior]. 🔴 Antes `limpiar()` a secas: si mientras se reimprimía falló OTRA venta, su aviso
     * y su reintento se borraban con el éxito de ésta (Codex D11 r1 #2).
     */
    fun soltarSi(anterior: TrabajoPendiente): Boolean = synchronized(cerrojo) {
        if (_pendiente.value?.trabajo != anterior) return false
        limpiar()
        true
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
    ): EstadoDeComanda.NoSalio? = leerConFecha(venueIdActual, ahora)?.first

    /** [leer], más la hora del primer fallo — la que [cargar] guarda para que [vigente] la aplique. */
    private fun leerConFecha(venueIdActual: String?, ahora: Long): Pair<EstadoDeComanda.NoSalio, Long>? = synchronized(cerrojo) {
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
            // 🔴 SUSPENDE, no borra. Es de otra sucursal: no se enseña ni se puede reimprimir
            // aquí —con dos locales en un 192.168.1.x el papel saldría en el equivocado— pero
            // BORRARLO haría desaparecer una comanda pendiente real sólo porque alguien cambió
            // de sucursal en la tablet. Al volver a su sucursal, ahí sigue.
            // (Lo señaló Codex al revisar mi propio arreglo del venue, 2026-09-08.)
            return null
        }
        return EstadoDeComanda.NoSalio(
            estaciones = guardada.estaciones,
            causa = guardada.causa,
            orderNumber = guardada.orderNumber,
            trabajo = guardada.trabajo,
        ) to guardada.guardadaEn
    }

    /**
     * «Ya la canté» (I2 de la revisión de la Task 7): una PERSONA resolvió la comanda. Se suelta la libreta Y se cierran
     * las entregas por WiFi del papel de RESPALDO de esa comanda (venue + orden + plan exacto, como `cerrarPorPapel`): si
     * no, el reloj de `ReplayDeEntregasKds` la imprimiría sola en el siguiente tic o en la próxima apertura — el ticket
     * duplicado que esta libreta existe para evitar.
     *
     * 🔴 NO va dentro de [limpiar]: `limpiar` también lo llama un `Salio` de OTRA venta y el reloj de la libreta; cerrar
     * ahí borraría la fila que recupera la comanda de una ronda de mesa en la próxima apertura (pérdida, no duplicado).
     *
     * @return el cierre en curso (las pruebas lo esperan), o `null` si no había nada que cerrar.
     */
    fun yaLaCante(): Job? {
        val trabajo = synchronized(cerrojo) { _pendiente.value?.trabajo.also { limpiar() } }
        val entregas = entregaPorWifi ?: return null
        val venueId = trabajo?.venueId ?: return null
        val respaldo = trabajo.config.stations.filter { it.respaldoLocal }.map { it.id }.toSet()
        val planes = trabajo.planes.filter { it.stationId in respaldo }
        if (planes.isEmpty()) return null
        return fondo.launch { entregas.cerrarPorPapel(venueId, trabajo.orderNumber, planes) }
    }

    fun limpiar() = synchronized(cerrojo) {
        runCatching { almacen.borrar() }
        _pendiente.value = null
        desde = null
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
    /** @return `true` si de verdad quedó en disco — ver la nota de `commit()` vs `apply()`. */
    fun escribir(texto: String): Boolean
    fun borrar()
}

class PrefsComoAlmacen(context: Context) : AlmacenDeTexto {
    private val prefs = context.getSharedPreferences("comandas_pendientes", Context.MODE_PRIVATE)
    override fun leer(): String? = prefs.getString("ultima_no_salio", null)
    // 🔴 `commit()`, no `apply()`. `apply()` escribe en segundo plano y devuelve de inmediato:
    // si el proceso muere justo después, lo «guardado» puede no estar en disco — y este almacén
    // existe precisamente para sobrevivir a que la app muera. Android distingue las dos a
    // propósito. El coste es un write síncrono de un JSON pequeño, y quien llama ya está fuera
    // del camino del cobro. Devuelve si de verdad quedó guardado.
    override fun escribir(texto: String): Boolean = prefs.edit().putString("ultima_no_salio", texto).commit()
    override fun borrar() { prefs.edit().remove("ultima_no_salio").commit() }
}
