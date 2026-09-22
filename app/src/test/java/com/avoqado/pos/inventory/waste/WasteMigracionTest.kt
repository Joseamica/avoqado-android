package com.avoqado.pos.inventory.waste

import com.avoqado.pos.inventory.waste.data.PendingWasteSql
import com.avoqado.pos.inventory.waste.data.WasteSql
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 🔴 LAS MIGRACIONES DE MERMA CREAN LAS TABLAS EXACTAMENTE COMO ROOM LAS ESPERA.
 *
 * Si no coinciden, la app NO arranca: al abrir la base, Room compara lo que dejó la
 * migración contra lo que declaran las entidades y revienta con «Migration didn't properly
 * handle». Ninguna prueba de JVM abre la base real, y la prueba instrumentada del repo
 * (`AvoqadoDatabaseMigrationTest`) sólo llega a la v8.
 *
 * Lo que sí existe sin aparato: el esquema que ROOM MISMO exporta al compilar
 * (`room.schemaLocation` → `app/schemas/…/N.json`). Se lee el MÁS RECIENTE, que es contra
 * el que Room valida al abrir la base, y se compara su `createSql` con la constante que
 * ejecuta cada migración — si alguien cambia una columna de una entidad y no su migración,
 * cae aquí y no en la tablet de un cliente.
 */
class WasteMigracionTest {

    private val esquema: File =
        File("schemas/com.avoqado.pos.core.data.local.database.AvoqadoDatabase")
            .listFiles { f -> f.extension == "json" }
            .orEmpty()
            .maxByOrNull { it.nameWithoutExtension.toIntOrNull() ?: -1 }
            ?: File("no-hay-esquema-exportado")

    private fun entidad(tabla: String): JsonObject {
        assertTrue("falta el esquema exportado por Room: ${esquema.absolutePath}", esquema.exists())
        val raiz = Json.parseToJsonElement(esquema.readText()).jsonObject
        return raiz["database"]!!.jsonObject["entities"]!!.jsonArray
            .map { it.jsonObject }
            .single { it["tableName"]!!.jsonPrimitive.content == tabla }
    }

    private fun tablaEsperada(tabla: String): String =
        entidad(tabla)["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", tabla)

    /**
     * Las migraciones no crean índices, así que las entidades tampoco pueden declararlos: Room
     * compara también los índices al abrir la base, y uno declarado sin su `CREATE INDEX` en la
     * migración revienta igual al arrancar.
     */
    private fun sinIndices(tabla: String) {
        val indices = entidad(tabla)["indices"] as? JsonArray
        assertTrue("$tabla declara índices que su migración no crea: $indices", indices.isNullOrEmpty())
    }

    @Test
    fun `el catalogo que crea la migracion 10 a 11 es el que Room espera`() {
        assertEquals(tablaEsperada("waste_catalog_item"), WasteSql.CREAR_TABLA_CATALOGO)
        sinIndices("waste_catalog_item")
    }

    @Test
    fun `la cola que crea la migracion 11 a 12 es la que Room espera`() {
        assertEquals(tablaEsperada("pending_waste"), PendingWasteSql.CREAR_TABLA)
        sinIndices("pending_waste")
    }
}
