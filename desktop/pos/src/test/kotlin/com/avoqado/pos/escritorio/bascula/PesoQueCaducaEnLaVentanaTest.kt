package com.avoqado.pos.escritorio.bascula

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.avoqado.pos.designsystem.components.AtajosDeTeclado
import com.avoqado.pos.designsystem.components.LocalEsEscritorio
import com.avoqado.pos.pos.data.model.NormalizedScaleReading
import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.pos.presentation.product.WeightCapturePanel
import com.avoqado.pos.scale.ScaleConnectionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * D15 en la ventana «Peso» real (la misma que compila ANDROID): si la báscula calla con el cable puesto, su último
 * «Lectura estable» no puede cobrarse para otro producto. Sólo cuenta un peso de hace ≤ 2 s llegado después de abrir
 * el producto; si no, la ventana dice «Coloca el producto…» y «Agregar»/F12 no hacen nada.
 */
@OptIn(ExperimentalTestApi::class)
class PesoQueCaducaEnLaVentanaTest {
    private val queso = Product(id = "queso", name = "Queso Oaxaca", priceValue = 280.20, soldByWeight = true)
    private val crema = Product(id = "crema", name = "Crema", priceValue = 120.00, soldByWeight = true)

    private class Caja(producto: Product, estado: ScaleConnectionState) {
        var producto by mutableStateOf(producto)
        var estado by mutableStateOf(estado)
        val agregados = mutableListOf<Double>()
    }

    private fun estable(observedAt: Long, kg: String = "0.435") = ScaleConnectionState.Stable(
        "Rhino",
        NormalizedScaleReading(deviceId = "rhino", netKg = kg, stable = true, observedAtEpochMillis = observedAt),
    )

    /** Abre la ventana y espera a que se pinte: en la caja real se abre ANTES de que llegue la lectura siguiente. */
    private fun ComposeUiTest.ventana(c: Caja) {
        pintar(c)
        waitForIdle()
    }

    private fun ComposeUiTest.pintar(c: Caja) = setContent {
        CompositionLocalProvider(LocalEsEscritorio provides true) {
            MaterialTheme {
                WeightCapturePanel(
                    product = c.producto,
                    isTablet = true,
                    scaleState = c.estado,
                    onAdd = { c.agregados += it },
                    onDismiss = {},
                )
            }
        }
    }

    private fun ComposeUiTest.sinPesoQueOfrecer(c: Caja) {
        onNodeWithText("Agregar •", substring = true).assertDoesNotExist()
        onNodeWithText("Lectura estable", substring = true).assertDoesNotExist()
        onNodeWithText("Coloca el producto sobre la báscula.").assertExists()
        runOnIdle { assertFalse(AtajosDeTeclado.atender(Key.F12), "F12 no agrega sin peso vigente") }
        runOnIdle { assertEquals(emptyList(), c.agregados) }
    }

    @Test fun `la bascula callada - al volver a abrir la ventana su ultimo peso estable no se ofrece`() = runComposeUiTest {
        // El «Estable» de otro producto sigue en el lector (la ventana se cerró sin desconectar, o se salió de Cobrar).
        val c = Caja(crema, estable(System.currentTimeMillis() - 3_000))
        ventana(c)
        sinPesoQueOfrecer(c)
    }

    @Test fun `un peso de antes de abrir el producto no se ofrece aunque sea de hace un instante`() = runComposeUiTest {
        val c = Caja(crema, estable(System.currentTimeMillis() - 50))
        ventana(c)
        sinPesoQueOfrecer(c)
    }

    @Test fun `la pistola cambia de producto con la ventana abierta - el peso del anterior no pasa al nuevo`() = runComposeUiTest {
        val c = Caja(queso, ScaleConnectionState.Ready("Rhino"))
        ventana(c)
        c.estado = estable(System.currentTimeMillis())
        onNodeWithText("Agregar •", substring = true).assertExists()
        c.producto = crema   // la báscula ya no manda nada: el estado se queda igual
        sinPesoQueOfrecer(c)
    }

    @Test fun `un peso nuevo se agrega, y si la bascula calla 2 s se apaga`() = runComposeUiTest {
        val c = Caja(queso, ScaleConnectionState.Ready("Rhino"))
        ventana(c)
        c.estado = estable(System.currentTimeMillis())
        onNodeWithText("Lectura estable · Rhino").assertExists()
        onNodeWithText("Agregar • \$121.89").assertExists()
        mainClock.advanceTimeBy(2_100)
        sinPesoQueOfrecer(c)
        // Llega otra lectura: vuelve a ofrecerse y F12 la agrega una vez.
        c.estado = estable(System.currentTimeMillis(), kg = "0.500")
        onNodeWithText("Agregar • \$140.10").assertExists()
        runOnIdle { assertTrue(AtajosDeTeclado.atender(Key.F12)) }
        runOnIdle { assertEquals(listOf(0.5), c.agregados) }
    }
}
