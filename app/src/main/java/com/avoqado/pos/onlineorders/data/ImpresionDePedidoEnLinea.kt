package com.avoqado.pos.onlineorders.data

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.printing.ComandaDispatcher
import com.avoqado.pos.kds.presentation.laComandaSalio
import com.avoqado.pos.onlineorders.domain.OnlineOrder
import com.avoqado.pos.core.util.VenueTimeZone
import com.avoqado.pos.onlineorders.presentation.TextosPedidosEnLinea
import com.avoqado.pos.onlineorders.presentation.estaPagado
import com.avoqado.pos.onlineorders.presentation.formatoPesos
import java.time.format.DateTimeFormatter
import com.avoqado.pos.printing.data.EstadoDeComanda
import com.avoqado.pos.printing.data.PrinterService
import com.avoqado.pos.printing.data.model.MARCA_NEGRITA_ENCABEZADO
import com.avoqado.pos.printing.data.model.PrinterRole
import com.avoqado.pos.printing.routing.PrintConfigRepository
import com.avoqado.pos.printing.routing.RoutableItem
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Qué pasó al intentar sacar la comanda de un pedido en línea. */
sealed interface ResultadoImpresion {
    /** Salió el papel (y se avisó al servidor). */
    data object Impresa : ResultadoImpresion

    /** Otro aparato ganó el reclamo, o no se pudo reclamar (sin red / error): este aparato NO imprime. */
    data object OtroAparato : ResultadoImpresion

    /** Este aparato no pudo imprimirla (sin impresora de cocina, o la impresora no respondió). */
    data object NoSalio : ResultadoImpresion

    /** Ya se está imprimiendo ESTE pedido en este aparato. */
    data object YaEnCurso : ResultadoImpresion
}

/**
 * ¿Este aparato puede sacar una comanda? Sí con una impresora de cocina guardada en el aparato, o con una impresora ACTIVA del
 * negocio que esté ligada a una estación ACTIVA (así se marca una impresora de cocina en la configuración; una impresora sin
 * estación es la de recibos y NO cuenta). PURA.
 */
fun hayImpresoraDeCocina(localDeCocina: Boolean, config: com.avoqado.pos.printing.routing.PrintConfig): Boolean {
    if (localDeCocina) return true
    val ligadas = config.stations.filter { it.active }.mapNotNull { it.printerId }.toSet()
    return config.printers.any { it.active && it.id in ligadas }
}

/** Tras un `NoSalio` este aparato deja pasar este tiempo antes de volver a reclamar el mismo pedido (otro aparato lo toma). */
private const val DESCANSO_TRAS_NO_SALIO_MS = 3 * 60 * 1000L

/**
 * Task A4: la comanda de un pedido en línea sale UNA vez, en UN aparato.
 *
 * - Sólo reclama (`claimPrint`) si ESTE aparato tiene al menos una impresora de cocina: así S4 no detiene la entrega por
 *   hora de quien no imprime. Cualquier `false` del reclamo (perdido, sin red, 5xx) = no imprimir: el TTL de 90 s del
 *   servidor decide quién la saca.
 * - `confirmPrint` SÓLO si la comanda salió de verdad ([laComandaSalio]); si no, no se confirma y a los 90 s otro aparato la
 *   saca. Límite aceptado (R20): si una estación imprimió y otra falló, a los 90 s se reimprime el pedido completo.
 * - Nunca dos veces a la vez el mismo pedido en este aparato ([ResultadoImpresion.YaEnCurso]); y el trabajo corre en
 *   `NonCancellable`: cerrar la pantalla a media impresión no deja un reclamo colgado sin imprimir.
 */
@Singleton
class ImpresionDePedidoEnLinea internal constructor(
    private val repositorio: OnlineOrdersRepository,
    private val hayImpresoraDeCocina: suspend () -> Boolean,
    private val venueId: () -> String?,
    private val despachar: suspend (venueId: String?, lineas: List<RoutableItem>, folio: String, tipo: String, orderId: String, encabezado: List<String>) -> EstadoDeComanda?,
    private val reloj: () -> Long = System::currentTimeMillis,
) {
    @Inject
    constructor(
        repositorio: OnlineOrdersRepository,
        dispatcher: ComandaDispatcher,
        printerService: PrinterService,
        printConfigRepository: PrintConfigRepository,
        secureStorage: SecureStorage,
    ) : this(
        repositorio = repositorio,
        // Una impresora de cocina de ESTE aparato (USB/BT/WiFi guardada) o una activa en la configuración del negocio.
        hayImpresoraDeCocina = {
            hayImpresoraDeCocina(
                localDeCocina = printerService.savedPrinters.value.any { it.isEnabled && it.hasRole(PrinterRole.KITCHEN) },
                config = printConfigRepository.getCurrentConfig(),
            )
        },
        venueId = { secureStorage.venueId },
        despachar = { venue, lineas, folio, tipo, orderId, encabezado ->
            dispatcher.dispatch(
                venueId = venue,
                lines = lineas,
                orderNumber = folio,
                orderType = tipo,
                // Como el KDS: sin reintento largo. Hay aparatos hermanos que la sacan a los 90 s si ésta no pudo.
                maxIntentos = 1,
                orderId = orderId,
                encabezado = encabezado.ifEmpty { null },
            )
        },
    )

    private val enCurso: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** orderId -> cuándo (reloj) falló aquí por última vez. En memoria: reiniciar la app lo olvida, que es lo seguro. */
    private val fallosRecientes = ConcurrentHashMap<String, Long>()

    suspend fun imprimirSiToca(order: OnlineOrder, deviceId: String): ResultadoImpresion {
        // Sin renglones no hay comanda que sacar: reclamar sólo retendría el pedido a otro aparato.
        if (order.items.isEmpty()) return ResultadoImpresion.NoSalio
        val ahora = reloj()
        fallosRecientes.entries.removeIf { ahora - it.value >= DESCANSO_TRAS_NO_SALIO_MS }
        if (fallosRecientes.containsKey(order.orderId)) return ResultadoImpresion.NoSalio
        if (!enCurso.add(order.orderId)) return ResultadoImpresion.YaEnCurso
        return try {
            withContext(NonCancellable) {
                val resultado = run {
                    if (!hayImpresoraDeCocina()) return@run ResultadoImpresion.NoSalio
                    if (!repositorio.claimPrint(order.orderId, deviceId)) return@run ResultadoImpresion.OtroAparato
                    if (!sacarComanda(order)) return@run ResultadoImpresion.NoSalio
                    // Si la confirmación se pierde, el papel ya salió: a los 90 s otro aparato puede repetirla (límite aceptado, R20).
                    repositorio.confirmPrint(order.orderId, deviceId)
                    ResultadoImpresion.Impresa
                }
                if (resultado == ResultadoImpresion.NoSalio) fallosRecientes[order.orderId] = reloj() else fallosRecientes.remove(order.orderId)
                resultado
            }
        } finally {
            enCurso.remove(order.orderId)
        }
    }

    /** «Reimprimir»: la saca en ESTE aparato sin reclamar ni confirmar (no toca el estado de impresión del servidor). */
    suspend fun reimprimir(order: OnlineOrder): ResultadoImpresion {
        if (!enCurso.add(order.orderId)) return ResultadoImpresion.YaEnCurso
        return try {
            withContext(NonCancellable) {
                if (!hayImpresoraDeCocina()) return@withContext ResultadoImpresion.NoSalio
                if (sacarComanda(order)) ResultadoImpresion.Impresa else ResultadoImpresion.NoSalio
            }
        } finally {
            enCurso.remove(order.orderId)
        }
    }

    /** `dispatch` nunca lanza ante una impresora caída: devuelve el estado. «No lanzó» NO es «salió». */
    private suspend fun sacarComanda(order: OnlineOrder): Boolean {
        val estado = try {
            despachar(venueId(), lineasDeComanda(order), order.orderNumber, TextosPedidosEnLinea.PARA_RECOGER, order.orderId, encabezadoDeComanda(order))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        return laComandaSalio(estado)
    }
}

/** Los renglones del pedido como los entiende el ruteo por estación (`productId`/`categoryId` de S3; sin ellos ⇒ estación por defecto). */
fun lineasDeComanda(order: OnlineOrder): List<RoutableItem> = order.items.mapIndexed { i, item ->
    RoutableItem(
        orderItemId = "${order.orderId}-$i",
        productId = item.productId,
        categoryId = item.categoryId,
        productName = item.name,
        quantity = item.quantity,
        modifiers = item.modifiers.map { if (it.quantity > 1) "${it.quantity} × ${it.name}" else it.name },
        notes = item.note?.takeIf { it.isNotBlank() },
    )
}

private val FORMATO_HORA_COMANDA = DateTimeFormatter.ofPattern("HH:mm")

/**
 * Lo que el mostrador necesita para ENTREGAR el pedido: a nombre de quién, su teléfono, a qué hora estará, cuánto cobrar y la
 * nota del cliente. Las líneas vacías no se imprimen. La hora va en la zona del NEGOCIO; el dinero sale del saldo exacto en centavos.
 */
fun encabezadoDeComanda(order: OnlineOrder): List<String> = listOfNotNull(
    order.customerName?.trim()?.takeIf { it.isNotEmpty() },
    order.customerPhone?.trim()?.takeIf { it.isNotEmpty() },
    order.estimatedReadyAt?.let { TextosPedidosEnLinea.listoALas(VenueTimeZone.at(it).format(FORMATO_HORA_COMANDA)) },
    // Lo que el mostrador no se puede equivocar: la línea del dinero va en negrita.
    MARCA_NEGRITA_ENCABEZADO +
        if (estaPagado(order)) TextosPedidosEnLinea.PAGADO else TextosPedidosEnLinea.cobrarAlRecoger(formatoPesos(order.remainingCents)),
    order.note?.trim()?.takeIf { it.isNotEmpty() }?.let { TextosPedidosEnLinea.notaDelPedido(it) },
)
