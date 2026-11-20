package com.avoqado.pos.maquina

import com.avoqado.pos.core.data.local.database.AvoqadoDatabaseMigrations
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 🔴 LA MIGRACIÓN 19→20 CREA `cobros_en_maquina` EXACTAMENTE COMO ROOM LA ESPERA (patrón `KdsLanMigracionTest`): se compara
 * la constante que ejecuta la migración con el `createSql` del esquema que Room exporta (`app/schemas/…/20.json`, que se
 * COMMITEA). Si difieren, la app revienta al abrir la base en el aparato. La validación completa con Room de verdad vive en
 * escritorio (`MigracionCobrosEnMaquinaTest`).
 */
class CobroEnMaquinaMigracionTest {

    private val esquema = File("schemas/com.avoqado.pos.core.data.local.database.AvoqadoDatabase/20.json")

    @Test
    fun `la tabla que crea la migracion 19 a 20 es la que Room espera, sin indices`() {
        assertTrue("falta el esquema exportado por Room: ${esquema.absolutePath}", esquema.exists())
        val entidad = Json.parseToJsonElement(esquema.readText()).jsonObject["database"]!!.jsonObject["entities"]!!.jsonArray
            .map { it.jsonObject }.single { it["tableName"]!!.jsonPrimitive.content == "cobros_en_maquina" }
        assertEquals(
            entidad["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", "cobros_en_maquina"),
            CobroEnMaquinaSql.CREAR_TABLA,
        )
        assertTrue((entidad["indices"] as? JsonArray).isNullOrEmpty())
    }

    @Test
    fun `la migracion 19 a 20 existe y va de la 19 a la 20`() {
        assertEquals(19 to 20, AvoqadoDatabaseMigrations.MIGRATION_19_20.let { it.startVersion to it.endVersion })
    }
}
