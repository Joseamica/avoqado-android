package com.avoqado.pos.inventory.data

import com.avoqado.pos.core.util.Plurales
import com.avoqado.pos.inventory.data.model.StockCount
import com.avoqado.pos.inventory.data.model.StockCountItem
import com.avoqado.pos.inventory.data.model.StockCountType
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * El conteo que se está capturando en ESTE aparato, tal como se guarda en disco.
 *
 * Se escribe ANTES de tocar la red (`todo-funciona-sin-red.md`): si el proceso muere
 * entre teclear una cantidad y el PUT, lo contado sigue aquí. Mindform (7-sep-2026)
 * contó 3 minutos, salió, y el servidor se quedó con 0 líneas: no había nada de esto.
 */
@Serializable
data class BorradorDeConteo(
    val venueId: String,
    /**
     * id del conteo en el servidor; null mientras un CÍCLICO no se haya creado allá.
     *
     * El `= null` es cinturón, no tirante, y conviene no creerle de más: MEDIDO el 2026-09-08
     * quitándolo y corriendo las pruebas, el borrador cíclico siguió viajando bien. La razón es
     * que `explicitNulls = false` relaja también la DECODIFICACIÓN — una propiedad nullable
     * ausente se lee como null aunque no traiga default. Se conserva porque vuelve a sostener
     * el peso si alguien restaura `explicitNulls = true` sobre un JSON viejo (o de iOS) que
     * nunca escribió la llave, y porque iguala a iOS, donde el `String?` ya es opcional por tipo.
     * Lo que de verdad guarda el contrato es la prueba `P1 un JSON sin countId se lee igual`.
     */
    val countId: String? = null,
    val type: StockCountType,
    /** Las líneas COMPLETAS (con producto y existencia esperada): retomar sin red no puede pedirlas. */
    val lineas: List<StockCountItem>,
    val nota: String = "",
    /**
     * `true` cuando [nota] difiere de la última nota reconocida por el servidor, incluso si la
     * intención local es dejarla vacía. `null` identifica un borrador anterior a este marcador:
     * por seguridad, una nota vieja no vacía se considera pendiente porque el PUT incremental
     * nunca mandó notas.
     */
    val notaPendienteDeEnviar: Boolean? = null,
    /** ids de líneas contadas aquí que el servidor todavía no confirmó por PUT. */
    val pendientesDeEnviar: Set<String> = emptySet(),
    /** Revisión real del servidor. null = el borrador viejo no conoce su base; nunca significa 0. */
    val revision: Int? = null,
    /** Un conflicto o base desconocida pausa cualquier replay hasta resolución humana. */
    val conflictoRevision: ConflictoRevision? = null,
    /** Revisión reconocida por el PUT final; sólo un nuevo toque de confirmar puede consumirla. */
    val revisionConPutFinalConfirmado: Int? = null,
    val actualizadoEn: Long,
)

@Serializable
data class ConflictoRevision(
    val code: String,
    val message: String,
    val venueId: String,
    val countId: String,
    val expectedRevision: Int? = null,
    val currentRevision: Int? = null,
    val status: String? = null,
)

@Serializable
data class CancelacionPendienteDeConteo(
    val venueId: String,
    val countId: String,
    val expectedRevision: Int? = null,
    val conflictoRevision: ConflictoRevision? = null,
)

/** Qué hacer con la respuesta de un PUT incremental. */
enum class DestinoDelEnvio { ENVIADO, REINTENTAR, RECHAZADO, CONFLICTO }

/** Qué hacer con la respuesta de un POST …/cancel. */
enum class DestinoDeLaCancelacion { HECHA, REINTENTAR, RECHAZADA }

/**
 * Reglas PURAS del conteo en curso: sin red, sin disco, sin reloj. Espejo exacto de
 * `ConteoEnCurso.swift` en avoqado-ios — cambiar una regla aquí obliga a cambiarla allá.
 */
object ConteoEnCurso {

    const val CODIGO_CONFLICTO_REVISION = "INVENTORY_COUNT_REVISION_CONFLICT"
    const val CODIGO_REVISION_DESCONOCIDA = "REVISION_UNKNOWN"
    const val CODIGO_APLICANDO = "STOCK_COUNT_APPLYING"
    const val CONFLICTO_REVISION =
        "El conteo cambió en el servidor. Tu avance se conserva en este aparato."
    const val REVISION_DESCONOCIDA =
        "No pudimos comprobar si este conteo cambió. Tu avance se conserva en este aparato."

    const val TITULO_SALIR = "¿Qué hacemos con este conteo?"
    const val GUARDAR_EL_AVANCE = "Guardar el avance"
    const val DESCARTAR = "Descartar el conteo"
    const val SEGUIR_CONTANDO = "Seguir contando"
    const val SALIR_Y_CONSERVAR = "Salir y conservar para consulta"
    const val SEGUIR_VIENDO = "Seguir viendo"
    const val DESCRIPCION_DESCARTAR = "Esto no se puede deshacer."
    const val VOLVER = "Volver"
    const val DETALLE_NO_DISPONIBLE =
        "Este conteo ya no está disponible. Puedes consultar lo que se guardó aquí."
    const val ESTADO_NO_DISPONIBLE = "No disponible"
    const val DETALLE_NO_ACTUALIZADO =
        "No se pudo actualizar el estado. Se muestra la última información guardada."
    const val CARGANDO_ARTICULOS = "Cargando artículos..."
    const val NO_HAY_ARTICULOS_DISPONIBLES = "No hay artículos disponibles"
    const val CONTINUAR = "Continuar"
    const val CONFLICTO = "Este conteo ya se cerró desde otro aparato. Lo que contaste aquí se conserva sólo para consulta."

    /**
     * Un conteo en curso por aparato (como Square). Sale al intentar abrir OTRO conteo mientras
     * el borrador de uno anterior todavía tiene trabajo que el servidor no conoce: empezar otro
     * pisaría ese borrador —hay UNA ranura por sucursal— y lo contado se perdería sin un aviso.
     */
    const val HAY_OTRO_BORRADOR =
        "Tienes un conteo sin terminar en este aparato. Continúalo o descártalo antes de empezar otro."

    /**
     * Confirmar es ONLINE-ONLY a propósito: el ajuste de existencias lo aplica el servidor, y
     * fingirlo aquí sería inventar inventario. Lo que faltaba era DECIRLO — medido en la
     * OrderPAD 3 (7-sep), tocar «Confirmar» sin red dejaba la pantalla idéntica, y el único texto
     * visible era el banner global «Sin conexión — las ventas se guardan en el dispositivo», que
     * habla de VENTAS: se lee como «lo que hice quedó guardado» y el cajero se va creyendo que
     * cerró el conteo.
     */
    const val CONFIRMAR_SIN_RED =
        "Sin conexión: el conteo quedó guardado en este aparato. Confirma cuando vuelva la red."

    const val SIN_CONTEO_ACTIVO =
        "No hay un conteo activo para confirmar. Vuelve a abrirlo e intenta de nuevo."

    /**
     * El conteo pertenece a la sucursal en la que se abrió, y el aparato puede cambiar de sucursal
     * a media captura (el POS lo permite sin cerrar sesión). A partir de ahí, TODO lo que toque la
     * red iría a `/venues/<la otra>/…` con el id de un conteo que allá no existe: el servidor
     * contesta 404 y el cajero vería «este conteo ya se cerró desde otro aparato», que es falso.
     * Su trabajo sigue intacto en el borrador de la sucursal original; lo único que hay que hacer
     * es volver.
     */
    const val CAMBIASTE_DE_SUCURSAL =
        "Cambiaste de sucursal: vuelve a la sucursal del conteo para seguir con este conteo."

    /** El mismo aviso NOMBRANDO la sucursal, que es lo que el cajero necesita para volver. */
    fun cambiasteDeSucursal(nombreDeLaSucursal: String?): String =
        if (nombreDeLaSucursal.isNullOrBlank()) CAMBIASTE_DE_SUCURSAL
        else "Cambiaste de sucursal: vuelve a $nombreDeLaSucursal para seguir con este conteo."

    /**
     * Al crear un cíclico, el servidor DESCARTA en silencio lo que no puede contar (un producto
     * dado de baja entre que se listó y se confirmó, o uno cuyo inventario sale de una receta).
     * Esas líneas no viajan en el PUT —es todo-o-nada: un id que el servidor no reconoce tumba el
     * conteo ENTERO— así que lo contado en ellas no se aplica. Se DICE, nunca se calla.
     */
    fun lineasNoIncluidas(cuantas: Int): String =
        if (cuantas == 1) "1 artículo no entró al conteo: no se cuenta en inventario"
        else "$cuantas artículos no entraron al conteo: no se cuentan en inventario"

    fun descripcionSalir(contadas: Int, total: Int, hayConflicto: Boolean = false): String = when {
        hayConflicto -> "El conteo ya se cerró. Lo que contaste aquí se conservará en este aparato para consulta."
        contadas == 0 -> "Todavía no has contado ningún artículo."
        else -> "Llevas $contadas de $total artículos contados. Si guardas el avance, puedes continuar después desde este aparato."
    }

    fun necesitaConfirmarDescarte(lineas: List<StockCountItem>): Boolean = contadas(lineas) > 0

    fun tituloDescartar(contadas: Int): String =
        if (contadas == 1) "¿Descartar 1 línea contada?" else "¿Descartar $contadas líneas contadas?"

    fun sinResultados(busqueda: String): String = "Sin resultados para \"$busqueda\""

    fun descripcionConflictoRevision(conflicto: ConflictoRevision): String =
        if (conflicto.code == CODIGO_REVISION_DESCONOCIDA) REVISION_DESCONOCIDA else CONFLICTO_REVISION

    fun avisoSinRed(pendientes: Int): String =
        if (pendientes == 1) "Sin conexión — 1 línea guardada en este aparato"
        else "Sin conexión — $pendientes líneas guardadas en este aparato"

    fun avisoPendienteDeSubir(pendientes: Int): String =
        if (pendientes == 1) "1 línea guardada en este aparato sin subir"
        else "$pendientes líneas guardadas en este aparato sin subir"

    /** Compatibilidad segura: antes del marcador, una nota no vacía nunca se había enviado en el avance. */
    fun notaPendienteDeEnviar(borrador: BorradorDeConteo): Boolean =
        borrador.notaPendienteDeEnviar ?: borrador.nota.isNotBlank()

    fun puedeReintentarConfirmacion(borrador: BorradorDeConteo): Boolean =
        borrador.revisionConPutFinalConfirmado != null &&
            borrador.revision == borrador.revisionConPutFinalConfirmado &&
            borrador.pendientesDeEnviar.isEmpty() &&
            !notaPendienteDeEnviar(borrador) &&
            borrador.conflictoRevision == null

    fun tituloBorrador(type: StockCountType): String = when (type) {
        StockCountType.FULL -> "Conteo completo sin terminar en este aparato"
        StockCountType.CYCLE -> "Conteo cíclico sin terminar en este aparato"
    }

    fun avanceTexto(contadas: Int, total: Int): String = "$contadas de $total contados"

    /** Segunda línea de la fila de la lista. Con resumen del servidor y en progreso: cuántas van. */
    fun lineaDeEstado(count: StockCount): String {
        val s = count.summary
        return if (count.status == "IN_PROGRESS" && s != null) "${count.statusDisplay} · ${avanceTexto(s.countedCount, s.itemCount)}"
        else "${count.statusDisplay} - ${Plurales.articulos(count.itemCount)}"
    }

    /**
     * Retomar: el servidor manda la lista completa (con lo que OTRO aparato haya contado) y es la
     * verdad de QUÉ líneas existen — las locales que ya no estén se descartan.
     *
     * 🔴 Lo local gana SÓLO en las líneas todavía PENDIENTES de subir. Una línea que este aparato
     * ya mandó y el servidor confirmó (200) no es «lo mío contra lo suyo»: es MI propio valor que
     * el servidor ya guardó, así que si vuelve distinto es porque OTRO aparato lo corrigió después
     * — y ése es el dato más nuevo. Ganando siempre lo local, retomar resucitaba el valor viejo y
     * al confirmar lo sellaba: el cajero de la otra tablet veía su corrección desaparecer sin un
     * aviso. Es el mismo criterio que la cola del cajón: lo que ya salió deja de ser mío.
     *
     * @param pendientes ids de las líneas contadas aquí que el servidor todavía NO confirmó.
     */
    fun fusionar(
        servidor: List<StockCountItem>,
        local: List<StockCountItem>?,
        pendientes: Set<String>,
    ): List<StockCountItem> {
        if (local.isNullOrEmpty()) return servidor
        val locales = local.filter { it.yaSeConto && it.id in pendientes }.associateBy { it.id }
        return servidor.map { s ->
            val l = locales[s.id] ?: return@map s
            s.copy(counted = l.counted, difference = l.counted - s.expected, countedAt = l.countedAt)
        }
    }

    /**
     * Sobrecarga de compatibilidad: «todo lo contado aquí sigue siendo mío». Vale sólo donde NO
     * hay nada confirmado por el servidor —un cíclico que todavía no existe allá—, y por eso el
     * ViewModel llama SIEMPRE a la de tres parámetros: con un conteo del servidor delante, esta
     * versión es justo el defecto de arriba.
     */
    fun fusionar(servidor: List<StockCountItem>, local: List<StockCountItem>?): List<StockCountItem> =
        fusionar(servidor, local, idsContadas(local.orEmpty()))

    fun primerPendiente(lineas: List<StockCountItem>): Int = when {
        lineas.isEmpty() -> -1
        else -> lineas.indexOfFirst { !it.yaSeConto }.takeIf { it >= 0 } ?: 0
    }

    fun contadas(lineas: List<StockCountItem>): Int = lineas.count { it.yaSeConto }

    fun idsContadas(lineas: List<StockCountItem>): Set<String> = lineas.filter { it.yaSeConto }.map { it.id }.toSet()

    /** Sólo líneas contadas Y pendientes, nunca con id vacío (una cíclica sin crear no tiene id del servidor). */
    fun lineasParaEnviar(lineas: List<StockCountItem>, pendientes: Set<String>): List<StockCountItem> =
        lineas.filter { it.id.isNotBlank() && it.yaSeConto && it.id in pendientes }

    /**
     * 🔴 Lista EXPLÍCITA de rechazos, no un rango (misma lección que el cajón: `400..499`
     * arrastraba el 401 de un token vencido). El 404 es propio de este dominio: el servidor
     * responde así cuando el conteo ya no está IN_PROGRESS — lo cerró o canceló otro aparato.
     */
    fun clasificarEnvio(code: Int): DestinoDelEnvio = when {
        code in 200..299 -> DestinoDelEnvio.ENVIADO
        code == 404 -> DestinoDelEnvio.CONFLICTO
        code in setOf(400, 403, 409, 422) -> DestinoDelEnvio.RECHAZADO
        else -> DestinoDelEnvio.REINTENTAR
    }

    /**
     * ¿Ese 404 lo escribió el SERVIDOR hablando de este conteo, o es el 404 de una RUTA que no
     * existe? Los dos traen el mismo número y significan lo contrario.
     *
     * El de dominio sale de `inventory.mobile.service.ts` («Conteo no encontrado», y su variante
     * «… o ya completado»). El de ruta lo escribe Express cuando nadie registró el endpoint —
     * `avoqado-server/src/app.ts` no monta ningún manejador de 404, así que contesta su HTML por
     * defecto («Cannot POST /api/v1/…») — y también un proxy o el edge de un túnel.
     *
     * Se exigen las DOS marcas porque el coste es asimétrico: dar por hecha una cancelación que no
     * ocurrió deja el conteo `IN_PROGRESS` para siempre y en silencio; conservarla de más sólo la
     * hace esperar.
     */
    private fun es404DeDominio(body: String): Boolean {
        val b = body.lowercase()
        return b.contains("conteo") && b.contains("no encontrado")
    }

    /**
     * Cancelar: si el servidor dice que ya no existe (404 de DOMINIO) o que ya cambió de estado
     * (409), su estado gana.
     *
     * 🔴 El 404 se mira con el CUERPO delante. Una app nueva contra un servidor sin la fase 1
     * recibe el 404 de RUTA de `POST …/cancel`, y tratarlo como «hecha» sacaba la cancelación de
     * la cola sin haber cancelado nada: ese conteo se quedaba abierto para siempre y nadie volvía
     * a intentarlo. Ahora la cola ESPERA al despliegue — el orden de despliegue deja de ser un
     * riesgo silencioso. El 409 sí es terminal venga como venga: sólo lo emite este dominio, y
     * cualquier cuerpo raro con ese código sigue significando «el estado del servidor ya cambió».
     */
    fun clasificarCancelacion(code: Int, body: String): DestinoDeLaCancelacion = when {
        code in 200..299 -> DestinoDeLaCancelacion.HECHA
        code == 404 -> if (es404DeDominio(body)) DestinoDeLaCancelacion.HECHA else DestinoDeLaCancelacion.REINTENTAR
        code == 409 -> DestinoDeLaCancelacion.HECHA
        code in setOf(400, 403, 422) -> DestinoDeLaCancelacion.RECHAZADA
        else -> DestinoDeLaCancelacion.REINTENTAR
    }

    // `encodeDefaults = true`: un borrador guardado hoy debe leerse igual aunque mañana el
    // modelo gane campos con default; `ignoreUnknownKeys` cubre la dirección contraria.
    //
    // `explicitNulls = false`: los nulos NO se escriben. Cada `StockCountItem` tiene 7 campos
    // nullable que casi siempre van vacíos, y este JSON se reescribe ENTERO con `commit()`
    // (fsync) por cada cantidad que teclea el cajero — o sea que esos nulos se pagan por tecla
    // y multiplicados por las líneas del conteo. Mismo `Json` que
    // `payment/data/TerminalPaymentService.kt:81`.
    // Y relaja también la DECODIFICACIÓN: una propiedad nullable ausente se lee como null
    // aunque no traiga default (MEDIDO el 2026-09-08 quitando el `= null` de `countId`: no
    // rompió ninguna prueba). Los defaults de `InventoryModels.kt` se conservan igual, porque
    // vuelven a hacer falta si alguien restaura `explicitNulls = true`.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    fun codificar(borrador: BorradorDeConteo): String = json.encodeToString(borrador)

    fun decodificar(raw: String?): BorradorDeConteo? =
        raw?.let { runCatching { json.decodeFromString<BorradorDeConteo>(it) }.getOrNull() }

    fun codificarCancelaciones(ids: List<String>): String = json.encodeToString(ids)

    /** `null` = eso NO es una lista JSON (p. ej. el id suelto que escribía la versión anterior). */
    fun decodificarCancelaciones(raw: String?): List<String>? =
        raw?.let { runCatching { json.decodeFromString<List<String>>(it) }.getOrNull() }

    fun codificarCancelacionesConRevision(cancelaciones: List<CancelacionPendienteDeConteo>): String =
        json.encodeToString(cancelaciones)

    fun decodificarCancelacionesConRevision(raw: String?): List<CancelacionPendienteDeConteo>? =
        raw?.let { runCatching { json.decodeFromString<List<CancelacionPendienteDeConteo>>(it) }.getOrNull() }
}
