package com.avoqado.pos.escritorio

import com.avoqado.pos.BuildConfig
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** El POS de Windows manda el MISMO número de compilación que Android (`x-app-version-code`), leído de app/build.gradle.kts. */
class BuildConfigDeEscritorioTest {

    @Test
    fun `VERSION_CODE de escritorio es el de Android`() {
        // `:pos:test` corre con desktop/pos como directorio de trabajo.
        val gradle = File("../../app/build.gradle.kts").readText()
        val android = Regex("""versionCode\s*=\s*(\d+)""").find(gradle)!!.groupValues[1].toInt()
        assertEquals(android, BuildConfig.VERSION_CODE)
        assertTrue(BuildConfig.VERSION_CODE > 0)
    }
}
