package com.avoqado.pos.tables.data

import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.printing.ComandaDispatcher
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.printing.data.AlmacenDeTexto
import com.avoqado.pos.printing.data.ComandasPendientesStore
import com.avoqado.pos.printing.data.EstadoDeComanda
import com.avoqado.pos.printing.routing.PrintConfig
import com.avoqado.pos.printing.routing.RoutableItem
import com.avoqado.pos.tables.presentation.TableOrderViewModel.PendingLine
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * na13e014 (tarjeta de la nube, 17-nov): la comanda de una ronda de mesa que todavía no se decide.
 *
 * Antes, la ronda se guardaba en el borrador, se encolaba (o se mandaba en línea) y SÓLO DESPUÉS se despachaba su comanda
 * (`despacharEnFondo`: refresca la config, rutea, imprime e insiste ~1 min). Para una estación con impresora no había nada en
 * disco hasta que el reintento se rendía y la guardaba en la libreta: si la app moría en ese rato (Android la cerró, el mesero la
 * deslizó, se apagó la tablet), la comanda no existía en ningún lado, y al reabrir la mesa el borrador re-encolaba la ronda sin
 * imprimirla. La cocina nunca se enteraba.
 *
 * Ahora la mesa apunta aquí la ronda ANTES de tocar la cola o la red, y la borra cuando su despacho termina (salió, quedó en la
 * libreta o en las entregas de la pantalla: ya hay quien la reintente) o cuando la ronda no va a subir. Al abrir la app,
 * [ReplayDeRondasSinPapel] despacha lo que quedó de un proceso anterior. Duplicado posible (el papel salió y el proceso murió
 * antes de borrar la entrada); pérdida, no.
 *
 * `SharedPreferences` con `commit()` ([AlmacenDeTexto]), como la libreta: es poco y tiene que estar en disco ANTES de la red.
 */
class ComandasDeRondaPorDecidir(private val almacen: AlmacenDeTexto) {
    @Serializable
    data class Ronda(
        val roundKey: String,
        val venueId: String,
        val orderNumber: String,
        /** `null` en una mesa provisional (abierta sin red): todavía no tiene id del servidor. */
        val orderId: String?,
        val tableNumber: String,
        val lines: List<PendingLine>,
        /** La config con que la ronda quedó ruteada en el borrador (rondas con preparación); `null` = la vigente. */
        val configCongelada: PrintConfig? = null,
        val creadaEnMillis: Long,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Ronda.serializer())
    private val cerrojo = Any()

    private fun leer(): List<Ronda> = runCatching { almacen.leer()?.let { json.decodeFromString(serializer, it) } }
        .onFailure { Log.w(TAG, "Diario de comandas ilegible: se ignora (${it.message})") }
        .getOrNull().orEmpty()

    private fun escribir(rondas: List<Ronda>): Boolean = runCatching {
        if (rondas.isEmpty()) almacen.escribir("[]") else almacen.escribir(json.encodeToString(serializer, rondas))
    }.getOrDefault(false)

    /** @return `true` si quedó en disco. Si no, la ronda sigue como antes (su despacho en vivo la imprime igual). */
    fun apuntar(ronda: Ronda): Boolean = synchronized(cerrojo) {
        escribir(leer().filterNot { it.roundKey == ronda.roundKey } + ronda)
            .also { if (!it) Log.w(TAG, "No se pudo apuntar la comanda de la ronda ${ronda.roundKey}") }
    }

    fun quitar(roundKey: String) {
        synchronized(cerrojo) {
            val rondas = leer()
            if (rondas.none { it.roundKey == roundKey }) return
            if (!escribir(rondas.filterNot { it.roundKey == roundKey })) Log.w(TAG, "No se pudo quitar la ronda $roundKey del diario")
        }
    }

    fun todas(): List<Ronda> = synchronized(cerrojo) { leer() }

    private companion object { const val TAG = "ComandasDeRonda" }
}

/** Un renglón de la ronda (ya con su llave `sync:<ronda>:<i>`) como lo rutea la cocina: el MISMO al enviar, reimprimir y recuperar. */
fun renglonDeCocinaDeRonda(line: PendingLine) = RoutableItem(
    orderItemId = line.item.id,
    productId = (line.item.type as? CartItemType.ProductItem)?.productId,
    categoryId = line.item.categoryId,
    productName = line.item.name,
    quantity = line.item.quantity,
    modifiers = line.item.selectedModifiers.map { it.modifierName },
    notes = line.item.itemNote,
    comboName = line.item.promotionName,
    serviceCourse = line.item.serviceCourse,
    externalId = line.externalId,
    orderPromotionId = line.item.promotionInstanceId,
)

/**
 * Los pedidos de cocina de una ronda: un lote por tiempo («Mesa 8 · Aperitivos»), sólo productos del catálogo (un importe libre
 * va a la cuenta, nunca a cocina). El mismo armado al enviarla y al recuperarla del diario.
 */
fun pedidosDeLaRonda(lines: List<PendingLine>, roundKey: String, tableNumber: String, configCongelada: PrintConfig?): List<ComandaDispatcher.Pedido> =
    stampTableRoundLines(lines, roundKey)
        .filter { it.item.type is CartItemType.ProductItem }
        .groupBy { kitchenCourseLabel(it.item.serviceCourse, it.course) }
        .map { (course, courseLines) ->
            ComandaDispatcher.Pedido(
                lines = courseLines.map(::renglonDeCocinaDeRonda),
                orderType = "Mesa $tableNumber" + (course?.let { " · $it" } ?: ""),
                // KDS 3.6: la pantalla junta los cursos en una tarjeta — «Mesa 8» arriba y el tiempo en cada platillo.
                curso = course,
                etiquetaPantalla = "Mesa $tableNumber",
                configCongelada = configCongelada,
                omitirSinComanda = true, // «No lleva comanda»: la ronda es una venta del POS.
            )
        }

/**
 * na13e014: al abrir la app, despacha la comanda de las rondas que un proceso ANTERIOR apuntó y nunca decidió (murió entre
 * guardar la ronda y su papel). Sólo las de la sucursal vigente; las de otra esperan a que vuelva. Más de 8 h ⇒ se borran sin
 * imprimir (la misma vigencia que la libreta y las entregas: imprimir sola una comanda de ayer manda comida que nadie pidió).
 * Las de ESTE proceso nunca se tocan: su despacho vivo las decide.
 *
 * Sale con `servidorLaTiene = false` (no se sabe si subió): una estación «sólo pantalla» se empuja por el WiFi y, si no acusa,
 * sale en papel; lo que no sale va a la libreta, como cualquier ronda.
 */
@Singleton
class ReplayDeRondasSinPapel @Inject constructor(
    private val diario: ComandasDeRondaPorDecidir,
    private val comandaDispatcher: ComandaDispatcher,
    private val comandasPendientesStore: ComandasPendientesStore,
    private val secureStorage: SecureStorage,
) {
    private val arranqueDelProcesoMillis = System.currentTimeMillis()
    /** Las ya tomadas en este proceso: `startOfflineOutbox` corre 2-3 veces al abrir; una ronda se despacha UNA vez. */
    private val tomadas = mutableSetOf<String>()

    fun reproducirAlAbrir(
        venueId: String,
        ahora: Long = System.currentTimeMillis(),
        arranqueDelProceso: Long = arranqueDelProcesoMillis,
    ) {
        val porDespachar = synchronized(tomadas) {
            diario.todas().filter { it.creadaEnMillis < arranqueDelProceso && it.roundKey !in tomadas }
                .filter { ronda ->
                    when {
                        ahora - ronda.creadaEnMillis > VIGENCIA_MS -> {
                            Log.w(TAG, "🗑️ Comanda de la ronda ${ronda.roundKey} de hace más de 8 h: se descarta sin imprimir")
                            diario.quitar(ronda.roundKey)
                            false
                        }
                        ronda.venueId != venueId || secureStorage.venueId != venueId -> false // suspendida: es de otra sucursal
                        else -> true
                    }
                }
                .onEach { tomadas += it.roundKey }
        }
        for (ronda in porDespachar) {
            val pedidos = pedidosDeLaRonda(ronda.lines, ronda.roundKey, ronda.tableNumber, ronda.configCongelada)
            if (pedidos.isEmpty()) { diario.quitar(ronda.roundKey); continue }
            Log.i(TAG, "🧾 La comanda de la ronda ${ronda.roundKey} (mesa ${ronda.tableNumber}) no se decidió antes de cerrar la app: sale ahora")
            var libretaFallo = false
            comandaDispatcher.despacharEnFondo(
                venueId = ronda.venueId,
                orderNumber = ronda.orderNumber,
                pedidos = pedidos,
                orderId = ronda.orderId,
                servidorLaTiene = false,
                origenDelFolio = "round:${ronda.roundKey}",
                alCambiarEstado = { estado ->
                    if (estado is EstadoDeComanda.NoSalio && estado.trabajo != null && !comandasPendientesStore.guardar(estado)) libretaFallo = true
                },
            ).invokeOnCompletion { error -> if (error == null && !libretaFallo) diario.quitar(ronda.roundKey) }
        }
    }

    private companion object {
        const val TAG = "ReplayDeRondasSinPapel"
        const val VIGENCIA_MS = 8L * 60 * 60 * 1000
    }
}
