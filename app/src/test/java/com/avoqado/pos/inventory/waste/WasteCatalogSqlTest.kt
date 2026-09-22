package com.avoqado.pos.inventory.waste

import com.avoqado.pos.inventory.waste.data.WasteSql
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * EL SQL DEL CATÁLOGO, EJECUTADO DE VERDAD.
 *
 * Mismo patrón que `CashDrawerDeleteSqlTest`: la prueba toma las MISMAS constantes que
 * Room compila en `@Query` —no una copia— y las corre contra SQLite en memoria. Lo que
 * se mide es la semántica del motor, que una réplica escrita a mano no puede prometer:
 * el alcance del `DELETE` por sucursal y el `MAX` sobre una sucursal sin filas.
 *
 * 🔴 Y la tabla se crea con [WasteSql.CREAR_TABLA_CATALOGO], la MISMA cadena que corre
 * la migración de Room. Si alguien cambia una columna en la migración y no aquí, no hay
 * dos verdades que se puedan desincronizar: es una sola.
 *
 * La búsqueda por texto NO está aquí: va en memoria (`WasteCatalogBusquedaTest`).
 */
class WasteCatalogSqlTest {

    private lateinit var db: Connection

    @Before
    fun abrirBase() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { it.executeUpdate(WasteSql.CREAR_TABLA_CATALOGO) }
    }

    @After
    fun cerrarBase() {
        db.close()
    }

    private fun insertar(venueId: String, itemId: String, actualizadoEn: Long = 0L) {
        db.prepareStatement(
            "INSERT INTO waste_catalog_item (venueId, itemType, itemId, name, sku, unit, actualizadoEn) " +
                "VALUES (?,?,?,?,?,?,?)",
        ).use {
            it.setString(1, venueId); it.setString(2, "RAW_MATERIAL"); it.setString(3, itemId)
            it.setString(4, "Artículo $itemId"); it.setString(5, ""); it.setString(6, "kg"); it.setLong(7, actualizadoEn)
            it.executeUpdate()
        }
    }

    /** Sin `ORDER BY` el orden no está definido: se compara ordenado. */
    private fun catalogoDelVenue(venueId: String): List<String> =
        db.prepareStatement(WasteSql.CATALOGO_DEL_VENUE.replace(":venueId", "?")).use { st ->
            st.setString(1, venueId)
            val rs = st.executeQuery()
            generateSequence { if (rs.next()) rs.getString("itemId") else null }.toList().sorted()
        }

    private fun borrarDelVenue(venueId: String) {
        db.prepareStatement(WasteSql.BORRAR_CATALOGO_DEL_VENUE.replace(":venueId", "?")).use {
            it.setString(1, venueId); it.executeUpdate()
        }
    }

    /** `MAX()` sobre una tabla sin filas de ese venue devuelve UNA fila con NULL. */
    private fun actualizadoEn(venueId: String): Long? =
        db.prepareStatement(WasteSql.CATALOGO_ACTUALIZADO_EN.replace(":venueId", "?")).use {
            it.setString(1, venueId)
            val rs = it.executeQuery()
            if (!rs.next()) null else (rs.getObject(1) as? Number)?.toLong()
        }

    /**
     * 🔴 El catálogo se REEMPLAZA sólo al bajarse ENTERO. Si una descarga se corta a
     * media página y se guardara lo que llegó, el cajero buscaría un artículo que sí
     * existe, no lo encontraría, y concluiría que el sistema se lo perdió. Es preferible
     * un catálogo viejo —que se ETIQUETA como viejo— a uno incompleto que no se
     * distingue de uno completo.
     */
    @Test
    fun `reemplazar borra lo que ya no esta`() {
        insertar("v1", "rm1"); insertar("v1", "rm2")
        assertEquals(listOf("rm1", "rm2"), catalogoDelVenue("v1"))

        borrarDelVenue("v1")
        insertar("v1", "rm1")

        assertEquals(listOf("rm1"), catalogoDelVenue("v1"))
    }

    /**
     * 🔴 Dos sucursales en el mismo aparato NO se pisan. Un `DELETE` sin el `WHERE
     * venueId` dejaría a la otra sucursal sin catálogo, y su cajero sin poder buscar
     * nada — sin ningún error a la vista. Y la lectura de una no trae artículos de la otra.
     */
    @Test
    fun `el catalogo de otra sucursal sobrevive al reemplazo`() {
        insertar("v1", "rm1")
        insertar("v2", "rm9")

        borrarDelVenue("v1")

        assertEquals(listOf("rm9"), catalogoDelVenue("v2"))
        assertEquals(emptyList<String>(), catalogoDelVenue("v1"))
    }

    /**
     * 🔴 La antigüedad se guarda para poder DECIRLA («catálogo de hace N h»). Sin ella,
     * un catálogo de hace tres días se ve idéntico a uno de hace un minuto, y el cajero
     * no tiene forma de saber que el artículo nuevo todavía no está.
     */
    @Test
    fun `el catalogo recuerda cuando se bajo, y un venue sin catalogo devuelve nulo`() {
        insertar("v1", "rm1", actualizadoEn = 1_700_000_000_000)

        assertEquals(1_700_000_000_000L, actualizadoEn("v1"))
        assertNull(actualizadoEn("venue-que-no-existe"))
    }
}
