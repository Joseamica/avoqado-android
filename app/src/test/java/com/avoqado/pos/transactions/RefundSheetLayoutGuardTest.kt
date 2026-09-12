package com.avoqado.pos.transactions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 🔴 TRES INVARIANTES DE LA HOJA DE REEMBOLSO, MEDIDOS EN LA SUNMI EL 2026-09-11.
 *
 * El founder la describió como «horrible: tapaba los montos». Al recorrerla en el aparato
 * salieron tres cosas distintas, y ninguna la ve el compilador:
 *
 *   1. Un HUECO enorme entre la lista y el motivo, con los controles apretados abajo.
 *      Causa: la banda del medio usaba `weight(1f, fill = true)`, que la estira a TODO
 *      el alto sobrante aunque el contenido sea corto.
 *   2. Con 3 artículos sólo se veían 2, y NADA decía que hubiera más. La banda sí se
 *      desliza; simplemente no lo parecía. Un cajero reembolsa lo que alcanza a ver.
 *   3. Al abrir el teclado, la casilla «Incluir propina» salía de la pantalla. Esa
 *      casilla decide si la propina del mesero se devuelve: es DINERO confirmándose a
 *      ciegas.
 *
 * Este guard mira el CÓDIGO FUENTE porque no hay otra forma — el módulo no tiene
 * Robolectric ni `compose.ui.test`, así que ninguna prueba puede renderizar la hoja y
 * mirar el pixel. Misma técnica que [RefundSheetDesignSystemGuardTest] y
 * [RefundCashDrawerOwnershipTest].
 *
 * ⚠️ **LO QUE ESTE GUARD NO PRUEBA, declarado para no prometer de más.** Una auditoría
 * (Codex gpt-6-astra, 2026-09-12) aplicó ocho mutaciones: **cae con la que motivó el
 * guard** —borrar la llamada de la casilla— y **pasa con estas siete**:
 *
 * | Mutación | Qué se perdería sin que el guard lo note |
 * |---|---|
 * | Comentar la llamada (comentario de bloque) | la casilla desaparece; `includeTip` se queda en `true` |
 * | `if (false && tab == …)` | la llamada existe y nunca se pinta |
 * | Envolver la llamada en una `Column` con `verticalScroll` | la casilla vuelve a poder salirse de la pantalla |
 * | `onIncludeTipChange = { }` | la casilla se ve y el cajero no puede cambiarla |
 * | `"${if (false) transaction.items.size else ""} artículos"` | el número desaparece; el guard sólo busca el texto |
 * | Quitar `.verticalScroll(bandaScroll)` | el contenido largo deja de poder desplazarse |
 * | `weight(1f)` en el `Box` y `weight(1f, fill = false)` en un `Spacer` hijo | vuelve el hueco muerto |
 *
 * 🔑 La lección es la de la casa: **un guard que promete más de lo que comprueba es peor
 * que uno que declara su alcance.** Esto caza la reversión perezosa —que es la que de
 * verdad ocurre— y nada más. La protección real de «la casilla se ve y se puede tocar»
 * exige una prueba instrumentada (`compose.ui.test`), que el módulo no tiene: queda
 * anotado como pendiente, no disimulado.
 */
class RefundSheetLayoutGuardTest {

    private val hoja = "app/src/main/java/com/avoqado/pos/transactions/presentation/IssueRefundSheet.kt"

    @Test
    fun `la banda desplazable no se estira cuando el contenido es corto`() {
        val codigo = leerCodigoSinComentarios(hoja)

        // Sin espacios: reformatear `weight(1f,fill=false)` no puede tumbar el guard
        // (lo señaló la auditoría de Codex, 2026-09-11).
        val sinEspacios = codigo.replace(" ", "")
        assertFalse(
            "La banda del medio volvió a `weight(1f, fill = true)`: se estira a todo el alto " +
                "sobrante y deja el hueco enorme que el founder vio en la tablet. Usa `fill = false`.",
            sinEspacios.contains("fill=true"),
        )
        assertTrue(
            "La banda del medio dejó de declarar `weight(1f, fill = false)`.",
            sinEspacios.contains("weight(1f,fill=false)"),
        )
    }

    @Test
    fun `la banda avisa cuando queda contenido por debajo`() {
        val codigo = leerCodigoSinComentarios(hoja)

        // ⚠️ Lo que de verdad avisa es el CONTEO en el subtítulo: el degradado del borde va
        // de transparente a blanco sobre una lista blanca y NO se ve (comprobado en la Sunmi).
        // Este guard exige el conteo; `canScrollForward` se comprueba aparte y se declara que
        // su sola presencia no prueba que la señal sea visible (auditoría Codex, 2026-09-11).
        assertTrue(
            "El subtítulo de la lista dejó de decir cuántos artículos tiene la venta. Es la " +
                "única señal que un cajero VE de que hay más abajo: sin ella, una venta de 3 " +
                "enseña 2 y él reembolsa lo que alcanza a ver.",
            codigo.contains("transaction.items.size") && codigo.contains("artículo"),
        )
        assertTrue(
            "Desapareció `canScrollForward` (el degradado del borde de la banda).",
            codigo.contains("canScrollForward"),
        )
    }

    /**
     * 🔴 El que más importa: la casilla de la propina cambia CUÁNTO dinero se devuelve,
     * así que no puede vivir en la banda que se desliza.
     */
    @Test
    fun `la casilla de propina vive en el pie fijo, no en la banda desplazable`() {
        val codigo = leerCodigoSinComentarios(hoja)

        // `substringAfter` a secas se lleva TODO el resto del archivo —incluido el propio
        // `FilaIncluirPropina`— y el guard acusaba en falso. El cuerpo de `AmountBody`
        // termina donde empieza el siguiente `@Composable`.
        val cuerpoDelImporte = codigo
            .substringAfter("private fun AmountBody(")
            .substringBefore("@Composable")
        assertFalse(
            "La casilla «Incluir propina» volvió dentro de `AmountBody`, que vive en la banda " +
                "desplazable: con el teclado abierto sale de la pantalla y el cajero confirma " +
                "incluir la propina sin verla. Va en el pie fijo, con `FilaIncluirPropina`.",
            cuerpoDelImporte.contains("Incluir propina en el reembolso"),
        )

        // 🔴 Antes esto era `codigo.contains("FilaIncluirPropina(")`, y una auditoría
        // (Codex, 2026-09-11) demostró que pasaba aunque se BORRARA la llamada: el patrón
        // encontraba la propia DEFINICIÓN de la función. Con la llamada borrada `includeTip`
        // se queda en su default `true` y el cajero pierde la forma de conservarle la propina
        // al mesero — con el guard en verde. Ahora se comprueba la UBICACIÓN: la llamada
        // tiene que estar DESPUÉS del fin de la banda desplazable, o sea en el pie fijo.
        val fuente = leerFuente(hoja)
        val marca = "=== FIN DE LA BANDA DESPLAZABLE ==="
        assertTrue(
            "Desapareció la marca `$marca`, que es lo que separa la banda del pie fijo y lo " +
                "único con lo que este guard puede saber dónde vive un control.",
            fuente.contains(marca),
        )
        val pieFijo = fuente.substringAfter(marca).substringBefore("private fun ItemsBody(")
        assertTrue(
            "`FilaIncluirPropina(` ya no se INVOCA en el pie fijo. Si sólo quedó su definición, " +
                "la casilla no se pinta: `includeTip` se queda en `true` y el cajero ya no puede " +
                "conservar la propina del mesero.",
            pieFijo.contains("FilaIncluirPropina("),
        )
    }

    /**
     * Las dos reglas de dinero viven fuera del Composable para que alguien pueda
     * probarlas. Si vuelven a escribirse en línea dentro del `onClick`, se pierde eso.
     */
    @Test
    fun `el envio usa las reglas de dinero probadas, no aritmetica en linea`() {
        val codigo = leerCodigoSinComentarios(hoja)

        assertFalse(
            "Volvió la conversión a centavos escrita en línea. `(pesos * 100).toInt()` TRUNCA: " +
                "1 145 de los 20 000 importes entre \$0.01 y \$200 se devolverían un centavo cortos. " +
                "Usa `centavosDelImporte(...)`.",
            codigo.contains("* 100).toInt()"),
        )
        assertTrue(
            "El envío dejó de usar `centavosDelImporte(...)`.",
            codigo.contains("centavosDelImporte("),
        )
        assertTrue(
            "El envío dejó de usar `tipRefundCentsParaEnvio(...)`.",
            codigo.contains("tipRefundCentsParaEnvio("),
        )
    }

    /** Quita comentarios para que un guard no se dé por satisfecho con una mención en prosa. */
    private fun leerCodigoSinComentarios(rutaRelativa: String): String =
        leerFuente(rutaRelativa)
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("""//[^\n]*"""), " ")

    /**
     * El working dir de los tests es `app/` o la raíz del repo según cómo se invoque
     * Gradle. Un guard que no encuentra su archivo y pasa en silencio no vale nada:
     * se busca hacia arriba y se revienta si no aparece. (Misma forma que
     * [RefundSheetDesignSystemGuardTest]; la primera versión de este archivo usaba una
     * ruta relativa pelada y los 4 guards fallaron por no encontrar la hoja.)
     */
    private fun leerFuente(rutaRelativa: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidato = File(dir, rutaRelativa)
            if (candidato.isFile) return candidato.readText()
            val sinModulo = File(dir, rutaRelativa.removePrefix("app/"))
            if (sinModulo.isFile) return sinModulo.readText()
            dir = dir.parentFile
        }
        throw AssertionError("No se encontró $rutaRelativa desde ${System.getProperty("user.dir")}")
    }
}
