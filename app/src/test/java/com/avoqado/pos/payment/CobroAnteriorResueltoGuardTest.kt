package com.avoqado.pos.payment

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Re-revisión (I-1, 26-sep): la MESA componía `PaymentFlowScreen` sin `onPreviousChargeResolved`, y el valor por defecto
 * `{ onCancel() }` se tragaba «El cobro anterior sí se había realizado» sin que nadie lo reconociera (un callback con valor
 * por defecto es un botón muerto). Ahora el parámetro no tiene default —el compilador obliga a cada anfitrión— y cada uno
 * pinta el mensaje y lo reconoce.
 *
 * Mira el CÓDIGO FUENTE porque el módulo no tiene Robolectric ni `compose.ui.test` (misma técnica que
 * `RefundSheetLayoutGuardTest`). ⚠️ Alcance declarado: caza la reversión perezosa —volver a poner el default, o un
 * anfitrión que no pase el callback o no reconozca—; no prueba que el diálogo se VEA. Eso queda para la QA en aparato.
 */
class CobroAnteriorResueltoGuardTest {

    private val flujo = "app/src/main/java/com/avoqado/pos/payment/presentation/PaymentFlowScreen.kt"
    private val anfitriones = listOf(
        "app/src/main/java/com/avoqado/pos/pos/presentation/checkout/CheckoutScreen.kt",
        "app/src/main/java/com/avoqado/pos/tables/presentation/TableOrderScreen.kt",
    )

    @Test
    fun `I-1 cada anfitrion del cobro pinta el SI paso de su orden y lo reconoce`() {
        val codigoDelFlujo = sinComentarios(leerFuente(flujo)).replace(Regex("""\s+"""), "")
        assertFalse(
            "onPreviousChargeResolved volvió a tener valor por defecto: un anfitrión que lo olvide se traga el mensaje",
            codigoDelFlujo.contains("onPreviousChargeResolved:(String)->Unit="),
        )
        for (anfitrion in anfitriones) {
            val codigo = sinComentarios(leerFuente(anfitrion))
            assertTrue("$anfitrion no le pasa onPreviousChargeResolved al cobro", codigo.contains("onPreviousChargeResolved ="))
            assertTrue("$anfitrion no reconoce el cobro que SÍ pasó", codigo.contains("reconocerCobroAnteriorResuelto()"))
        }
    }

    private fun sinComentarios(codigo: String): String =
        codigo.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ").replace(Regex("""//[^\n]*"""), " ")

    /** El working dir de los tests es `app/` o la raíz del repo según cómo se invoque Gradle: se busca hacia arriba. */
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
