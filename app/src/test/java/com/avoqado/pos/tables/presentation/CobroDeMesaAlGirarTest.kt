package com.avoqado.pos.tables.presentation

import androidx.compose.runtime.saveable.SaverScope
import com.avoqado.pos.payment.presentation.SplitConfig
import com.avoqado.pos.payment.presentation.SplitType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * D61 (13-nov): en una MESA, girar el aparato (o cambiar el tema o el tamaño de letra) recrea la Activity. La bandera del cobro
 * abierto era `remember`: la pantalla de cobro DESAPARECÍA mientras su `PaymentFlowViewModel` (del back stack) seguía cobrando,
 * el cajero veía la cuenta otra vez y «Pagar» abría OTRO cobro de la misma mesa; y la sesión quedaba en PAYING.
 *
 * El módulo no tiene Robolectric ni `compose.ui.test` (ver `CobroAnteriorResueltoGuardTest`): aquí se prueba lo puro (el reparto
 * guardado regresa igual; la salida tras el recibo) y, mirando el código fuente, que el estado del cobro de la mesa se guarda con
 * `rememberSaveable` y que ninguna salida del cobro se salta `cerrarCobro()`. Que la pantalla SIGA tras girar se prueba en la N86.
 */
class CobroDeMesaAlGirarTest {

    private val scope = SaverScope { true }

    @Test
    fun `el reparto del cobro regresa igual tras guardarse (girar no lo pierde)`() {
        val reparto = SplitConfig(type = SplitType.EQUAL_PARTS, selectedItemIds = listOf("a", "b"), numberOfParts = 3,
            customAmountCents = 4500)
        val guardado = with(GuardadoDelReparto) { scope.save(reparto) }!!
        assertEquals(reparto, GuardadoDelReparto.restore(guardado))
        val porDefecto = SplitConfig()
        assertEquals(porDefecto, GuardadoDelReparto.restore(with(GuardadoDelReparto) { scope.save(porDefecto) }!!))
    }

    @Test
    fun `tras el recibo sin cobro confirmado la mesa se cierra por cerrarCobro (no queda en PAYING)`() {
        assertEquals(SalidaTrasElRecibo.CERRAR_COBRO, salidaTrasElRecibo(restanteConfirmado = null))
    }

    @Test
    fun `tras el recibo con resto la mesa vuelve a la cuenta y sin resto regresa al plano`() {
        assertEquals(SalidaTrasElRecibo.CERRAR_COBRO, salidaTrasElRecibo(restanteConfirmado = 2500))
        assertEquals(SalidaTrasElRecibo.SALIR_AL_PLANO, salidaTrasElRecibo(restanteConfirmado = 0))
    }

    @Test
    fun `el estado del cobro de la mesa sobrevive a recrear la Activity`() {
        val codigo = sinComentarios(leerFuente(PANTALLA))
        for (variable in listOf("showPayment", "paymentSeedCents", "showSplitImporte", "pendingSplitConfig", "restanteConfirmado")) {
            assertTrue("$variable de la mesa no es rememberSaveable: girar el aparato lo pierde a media venta",
                Regex("""var\s+$variable\s+by\s+rememberSaveable""").containsMatchIn(codigo))
        }
        assertFalse("la mesa volvió a guardar el cobro confirmado en un remember (girar lo pierde)",
            codigo.contains("committedPaymentCompletion"))
    }

    @Test
    fun `toda salida del cobro de la mesa pasa por cerrarCobro`() {
        val codigo = sinComentarios(leerFuente(PANTALLA)).replace(Regex("""\s+"""), " ")
        // `showPayment = false` sólo dentro de `cerrarCobro()` (cerrar), de la salida al plano (mesa pagada) y del paso a «Dividir
        // importe» (el cobro se reabre con su reparto). Cualquier otro sitio deja la sesión en PAYING.
        assertEquals("showPayment = false fuera de las tres salidas conocidas", 3,
            Regex("""showPayment = false""").findAll(codigo).count())
        assertTrue("cancelar «Dividir importe» no regresa la mesa a ORDERING",
            codigo.contains("onDismiss = { cerrarCobro() }"))
        assertTrue("el cobro no usa salidaTrasElRecibo", codigo.contains("salidaTrasElRecibo("))
    }

    private companion object {
        const val PANTALLA = "app/src/main/java/com/avoqado/pos/tables/presentation/TableOrderScreen.kt"
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
