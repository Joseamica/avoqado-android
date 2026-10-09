// Disparo de comanda — el mecanismo compartido por los DOS momentos en que una comanda puede salir:
//
//   POST-PAGO  mostrador: se cobra y ENTONCES sale la comanda (PaymentFlowViewModel).
//   PRE-PAGO   vale de área: el área imprime el vale, se queda con el producto y necesita su
//              comanda ANTES de que el cliente pase a la caja (§5.6 del spec
//              docs/superpowers/specs/2026-07-28-vales-por-area-y-bascula-design.md).
//
// Hasta hoy el único disparo pre-pago vivía enterrado en el flujo de MESAS
// (TableOrderViewModel.printRoundComandas). Esta clase saca el mecanismo de ahí para que un vale
// pueda usarlo sin duplicar el ruteo: PrintRoutingMapper.buildComandas y ComandaPrinter.printComandas
// siguen siendo los mismos de siempre, esto sólo es la secuencia que los encadena.
//
// 🔴 LA REGLA QUE MANDA SOBRE TODO LO DEMÁS. Este archivo está en el camino de impresión de
// comandas, y ese camino lo usan TODOS los restaurantes. Un venue SIN el feature `AREA_TICKETS`
// tiene que comportarse EXACTAMENTE igual que antes de que esta clase existiera: las mismas
// llamadas, con los mismos argumentos, en el mismo orden. Por eso [ComandaDispatcher.dispatch] no
// consulta feature flags, ni planes, ni modos de área — es puro mecanismo. La decisión de "¿este
// venue emite vales?" vive en quien emite el vale, jamás aquí: un restaurante que deja de imprimir
// comandas es una cocina que no se entera de los pedidos.
//
// Y por la misma razón NO hay guard de configuración delante de la impresión
// (.claude/rules/offline-first-y-hub-lan.md §4.1a): sin estaciones activas se rutea igual, el motor
// arma un ticket "SIN ESTACIÓN" y ComandaPrinter cae a la impresora KITCHEN local. La ÚNICA
// excepción es [NoStationsFallback.LegacySingleTicket], y existe para reproducir tal cual lo que el
// mostrador YA hacía — no para dejar de imprimir.
package com.avoqado.pos.core.domain.printing

import android.util.Log
import com.avoqado.pos.core.data.sync.SyncIntentTypes
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.printing.data.ComandaPrinter
import com.avoqado.pos.printing.data.EntregaKds
import com.avoqado.pos.printing.data.EntregaPorWifi
import com.avoqado.pos.printing.data.EstadoDeComanda
import com.avoqado.pos.printing.data.PoliticaDeReintento
import com.avoqado.pos.printing.data.PrinterService
import com.avoqado.pos.printing.data.ReintentoDeComanda
import com.avoqado.pos.printing.data.TrabajoPendiente
import com.avoqado.pos.printing.data.model.KitchenItem
import com.avoqado.pos.printing.data.model.KitchenTicketData
import com.avoqado.pos.printing.routing.KitchenDeliveryPolicy
import com.avoqado.pos.printing.routing.PrintConfig
import com.avoqado.pos.printing.routing.PrintConfigRepository
import com.avoqado.pos.printing.routing.PrintRoutingMapper
import com.avoqado.pos.printing.routing.RoutableItem
import com.avoqado.pos.printing.routing.TicketPlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ComandaDispatcher"

/**
 * Qué hacer cuando el venue no tiene NINGUNA estación activa. Los dos flujos que ya existían hacían
 * cosas distintas y las dos son correctas para su caso — por eso es un parámetro explícito y no una
 * decisión escondida adentro del despachador.
 */
sealed interface NoStationsFallback {

    /**
     * Rutear igual (fail-open). El motor mete todo en un plan sin estación y [ComandaPrinter] cae a
     * la impresora con rol KITCHEN. Es lo que hace el flujo de MESAS —incluso sin red— y es el
     * default para cualquier disparo nuevo, vales incluidos.
     */
    data object RouteAnyway : NoStationsFallback

    /**
     * UN solo ticket de cocina abanicado a TODAS las impresoras con rol KITCHEN
     * ([PrinterService.autoPrintKitchenTicket]). Es EXACTAMENTE lo que el mostrador hace hoy después
     * de cobrar cuando el venue no tiene estaciones configuradas.
     *
     * Los [KitchenItem] los arma quien llama, a propósito: ese ticket lleva `category` (el subtítulo
     * del carrito) y el ruteo por estaciones no la lleva. Pasándolos ya construidos, el ticket sale
     * idéntico al de antes en vez de "parecido".
     */
    data class LegacySingleTicket(val items: List<KitchenItem>) : NoStationsFallback
}

@Singleton
class ComandaDispatcher @Inject constructor(
    private val printConfigRepository: PrintConfigRepository,
    private val reintentoDeComanda: ReintentoDeComanda,
    private val printerService: PrinterService,
    /**
     * Etapa 3 del KDS (3.4): la cola donde va la marca `FALLBACK_PRINTED` del papel de respaldo. Hilt SIEMPRE la
     * inyecta. ponytail: opcional sólo porque nueve archivos de prueba construyen el despachador con tres argumentos y
     * dos son WIP de otra sesión; sin cola no se marca (la pantalla mostraría esa comanda al volver la red: duplicado,
     * no pérdida).
     */
    private val syncOutbox: SyncOutbox? = null,
    /**
     * Etapa 3 del KDS (3.5): el empuje por WiFi a las pantallas del local. Hilt SIEMPRE la inyecta. ponytail: opcional
     * por lo mismo que `syncOutbox` (11 sitios de prueba con ≤ 4 argumentos, dos con WIP ajeno); sin ella no se empuja
     * y las «sólo pantalla» salen en papel de respaldo, que es el lado seguro.
     */
    private val entregaPorWifi: EntregaPorWifi? = null,
) {

    /**
     * Etapa 3 del KDS (3.4): el ámbito de los despachos que NO pueden depender de una pantalla. Esta clase es
     * `@Singleton`, así que vive con la app. `Dispatchers.Default` y no `Main`: la impresión ya salta a su hilo, y así
     * las pruebas de JVM no necesitan un `Main` falso. El manejador sólo registra: `dispatch` no lanza por contrato,
     * pero una excepción suelta en este ámbito tumbaría la app.
     */
    private val fondo = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, e -> Log.e(TAG, "❌ Despacho en fondo falló: ${e.message}", e) },
    )

    /** Una comanda de un despacho en fondo: sus renglones y su encabezado (p. ej. un curso de la ronda). */
    /**
     * [curso] y [etiquetaPantalla] (KDS 3.6): la pantalla de cocina junta los cursos de una ronda en UNA tarjeta, así que
     * no le sirve el «Mesa 8 · Aperitivos» del papel: recibe «Mesa 8» arriba y el tiempo en cada platillo. `null` = lo de
     * siempre ([orderType] para las dos).
     */
    data class Pedido(
        val lines: List<RoutableItem>,
        val orderType: String,
        val curso: String? = null,
        val etiquetaPantalla: String? = null,
        val configCongelada: PrintConfig? = null,
    )

    /**
     * El MISMO [dispatch], uno por [Pedido] y EN ORDEN, en el ámbito del despachador. Lo usan las RONDAS de mesa
     * (Task 9): si corriera en el `viewModelScope` de la mesa, el mesero que regresa al plano antes de que la impresora
     * conteste cancelaría el reintento (~1 min) y la comanda que no salió desaparecería sin aviso. Aquí sigue:
     * reintenta, y su [alCambiarEstado] guarda el pendiente y avisa aunque la pantalla ya no exista.
     *
     * Uno detrás de otro, como hasta hoy: dos cursos a la misma impresora no se pisan la conexión ni salen al revés.
     *
     * 🔴 [alCambiarEstado] se llama en `Dispatchers.Default` (el de [fondo]): si el llamador toca Compose o guarda
     * estado que no es thread-safe, tiene que publicar en un `StateFlow` o saltar a `Dispatchers.Main` él mismo.
     */
    fun despacharEnFondo(
        venueId: String?,
        orderNumber: String,
        pedidos: List<Pedido>,
        orderId: String? = null,
        servidorLaTiene: Boolean? = null,
        origenDelFolio: String? = null,
        alCambiarEstado: (EstadoDeComanda) -> Unit = {},
    ): Job = fondo.launch {
        val configCongelada = pedidos.firstNotNullOfOrNull { it.configCongelada }
        require(pedidos.all { it.configCongelada == configCongelada }) { "Los tiempos deben conservar el mismo destino de cocina" }
        val guardada = guardarLaRonda(venueId, orderNumber, pedidos, orderId, servidorLaTiene, origenDelFolio, configCongelada)
        val guardadasAntes = guardada?.entregas
        // Codex 3.6 (#4): UNA config para toda la ronda, la misma con que se guardó. El tiempo 1 puede insistir ~1 min y en
        // ese minuto se puede cambiar de sucursal: si cada tiempo releyera la global, el 2 saldría por las impresoras de la
        // otra (y su papel, si no salía, se perdía). La tablet sigue en la sucursal de la ronda. No se detiene el despacho:
        // un tiempo «sólo impresora» no tiene fila en disco, detenerlo sería perderlo.
        val configDeLaRonda = guardada?.config ?: configCongelada ?: run {
            venueId?.let { printConfigRepository.refreshConTope(it) }
            printConfigRepository.getCurrentConfig()
        }
        // Codex 3.6 (#1): los tiempos comparten folio y la marca de papel esconde el folio ENTERO en el servidor. El papel de
        // un tiempo no cubre un folio que va en más de un tiempo: si otro tiempo llegó a la pantalla sin papel, la marca lo
        // borraría de la cocina. Esos folios no se marcan (papel Y pantalla: duplicado, nunca pérdida). Sin guardado previo no
        // se sabe qué folios comparten: con más de un tiempo, no se marca ninguno.
        val noMarcar: (String) -> Boolean = when {
            guardadasAntes != null -> guardadasAntes.groupingBy { it.mensaje.sourceKey }.eachCount().filterValues { it > 1 }.keys::contains
            pedidos.size > 1 -> { _ -> true }
            else -> { _ -> false }
        }
        for (pedido in pedidos) {
            // I-2 de la revisión (ronda 1): sin esta guarda, un `dispatch` que revienta se llevaba entre pies a
            // TODOS los pedidos que seguían — el `for` moría ahí y el único rastro quedaba en el log del
            // `CoroutineExceptionHandler` de arriba. `dispatch` está documentado como que no lanza, pero
            // `internalPrinterForRouting()` (bind AIDL, fuera del try/catch por plan de `ComandaPrinter`) y el
            // `alCambiarEstado` del propio llamador (que `insistir` invoca directo) sí pueden.
            try {
                dispatch(
                    venueId = venueId,
                    lines = pedido.lines,
                    orderNumber = orderNumber,
                    orderType = pedido.orderType,
                    orderId = orderId,
                    servidorLaTiene = servidorLaTiene,
                    origenDelFolio = origenDelFolio,
                    alCambiarEstado = alCambiarEstado,
                    configDeLaRonda = configDeLaRonda,
                    etiquetaPantalla = pedido.etiquetaPantalla,
                    curso = pedido.curso,
                    noMarcar = noMarcar,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "❌ Pedido \"${pedido.orderType}\" del despacho en fondo falló: ${e.message}", e)
                // El aviso es best-effort: si el propio `alCambiarEstado` truena, no puede tirar los pedidos
                // que faltan — es la MISMA falla que se está blindando, una capa más adentro.
                runCatching {
                    alCambiarEstado(
                        EstadoDeComanda.NoSalio(
                            estaciones = listOf(pedido.orderType),
                            causa = e.message,
                            orderNumber = orderNumber,
                        ),
                    )
                }
            }
        }
        // Ya no hay despacho vivo de este lote: una fila guardada de antemano que su curso no reusó (la config cambió en
        // medio, o el curso tronó antes de empujar) se SUELTA para que el replay la decida — duplicado, nunca pérdida. Las
        // que su curso ya cerró no existen: soltarlas no toca nada.
        guardadasAntes?.let { entregaPorWifi?.soltar(it) }
    }

    /**
     * Revisión final de la 3.5 (I3): los cursos de una ronda comparten folio y salen uno tras otro. Las entregas de TODOS
     * quedan en disco ANTES de que el primero toque la red (`todo-funciona-sin-red.md`, pregunta 2, aplicada al lote, no al
     * curso): si el proceso muere entre dos cursos, el replay empuja o imprime el que faltaba. Sin esto el curso 2 no
     * existía en ningún lado, y un LISTO sin red sobre el curso 1 cerraba el folio entero en el servidor: pérdida.
     *
     * Refresca la config UNA vez y la devuelve: cada curso se despacha con ELLA (mismos planes, mismas filas). Cada curso
     * vuelve a guardar la suya al despachar (`REPLACE` por `entregaId`, inocuo). `null` = el lote no reparte por pantalla
     * (o no se pudo preparar): [despacharEnFondo] lee la config una vez por su cuenta.
     */
    private suspend fun guardarLaRonda(
        venueId: String?,
        orderNumber: String,
        pedidos: List<Pedido>,
        orderId: String?,
        servidorLaTiene: Boolean?,
        origen: String?,
        configCongelada: PrintConfig? = null,
    ): RondaGuardada? {
        val wifi = entregaPorWifi ?: return null
        if (servidorLaTiene == null || venueId == null || origen == null) return null
        return try {
            if (configCongelada == null) printConfigRepository.refreshConTope(venueId)
            val config = configCongelada ?: printConfigRepository.getCurrentConfig()
            val entregas = pedidos.flatMap { p ->
                entregasPorWifi(
                    venueId, PrintRoutingMapper.buildComandas(p.lines, config), config, orderNumber, p.orderType,
                    serverName = null, comboNames = comboNamesDe(p.lines), orderId = orderId, origen = origen,
                    etiquetaPantalla = p.etiquetaPantalla, curso = p.curso,
                )
            }
            wifi.guardar(entregas)
            RondaGuardada(entregas, config)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Preparar el lote nunca puede impedir que salgan los cursos: sin esto, cada uno guarda la suya como antes.
            Log.w(TAG, "No se pudieron guardar de antemano los cursos de $orderNumber: ${e.message}")
            null
        }
    }

    /** Lo que [guardarLaRonda] dejó en disco y la config con que lo ruteó (la de toda la ronda, Codex 3.6 #4). */
    private data class RondaGuardada(val entregas: List<EntregaKds>, val config: PrintConfig)

    private fun comboNamesDe(lines: List<RoutableItem>): Map<String, String> =
        lines.mapNotNull { line -> line.comboName?.let { line.orderItemId to it } }.toMap()

    /**
     * Dispara las comandas de [lines]. Es la MISMA secuencia de siempre, en el mismo orden:
     *
     *  1. refrescar la config de impresión (sólo si hay [venueId] — igual que antes),
     *  2. leer la config vigente (cache-first: un refresh fallido nunca borra la buena),
     *  3. rutear con [PrintRoutingMapper.buildComandas] e imprimir CON REINTENTO
     *     ([ReintentoDeComanda.insistir], que sigue [com.avoqado.pos.printing.data.PoliticaDeReintento] —
     *     hasta ~1 minuto, y sólo vuelve a mandar lo que TRONÓ); salvo que no haya estaciones
     *     activas y quien llama haya pedido [NoStationsFallback.LegacySingleTicket].
     *
     * Nunca lanza hacia afuera de lo que ya lanzaba: `refresh` falla en silencio por contrato y
     * `ReintentoDeComanda` (como antes `ComandaPrinter` directo) envuelve cada estación por
     * separado.
     *
     * 🔴 Esta función puede tardar hasta ~1 minuto cuando una estación insiste en fallar —
     * frenar el camino del cobro con eso es EXACTAMENTE lo que no puede pasar. El llamador es
     * quien decide: el disparo post-cobro la corre en su propio `viewModelScope.launch`, ya
     * desligado del flujo de pago (el dinero se resuelve antes de que esto siquiera empiece a
     * reintentar).
     *
     * @param maxIntentos tope de intentos para ESTA llamada — default el de
     *   [PoliticaDeReintento.INTENTOS_MAXIMOS] (~1 minuto, el mostrador). El KDS pasa **1** (sin
     *   reintento): sus tablets hermanas pueden tomar el pedido si ésta no lo saca, y con el
     *   reintento activo la reclamación se quedaría retenida hasta ~50 s en vez de soltarse en
     *   segundos — ver [com.avoqado.pos.kds.presentation.KDSViewModel].
     * @param alCambiarEstado se dispara cada vez que [ReintentoDeComanda] cambia de estado
     *   mientras insiste, para que la pantalla pueda mostrar "reintentando…" y, al final, el
     *   aviso con la causa real. Nunca se llama en el camino legado (fire-and-forget: no hay
     *   nada que avisar hasta que termina, y termina imprimiendo, no reportando).
     * @param orderId el id REAL de la orden (server) — para «la libreta» (Task 16), que lo manda
     *   al servidor junto con el resultado. `null` en los caminos que todavía no lo tienen a
     *   mano (hoy: el KDS); ahí el reporte cae a `orderNumber` en vez de perderse.
     * @param servidorLaTiene Etapa 3 del KDS: `true`/`false` = este disparo reparte por pantalla (se empuja por WiFi y
     *   el ACUSE decide el papel de las «sólo pantalla», 3.5 D6); `null` = como antes (KDS de Uber, vale de área).
     * @param origenDelFolio `sale:<Order.externalId>` o `round:<roundKey>`: la base del folio de la marca. `null` = no
     *   se marca.
     * @return el [EstadoDeComanda] final del ruteo, para que el caller pueda AVISAR de una
     *   comanda que no salió (Testarudo cobró días sin comanda de barra porque este resultado
     *   se tiraba). `null` cuando no hubo ruteo que reportar: sin renglones, o el camino
     *   legado ([NoStationsFallback.LegacySingleTicket]), cuyo abanico es fire-and-forget.
     *   El aviso es del caller — imprimir jamás frena un cobro.
     */
    suspend fun dispatch(
        venueId: String?,
        lines: List<RoutableItem>,
        orderNumber: String,
        orderType: String,
        serverName: String? = null,
        noStationsFallback: NoStationsFallback = NoStationsFallback.RouteAnyway,
        maxIntentos: Int = PoliticaDeReintento.INTENTOS_MAXIMOS,
        orderId: String? = null,
        servidorLaTiene: Boolean? = null,
        origenDelFolio: String? = null,
        alCambiarEstado: (EstadoDeComanda) -> Unit = {},
        /** La config de toda la ronda ([despacharEnFondo], Codex 3.6 #4): no se refresca ni se relee la vigente. */
        configDeLaRonda: PrintConfig? = null,
        /** Lo que lee la pantalla de cocina arriba («Mesa 8»); `null` = [orderType]. Ver [Pedido]. */
        etiquetaPantalla: String? = null,
        /** El tiempo de estos renglones en la pantalla de cocina («Aperitivos»). */
        curso: String? = null,
        /** Folios cuyo papel de respaldo NO se marca: van en más de un tiempo de la ronda ([despacharEnFondo], Codex 3.6 #1). */
        noMarcar: (String) -> Boolean = { false },
    ): EstadoDeComanda? {
        // Sin renglones no hay nada que imprimir — y nos ahorramos hasta el refresh, igual que el
        // mostrador, que salía antes de tocar la red. No es un guard de configuración: es que
        // literalmente no hay qué mandar a cocina.
        if (lines.isEmpty()) return null

        // El refresh nunca lanza (falla abierto y conserva la config vigente), así que una red lenta
        // o caída sólo significa "imprime con lo último que sabías" — jamás "no imprimas". Con tope de
        // 1.5 s (spec §5, H2): si la red tarda, se decide con la guardada y la descarga sigue sola.
        if (configDeLaRonda == null) venueId?.let { printConfigRepository.refreshConTope(it) }
        val config = configDeLaRonda ?: printConfigRepository.getCurrentConfig()

        val legacy = noStationsFallback as? NoStationsFallback.LegacySingleTicket
        if (legacy != null && config.stations.none { it.active } && lines.none { it.serviceCourse?.preparationVersion == 1 }) {
            val resultadoLegado = printerService.autoPrintKitchenTicket(
                KitchenTicketData(
                    orderNumber = orderNumber,
                    orderType = orderType,
                    items = legacy.items,
                ),
            )
            // 🔴 Este camino devolvía `null` SIEMPRE, y `null` no distingue «salió» de «tronó».
            // El KDS lo leía como éxito y confirmaba la impresión de una comanda que no existía
            // (P1 #10 y #11 de Codex). Ahora habla: si alguna impresora falló, se dice — con su
            // nombre y sin botón de reimprimir, porque aquí no hay planes que reenviar.
            val estadoLegado = if (resultadoLegado.salio) {
                EstadoDeComanda.Salio
            } else if (resultadoLegado.intentadas == 0) {
                // No hubo NINGUNA impresora que intentarlo. Cantar «Salio» aquí es la mentira
                // más cara de todas: el mostrador cree que la cocina recibió el pedido.
                EstadoDeComanda.NoSalio(
                    listOf("Cocina"),
                    "No hay ninguna impresora de cocina configurada.",
                    orderNumber,
                    trabajo = null,
                )
            } else {
                EstadoDeComanda.NoSalio(
                    resultadoLegado.fallidas, "La impresora no respondió.", orderNumber, trabajo = null,
                )
            }
            alCambiarEstado(estadoLegado)
            return estadoLegado
        }

        val plans = PrintRoutingMapper.buildComandas(lines, config)
        val paperPlans = plans.map { it.copy(lines = it.lines.filterNot { line ->
            line.serviceCourse?.preparationVersion == 1 && line.serviceCourse.kind == "STANDARD"
        }) }.filter { it.lines.isNotEmpty() }
        val comboNames = comboNamesDe(lines)
        // Etapa 3 del KDS (3.5, D6): «WiFi primero». Sólo si el llamador reparte por pantalla (`servidorLaTiene != null`:
        // mostrador y rondas; Uber y vales no) y hay folio (sin folio no hay `sourceKey` que empujar). La entrega se
        // GUARDA antes de conectar; el acuse por ESTACIÓN es lo que decide el papel de las «sólo pantalla».
        val entregas = if (servidorLaTiene != null && venueId != null && origenDelFolio != null) {
            entregasPorWifi(venueId, plans, config, orderNumber, orderType, serverName, comboNames, orderId, origenDelFolio, etiquetaPantalla, curso)
        } else {
            emptyList()
        }
        // 🔴 Ronda 1 (I2): SIN `finally`. Si algo truena (el bind AIDL, el callback del llamador) o se cancela antes de
        // decidir el papel, las filas se QUEDAN: no salió papel ni quedó nada en la libreta. Rondas 2 y 3: además se
        // SUELTAN para que el reloj del replay las retome en este mismo proceso — la cancelación, bajo `NonCancellable`
        // (ya no hay ámbito vivo). Revisión final (I1): el EMPUJE también va aquí adentro. Afuera, cancelar a media entrega
        // (el cajero sale de la pantalla de cobro) dejaba las filas «en curso» —el replay las salta— hasta reiniciar, y a
        // las 8 h se tiraban sin papel. Cancelado mientras empuja, `acusadas` sigue vacío: se sueltan todas.
        var acusadas = emptySet<String>()
        val (reparto, estado) = try {
            if (entregas.isNotEmpty()) acusadas = entregaPorWifi?.entregar(entregas).orEmpty()
            val decidido = servidorLaTiene?.let { KitchenDeliveryPolicy.decidir(paperPlans, config, acusadas) }
                ?: KitchenDeliveryPolicy.Reparto(paperPlans, emptyList())
            // Todo iba a pantallas que acusaron: no hay papel que mandar ni que reportar a «la libreta» (y sus filas ya se
            // borraron al acusar). M-3 de la revisión (3.4): con `servidorLaTiene = null` esto también puede vaciar `plans`
            // (todas las líneas con `quantity <= 0`) y regresa `null` sin avisar — cambio aceptado, iOS trae la misma guarda.
            if (decidido.aImprimir.isEmpty()) return null
            decidido to reintentoDeComanda.insistir(
                plans = decidido.aImprimir,
                // La config CONGELADA del trabajo lleva marcadas las estaciones de respaldo: `ComandaPrinter` les pone su
                // encabezado y su impresora, y `TrabajoPendiente` lo conserva para cualquier reintento.
                config = KitchenDeliveryPolicy.conRespaldo(config, decidido.respaldo),
                orderNumber = orderNumber,
                orderType = orderType,
                serverName = serverName,
                // COMBOS — el nombre viaja aparte del motor de ruteo (que es espejo byte a byte
                // del server y no sabe de promociones) y se vuelve a atar por `orderItemId` ya
                // ruteado, para que cada estación encabece SUS productos con su combo.
                comboNames = comboNames,
                maxIntentos = maxIntentos,
                // «La libreta» (Task 16) — el MISMO venueId que ya se usa arriba para el refresh.
                venueId = venueId,
                orderId = orderId,
                alCambiarEstado = alCambiarEstado,
            )
        } catch (e: CancellationException) {
            withContext(NonCancellable) { soltarSinPapel(entregas, acusadas) }
            throw e
        } catch (e: Exception) {
            soltarSinPapel(entregas, acusadas)
            throw e
        }
        if (reparto.respaldo.isNotEmpty()) {
            marcarPapelDeRespaldo(venueId, reparto.respaldo, config, estado, origenDelFolio, orderNumber, noMarcar)
        }
        cerrarLasDecididas(entregas, acusadas, estado)
        return estado
    }

    /** El despacho no llegó a decidir el papel (truena o se cancela): todas sus filas guardadas sin acuse se sueltan. */
    private suspend fun soltarSinPapel(entregas: List<EntregaKds>, acusadas: Set<String>) {
        val guardadas = entregas.filter { it.entregaId != null && it.mensaje.stationId !in acusadas }
        if (guardadas.isNotEmpty()) entregaPorWifi?.soltar(guardadas)
    }

    /**
     * Ronda 1 (I2) — D7: la fila de una entrega se borra cuando su papel ya se DECIDIÓ. Las que acusaron ya se borraron;
     * aquí se cierran las de respaldo cuyo papel SALIÓ y las de una estación SIN impresora (insistir no le inventa una:
     * reintentarla en cada apertura sólo repetiría el mismo «no hay impresora»; su pantalla la verá por el servidor).
     * La que TRONÓ se queda: va en el `NoSalio` que el llamador guarda en la libreta, y la cierra el papel cuando por fin
     * sale ([reintentar] → [EntregaPorWifi.cerrarPorPapel]). Ronda 2 (N2): además se SUELTA, para que el reloj del
     * replay la retome en este mismo proceso si la libreta la pierde (su única ranura la pisa otra falla).
     */
    private suspend fun cerrarLasDecididas(entregas: List<EntregaKds>, acusadas: Set<String>, estado: EstadoDeComanda) {
        val tronaron = (estado as? EstadoDeComanda.NoSalio)?.trabajo?.planes.orEmpty()
        val guardadas = entregas.filter { e -> e.trabajoDeRespaldo != null && e.mensaje.stationId !in acusadas }
        val (retenidas, decididas) = guardadas.partition { e -> e.trabajoDeRespaldo!!.planes.any { it in tronaron } }
        if (decididas.isNotEmpty()) entregaPorWifi?.cerrar(decididas)
        if (retenidas.isNotEmpty()) entregaPorWifi?.soltar(retenidas)
    }

    /** Una entrega por plan con pantalla (D6). Trabajo de respaldo SÓLO para «sólo pantalla»: impresora+pantalla imprime su papel de todos modos. */
    private fun entregasPorWifi(
        venueId: String,
        plans: List<TicketPlan>,
        config: PrintConfig,
        orderNumber: String,
        orderType: String,
        serverName: String?,
        comboNames: Map<String, String>,
        orderId: String?,
        origen: String,
        etiquetaPantalla: String? = null,
        curso: String? = null,
    ): List<EntregaKds> {
        val deviceId = entregaPorWifi?.deviceId ?: return emptyList()
        val ahora = System.currentTimeMillis()
        return KitchenDeliveryPolicy.planesConPantalla(plans, config).map { plan ->
            val stationId = requireNotNull(plan.stationId)
            val soloPantalla = config.stations.firstOrNull { it.id == stationId }?.let { KitchenDeliveryPolicy.esSoloPantalla(it) } == true
            EntregaKds(
                mensaje = KitchenDeliveryPolicy.mensajeParaPantalla(
                    plan, venueId, deviceId, origen, orderNumber, etiquetaPantalla ?: orderType, orderId, ahora, curso,
                ),
                trabajoDeRespaldo = if (soloPantalla || plan.lines.any { it.serviceCourse?.preparationVersion == 1 }) {
                    TrabajoPendiente(
                        planes = listOf(plan.copy(lines = plan.lines.filterNot {
                            it.serviceCourse?.preparationVersion == 1 && it.serviceCourse.kind == "STANDARD"
                        })).filter { it.lines.isNotEmpty() },
                        config = KitchenDeliveryPolicy.conRespaldo(config, listOf(stationId)),
                        orderNumber = orderNumber, orderType = orderType, serverName = serverName,
                        comboNames = comboNames, venueId = venueId, orderId = orderId,
                    )
                } else {
                    null
                },
            )
        }
    }

    /**
     * Etapa 3 del KDS (3.4): encola `KDS_TICKET_MARK FALLBACK_PRINTED` por cada estación de respaldo que SÍ salió en
     * papel: al volver la red, el servidor la esconde de las pantallas (spec §3). Se encola DESPUÉS del papel a
     * propósito — ver [KitchenDeliveryPolicy.marcasDeRespaldo]. Nunca lanza: una marca que no se guardó sólo significa
     * que la pantalla también la mostrará al volver la red.
     */
    private suspend fun marcarPapelDeRespaldo(
        venueId: String?,
        respaldo: List<String>,
        config: PrintConfig,
        estado: EstadoDeComanda,
        origen: String?,
        label: String,
        noMarcar: (String) -> Boolean,
    ) {
        val vId = venueId ?: return
        val cola = syncOutbox ?: return
        val sinPapel = (estado as? EstadoDeComanda.NoSalio)?.estaciones.orEmpty()
        val (compartidas, marcas) = KitchenDeliveryPolicy.marcasDeRespaldo(respaldo, config, sinPapel, origen, label)
            .partition { noMarcar(it.sourceKey) }
        if (compartidas.isNotEmpty()) Log.i(TAG, "🧾 Papel de un tiempo sin marca: ${compartidas.map { it.sourceKey }} va en otros tiempos")
        if (marcas.isEmpty()) Log.w(TAG, "🧾 Respaldo sin marca (origen=$origen, sin papel=$sinPapel)")
        for (m in marcas) {
            try {
                cola.enqueue(
                    vId,
                    SyncIntentTypes.KDS_TICKET_MARK,
                    buildJsonObject {
                        put("sourceKey", m.sourceKey)
                        put("stationId", m.stationId)
                        put("action", KitchenDeliveryPolicy.FALLBACK_PRINTED)
                        put("label", m.label)
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo encolar la marca de papel ${m.sourceKey}: ${e.message}")
            }
        }
    }

    /**
     * PRE-PAGO — punto de entrada del vale de área (§5.6). Lo llama
     * `AreaTicketOperationsViewModel.issue` al emitir (La Galeterie, 5-oct-2026); el regreso pagado
     * (`AREA_TICKET_PAID`) todavía no tiene quien lo llame.
     *
     * Quien emita el vale es quien debe haber comprobado que el venue tiene `AREA_TICKETS`. Aquí NO
     * se comprueba, a propósito: un feature flag adentro del camino de impresión es justo el guard
     * que ya dejó a un local entero sin comandas.
     *
     * @return `true` si la comanda se disparó; `false` si el modo del área no la pide en este
     *   momento (ver [AreaComandaPolicy], que es donde vive esa decisión y donde se prueba).
     */
    /**
     * «Volver a imprimir»: reenvía el [TrabajoPendiente] congelado de un fallo, tal cual.
     *
     * 🔴 NO reconstruye nada. Reconstruir desde el carrito —lo que hacía antes— duplicaba las
     * estaciones que sí imprimieron y, si el cajero ya había empezado otra venta, imprimía la
     * venta equivocada (P1 #2 y #3 de la auditoría de Codex, 2026-09-07).
     */
    suspend fun reintentar(
        trabajo: TrabajoPendiente,
        alCambiarEstado: (EstadoDeComanda) -> Unit = {},
        /**
         * `false` = quien llama ya refrescó con [refrescarConfig]. El replay de entregas (KDS 3.5, paridad con iOS) lo
         * hace aparte para revalidar la sucursal ANTES y DESPUÉS del refresco: aquí no se vuelve a refrescar.
         */
        refrescar: Boolean = true,
    ): EstadoDeComanda {
        // 🔴 Se congela QUÉ imprimir, no DÓNDE. La config vieja llevaba la dirección de la
        // impresora en el momento del fallo, así que corregir la IP en Ajustes y tocar «Volver
        // a imprimir» seguía marcando la dirección averiada — para siempre, y también después
        // de reiniciar (P2 #10 de la 2ª auditoría de Codex, 2026-09-07).
        //
        // El refresh falla abierto (conserva la config vigente), así que sin red esto es
        // exactamente lo de antes: se reimprime con lo último que el aparato sabía.
        if (refrescar) trabajo.venueId?.let { refrescarConfig(it) }
        // Etapa 3 del KDS (3.4): la config vigente, pero con las estaciones que iban de RESPALDO marcadas otra vez — si
        // no, «Volver a imprimir» sacaría la hoja sin su encabezado y sin la impresora de la default.
        val configVigente = KitchenDeliveryPolicy.heredarRespaldo(de = trabajo.config, en = printConfigRepository.getCurrentConfig())
        val estado = reintentoDeComanda.reintentar(
            trabajo.copy(config = configVigente),
            alCambiarEstado = alCambiarEstado,
        )
        cerrarPapelQueSalio(trabajo, estado)
        return estado
    }

    /** El refresco con tope de [reintentar], suelto: falla abierto (sin red conserva la config vigente). */
    suspend fun refrescarConfig(venueId: String) {
        printConfigRepository.refreshConTope(venueId)
    }

    /**
     * Etapa 3 del KDS (3.5), ronda 1 (I2): el papel de RESPALDO que por fin salió («Volver a imprimir», el reloj de la
     * libreta, el replay) cierra su entrega pendiente; si no, la próxima apertura lo imprimiría otra vez. Sólo mira las
     * estaciones que el trabajo lleva marcadas de respaldo: un trabajo sin respaldo no toca la base.
     */
    private suspend fun cerrarPapelQueSalio(trabajo: TrabajoPendiente, estado: EstadoDeComanda) {
        val vId = trabajo.venueId ?: return
        val respaldo = trabajo.config.stations.filter { it.respaldoLocal }.map { it.id }.toSet()
        if (respaldo.isEmpty()) return
        val tronaron = (estado as? EstadoDeComanda.NoSalio)?.trabajo?.planes.orEmpty()
        val salieron = trabajo.planes.filter { it.stationId in respaldo && it !in tronaron }
        if (salieron.isNotEmpty()) entregaPorWifi?.cerrarPorPapel(vId, trabajo.orderNumber, salieron)
    }

    suspend fun dispatchAreaComanda(
        venueId: String?,
        lines: List<RoutableItem>,
        areaTicketCode: String,
        areaName: String?,
        mode: FulfillmentMode,
        moment: ComandaMoment,
        serverName: String? = null,
        /** Lo mismo que en [dispatch]: una comanda que se rindió se le DICE al cajero que emitió el vale. */
        alCambiarEstado: (EstadoDeComanda) -> Unit = {},
    ): Boolean {
        if (!AreaComandaPolicy.shouldPrint(mode, moment)) {
            Log.d(TAG, "🎟️ Vale $areaTicketCode: $mode no pide comanda en $moment — se omite")
            return false
        }

        dispatch(
            venueId = venueId,
            lines = lines,
            // El vale (10 dígitos) es el papel que el cliente trae en la mano y contra el que el
            // área compara. El `orderNumber` de la orden es `ORD-<epoch>`, 17 caracteres, y no cabe
            // confiable en 58 mm (§4.4 del spec).
            orderNumber = areaTicketCode,
            orderType = areaTicketHeader(areaName, moment),
            serverName = serverName,
            // Vales: fail-open siempre. Un área sin estación configurada tiene que seguir sacando su
            // papel por la impresora de cocina local.
            noStationsFallback = NoStationsFallback.RouteAnyway,
            alCambiarEstado = alCambiarEstado,
        )
        return true
    }

    /** Encabezado del ticket: el área, y si ya está pagado lo dice — el área lo necesita para saber
     *  si puede entregar o sólo preparar. */
    private fun areaTicketHeader(areaName: String?, moment: ComandaMoment): String {
        val area = areaName?.takeIf { it.isNotBlank() } ?: "Vale de área"
        return if (moment == ComandaMoment.AREA_TICKET_PAID) "$area · PAGADO" else area
    }
}
