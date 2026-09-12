package com.avoqado.pos.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 🔴 PESOS → CENTAVOS SE REDONDEA, NUNCA SE TRUNCA.
 *
 * Origen: Testarudo, 2026-09-11. La hoja de reembolso convertía con
 * `(pesos * 100).toInt()`; al barrer el repo aparecieron **8 sitios más** haciendo lo
 * mismo, incluido `Product.priceInCents`, que es el `unitPrice` del carrito
 * (`CartViewModel.kt`) y el de las órdenes de mesa.
 *
 * ⚠️ Medido en producción el mismo día: **0 de 205 precios de producto activos** caen en
 * el caso malo, porque un menú real usa precios redondos ($65, $130). Era una mina
 * latente, no un incendio. Donde SÍ muerde hoy es en los importes que **teclea el
 * cajero** — reembolso y división de cuenta — que pueden ser cualquier valor.
 */
class ACentavosTest {

    /**
     * El barrido es el que de verdad guarda esto: unos ejemplos a mano se cuelan entre los
     * valores que el truncamiento sí acierta. Con `toInt()` caen 1 145 de estos 20 000.
     */
    @Test
    fun `todo importe de 1 centavo a 200 pesos vuelve a sus centavos exactos`() {
        val fallos = (1..20_000).filter { centavos -> (centavos / 100.0).aCentavos() != centavos }
        assertEquals(
            "Se está truncando en vez de redondear. Primeros importes afectados: " +
                fallos.take(5).joinToString { "$%.2f".format(it / 100.0) },
            emptyList<Int>(),
            fallos,
        )
    }

    @Test
    fun `los casos que el truncamiento corta en un centavo`() {
        assertEquals(29, 0.29.aCentavos())
        assertEquals(113, 1.13.aCentavos())
        assertEquals(435, 4.35.aCentavos())
    }

    @Test
    fun `los importes redondos y el cero no cambian`() {
        assertEquals(0, 0.0.aCentavos())
        assertEquals(6_500, 65.0.aCentavos())
        assertEquals(13_000, 130.0.aCentavos())
    }

    /**
     * Guard de fuente: la conversión escrita en línea es justo lo que había que barrer.
     * Si vuelve a aparecer, este test lo dice con el archivo en la mano.
     */
    /**
     * ⚠️ **Esto es un CEDAZO, no una demostración.** Una auditoría (Codex gpt-6-astra,
     * 2026-09-12) reprodujo evasiones que siguen pasando y falsos positivos que hacía
     * fallar. Se ensanchó lo barato y se declara el resto en vez de fingir cobertura:
     *
     * **NO detecta** (falsos negativos conocidos): la expresión partida en dos líneas
     * (`(x\n  * 100).toInt()`), y un `round(` en la MISMA línea aplicado a OTRA expresión
     * (`val a = (x * 100).toInt(); val b = round(y)`), porque la exclusión es por línea.
     *
     * **Puede acusar en falso**: una conversión legítima que NO es dinero — un porcentaje
     * como `(progress * 100).toInt()`, donde truncar es deliberado. Para esos casos hay
     * una salida EXPLÍCITA: terminar la línea con `// no-es-dinero`. Se pide explícita a
     * propósito: obliga a declarar la intención en vez de ablandar el guard.
     *
     * La protección de verdad son [ACentavosTest] arriba (el barrido de 20 000 importes) y
     * que todo consumidor use `Double.aCentavos()`. Esto sólo caza la reversión perezosa.
     */
    @Test
    fun `nadie vuelve a convertir a centavos truncando`() {
        val raiz = raizDelRepo()
        // `100`, `100.0`, `100.00`; el multiplicador de cualquier lado; tabuladores incluidos.
        val truncaDerecha = Regex("""\*\s*100(\.0+)?\s*\)+\s*\.toInt\(\)""")
        val truncaIzquierda = Regex("""\(\s*100(\.0+)?\s*\*[^)]*\)+\s*\.toInt\(\)""")

        val culpables = File(raiz, "app/src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { archivo ->
                archivo.readLines().withIndex()
                    .filter { (_, linea) ->
                        val recortada = linea.trimStart()
                        val esComentario = recortada.startsWith("*") ||
                            recortada.startsWith("//") ||
                            recortada.startsWith("/*")
                        // Un ejemplo dentro de comillas también se declara con la salida
                        // explícita: detectar cadenas por heurística fallaba más de lo que
                        // acertaba, y un cedazo rebuscado es peor que uno simple y declarado.
                        val declaradoNoDinero = linea.contains("no-es-dinero")
                        val codigo = linea.substringBefore("//").replace("\t", " ")
                        !esComentario && !declaradoNoDinero &&
                            (truncaDerecha.containsMatchIn(codigo) || truncaIzquierda.containsMatchIn(codigo)) &&
                            !codigo.contains("round(") &&
                            !codigo.contains("aCentavos")
                    }
                    .map { (i, linea) -> "${archivo.name}:${i + 1}  ${linea.trim()}" }
            }
            .toList()

        assertTrue(
            "Conversión a centavos truncando (usa `Double.aCentavos()`; si de verdad NO es " +
                "dinero, termina la línea con `// no-es-dinero`):\n" + culpables.joinToString("\n"),
            culpables.isEmpty(),
        )
    }

    private fun raizDelRepo(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "app/src/main/java").isDirectory) return dir
            dir = dir.parentFile
        }
        throw AssertionError("No se encontró la raíz del repo desde ${System.getProperty("user.dir")}")
    }
}
