package com.avoqado.pos.escritorio

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.avoqado.pos.customerdisplay.CustomerContent
import com.avoqado.pos.customerdisplay.CustomerDisplayScreen
import com.avoqado.pos.customerdisplay.CustomerDisplayState
import com.avoqado.pos.customerdisplay.TipOption
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * El «10 %» de la pantalla del cliente cobraba sobre el total CON IVA y el del cajero sobre la base SIN IVA. El defecto
 * vivía en el botón, así que se prueba tocándolo: lo que entrega es el monto que calculó el ViewModel, sin aritmética propia.
 */
@OptIn(ExperimentalTestApi::class)
class PropinaDelClienteEnLaEscenaTest {
    @Test fun `P1 tocar 10 por ciento entrega el monto del ViewModel y no el del total con IVA`() = runComposeUiTest {
        val state = CustomerDisplayState()
        var entregado: Int? = null
        // total con IVA 10000; el ViewModel ya calculó 862 sobre la base sin IVA (8621)
        state.show(
            CustomerContent.Tip(
                amountCents = 10_000,
                options = listOf(TipOption(10, 862), TipOption(15, 1293)),
                selectedTipCents = null,
            ),
        )
        setContent { MaterialTheme { CustomerDisplayScreen(state = state, onRating = {}, onTip = { entregado = it }) } }
        onNodeWithText("10%").performClick()
        waitForIdle()
        assertEquals(862, entregado) // antes entregaba 1000
    }
}
