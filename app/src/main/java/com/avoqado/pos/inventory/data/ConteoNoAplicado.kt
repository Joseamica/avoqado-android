package com.avoqado.pos.inventory.data

import com.avoqado.pos.inventory.data.model.StockCount
import com.avoqado.pos.inventory.data.model.StockCountItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Por qué el servidor NO aplicó la línea de un conteo (conector Shopify, C12).
 *
 * Con un envío a Shopify en camino, o con una duda abierta sobre el producto, el servidor no sabe si un cambio de
 * «apartadas» vino de un pedido o de un despacho, así que deja esa línea sin aplicar (el stock no cambia) en vez
 * de escribir un número que podría estar mal. Hasta C12 el POS la enseñaba como aplicada.
 *
 * 🔴 Se lee TOLERANTE: el motivo llega como texto y cualquier valor que esta versión no conozca (o ninguno) se lee
 * como [DUDA_POR_REVISAR], el conservador — «recontar no sirve, alguien tiene que resolverlo». Nunca como aplicada.
 * Espejo exacto de `MotivoNoAplicado` en iOS (mismos textos, palabra por palabra).
 */
enum class MotivoNoAplicado {
    /** Se resuelve solo: en unos minutos el envío llega y el producto se puede volver a contar. */
    ENVIO_EN_CAMINO,

    /** Hay una DEAD_LETTER ambigua o una revisión abierta: recontar no sirve hasta resolverla en «Por revisar». */
    DUDA_POR_REVISAR;

    val texto: String
        get() = when (this) {
            ENVIO_EN_CAMINO -> ConteoNoAplicado.TEXTO_ENVIO_EN_CAMINO
            DUDA_POR_REVISAR -> ConteoNoAplicado.TEXTO_DUDA_POR_REVISAR
        }

    companion object {
        fun desde(valor: String?): MotivoNoAplicado =
            if (valor == ENVIO_EN_CAMINO.name) ENVIO_EN_CAMINO else DUDA_POR_REVISAR

        /** El motivo de un objeto `{ at, motivo }` del servidor; lo que no sea texto cae en la duda. */
        internal fun desdeJson(elemento: JsonElement?): MotivoNoAplicado {
            val motivo = ((elemento as? JsonObject)?.get("motivo") as? JsonPrimitive)?.takeIf { it.isString }?.content
            return desde(motivo)
        }
    }
}

/** Un renglón de `noAplicados` de la respuesta del confirm. */
data class NoAplicadoDelConfirm(val productId: String, val motivo: MotivoNoAplicado)

/** Una línea que no se aplicó, ya con el nombre que ve el cajero. */
data class LineaNoAplicada(val productId: String, val nombre: String, val motivo: MotivoNoAplicado)

/**
 * Lo que se le dice al cajero al cerrar un conteo con líneas que no se aplicaron. NO es un error: el conteo se
 * cerró y lo demás sí movió el stock.
 *
 * @param contadas cuántas líneas se contaron en total (para decir, o no, «el resto sí se aplicó»).
 */
data class ResultadoNoAplicado(
    val venueId: String,
    val countId: String,
    val lineas: List<LineaNoAplicada>,
    val contadas: Int,
) {
    val titulo: String get() = ConteoNoAplicado.titulo(lineas.size)

    /** Hubo líneas contadas que SÍ se aplicaron. Si todo lo contado quedó retenido, no se dice. */
    val hayRestoAplicado: Boolean get() = contadas > lineas.size
}

object ConteoNoAplicado {
    const val ETIQUETA = "No se aplicó"
    const val TEXTO_ENVIO_EN_CAMINO =
        "Había un envío a Shopify en camino: vuelve a contar este producto en unos minutos."
    const val TEXTO_DUDA_POR_REVISAR =
        "Hay una diferencia con Shopify por revisar: pídele al dueño que la resuelva en el dashboard " +
            "(Integraciones → Shopify → Por revisar)."
    const val RESTO_APLICADO = "El resto del conteo sí se aplicó."
    const val PRODUCTO_SIN_NOMBRE = "Producto"
    const val ENTENDIDO = "Entendido"

    /** «1 producto no se aplicó» · «3 productos no se aplicaron». */
    fun titulo(cantidad: Int): String =
        if (cantidad == 1) "1 producto no se aplicó" else "$cantidad productos no se aplicaron"

    /** Lo que se agrega a la fila de la lista: «1 no se aplicó» · «2 no se aplicaron». */
    fun enLaLista(cantidad: Int): String =
        if (cantidad == 1) "1 no se aplicó" else "$cantidad no se aplicaron"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * `noAplicados` del cuerpo del confirm. Ausente (servidor viejo, todo aplicado, o el reintento
     * `alreadyCompleted`, que NO lo trae) ⇒ lista vacía. Nunca lanza: un cuerpo raro también es lista vacía, y
     * por eso quien llama relee el GET antes de concluir que todo se aplicó.
     */
    fun decodificarNoAplicados(body: String): List<NoAplicadoDelConfirm> = runCatching {
        val arreglo = (json.parseToJsonElement(body) as? JsonObject)?.get("noAplicados") as? JsonArray
            ?: return emptyList()
        arreglo.mapNotNull { elemento ->
            val obj = elemento as? JsonObject ?: return@mapNotNull null
            val productId = (obj["productId"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
            NoAplicadoDelConfirm(productId, MotivoNoAplicado.desdeJson(obj))
        }
    }.getOrDefault(emptyList())

    /** Lo que dijo el confirm, nombrado con las líneas que el cajero tenía en pantalla. */
    fun desdeConfirm(
        venueId: String,
        countId: String,
        noAplicados: List<NoAplicadoDelConfirm>,
        lineasLocales: List<StockCountItem>,
    ): ResultadoNoAplicado? {
        if (noAplicados.isEmpty()) return null
        // Sólo líneas de PRODUCTO: el servidor reporta `product.id`, y un insumo puede compartir ese id.
        val nombres = lineasLocales.filter { !it.isIngredient }.associate { it.productId to it.productName }
        val lineas = sinRepetir(
            noAplicados.map { n ->
                LineaNoAplicada(
                    productId = n.productId,
                    nombre = nombres[n.productId]?.takeIf { it.isNotBlank() } ?: PRODUCTO_SIN_NOMBRE,
                    motivo = n.motivo,
                )
            },
        )
        return ResultadoNoAplicado(venueId, countId, lineas, contadas = lineasLocales.count { it.yaSeConto })
    }

    /** Lo que guardó el servidor en cada línea (`shopifyHeld`): la verdad tras un reintento o al releer. */
    fun desdeConteo(venueId: String, conteo: StockCount): ResultadoNoAplicado? {
        val lineas = sinRepetir(
            conteo.items.mapNotNull { item ->
                val motivo = item.motivoNoAplicado ?: return@mapNotNull null
                LineaNoAplicada(item.productId, item.productName.ifBlank { PRODUCTO_SIN_NOMBRE }, motivo)
            },
        )
        if (lineas.isEmpty()) return null
        return ResultadoNoAplicado(venueId, conteo.id, lineas, contadas = conteo.items.count { it.yaSeConto })
    }

    /**
     * Junta las dos fuentes sin perder nada: manda el GET (lo que el servidor guardó) y se le suma lo que sólo dijo
     * el confirm. Un GET que falló (`null`) no borra lo que dijo el confirm.
     */
    fun combinar(delConfirm: ResultadoNoAplicado?, delServidor: ResultadoNoAplicado?): ResultadoNoAplicado? {
        if (delConfirm == null) return delServidor
        if (delServidor == null) return delConfirm
        val delGet = delServidor.lineas.map { it.productId }.toSet()
        return delServidor.copy(
            lineas = delServidor.lineas + delConfirm.lineas.filter { it.productId.isBlank() || it.productId !in delGet },
            contadas = maxOf(delServidor.contadas, delConfirm.contadas),
        )
    }

    private fun sinRepetir(lineas: List<LineaNoAplicada>): List<LineaNoAplicada> {
        val vistos = mutableSetOf<String>()
        return lineas.filter { it.productId.isBlank() || vistos.add(it.productId) }
    }

    /** ¿La línea viene retenida? Presente y no nulo, con la forma que sea, cuenta como retenida. */
    internal fun retenida(shopifyHeld: JsonElement?): Boolean = shopifyHeld != null && shopifyHeld !is JsonNull
}
