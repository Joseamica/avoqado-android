package com.avoqado.pos.inventory.waste.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * Un artículo que se puede declarar como merma, tal como lo entrega el servidor:
 * `GET /mobile/venues/:venueId/inventory/waste-items`.
 *
 * 🔴 SIN existencias ni costos, a propósito (spec §4.4, §5). El endpoint no los manda y
 * la pantalla no los enseña: la merma se declara por lo que se tiró, no por lo que el
 * sistema cree que quedaba.
 */
data class WasteCatalogItem(
    val itemType: String,
    val itemId: String,
    val name: String,
    val sku: String,
    val unit: String,
)

/**
 * El catálogo guardado en disco, por sucursal.
 *
 * Hoy el inventario de la app vive EN MEMORIA (`InventoryRepository.kt:181`): al matar
 * la app se pierde, y sin red no hay forma de recuperarlo. Sin esto, buscar un artículo
 * para declarar una merma sería online-only, que es justo lo contrario de lo que pide la
 * regla `todo-funciona-sin-red.md`.
 *
 * Sin índice propio: la llave primaria ya empieza por `venueId`, que es el único filtro
 * que se le pide a la base. La búsqueda por texto va en memoria ([buscarEnCatalogo]).
 */
@Entity(
    tableName = "waste_catalog_item",
    primaryKeys = ["venueId", "itemType", "itemId"],
)
data class WasteCatalogEntity(
    val venueId: String,
    val itemType: String,
    val itemId: String,
    val name: String,
    val sku: String,
    val unit: String,
    /** Cuándo se bajó este catálogo. Es lo que permite decir «catálogo de hace N h». */
    val actualizadoEn: Long,
)

/**
 * El SQL del catálogo, en constantes, por UNA razón: para que una prueba pueda
 * EJECUTARLO contra SQLite de verdad.
 *
 * Mismo patrón y misma justificación que [com.avoqado.pos.cashdrawer.data.CashDrawerSql]:
 * una réplica escrita a mano no puede prometer la semántica del motor — el alcance del
 * `DELETE` por sucursal y el `MAX` sobre una sucursal sin filas son justo eso.
 *
 * `@Query` de Room acepta una constante compilada, así que la anotación y la prueba leen
 * el mismo texto. [CREAR_TABLA_CATALOGO] la usan la migración Y la prueba, por lo mismo.
 */
internal object WasteSql {

    /**
     * 🔴 En el formato EXACTO que Room genera para [WasteCatalogEntity]. Room compara su
     * esquema esperado contra el real al abrir la base: una columna, un `NOT NULL` o el
     * orden de la llave primaria fuera de sitio y la app revienta al arrancar con
     * «Migration didn't properly handle».
     */
    const val CREAR_TABLA_CATALOGO: String =
        "CREATE TABLE IF NOT EXISTS `waste_catalog_item` (" +
            "`venueId` TEXT NOT NULL, `itemType` TEXT NOT NULL, `itemId` TEXT NOT NULL, " +
            "`name` TEXT NOT NULL, `sku` TEXT NOT NULL, `unit` TEXT NOT NULL, " +
            "`actualizadoEn` INTEGER NOT NULL, " +
            "PRIMARY KEY(`venueId`, `itemType`, `itemId`))"

    /** Acotado al venue: el catálogo de la otra sucursal del mismo aparato no se toca. */
    const val BORRAR_CATALOGO_DEL_VENUE: String =
        "DELETE FROM waste_catalog_item WHERE venueId = :venueId"

    /** Todo el catálogo de UNA sucursal; el orden y el filtro por texto los pone [buscarEnCatalogo]. */
    const val CATALOGO_DEL_VENUE: String =
        "SELECT * FROM waste_catalog_item WHERE venueId = :venueId"

    /** `MAX` sobre un venue sin catálogo devuelve una fila con NULL, que es «nunca». */
    const val CATALOGO_ACTUALIZADO_EN: String =
        "SELECT MAX(actualizadoEn) FROM waste_catalog_item WHERE venueId = :venueId"
}

@Dao
interface WasteCatalogDao {

    /**
     * `REPLACE`: si el catálogo cambia MIENTRAS se descarga, la paginación por posición puede
     * traer el mismo artículo al final de una página y al principio de la siguiente. Un
     * `insert` estricto tumbaría la actualización ENTERA por llave repetida.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertar(items: List<WasteCatalogEntity>)

    @Query(WasteSql.BORRAR_CATALOGO_DEL_VENUE)
    suspend fun borrarDelVenue(venueId: String)

    @Query(WasteSql.CATALOGO_DEL_VENUE)
    suspend fun catalogoDelVenue(venueId: String): List<WasteCatalogEntity>

    @Query(WasteSql.CATALOGO_ACTUALIZADO_EN)
    suspend fun actualizadoEn(venueId: String): Long?

    /**
     * Borra e inserta en UNA transacción: el catálogo nunca queda a medias.
     *
     * 🔴 Si una descarga se cortara y se guardara lo que llegó, el cajero buscaría un
     * artículo que sí existe, no lo encontraría, y concluiría que el sistema se lo
     * perdió. Un catálogo viejo —que la pantalla ETIQUETA como viejo— es mucho menos
     * dañino que uno incompleto, que no se distingue de uno completo.
     */
    @Transaction
    suspend fun reemplazarCatalogo(venueId: String, items: List<WasteCatalogEntity>) {
        borrarDelVenue(venueId)
        if (items.isNotEmpty()) insertar(items)
    }
}
