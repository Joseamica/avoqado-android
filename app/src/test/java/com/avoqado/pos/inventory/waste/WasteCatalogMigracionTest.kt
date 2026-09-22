package com.avoqado.pos.inventory.waste

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
 * 🔴 LA MIGRACIÓN 10→11 CREA LA TABLA EXACTAMENTE COMO ROOM LA ESPERA.
 *
 * Si no coincide, la app NO arranca: al abrir la base, Room compara lo que dejó la
 * migración contra lo que declara la entidad y revienta con «Migration didn't properly
 * handle». Ninguna prueba de JVM abre la base real, y la prueba instrumentada del repo
 * (`AvoqadoDatabaseMigrationTest`) sólo llega a la v8.
 *
 * Lo que sí existe sin aparato: el esquema que ROOM MISMO exporta al compilar
 * (`room.schemaLocation` → `app/schemas/…/11.json`). Su `createSql` es la definición
 * canónica de la tabla. Esta prueba compara esa cadena contra la constante que ejecuta la
 * migración — si alguien cambia una columna de la entidad y no la migración, cae aquí y
 * no en la tablet de un cliente.
 */
class WasteCatalogMigracionTest {

    private val esquema = File(
        "schemas/com.avoqado.pos.core.data.local.database.AvoqadoDatabase/11.json",
    )

    private fun entidad(tabla: String): JsonObject {
        assertTrue("falta el esquema exportado por Room: ${esquema.absolutePath}", esquema.exists())
        val raiz = Json.parseToJsonElement(esquema.readText()).jsonObject
        return raiz["database"]!!.jsonObject["entities"]!!.jsonArray
            .map { it.jsonObject }
            .single { it["tableName"]!!.jsonPrimitive.content == tabla }
    }

    @Test
    fun `la tabla que crea la migracion es la que Room espera`() {
        val esperada = entidad("waste_catalog_item")["createSql"]!!.jsonPrimitive.content
            .replace("\${TABLE_NAME}", "waste_catalog_item")
        assertEquals(esperada, WasteSql.CREAR_TABLA_CATALOGO)
    }

    /**
     * La migración no crea índices, así que la entidad tampoco puede declararlos: Room
     * compara también los índices al abrir la base, y uno declarado en la entidad sin su
     * `CREATE INDEX` en la migración revienta igual al arrancar.
     */
    @Test
    fun `y no declara indices que la migracion no crea`() {
        val indices = entidad("waste_catalog_item")["indices"] as? JsonArray
        assertTrue("índices sin migración: $indices", indices.isNullOrEmpty())
    }
}
