package com.avoqado.pos.kds.domain

import com.avoqado.pos.kds.data.KdsHttpException
import com.avoqado.pos.printing.routing.StationInfo
import java.io.IOException

// Reglas PURAS de la pantalla de cocina (etapa 3, fase 3.3). Espejo EXACTO de `KDSReglas.swift` de avoqado-ios:
// mismos nombres, mismos casos, mismos textos. El servidor (fase 3.1) es la autoridad; esto sólo decide qué enseñar
// para no ofrecer lo que va a rechazar.

// MARK: - Prender la pantalla desde la tablet (espejo de `estadoDeCasilla` del dashboard, fase 3.2)

data class EntradaDeCasilla(
    /** `PrintConfig.kitchenDisplayOpenToClients`: la puerta de lanzamiento (fase 3.6). */
    val abiertaAClientes: Boolean,
    val esSuperadmin: Boolean,
    /** `RoleManager.canManagePrinters` — `printers:manage` (decisión D-B). */
    val puedeConfigurar: Boolean,
    /** `PlanManager.hasFeature("KITCHEN_DISPLAY")` — Pro (decisión D-A). */
    val tieneAccesoPro: Boolean,
    /** `StationInfo.hasKitchenDisplay`: EFECTIVO (casilla Y plan), tal como lo manda el servidor. */
    val prendida: Boolean,
)

enum class MotivoSoloApagar { PLAN, LANZAMIENTO }

/** Qué ve y qué puede hacer quien mira la pantalla de UNA estación. */
sealed interface EstadoDeCasilla {
    data object Oculta : EstadoDeCasilla
    data object SoloLectura : EstadoDeCasilla
    data object RequierePro : EstadoDeCasilla
    data class SoloApagar(val motivo: MotivoSoloApagar) : EstadoDeCasilla
    data class Editable(val soloAvoqado: Boolean) : EstadoDeCasilla
}

/**
 * El MISMO cuerpo que `estadoDeCasilla` del dashboard: prender pasa por la puerta de lanzamiento y por el plan; apagar
 * siempre se puede. ⚠️ En la tablet `prendida` llega EFECTIVA (casilla Y plan), así que `SoloApagar(PLAN)` no se alcanza
 * desde aquí: un negocio que bajó de plan ve `RequierePro` y apaga desde el dashboard. El caso se conserva para que el
 * espejo sea literal.
 */
fun estadoDeCasilla(e: EntradaDeCasilla): EstadoDeCasilla {
    if (e.esSuperadmin) return EstadoDeCasilla.Editable(soloAvoqado = !e.abiertaAClientes)
    if (!e.abiertaAClientes) {
        return if (e.prendida && e.puedeConfigurar) {
            EstadoDeCasilla.SoloApagar(MotivoSoloApagar.LANZAMIENTO)
        } else {
            EstadoDeCasilla.Oculta
        }
    }
    if (!e.puedeConfigurar) return EstadoDeCasilla.SoloLectura
    if (!e.tieneAccesoPro) {
        return if (e.prendida) EstadoDeCasilla.SoloApagar(MotivoSoloApagar.PLAN) else EstadoDeCasilla.RequierePro
    }
    return EstadoDeCasilla.Editable(soloAvoqado = false)
}

fun puedePrender(estado: EstadoDeCasilla, prendida: Boolean): Boolean = !prendida && estado is EstadoDeCasilla.Editable

fun puedeApagar(estado: EstadoDeCasilla, prendida: Boolean): Boolean =
    prendida && (estado is EstadoDeCasilla.Editable || estado is EstadoDeCasilla.SoloApagar)

// MARK: - Estaciones

fun estacionesParaElegir(estaciones: List<StationInfo>): List<StationInfo> =
    estaciones.filter { it.active }.sortedWith(compareBy<StationInfo>({ it.displayOrder }, { it.name }))

/** La estación guardada en ESTE aparato, si todavía existe y está activa. Si no, la tablet vuelve a preguntar. */
fun estacionElegida(guardada: String?, estaciones: List<StationInfo>): StationInfo? =
    guardada?.let { id -> estaciones.firstOrNull { it.id == id && it.active } }

/**
 * La etiqueta de una comanda en el tablero de `elegida`: nada si es suya; «Sin estación» si el servidor no la repartió
 * (sale en todas); el nombre de la otra estación si llegó porque esa ya no tiene pantalla activa.
 */
fun etiquetaDeEstacion(printStationId: String?, elegida: String, estaciones: List<StationInfo>): String? =
    when (printStationId) {
        elegida -> null
        null -> TextosDeCocina.SIN_ESTACION
        else -> estaciones.firstOrNull { it.id == printStationId }?.name ?: TextosDeCocina.SIN_ESTACION
    }

// MARK: - Tablero

/** El tope del servidor (`KDS_LIST_MAX` y `KDS_BUMP_BATCH_MAX`). */
const val TOPE_DEL_TABLERO = 100

/** «Marcar todas listas»: nunca un delivery que nadie ha aceptado (terminarlo le diría «listo» a Uber). */
fun idsParaMarcarTodas(comandas: List<KDSOrder>): List<String> =
    comandas.filterNot { it.needsAcceptance }.map { it.id }.take(TOPE_DEL_TABLERO)

// MARK: - KDS 3.6: los tiempos de una mesa

/** Los platillos de UN tiempo de la tarjeta. [tiempo] `null` = sin encabezado. */
data class GrupoDeTiempo(val tiempo: String?, val items: List<KDSOrderItem>)

/**
 * Los platillos agrupados por tiempo, en el orden en que aparece cada uno (la caja los manda en el orden del menú). Sin
 * ningún tiempo ⇒ un grupo sin encabezado: la venta de mostrador se ve como siempre. Con tiempos, lo que no trae va bajo
 * «Inmediato», el mismo nombre del «¡MARCHAR Inmediato!» del papel.
 */
fun gruposPorTiempo(items: List<KDSOrderItem>): List<GrupoDeTiempo> {
    if (items.none { it.course != null }) return listOf(GrupoDeTiempo(null, items))
    return items.groupBy { it.course ?: TextosDeCocina.INMEDIATO }.map { (tiempo, suyos) -> GrupoDeTiempo(tiempo, suyos) }
}

// MARK: - Etapa 3 del KDS (3.5, D9): la mezcla por folio

/** Prefijo del id sintético de una comanda que SÓLO está en este aparato (llegó por WiFi y el servidor aún no la manda). */
const val ID_LAN = "lan:"

fun esLocal(id: String): Boolean = id.startsWith(ID_LAN)

fun KdsTicketLocal.aKDSOrder(): KDSOrder = KDSOrder(
    id = ID_LAN + sourceKey,
    orderId = null,
    orderNumber = orderNumber,
    orderType = orderType,
    items = items,
    createdAt = recibidaEnMillis,
    status = KDSOrderStatus.NEW,
    sourceKey = sourceKey,
    printStationId = stationId,
)

/**
 * `servidor ∪ locales` por folio: la copia del servidor GANA (la local se descarta); las locales sin copia del servidor
 * se muestran con id `lan:<folio>`; los folios marcados LISTO sin red (`listaEnMillis != null`) NO se muestran aunque el
 * servidor los devuelva (hasta que deje de devolverlos o venzan las 12 h). Se llama en cada consulta buena, cuando llega
 * una comanda por WiFi y cuando falla la consulta (sin red se muestra lo guardado). PURA; espejo de `KDSReglas.juntarPorFolio`.
 */
fun juntarPorFolio(servidor: List<KDSOrder>, locales: List<KdsTicketLocal>): List<KDSOrder> {
    val listas = locales.filter { it.listaEnMillis != null }.map { it.sourceKey }.toSet()
    val delServidor = servidor.filterNot { it.sourceKey != null && it.sourceKey in listas }
    val foliosDelServidor = servidor.mapNotNull { it.sourceKey }.toSet()
    val soloLocales = locales.filter { it.listaEnMillis == null && it.sourceKey !in foliosDelServidor }.map { it.aKDSOrder() }
    return (delServidor + soloLocales).sortedBy { it.createdAt }
}

// MARK: - Errores: sin red es un ESTADO, no un error rojo

data class AvisoDeCocina(val texto: String, val esError: Boolean)

enum class AccionDeCocina(val sinRed: String, val generico: String) {
    LISTO("Sin conexión: la comanda sigue pendiente. Márcala cuando vuelva la red.", "No se pudo marcar la comanda como lista."),
    DESHACER("Sin conexión: la comanda no regresó. Inténtalo cuando vuelva la red.", "No se pudo regresar la comanda."),
    MARCAR_TODAS("Sin conexión: las comandas siguen pendientes. Inténtalo cuando vuelva la red.", "No se pudieron marcar las comandas."),
    RECIENTES("Sin conexión: no se pueden ver las recientes. Inténtalo cuando vuelva la red.", "No se pudieron cargar las recientes."),
    PANTALLA("Sin conexión: la pantalla no cambió. Inténtalo cuando vuelva la red.", "No se pudo cambiar la pantalla."),
}

/** Códigos con que el servidor rechaza PRENDER una pantalla (fase 3.1, `assertPuedePrenderPantalla`). */
const val RECHAZO_NO_LANZADA = "KITCHEN_DISPLAY_NOT_RELEASED"
const val RECHAZO_REQUIERE_PRO = "KITCHEN_DISPLAY_REQUIRES_PRO"

/** Sin red o sin servidor (un 502-504 del proxy no dice nada del negocio). */
fun esSinRed(error: Throwable): Boolean = error is IOException || (error is KdsHttpException && error.status in 502..504)

fun avisoDeFallo(error: Throwable, accion: AccionDeCocina): AvisoDeCocina = when {
    esSinRed(error) -> AvisoDeCocina(accion.sinRed, esError = false)
    error is KdsHttpException && error.codigo == RECHAZO_NO_LANZADA -> AvisoDeCocina(TextosDeCocina.NO_LANZADA, esError = false)
    error is KdsHttpException && error.codigo == RECHAZO_REQUIERE_PRO -> AvisoDeCocina(TextosDeCocina.REQUIERE_PRO, esError = false)
    error is KdsHttpException -> AvisoDeCocina(error.message ?: accion.generico, esError = true)
    else -> AvisoDeCocina(accion.generico, esError = true)
}

// MARK: - Textos (los MISMOS en `KDSReglas.swift`; los de la casilla, los del dashboard 3.2)

object TextosDeCocina {
    const val SIN_CONEXION = "Sin conexión: la pantalla se pone al día cuando vuelva la red"
    /** 3.5, D11: sin internet pero con el receptor del WiFi vivo — la pantalla sigue recibiendo. */
    const val SIN_INTERNET_CON_WIFI = "Sin internet: recibiendo por el WiFi del local"
    /** Mientras llega la config por primera vez: nunca se afirma «no hay estaciones» antes de saberlo. */
    const val CARGANDO = "Cargando estaciones…"
    const val SIN_ESTACION = "Sin estación"
    /** 3.6: el encabezado de los platillos sin tiempo en una mesa con tiempos. */
    const val INMEDIATO = "Inmediato"
    const val ELEGIR_TITULO = "¿Qué estación es esta pantalla?"
    const val ELEGIR_DETALLE = "Cada pantalla enseña sólo las comandas de su estación. Se guarda en este aparato."
    const val ELEGIDA_YA_NO_EXISTE = "La estación que tenías elegida ya no existe o se desactivó. Elige otra."
    const val SIN_ESTACIONES = "No hay estaciones para elegir. Créalas en el dashboard, en Impresoras y estaciones › Estaciones."
    const val CON_PANTALLA = "Con pantalla"
    const val SIN_PANTALLA = "Sin pantalla"
    const val SOLO_QUIEN_CONFIGURA = "Sólo quien configura impresoras puede prenderla: el gerente, el administrador o el dueño."
    const val REQUIERE_PRO = "La pantalla de cocina es del plan Pro."
    const val PIDE_MEJORAR = "Pídele al dueño que mejore el plan."
    const val SOLO_AVOQADO_INSIGNIA = "Sólo Avoqado"
    const val SOLO_AVOQADO = "Sólo Avoqado la ve: la pantalla todavía no está abierta a clientes."
    const val PILOTO = "Avoqado la prendió como prueba. Puedes apagarla."
    const val NO_LANZADA = "La pantalla de cocina todavía no está disponible para clientes. Pídele a Avoqado que la active."
    const val CAMBIAR_ESTACION = "Cambiar de estación"
    const val LISTO = "Listo"
    const val RECIENTES = "Recientes"
    const val RECIENTES_DETALLE = "Lo que se marcó como listo en la última hora"
    const val RECIENTES_VACIO = "Nada se marcó como listo en la última hora."
    const val DESHACER = "Deshacer"
    const val MARCAR_TODAS = "Marcar todas listas"
    const val MARCAR_TODAS_TITULO = "¿Marcar todas como listas?"
    const val SIN_COMANDAS = "Sin comandas pendientes"
    const val AJUSTES_TITULO = "Configuración de cocina"
    const val SONIDO = "Sonido al llegar una comanda"
    const val SONIDO_DETALLE = "Suena cuando entra una comanda nueva"
    const val LETRA_GRANDE = "Letra grande"
    const val LETRA_GRANDE_DETALLE = "Agranda el texto de las comandas"
    const val PRENDER = "Prender pantalla"
    const val APAGAR = "Apagar pantalla"
    const val SIN_IMPRESORA_AL_PRENDER = "Como no tiene impresora, sólo saldrán en papel si la pantalla no contesta."
    const val APAGAR_SIN_IMPRESORA = "Las comandas dejan de llegar a la pantalla y vuelven a salir en la impresora de cocina de la caja."
    const val ENTENDIDO = "Entendido"
    const val CANCELAR = "Cancelar"

    fun sinPantalla(estacion: String) = "$estacion no tiene pantalla de cocina"
    fun prenderPara(estacion: String) = "Prender pantalla para $estacion"
    fun prenderTitulo(estacion: String) = "¿Prender la pantalla de $estacion?"
    fun prenderDetalle(estacion: String) =
        "Las comandas de $estacion van a aparecer en la pantalla de cocina, empezando por las que lleguen desde ahora."
    fun conImpresora(impresora: String) = "También siguen saliendo en la impresora $impresora."
    fun apagarTitulo(estacion: String) = "¿Apagar la pantalla de $estacion?"
    fun apagarConImpresora(impresora: String) =
        "Las comandas dejan de llegar a la pantalla y siguen saliendo en la impresora $impresora."
    fun apagarEstacion(estacion: String) = "Apagar la pantalla de $estacion"
    fun prendida(estacion: String) = "Pantalla de $estacion prendida"
    fun apagada(estacion: String) = "Pantalla de $estacion apagada"
    fun vacioDe(estacion: String) = "Las comandas nuevas de $estacion aparecen aquí"
    fun marcarTodasDetalle(n: Int, estacion: String) =
        "Las $n comandas de $estacion salen de la pantalla. Si te equivocas, las regresas desde «Recientes»."
    fun marcarN(n: Int) = "Marcar $n listas"

    /**
     * «100+» al llegar al tope del servidor: sin `X-Total-Count` la tablet no distingue 100 exactas de más de 100, y
     * se prefiere decir de más que esconder comandas en silencio (con exactamente 100 también dice «100+»).
     */
    fun pendientes(n: Int): String = when {
        n >= TOPE_DEL_TABLERO -> "$TOPE_DEL_TABLERO+ pendientes"
        n == 1 -> "1 pendiente"
        else -> "$n pendientes"
    }
}
