package com.avoqado.pos.tables.presentation

import com.avoqado.pos.payment.presentation.SplitConfig
import com.avoqado.pos.payment.presentation.SplitType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * D61 (13-nov): en una MESA, girar el aparato (o cambiar el tema o el tamaño de letra) recrea la Activity. El estado del cobro
 * abierto era `remember`: la pantalla de cobro DESAPARECÍA mientras su `PaymentFlowViewModel` seguía cobrando, el cajero veía la
 * cuenta otra vez y «Pagar» abría OTRO cobro de la misma mesa; y la sesión quedaba en PAYING. `rememberSaveable` no basta: en la
 * N86 girar cambia el árbol de teléfono por el de tableta (Codex r1 #1). Ahora vive en el ViewModel de la mesa ([CobroDeLaMesa]).
 *
 * El módulo no tiene Robolectric ni `compose.ui.test` (ver `CobroAnteriorResueltoGuardTest`): aquí se prueba lo puro (la apertura,
 * la salida tras el recibo) y, mirando el código fuente, que la pantalla toma el cobro del ViewModel, le pasa su apertura al flujo
 * y que ninguna salida del cobro se salta `cerrarCobro()`. Que la pantalla SIGA tras girar se prueba en el aparato.
 */
class CobroDeMesaAlGirarTest {

    @Test
    fun `abrir el cobro da una apertura nueva y la conserva mientras sigue abierto`() {
        val cobro = CobroDeLaMesa()
        assertFalse(cobro.abierto)
        cobro.abrir()
        val primera = cobro.apertura
        assertNotNull(primera)
        assertTrue(cobro.abierto)
        // Recrear la Activity no toca el ViewModel: la pantalla nueva lee la MISMA apertura y el flujo sigue su cobro.
        cobro.semillaCents = 4500
        cobro.reparto = SplitConfig(type = SplitType.EQUAL_PARTS, numberOfParts = 3)
        assertEquals(primera, cobro.apertura)
        // Cerrar y volver a abrir es OTRO cobro: apertura nueva (el flujo arranca de cero, como al tocar «Pagar»).
        cobro.abierto = false
        cobro.abrir()
        assertNotEquals(primera, cobro.apertura)
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
    fun `el estado del cobro de la mesa vive en el ViewModel y el flujo recibe su apertura`() {
        val pantalla = sinComentarios(leerFuente(PANTALLA))
        val campos = mapOf("showPayment" to "abierto", "paymentSeedCents" to "semillaCents", "pendingSplitConfig" to "reparto",
            "restanteConfirmado" to "restanteConfirmado", "showSplitImporte" to "dividiendo",
            "previousChargeNotice" to "avisoCobroAnterior")
        for ((variable, campo) in campos) {
            assertTrue("$variable de la mesa no sale del ViewModel: girar el aparato lo pierde a media venta",
                Regex("""var\s+$variable\s+by\s+cobroDeLaMesa::$campo\b""").containsMatchIn(pantalla))
        }
        assertFalse("la mesa volvió a guardar el cobro confirmado en un remember (girar lo pierde)",
            pantalla.contains("committedPaymentCompletion"))
        val plana = pantalla.replace(Regex("""\s+"""), " ")
        assertFalse("el cobro se abre sin apertura nueva: usa cobroDeLaMesa.abrir()", plana.contains("showPayment = true"))
        assertEquals("el cobro se abre en dos lugares («Pagar» y «Dividir importe»)", 2,
            Regex("""cobroDeLaMesa\.abrir\(\)""").findAll(plana).count())
        assertTrue("PaymentFlowScreen no recibe la apertura del ViewModel: tras girar arrancaría otro cobro",
            plana.contains("apertura = cobroDeLaMesa.apertura,"))
        assertTrue("el ViewModel de la mesa no guarda el cobro",
            sinComentarios(leerFuente(VIEW_MODEL)).contains("val cobroDeLaMesa = CobroDeLaMesa()"))
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
        assertTrue("cerrarCobro() de la composición que se fue al girar regresaría la sesión a ORDERING bajo el cobro restaurado",
            codigo.contains("fun cerrarCobro() { if (!pantallaViva.get()) return"))
    }

    private companion object {
        const val PANTALLA = "app/src/main/java/com/avoqado/pos/tables/presentation/TableOrderScreen.kt"
        const val VIEW_MODEL = "app/src/main/java/com/avoqado/pos/tables/presentation/TableOrderViewModel.kt"
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
