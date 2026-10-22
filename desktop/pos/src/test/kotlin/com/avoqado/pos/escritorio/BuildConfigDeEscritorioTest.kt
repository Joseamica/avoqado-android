package com.avoqado.pos.escritorio

import com.avoqado.pos.BuildConfig
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** El POS de Windows manda el MISMO número de compilación que Android (`x-app-version-code`), leído de app/build.gradle.kts. */
class BuildConfigDeEscritorioTest {

    // `:pos:test` corre con desktop/pos como directorio de trabajo. El patrón se lee del build MISMO (no una copia aquí):
    // así la prueba no puede pasar con un patrón distinto al que usa el build.
    private val patron: Regex = File("build.gradle.kts").readLines()
        .single { it.trimStart().startsWith("val patronVersionCode") }
        .let { Regex("\"\"\"(.+)\"\"\"").find(it)!!.groupValues[1] }
        .let { Regex(it, RegexOption.MULTILINE) }

    @Test
    fun `VERSION_CODE de escritorio es el de Android`() {
        val gradle = File("../../app/build.gradle.kts").readText()
        val coincidencias = patron.findAll(gradle).toList()
        assertEquals(1, coincidencias.size)
        assertEquals(coincidencias.single().groupValues[1].toInt(), BuildConfig.VERSION_CODE)
        assertTrue(BuildConfig.VERSION_CODE > 0)
    }

    @Test
    fun `un versionCode comentado no cuenta`() {
        val gradle = """
            |    defaultConfig {
            |        // versionCode = 54
            |        versionCode = 55
            |        versionName = "2.25.2"
            |    }
        """.trimMargin()
        assertEquals(listOf("55"), patron.findAll(gradle).map { it.groupValues[1] }.toList())
    }
}
