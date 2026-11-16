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
     * Una comanda pendiente: el aviso, la hora de su PRIMER fallo (`null` = no se sabe; la vigencia de 8 h cuenta desde
     * ahí, no desde el último reintento), su sucursal y si nació en esta vida de la app ([aqui]: sin sucursal cargada sólo
     * se enseña lo de aquí, como antes).
     */
    private data class Entrada(
        val estado: EstadoDeComanda.NoSalio,
        val desde: Long?,
        val venueId: String?,
        val aqui: Boolean,
    )

    /**
     * 🔴 D1 (hz:033d44ca:D1, 16-nov-2026): TODAS las comandas que no salieron, de todas las sucursales, en el orden en que
     * fallaron. Antes la libreta era de UNA ranura: con la cocina apagada en hora pico, la segunda comanda pisaba a la
     * primera y sólo la última se reintentaba sola y conservaba su «Volver a imprimir».
     *
     * 🔴 **La MEMORIA es la verdad; el disco es su copia** (Codex D1 r2 #1, #2 y #4). Se lee del disco una vez, la primera
     * vez que hace falta, y desde entonces cada cambio se aplica aquí y se copia entera al disco. Si el disco no acepta la
     * escritura (lleno), nada se pierde ni se revive mientras la app viva: la comanda sigue aquí, con su aviso y su
     * reintento, y [vigentes] —el reloj, cada minuto— vuelve a copiar en cuanto el disco responde. Lo único que queda al
     * azar es que la app MUERA con el disco sin responder: al abrir vuelve la última copia buena, que puede traer una ya
     * resuelta (duplicado), nunca perder una pendiente — la misma regla que el resto de lo sin red.
     *
     * Sólo se toca bajo [cerrojo].
     */
    private var entradas: List<Entrada> = emptyList()
    private var leidoDelDisco = false
    /** La última copia al disco falló: [vigentes] la vuelve a intentar. */
    private var discoAtrasado = false

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

    /**
     * 🔴 Sólo se enseña y se reintenta lo de ESTE negocio. La app cambia de sucursal y de sesión sin reinstalarse, y un
     * aviso guardado en la sucursal A reaparecía en la B: si las dos redes usan la misma IP privada —lo normal en un
     * 192.168.1.x— el papel podía salir FÍSICAMENTE en el local equivocado (P1 #5 de la 2ª auditoría de Codex). Lo de
     * otra sucursal se SUSPENDE, no se borra: al volver a su sucursal, ahí sigue (Codex, 2026-09-08).
     */
    private fun visible(e: Entrada): Boolean {
        val cargado = venueCargado
        return if (cargado != null) e.venueId == cargado else e.aqui
    }

    /** Lee el disco UNA vez (la primera operación). Lo vencido a [ahora] no entra (`null` = no se mira el reloj). */
    private fun asegurarLeido(ahora: Long?) {
        if (leidoDelDisco) return
        leidoDelDisco = true
        val delDisco = leerDisco(ahora).map { Entrada(it.aviso(), it.guardadaEn, it.trabajo?.venueId, aqui = false) }
        entradas = delDisco.filter { d -> entradas.none { it.estado.trabajo == d.estado.trabajo } } + entradas
    }

    /** Suelta lo vencido (8 h desde su primer fallo, o un reloj que se fue hacia atrás). @return si quitó algo. */
    private fun expirar(ahora: Long): Boolean {
        val siguen = entradas.filter { it.desde == null || !vencida(it.desde, ahora) }
        if (siguen.size == entradas.size) return false
        entradas = siguen
        return true
    }

    /** Copia al disco TODO lo que tiene trabajo reenviable (todas las sucursales). Bajo [cerrojo]. */
    private fun persistir(ahora: Long = System.currentTimeMillis()): Boolean {
        val lista = entradas.mapNotNull { e ->
            e.estado.trabajo?.let { Guardada(e.estado.estaciones, e.estado.causa, e.estado.orderNumber, it, e.desde ?: ahora) }
        }
        val ok = escribirDisco(lista)
        if (!ok) Log.w(TAG, "El aparato no aceptó la copia de las comandas pendientes: siguen en memoria y se reintenta")
        discoAtrasado = !ok
        return ok
    }

    /** Publica lo visible: la lista para el reloj y la más reciente para el aviso. Bajo [cerrojo]. */
    private fun publicar() {
        val visibles = entradas.filter(::visible).map { it.estado }
        _pendientes.value = visibles
        _pendiente.value = visibles.lastOrNull()
    }

    /** Se llama al abrir sesión en un venue (y al cambiar de sucursal) — ver `AppState.startOfflineOutbox`. */
    fun cargar(venueIdActual: String?, ahora: Long = System.currentTimeMillis()) = synchronized(cerrojo) {
        asegurarLeido(ahora)
        venueCargado = venueIdActual
        if (expirar(ahora) || discoAtrasado) persistir(ahora)
        publicar()
    }

    /**
     * La comanda que se enseña si sigue VIGENTE; ver [vigentes].
     */
    fun vigente(ahora: Long = System.currentTimeMillis()): EstadoDeComanda.NoSalio? = vigentes(ahora).lastOrNull()

    /**
     * Las comandas pendientes de esta sucursal que siguen VIGENTES (8 h desde su primer fallo), de la más vieja a la más
     * nueva; las vencidas se sueltan aquí. Si la última copia al disco falló, se vuelve a intentar aquí (lo llama el
     * reloj cada minuto).
     *
     * 🔴 Es lo que lee el reintento. `leer` sólo aplicaba la vigencia al abrir la app: con la tablet prendida nueve horas,
     * el reloj imprimía una comanda de ayer en cuanto volvía la impresora (Codex D11 r1 #3).
     */
    fun vigentes(ahora: Long = System.currentTimeMillis()): List<EstadoDeComanda.NoSalio> = synchronized(cerrojo) {
        asegurarLeido(ahora)
        if (expirar(ahora) || discoAtrasado) persistir(ahora)
        publicar()
        _pendientes.value
    }

    /**
     * ¿Se puede mandar YA este trabajo? Sigue en la libreta, no venció y es de la sucursal cargada. El reloj lo pregunta
     * justo antes de cada comanda, antes y DESPUÉS de refrescar la config (Codex D1 r1 #3, #5 y #6): en una pasada con
     * varias comandas, o mientras se espera el refresco, una pudo vencer, resolverse con «Ya la canté» o quedarse de otra
     * sucursal porque cambiaron de sucursal en la tablet. Una vencida se suelta aquí.
     */
    fun sigueVigente(trabajo: TrabajoPendiente, ahora: Long = System.currentTimeMillis()): Boolean = synchronized(cerrojo) {
        val e = entradas.firstOrNull { it.estado.trabajo == trabajo } ?: return false
        if (!visible(e)) return false
        if (e.desde != null && vencida(e.desde, ahora)) {
            entradas = entradas.filter { it !== e }
            persistir(ahora)
            publicar()
            return false
        }
        true
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
     *    puede reimprimir; guardarlo no le da nada al reloj ni al botón. Devuelve `false` sin tocar nada.
     * 2. **Venue ajeno, se SUSPENDE.** Si `trabajo.venueId` no es el del último [cargar] (cambiaron de sucursal mientras
     *    su comanda seguía insistiendo), entra pero NO se publica: el reloj de reintento y Cobrar, que leen [pendientes],
     *    lo mandarían a las impresoras de la sucursal equivocada. Vuelve al regresar a su sucursal. 🔴 Antes se rechazaba
     *    entero (la libreta era de una ranura) y esa comanda se perdía (Codex D1 r3 #3).
     * 3. **Nunca cargado** (sin venue conocido): como antes, entra y se enseña.
     *
     * 🔴 D1: se AGREGA a la lista; ya no pisa la comanda de otra venta. El MISMO trabajo guardado otra vez no se duplica
     * (saldría dos veces) y conserva su lugar y la hora de su primer fallo.
     *
     * @return `true` si además quedó en el DISCO. 🔴 Con `false` (y trabajo de esta sucursal) la comanda SÍ queda en
     * memoria —aviso y reintento— y el reloj reintenta la copia (Codex D1 r2 #4); sólo se perdería si la app muere antes.
     */
    fun guardar(estado: EstadoDeComanda.NoSalio, ahora: Long = System.currentTimeMillis()): Boolean = synchronized(cerrojo) {
        val trabajo = estado.trabajo ?: return false
        asegurarLeido(ahora)
        val previa = entradas.firstOrNull { it.estado.trabajo == trabajo }
        val nueva = Entrada(estado, previa?.desde ?: ahora, trabajo.venueId, aqui = true)
        entradas = if (previa != null) entradas.map { if (it === previa) nueva else it } else entradas + nueva
        val ok = persistir(ahora)
        publicar()
        return ok
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
     * - **Con trabajo reenviable**, queda la lista reducida **conservando la hora del primer fallo**: la vigencia de 8 h
     *   cuenta desde que la comanda no salió, no desde el último intento (si no, el reloj la renovaría cada minuto).
     * - **Sin nada reenviable** (lo que faltaba salió, pero lo que queda ya no tiene impresora): el aviso se sigue
     *   VIENDO —la pantalla lo pinta sin «Volver a imprimir» y el cajero lo cierra con «Ya la canté»— pero sale del
     *   disco y el reloj ya no tiene qué mandar. Reenviar el trabajo viejo sólo repetiría lo que ya salió.
     *
     * @return `false` si no cambió nada ([anterior] ya no estaba) o si el disco no quedó al día (se reintenta solo).
     */
    fun reemplazar(
        anterior: TrabajoPendiente,
        resultado: EstadoDeComanda.NoSalio,
        ahora: Long = System.currentTimeMillis(),
    ): Boolean = synchronized(cerrojo) {
        val previa = entradas.firstOrNull { it.estado.trabajo == anterior } ?: return false
        val nueva = previa.copy(estado = resultado, desde = previa.desde ?: ahora)
        entradas = entradas.map { if (it === previa) nueva else it }
        val ok = persistir(ahora)
        publicar()
        return ok
    }

    /**
     * El reintento de [anterior] terminó sin nada pendiente (salió, o ya no lleva comanda): se suelta ESA comanda, y sólo
     * ésa. 🔴 Antes `limpiar()` a secas: si mientras se reimprimía falló OTRA venta, su aviso y su reintento se borraban
     * con el éxito de ésta (Codex D11 r1 #2).
     */
    fun soltarSi(anterior: TrabajoPendiente): Boolean = synchronized(cerrojo) {
        if (entradas.none { it.estado.trabajo == anterior }) return false
        entradas = entradas.filter { it.estado.trabajo != anterior }
        persistir()
        publicar()
        true
    }

    /**
     * El aviso más reciente guardado para [venueIdActual], o `null` si no hay o si ya VENCIÓ (lo vencido se suelta).
     *
     * La vigencia no es cosmética: una comanda de ayer ya se resolvió de alguna forma —la
     * cocina la preparó, el cliente se fue, alguien la cantó— y ofrecer reimprimirla invita a
     * mandar comida que nadie pidió. [VIGENCIA_MS] cubre una jornada de mostrador sin llegar al
     * día siguiente. Sin venue no se arriesga: `null`.
     */
    fun leer(
        venueIdActual: String?,
        ahora: Long = System.currentTimeMillis(),
    ): EstadoDeComanda.NoSalio? = synchronized(cerrojo) {
        asegurarLeido(ahora)
        if (expirar(ahora)) {
            persistir(ahora)
            publicar()
        }
        if (venueIdActual == null) return null
        entradas.lastOrNull { it.venueId == venueIdActual && it.estado.trabajo != null }?.estado
    }

    private fun vencida(desde: Long, ahora: Long) = ahora - desde > VIGENCIA_MS || ahora < desde

    /**
     * Lo que hay en disco, de todas las sucursales, sin lo vencido a [ahora] (`null` = no se mira el reloj). Bajo [cerrojo].
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
        if (vigentes.isEmpty()) runCatching { almacen.borrar() }
        return vigentes
    }

    /**
     * Escribe la lista entera; vacía, borra la llave. Bajo [cerrojo].
     *
     * Sin tope a propósito (Codex D1 r1 #1): cualquier tope tiraría una comanda vigente al reiniciar. Lo que acota el
     * archivo es la vigencia de 8 h, que retira cada comanda sola.
     */
    private fun escribirDisco(lista: List<Guardada>): Boolean {
        // 🔴 Vaciar = primero escribir la lista vacía (eso SÍ dice si quedó: `commit()`), y luego borrar la llave por
        // limpieza. `borrar` no avisa si falló (`PrefsComoAlmacen` descarta el `commit()`): si fuera lo único, una comanda
        // ya cantada podría seguir en disco sin que el reloj se enterara (Codex D1 r3 #1). Un «[]» que se queda es inocuo.
        if (lista.isEmpty()) {
            val ok = runCatching { almacen.escribir("[]") }.getOrDefault(false)
            if (ok) runCatching { almacen.borrar() }
            return ok
        }
        return runCatching { almacen.escribir(json.encodeToString(lista)) }
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
     * 🔴 NO va dentro de [limpiar] ni de [soltarSi]: cerrar ahí borraría la fila que recupera la comanda de una ronda de
     * mesa en la próxima apertura (pérdida, no duplicado).
     *
     * 🔴 [aviso] es la comanda que la persona VIO al tocar (Codex D1 r4 #1): si entre leerla y tocar llegó otra, «la más
     * reciente» ya sería la nueva, y se tachaba ésa —sin haber salido ni haberla cantado nadie— mientras la cantada seguía
     * viva. `null` = la que se enseña ahora.
     *
     * @return el cierre en curso (las pruebas lo esperan), o `null` si no había nada que cerrar.
     */
    fun yaLaCante(aviso: EstadoDeComanda.NoSalio? = null): Job? {
        val trabajo = synchronized(cerrojo) {
            val vista = (if (aviso != null) entradas.lastOrNull { visible(it) && it.estado == aviso } else entradas.lastOrNull(::visible))
                ?: return null
            entradas = entradas.filter { it !== vista }
            persistir()
            publicar()
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
        leidoDelDisco = true
        entradas = emptyList()
        runCatching { almacen.borrar() }
        discoAtrasado = false
        publicar()
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
