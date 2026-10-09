package com.avoqado.pos.kds.data

import android.util.Log
import com.avoqado.pos.core.data.lan.KdsComanda
import com.avoqado.pos.core.data.lan.KdsComandaItem
import com.avoqado.pos.kds.data.local.KdsTicketLocalEntity
import com.avoqado.pos.kds.data.local.KdsTicketsLocalesDao
import com.avoqado.pos.kds.domain.KDSOrder
import com.avoqado.pos.kds.domain.KDSOrderItem
import com.avoqado.pos.kds.domain.KdsTicketLocal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "KdsTicketsLocales"

/**
 * Etapa 3 del KDS (3.5, D8) — lo que la PANTALLA guarda en el aparato: la comanda que llegó por WiFi (ANTES de acusar;
 * el mismo folio UNE renglones) y el LISTO sin red (`listaEnMillis`, pegajoso). Vive fuera del ViewModel (que muere con
 * la pantalla). Espejo de `KdsTicketsLocalesStore.swift`.
 */
@Singleton
class KdsTicketsLocalesStore @Inject constructor(private val dao: KdsTicketsLocalesDao) {

    private val json = Json { ignoreUnknownKeys = true }
    private val items = ListSerializer(KdsComandaItem.serializer())

    /**
     * Commit en disco; `true` SÓLO si quedó guardada — el acuse sale sólo entonces. Nunca lanza.
     *
     * Task 8b (hallazgo de pérdida): un curso que llega sobre una fila YA LISTA (`listaEnMillis != null`) con
     * renglones que esa fila no tenía NO se guarda ni se acusa — mezclarlo la dejaría LISTA y oculta, y la cocina
     * nunca vería el curso nuevo. Sin acuse la caja imprime el papel de respaldo. Un reenvío de LOS MISMOS renglones
     * (nada nuevo) y cualquier curso sobre una fila PENDIENTE siguen el camino de hoy: se guarda y se acusa.
     *
     * Ronda 1 (I1): TODA la regla vive en la transacción del DAO ([KdsTicketsLocalesDao.unir]) — leer la fila, decidir y
     * escribir en un solo paso. Leerla aquí y escribir en otra transacción dejaba una ventana: un LISTO que cayera
     * entre las dos escondía el curso ya acusado.
     */
    suspend fun unir(comanda: KdsComanda, ahora: Long = System.currentTimeMillis()): Boolean = try {
        require(comanda.preparationVersion != 1 || comanda.items.all {
            it.serviceCourse?.preparationVersion == 1 && it.preparation?.validFor(it.quantity) == true && it.externalId != null
        }) { "La comanda necesita identificadores y estados por producto" }
        val guardada = dao.unir(
            KdsTicketLocalEntity(
                sourceKey = comanda.sourceKey, venueId = comanda.venueId, stationId = comanda.stationId,
                orderNumber = comanda.orderNumber, orderType = comanda.orderType,
                itemsJson = json.encodeToString(items, comanda.items), recibidaEnMillis = ahora, listaEnMillis = null,
            ),
            idsEntrantes = comanda.items.map { it.id }.toSet(),
        )
        if (!guardada) Log.i(TAG, "${comanda.sourceKey}: curso nuevo sobre una comanda ya LISTA — sin acuse, sale en papel")
        guardada
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "❌ No se pudo guardar la comanda ${comanda.sourceKey}: ${e.message}")
        false
    }

    /**
     * LISTO sin red (D10). Una comanda que llegó por WiFi ya tiene fila (se marca); una del servidor no (se crea la sombra
     * con la marca puesta, para que un sondeo no la resucite). Revisión final (I2): marcar, leer y crear van en UNA
     * transacción del DAO ([KdsTicketsLocalesDao.marcarListaOCrear]) — aquí sólo se arma la sombra. Espejo de iOS.
     */
    suspend fun marcarLista(orden: KDSOrder, venueId: String, stationId: String, ahora: Long = System.currentTimeMillis()) {
        check(orden.preparationVersion != 1) { "Usa las acciones de cada producto" }
        val sourceKey = orden.sourceKey ?: return
        dao.marcarListaOCrear(
            KdsTicketLocalEntity(
                sourceKey = sourceKey, venueId = venueId, stationId = stationId, orderNumber = orden.orderNumber,
                orderType = orden.orderType,
                itemsJson = json.encodeToString(items, orden.items.map { KdsComandaItem(it.id, it.productName, it.quantity, it.modifiers, it.notes, it.course) }),
                // I2 (revisión T8): la vigencia de 12 h cuenta desde que ESTE aparato guardó la marca. Con el `createdAt`
                // del servidor, una comanda de ayer marcada sin red nacía vencida: la purga (cada minuto) la borraba y
                // la comanda volvía al tablero. Una fila LISTA nunca se pinta, así que su orden no importa.
                recibidaEnMillis = ahora, listaEnMillis = ahora,
            ),
            ahora,
        )
    }

    /** Pendientes Y listas de la estación (la mezcla necesita las dos), en orden de llegada. */
    fun deLaEstacion(venueId: String, stationId: String): Flow<List<KdsTicketLocal>> =
        dao.deLaEstacion(venueId, stationId).map { filas -> filas.map { it.aDominio() } }

    /**
     * D9, ronda 1: el servidor ya devolvió estos folios — su copia gana también en el DISCO. Se borran las locales
     * PENDIENTES (las LISTAS se quedan: esconden la del servidor hasta que procese el BUMP). Sin esto, al marcarla LISTO en
     * línea el servidor deja de mandarla y la copia del WiFi resucitaba. Nunca lanza: lo peor es que resucite.
     */
    suspend fun retirarPendientes(folios: Collection<String>) {
        if (folios.isEmpty()) return
        runCatching { dao.retirarPendientes(folios.toList()) }
            .onFailure { Log.w(TAG, "No se pudieron retirar las comandas que ya tiene el servidor: ${it.message}") }
    }

    /** «Deshacer» en línea: la marca LISTO local deja de esconder la comanda que el servidor regresó. Nunca lanza. */
    suspend fun quitarLista(sourceKey: String) {
        runCatching { dao.quitarLista(sourceKey) }
            .onFailure { Log.w(TAG, "No se pudo quitar la marca LISTO de $sourceKey: ${it.message}") }
    }

    suspend fun purgar(venueId: String, ahora: Long = System.currentTimeMillis()) {
        runCatching { dao.purgar(venueId, ahora - VIGENCIA_MS) }
    }

    private fun KdsTicketLocalEntity.aDominio() = KdsTicketLocal(
        sourceKey = sourceKey, venueId = venueId, stationId = stationId, orderNumber = orderNumber, orderType = orderType,
        items = runCatching { json.decodeFromString(items, itemsJson) }.getOrDefault(emptyList())
            .map { KDSOrderItem(id = it.id, productName = it.productName, quantity = it.quantity, modifiers = it.modifiers, notes = it.notes, course = it.course,
                externalId = it.externalId, serviceCourse = it.serviceCourse, orderPromotionId = it.orderPromotionId, preparation = it.preparation) },
        recibidaEnMillis = recibidaEnMillis, listaEnMillis = listaEnMillis,
    )

    companion object {
        /** 12 h (D8): una comanda de ayer que nadie marcó no se queda en la pantalla para siempre. */
        const val VIGENCIA_MS = 12L * 60 * 60 * 1_000
    }
}
