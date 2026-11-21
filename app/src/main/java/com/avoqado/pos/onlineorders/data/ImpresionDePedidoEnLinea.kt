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
import com.avoqado.pos.printing.data.GuardaDelPrimerByte
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

    /**
     * Ruling 50: la comanda esperó su impresora (el turno, el permiso USB, la conexión) hasta que su reclamo tuvo 75 s, y no mandó ni un
     * byte. Se suelta sin descanso: la siguiente vuelta la reclama otra vez y el SERVIDOR decide (devuelto = no; si no, repetida con marca).
     */
    data object ReclamoVencido : ResultadoImpresion
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

/** D661 · R2-5 / Ruling 49: lo más seguido que se vuelve a pedir la configuración antes de creerle un «no hay cocina» (una vez por vuelta). */
private const val REFRESCO_DE_CONFIG_MS = 30 * 1000L

/**
 * D661 · Ruling 50: hasta qué edad (reloj monotónico, desde ANTES de pedirlo) un reclamo deja mandar el PRIMER byte: los 90 s de vida del
 * reclamo en el SERVIDOR menos 15 s de margen (red, reloj, la confirmación).
 */
private const val VIGENCIA_UTIL_DEL_RECLAMO_MS = 75 * 1000L

/**
 * Task A4: la comanda de un pedido en línea sale UNA vez, en UN aparato.
 *
 * - Sólo reclama (`reclamarComanda`) si ESTE aparato tiene al menos una impresora de cocina: así S4 no detiene la entrega por
 *   hora de quien no imprime. Cualquier `false` del reclamo (perdido, sin red, 5xx) = no imprimir: el TTL de 90 s del
 *   servidor decide quién la saca.
 * - `confirmPrint` SÓLO si la comanda salió de verdad ([laComandaSalio]); si no, no se confirma y a los 90 s otro aparato la
 *   saca. Límite aceptado (R20): si una estación imprimió y otra falló, a los 90 s se reimprime el pedido completo.
 * - Ruling 9 (D661): el reclamo es «al menos una vez». Si se concede sobre otro que venció sin confirmarse (`posibleRepetida`), la
 *   comanda dice arriba «POSIBLE REPETIDA: revisa si ya salió» (también en «Mi equipo»: la regla del SERVIDOR es de todo reclamo).
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
    /**
     * Ruling 49: la configuración de impresión en memoria NO es una descarga del SERVIDOR de este proceso (es la copia del aparato, o
     * nada): su «no hay impresora de cocina» puede estar viejo.
     */
    private val configSinDescargar: () -> Boolean = { false },
    /** R2-5: la vuelve a pedir (la de siempre, con tope de espera: `PrintConfigRepository.refreshConTope`). */
    private val refrescarConfig: suspend () -> Unit = {},
    /** Ruling 50: reloj monotónico (ms) para la edad del reclamo; no salta con la hora del aparato. */
    private val relojMonotono: () -> Long = { android.os.SystemClock.elapsedRealtime() },
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
        configSinDescargar = { secureStorage.venueId?.let { !printConfigRepository.descargadaDelServidor(it) } ?: false },
        refrescarConfig = { secureStorage.venueId?.let { printConfigRepository.refreshConTope(it) } },
        despachar = { venue, lineas, folio, tipo, orderId, encabezado ->
            val config = venue?.let { printConfigRepository.savedConfig(it) }
            if (venue == null || secureStorage.venueId != venue) null else dispatcher.dispatch(
                venueId = venue,
                lines = lineas,
                orderNumber = folio,
                orderType = tipo,
                // Como el KDS: sin reintento largo. Hay aparatos hermanos que la sacan a los 90 s si ésta no pudo.
                maxIntentos = 1,
                orderId = orderId,
                encabezado = encabezado.ifEmpty { null },
                configDeLaRonda = config,
            )
        },
    )

    private val enCurso: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** orderId -> cuándo (reloj) falló aquí por última vez. En memoria: reiniciar la app lo olvida, que es lo seguro. */
    private val fallosRecientes = ConcurrentHashMap<String, Long>()

    suspend fun imprimirSiToca(order: OnlineOrder, deviceId: String): ResultadoImpresion {
        // Sin renglones no hay comanda que sacar: reclamar sólo retendría el pedido a otro aparato. R2-6: descansa como una que no salió
        // (si no, el vigilante lo volvía a leer en cada sondeo).
        if (order.items.isEmpty()) {
            descansar(order.orderId)
            return ResultadoImpresion.NoSalio
        }
        val sucursal = venueId() ?: return ResultadoImpresion.NoSalio
        if (enDescanso(order.orderId)) return ResultadoImpresion.NoSalio
        if (!enCurso.add(order.orderId)) return ResultadoImpresion.YaEnCurso
        return try {
            withContext(NonCancellable) {
                val resultado = run {
                    if (!hayCocinaConfirmada() || venueId() != sucursal) return@run ResultadoImpresion.NoSalio
                    // Ruling 50: la edad del reclamo se cuenta desde ANTES de pedirlo (lo que tarda la respuesta también se lo come).
                    val pedidoEn = relojMonotono()
                    val reclamo = repositorio.reclamarComanda(order.orderId, deviceId)
                    if (!reclamo.reclamado) return@run ResultadoImpresion.OtroAparato
                    if (venueId() != sucursal) return@run ResultadoImpresion.NoSalio
                    val permiso = PermisoDelReclamo(pedidoEn, relojMonotono)
                    // Ruling 9: el reclamo se concedió sobre otro que venció sin confirmarse; el papel pudo haber salido ya.
                    val salio = withContext(GuardaDelPrimerByte(permiso::puedeEscribir)) {
                        sacarComanda(order, sucursal, posibleRepetida = reclamo.posibleRepetida)
                    }
                    // Esperó su impresora hasta que el reclamo se hizo viejo y no salió ni un byte: se suelta sin confirmar ni descansar.
                    if (permiso.vencioSinEmpezar()) return@run ResultadoImpresion.ReclamoVencido
                    if (!salio) return@run ResultadoImpresion.NoSalio
                    // Si la confirmación se pierde, el papel ya salió: a los 90 s otro aparato puede repetirla (límite aceptado, R20).
                    if (venueId() == sucursal) repositorio.confirmPrint(order.orderId, deviceId)
                    ResultadoImpresion.Impresa
                }
                if (resultado == ResultadoImpresion.NoSalio) fallosRecientes[order.orderId] = reloj() else fallosRecientes.remove(order.orderId)
                resultado
            }
        } finally {
            enCurso.remove(order.orderId)
        }
    }

    /**
     * D661 (revisión del PR #308, Important #2): ¿vale la pena LEER este pedido del servidor para su comanda? Las mismas puertas que
     * [imprimirSiToca] mira en el aparato antes de reclamar —hay impresora de cocina, el pedido no está en su descanso tras un `NoSalio`
     * y no se está imprimiendo aquí—, sin tocar la red. El vigilante lo pregunta antes de cada lectura.
     */
    suspend fun puedeIntentar(orderId: String): Boolean {
        if (enDescanso(orderId) || orderId in enCurso) return false
        return hayCocinaConfirmada()
    }

    /**
     * Ruling 49: ¿hay impresora de cocina? Un SÍ vale siempre, también de la copia del aparato (imprimir sin red no espera). Un NO sólo
     * vale con la configuración que este proceso DESCARGÓ del SERVIDOR; si viene de la copia del aparato o de nada (el dueño pudo agregar
     * la impresora en el panel después), se vuelve a pedir —a lo más una vez cada [REFRESCO_DE_CONFIG_MS], o sea una por vuelta— y
     * entonces se decide. Lo usan las tres puertas: [puedeIntentar] (el vigilante), [imprimirSiToca] («Mi equipo» y la bandeja) y
     * [reimprimir].
     */
    private suspend fun hayCocinaConfirmada(): Boolean {
        if (hayImpresoraDeCocina()) return true
        if (!configSinDescargar()) return false
        val ahora = reloj()
        val ultimo = ultimoRefrescoDeConfig
        if (ultimo != null && ahora - ultimo < REFRESCO_DE_CONFIG_MS) return false
        ultimoRefrescoDeConfig = ahora
        refrescarConfig()
        return hayImpresoraDeCocina()
    }

    /** R2-5: cuándo se pidió por última vez la configuración que no había cargado; null = nunca. */
    @Volatile private var ultimoRefrescoDeConfig: Long? = null

    /** R2-6: el vigilante no pudo leer ese pedido: el mismo descanso que una comanda que no salió (no se relee en cada sondeo). */
    fun descansar(orderId: String) {
        fallosRecientes[orderId] = reloj()
    }

    /** Ese pedido falló aquí hace menos de [DESCANSO_TRAS_NO_SALIO_MS] (de paso olvida los descansos ya vencidos). */
    private fun enDescanso(orderId: String): Boolean {
        val ahora = reloj()
        fallosRecientes.entries.removeIf { ahora - it.value >= DESCANSO_TRAS_NO_SALIO_MS }
        return fallosRecientes.containsKey(orderId)
    }

    /** «Reimprimir»: la saca en ESTE aparato sin reclamar ni confirmar (no toca el estado de impresión del servidor). */
    suspend fun reimprimir(order: OnlineOrder): ResultadoImpresion {
        val sucursal = venueId() ?: return ResultadoImpresion.NoSalio
        if (!enCurso.add(order.orderId)) return ResultadoImpresion.YaEnCurso
        return try {
            withContext(NonCancellable) {
                if (!hayCocinaConfirmada() || venueId() != sucursal) return@withContext ResultadoImpresion.NoSalio
                // Sin reclamo: la pidió una persona, no tiene edad que cuidar (Ruling 50).
                if (sacarComanda(order, sucursal)) ResultadoImpresion.Impresa else ResultadoImpresion.NoSalio
            }
        } finally {
            enCurso.remove(order.orderId)
        }
    }

    /** `dispatch` nunca lanza ante una impresora caída: devuelve el estado. «No lanzó» NO es «salió». */
    private suspend fun sacarComanda(order: OnlineOrder, sucursal: String, posibleRepetida: Boolean = false): Boolean {
        val encabezado = (if (posibleRepetida) listOf(MARCA_NEGRITA_ENCABEZADO + TextosPedidosEnLinea.POSIBLE_REPETIDA) else emptyList()) +
            encabezadoDeComanda(order)
        val estado = try {
            despachar(sucursal, lineasDeComanda(order), order.orderNumber, TextosPedidosEnLinea.PARA_RECOGER, order.orderId, encabezado)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        return laComandaSalio(estado)
    }
}

/**
 * Ruling 50: el permiso de mandar el primer byte de UNA comanda reclamada. Lo pregunta `PrinterService` justo antes de escribir en cada
 * impresora, ya con su turno, su permiso USB y su conexión. El primer «sí» se queda (ya empezó: termina aunque la siguiente estación tarde);
 * antes de eso, un reclamo de [VIGENCIA_UTIL_DEL_RECLAMO_MS] o más dice que no.
 */
private class PermisoDelReclamo(private val pedidoEn: Long, private val relojMonotono: () -> Long) {
    private var empezo = false
    private var vencio = false

    @Synchronized
    fun puedeEscribir(): Boolean {
        if (empezo) return true
        if (relojMonotono() - pedidoEn >= VIGENCIA_UTIL_DEL_RECLAMO_MS) {
            vencio = true
            return false
        }
        empezo = true
        return true
    }

    @Synchronized
    fun vencioSinEmpezar(): Boolean = vencio && !empezo
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
        // Sólo un cargo CAPTURED afirma la tarjeta: la cuenta pudo pagarse por otro camino y la tarjeta se soltó.
        if (order.paymentMode == "CARD_ONLINE" && order.cardChargeStatus == "CAPTURED") TextosPedidosEnLinea.COMANDA_PAGADO_CON_TARJETA
        else if (estaPagado(order)) TextosPedidosEnLinea.PAGADO else TextosPedidosEnLinea.cobrarAlRecoger(formatoPesos(order.remainingCents)),
    order.note?.trim()?.takeIf { it.isNotEmpty() }?.let { TextosPedidosEnLinea.notaDelPedido(it) },
)
