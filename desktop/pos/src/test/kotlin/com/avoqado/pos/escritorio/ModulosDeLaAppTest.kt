package com.avoqado.pos.escritorio

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Alarma de paridad de la inyección: los `@Module` de Hilt que declara Android tienen que ser EXACTAMENTE los que
 * registra [Inyector.modulosDeLaApp]. Si Android agrega uno, escritorio no se enteraba hasta que una pantalla pidiera
 * algo que sólo ese módulo provee — y tronaba en el cliente, no aquí. Las huellas de `excluidos.txt` no lo cubren: el
 * módulo nuevo vive en un archivo que no está excluido.
 */
class ModulosDeLaAppTest {

    /** `<app>/src/main/java`: la propiedad `avoqado.esquemas` ya apunta a `<app>/schemas`. */
    private val fuentesAndroid: File =
        File(checkNotNull(System.getProperty("avoqado.esquemas")) { "falta avoqado.esquemas" }).parentFile.resolve("src/main/java")

    /** Clase (nombre completo) de cada archivo de Android con un renglón que empieza con `@Module`. */
    private fun modulosDeAndroid(): Set<String> = fuentesAndroid.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .filter { archivo -> archivo.useLines { renglones -> renglones.any { it.trimStart().startsWith("@Module") } } }
        .map { it.relativeTo(fuentesAndroid).path.removeSuffix(".kt").replace(File.separatorChar, '.') }
        .toSet()

    @Test fun `la carpeta de Android existe y trae modulos`() {
        assertTrue(fuentesAndroid.isDirectory, "no encontré $fuentesAndroid")
        assertTrue(modulosDeAndroid().isNotEmpty(), "no encontré ningún @Module en $fuentesAndroid")
    }

    @Test fun `escritorio registra exactamente los modulos de Hilt que declara Android`() {
        val deAndroid = modulosDeAndroid()
        val deEscritorio = Inyector.modulosDeLaApp.map { it.name }.toSet()
        assertEquals(
            deAndroid.sorted(), deEscritorio.sorted(),
            "Android y escritorio no registran los mismos módulos.\n" +
                "  Sólo en Android (agrégalo a Inyector.modulosDeLaApp o a su reemplazo): ${(deAndroid - deEscritorio).sorted()}\n" +
                "  Sólo en escritorio (¿Android lo quitó o lo renombró?): ${(deEscritorio - deAndroid).sorted()}",
        )
    }
}
