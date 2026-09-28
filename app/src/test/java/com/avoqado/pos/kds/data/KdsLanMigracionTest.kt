package com.avoqado.pos.kds.data

import com.avoqado.pos.kds.data.local.KdsLanSql
import com.avoqado.pos.kds.data.local.unirItemsJson
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
 * 🔴 LA MIGRACIÓN 12→13 CREA LAS TABLAS EXACTAMENTE COMO ROOM LAS ESPERA (patrón `WasteMigracionTest`): se lee el
 * esquema MÁS RECIENTE que Room exporta al compilar (`app/schemas/…/13.json`, que se COMMITEA) y se compara su
 * `createSql` con la constante que ejecuta la migración. Si difieren, la app revienta al abrir la base en el aparato.
 */
class KdsLanMigracionTest {

    private val esquema: File =
        File("schemas/com.avoqado.pos.core.data.local.database.AvoqadoDatabase")
            .listFiles { f -> f.extension == "json" }
            .orEmpty()
            .maxByOrNull { it.nameWithoutExtension.toIntOrNull() ?: -1 }
            ?: File("no-hay-esquema-exportado")

    private fun entidad(tabla: String): JsonObject {
        assertTrue("falta el esquema exportado por Room: ${esquema.absolutePath}", esquema.exists())
        val raiz = Json.parseToJsonElement(esquema.readText()).jsonObject
        return raiz["database"]!!.jsonObject["entities"]!!.jsonArray.map { it.jsonObject }
            .single { it["tableName"]!!.jsonPrimitive.content == tabla }
    }

    private fun tablaEsperada(tabla: String): String =
        entidad(tabla)["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", tabla)

    private fun sinIndices(tabla: String) {
        val indices = entidad(tabla)["indices"] as? JsonArray
        assertTrue("$tabla declara índices que su migración no crea: $indices", indices.isNullOrEmpty())
    }

    @Test
    fun `el esquema exportado es la version 13`() {
        assertEquals("13", esquema.nameWithoutExtension)
    }

    @Test
    fun `las entregas pendientes que crea la migracion 12 a 13 son las que Room espera`() {
        assertEquals(tablaEsperada("entregas_kds_pendientes"), KdsLanSql.CREAR_ENTREGAS)
        sinIndices("entregas_kds_pendientes")
    }

    @Test
    fun `los tickets locales que crea la migracion 12 a 13 son los que Room espera`() {
        assertEquals(tablaEsperada("kds_tickets_locales"), KdsLanSql.CREAR_TICKETS)
        sinIndices("kds_tickets_locales")
    }

    /** D8: una ronda con cursos manda el mismo folio varias veces — se UNEN renglones por `id`, sin duplicar ni perder. */
    @Test
    fun `una segunda entrega del mismo folio UNE renglones y no pierde el primer curso`() {
        val curso1 = """[{"id":"a","productName":"Café","quantity":1,"modifiers":[],"notes":null}]"""
        val curso2 = """[{"id":"a","productName":"Café","quantity":1,"modifiers":[],"notes":null},{"id":"b","productName":"Flan","quantity":2,"modifiers":[],"notes":null}]"""
        val unidos = Json.parseToJsonElement(unirItemsJson(curso1, curso2)).jsonArray
        assertEquals(listOf("a", "b"), unidos.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertEquals(curso1, unirItemsJson(curso1, "basura que no es json"))
    }
}
