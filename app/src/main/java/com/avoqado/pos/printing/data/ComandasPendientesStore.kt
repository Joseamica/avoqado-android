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

    /**
     * Una comanda pendiente en memoria: el aviso y la hora de su PRIMER fallo (`null` = no se sabe). La hora viaja con la
     * comanda para que la vigencia de 8 h cuente desde que no salió, no desde el último reintento.
     */
    private data class Entrada(val estado: EstadoDeComanda.NoSalio, val desde: Long?)

    /**
     * 🔴 D1 (hz:033d44ca:D1, 16-nov-2026): TODAS las comandas que no salieron, en el orden en que fallaron. Antes la
     * libreta era de UNA ranura: con la cocina apagada en hora pico, la segunda comanda pisaba a la primera y sólo la
     * última se reintentaba sola y conservaba su «Volver a imprimir»; las demás dependían de que el cajero hubiera visto
     * el aviso. Sólo se toca bajo [cerrojo].
     */
    private var entradas: List<Entrada> = emptyList()

    private val _pendientes = MutableStateFlow<List<EstadoDeComanda.NoSalio>>(emptyList())
    private val _pendiente = MutableStateFlow<EstadoDeComanda.NoSalio?>(null)

    /** Todas las comandas que no salieron de ESTA sucursal, de la más vieja a la más nueva. Las reintenta el reloj. */
    val pendientes: StateFlow<List<EstadoDeComanda.NoSalio>> = _pendientes.asStateFlow()

    /**
     * La comanda que no salió que se ENSEÑA — la más reciente, **UNA sola en toda la app**. Al resolverla («Ya la canté»
     * o porque salió), aparece la anterior.
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
     * 🔴 Un solo candado para todo lo que lee-y-escribe la libreta (Codex D11 r1 #1 y #2). Sin él, el reintento comprobaba
     * «sigue siendo mi comanda» y, antes de escribir, una ronda de mesa (otro hilo) guardaba la suya: el reintento la
     * pisaba y esa comanda desaparecía. Escribe un JSON chico: el candado dura milisegundos.
     */
    private val cerrojo = Any()

    /** Publica [entradas]: la lista para el reloj y la más reciente para el aviso. Bajo [cerrojo]. */
    private fun publicar(nuevas: List<Entrada>) {
        entradas = nuevas
        _pendientes.value = nuevas.map { it.estado }
        _pendiente.value = nuevas.lastOrNull()?.estado
    }

    /** Se llama UNA vez al abrir sesión en un venue — ver `AppState.startOfflineOutbox`. */
    fun cargar(venueIdActual: String?, ahora: Long = System.currentTimeMillis()) = synchronized(cerrojo) {
        venueCargado = venueIdActual
        publicar(delVenue(leerDisco(ahora), venueIdActual).map { Entrada(it.aviso(), it.guardadaEn) })
    }

    /**
     * La comanda que se enseña si sigue VIGENTE; ver [vigentes].
     */
    fun vigente(ahora: Long = System.currentTimeMillis()): EstadoDeComanda.NoSalio? = vigentes(ahora).lastOrNull()

    /**
     * Las comandas pendientes que siguen VIGENTES (8 h desde su primer fallo), de la más vieja a la más nueva; las
     * vencidas se sueltan aquí, de memoria y de disco.
     *
     * 🔴 Es lo que lee el reintento. `leer` sólo aplicaba la vigencia al abrir la app: con la tablet prendida nueve horas,
     * el reloj imprimía una comanda de ayer en cuanto volvía la impresora (Codex D11 r1 #3).
     */
    fun vigentes(ahora: Long = System.currentTimeMillis()): List<EstadoDeComanda.NoSalio> = synchronized(cerrojo) {
        val (siguen, vencidas) = entradas.partition { e -> e.desde == null || !vencida(e.desde, ahora) }
        if (vencidas.isNotEmpty()) {
            val fuera = vencidas.mapNotNull { it.estado.trabajo }
            if (fuera.isNotEmpty()) escribirDisco(leerDisco(ahora).filter { it.trabajo !in fuera })
            publicar(siguen)
        }
        siguen.map { it.estado }
    }

    @Serializable
    private data class Guardada(
        val estaciones: List<String>,
        val causa: String?,
        val orderNumber: String,
        val trabajo: TrabajoPendiente?,
        val guardadaEn: Long,
    ) {
        fun aviso() = EstadoDeComanda.NoSalio(estaciones = estaciones, causa = causa, orderNumber = orderNumber, trabajo = trabajo)
    }

    /**
     * La ÚNICA guarda de lo que entra aquí (revisión final de la 3.4, I-1 y m-8): los llamadores —mostrador y ronda—
     * pueden conservar la suya, pero la verdad vive en este almacén.
     *
     * 1. **Sin trabajo, nada.** Un `NoSalio` sin trabajo (todas las estaciones saltadas, o el camino legado) no se
     *    puede reimprimir; guardarlo no le da nada al reloj ni al botón. Devuelve `false` sin tocar memoria ni disco.
     * 2. **Venue ajeno, se rechaza entero.** Si `trabajo.venueId` no es el del último [cargar], NO se publica en
     *    memoria —el reloj de reintento y Cobrar, que leen [pendientes], lo mandarían a las impresoras de la sucursal
     *    equivocada— y tampoco se escribe a disco (igual que iOS). Lo único que se pierde es el reintento automático del
     *    trabajo viejo; su aviso ya se mostró. Devuelve `false`.
     * 3. **Nunca cargado** (sin venue conocido): como antes, se escribe y se publica.
     *
     * 🔴 D1: se AGREGA a la lista; ya no pisa la comanda de otra venta. El MISMO trabajo guardado otra vez no se duplica
     * (saldría dos veces) y conserva su lugar y la hora de su primer fallo. Lo suspendido de otra sucursal en disco se
     * conserva.
     *
     * @return `true` si el aviso quedó de verdad en el aparato. 🔴 Un `false` NO se puede tratar
     * como éxito: significa que si la app muere ahora, esa comanda desaparece.
     */
    fun guardar(estado: EstadoDeComanda.NoSalio, ahora: Long = System.currentTimeMillis()): Boolean = synchronized(cerrojo) {
        val trabajo = estado.trabajo ?: return false
        val cargado = venueCargado
        if (cargado != null && trabajo.venueId != cargado) return false
        val previa = entradas.firstOrNull { it.estado.trabajo == trabajo }
        val desde = previa?.desde ?: ahora
        return runCatching {
            val disco = leerDisco(ahora)
            val nueva = Guardada(estado.estaciones, estado.causa, estado.orderNumber, trabajo, desde)
            val i = disco.indexOfFirst { it.trabajo == trabajo }
            val escrito = escribirDisco(if (i >= 0) disco.toMutableList().also { it[i] = nueva } else disco + nueva)
            if (escrito) {
                val entrada = Entrada(estado, desde)
                publicar(if (previa != null) entradas.map { if (it === previa) entrada else it } else entradas + entrada)
            }
            escrito
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
     * - **Sólo si [anterior] sigue en la libreta**, comprobado y escrito bajo el mismo candado que [guardar]. Se cambia en
     *   su lugar; las comandas de otras ventas que entraron mientras se imprimía no se tocan.
     * - **Con trabajo reenviable**, se guarda la lista reducida, en memoria y en disco, **conservando la hora del
     *   primer fallo**: la vigencia de 8 h cuenta desde que la comanda no salió, no desde el último intento (si no, el
     *   reloj la renovaría cada minuto y una comanda de ayer seguiría viva).
     * - **Sin nada reenviable** (lo que faltaba salió, pero lo que queda ya no tiene impresora): el aviso se sigue
     *   VIENDO —la pantalla lo pinta sin «Volver a imprimir» y el cajero lo cierra con «Ya la canté»— pero se borra
     *   del disco y el reloj ya no tiene qué mandar. Reenviar el trabajo viejo sólo repetiría lo que ya salió.
     * - Si el disco no acepta la escritura, se QUITA el viejo del disco en vez de dejarlo: tras reiniciar, su «Volver a
     *   imprimir» repetiría lo que ya salió. Un ticket duplicado en la cocina es peor que el aviso que se pierde.
     *
     * @return `false` si no cambió nada ([anterior] ya no estaba) o si el disco no quedó al día.
     */
    fun reemplazar(
        anterior: TrabajoPendiente,
        resultado: EstadoDeComanda.NoSalio,
        ahora: Long = System.currentTimeMillis(),
    ): Boolean = synchronized(cerrojo) {
        val previa = entradas.firstOrNull { it.estado.trabajo == anterior } ?: return false
        val primerFallo = previa.desde ?: ahora
        val disco = leerDisco(ahora)
        val sinAnterior = disco.filter { it.trabajo != anterior }
        val trabajo = resultado.trabajo
        val escrito = if (trabajo == null) {
            escribirDisco(sinAnterior)
            true
        } else {
            val nueva = Guardada(resultado.estaciones, resultado.causa, resultado.orderNumber, trabajo, primerFallo)
            val i = disco.indexOfFirst { it.trabajo == anterior }
            val ok = escribirDisco(if (i >= 0) disco.toMutableList().also { it[i] = nueva } else disco + nueva)
            if (!ok) escribirDisco(sinAnterior)
            ok
        }
        val entrada = Entrada(resultado, primerFallo)
        publicar(entradas.map { if (it === previa) entrada else it })
        return escrito
    }

    /**
     * El reintento de [anterior] terminó sin nada pendiente (salió, o ya no lleva comanda): se suelta ESA comanda, y sólo
     * ésa. 🔴 Antes `limpiar()` a secas: si mientras se reimprimía falló OTRA venta, su aviso y su reintento se borraban
     * con el éxito de ésta (Codex D11 r1 #2).
     */
    fun soltarSi(anterior: TrabajoPendiente): Boolean = synchronized(cerrojo) {
        if (entradas.none { it.estado.trabajo == anterior }) return false
        escribirDisco(leerDisco(null).filter { it.trabajo != anterior })
        publicar(entradas.filter { it.estado.trabajo != anterior })
        true
    }

    /**
     * La comanda de la venta [orderNumber] por fin salió (el `Salio` del mostrador): se sueltan SÓLO las de ese folio.
     * 🔴 D1: antes era `limpiar()`, que con una sola ranura daba igual; con la lista, vaciaba las comandas de las demás
     * ventas — también la de un aviso que el cajero había ocultado sin resolver.
     */
    fun soltarPorFolio(orderNumber: String) = synchronized(cerrojo) {
        val fuera = entradas.filter { it.estado.orderNumber == orderNumber }
        if (fuera.isEmpty()) return@synchronized
        val trabajos = fuera.mapNotNull { it.estado.trabajo }
        if (trabajos.isNotEmpty()) escribirDisco(leerDisco(null).filter { it.trabajo !in trabajos })
        publicar(entradas - fuera.toSet())
    }

    /**
     * Devuelve el aviso guardado más reciente de esta sucursal, o `null` si no hay o si ya VENCIÓ.
     *
     * La vigencia no es cosmética: una comanda de ayer ya se resolvió de alguna forma —la
     * cocina la preparó, el cliente se fue, alguien la cantó— y ofrecer reimprimirla invita a
     * mandar comida que nadie pidió. [VIGENCIA_MS] cubre una jornada de mostrador sin llegar al
     * día siguiente.
     */
    fun leer(
        venueIdActual: String?,
        ahora: Long = System.currentTimeMillis(),
    ): EstadoDeComanda.NoSalio? = synchronized(cerrojo) { delVenue(leerDisco(ahora), venueIdActual).lastOrNull()?.aviso() }

    /**
     * 🔴 Sólo vuelve lo de ESTE negocio. La app cambia de sucursal y de sesión sin reinstalarse, y un aviso guardado en la
     * sucursal A reaparecía en la B: si las dos redes usan la misma IP privada —lo normal en un 192.168.1.x— el papel
     * podía salir FÍSICAMENTE en el local equivocado (P1 #5 de la 2ª auditoría de Codex, 2026-09-07). Sin venue no se
     * arriesga.
     *
     * Lo de otra sucursal se SUSPENDE, no se borra: se queda en disco y vuelve al regresar a su sucursal. BORRARLO haría
     * desaparecer una comanda pendiente real sólo porque alguien cambió de sucursal en la tablet (Codex, 2026-09-08).
     */
    private fun delVenue(disco: List<Guardada>, venueIdActual: String?): List<Guardada> =
        disco.filter { it.trabajo?.venueId != null && it.trabajo.venueId == venueIdActual }

    private fun vencida(desde: Long, ahora: Long) = ahora - desde > VIGENCIA_MS || ahora < desde

    /**
     * Todo lo que hay en disco, de todas las sucursales, sin lo vencido a [ahora] (que se borra aquí; `null` = no se
     * mira la vigencia, para quitar una comanda sin depender del reloj). Bajo [cerrojo].
     *
     * Lee la lista nueva (`[…]`) y también la hoja suelta de la versión anterior (`{…}`): al actualizar la app, la comanda
     * pendiente que ya estaba guardada no se pierde. Un formato viejo o corrupto no puede dejar la app atorada
     * arrastrándolo para siempre: se descarta y se sigue.
     */
    private fun leerDisco(ahora: Long?): List<Guardada> {
        val texto = runCatching { almacen.leer() }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        val todas = runCatching {
            if (texto.startsWith("[")) json.decodeFromString<List<Guardada>>(texto)
            else listOf(json.decodeFromString<Guardada>(texto))
        }.getOrElse {
            Log.w(TAG, "Comandas pendientes ilegibles, se descartan: ${it.message}")
            runCatching { almacen.borrar() }
            return emptyList()
        }
        val vigentes = if (ahora == null) todas else todas.filter { !vencida(it.guardadaEn, ahora) }
        if (vigentes.size != todas.size || !texto.startsWith("[")) escribirDisco(vigentes)
        return vigentes
    }

    /**
     * Escribe la lista entera; vacía, borra la llave. Bajo [cerrojo].
     *
     * Con más de [MAXIMO] comandas pendientes (una cocina apagada horas en hora pico) se queda con las más nuevas y lo
     * dice en el log: las viejas siguen en el aviso de esta vida de la app, pero no sobreviven a un reinicio. El tope sólo
     * protege el tamaño del archivo; la vigencia de 8 h las retira mucho antes en un día normal.
     */
    private fun escribirDisco(lista: List<Guardada>): Boolean {
        if (lista.isEmpty()) {
            runCatching { almacen.borrar() }
            return true
        }
        val acotada = if (lista.size > MAXIMO) {
            Log.w(TAG, "Hay ${lista.size} comandas pendientes; en el aparato se guardan las $MAXIMO más nuevas")
            lista.takeLast(MAXIMO)
        } else lista
        return runCatching { almacen.escribir(json.encodeToString(acotada)) }
            .onFailure { Log.w(TAG, "No se pudieron escribir las comandas pendientes: ${it.message}") }
            .getOrDefault(false)
    }

    /**
     * «Ya la canté» (I2 de la revisión de la Task 7): una PERSONA resolvió la comanda que se VE ([pendiente], la más
     * reciente). Se suelta ESA comanda —la anterior, si hay, pasa a verse— Y se cierran las entregas por WiFi del papel
     * de RESPALDO de esa comanda (venue + orden + plan exacto, como `cerrarPorPapel`): si no, el reloj de
     * `ReplayDeEntregasKds` la imprimiría sola en el siguiente tic o en la próxima apertura — el ticket duplicado que
     * esta libreta existe para evitar.
     *
     * 🔴 NO va dentro de [limpiar] ni de [soltarPorFolio]: los llama un `Salio`; cerrar ahí borraría la fila que recupera
     * la comanda de una ronda de mesa en la próxima apertura (pérdida, no duplicado).
     *
     * @return el cierre en curso (las pruebas lo esperan), o `null` si no había nada que cerrar.
     */
    fun yaLaCante(): Job? {
        val trabajo = synchronized(cerrojo) {
            val vista = entradas.lastOrNull() ?: return null
            vista.estado.trabajo?.let { t -> escribirDisco(leerDisco(null).filter { it.trabajo != t }) }
            publicar(entradas.dropLast(1))
            vista.estado.trabajo
        }
        val entregas = entregaPorWifi ?: return null
        val venueId = trabajo?.venueId ?: return null
        val respaldo = trabajo.config.stations.filter { it.respaldoLocal }.map { it.id }.toSet()
        val planes = trabajo.planes.filter { it.stationId in respaldo }
        if (planes.isEmpty()) return null
        return fondo.launch { entregas.cerrarPorPapel(venueId, trabajo.orderNumber, planes) }
    }

    /** Vacía la libreta entera, en memoria y en disco (todas las sucursales). Lo normal es soltar UNA: [soltarSi]. */
    fun limpiar() = synchronized(cerrojo) {
        runCatching { almacen.borrar() }
        publicar(emptyList())
    }

    private companion object {
        const val TAG = "ComandasPendientes"
        /** 8 horas: cubre un turno de mostrador y nunca llega al día siguiente. */
        const val VIGENCIA_MS = 8L * 60 * 60 * 1000
        /** Tope del archivo: ver [escribirDisco]. Una cocina apagada 8 h en hora pico no llega aquí en un local normal. */
        const val MAXIMO = 200
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
