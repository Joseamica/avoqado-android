package com.avoqado.pos.escritorio

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.res.painterResource
import com.avoqado.pos.R
import kotlin.test.Test
import kotlin.test.assertTrue

class RecursosTest {
    @OptIn(ExperimentalTestApi::class)
    @Test fun `los recursos de Android se cargan (png y vector)`() = runDesktopComposeUiTest {
        var anchos = listOf<Float>()
        setContent {
            anchos = listOf(
                painterResource(R.drawable.avoqado_logo_mark),
                painterResource(R.drawable.ic_whatsapp),
                // El «Powered by» de la pantalla del cliente (logo horizontal negro y blanco).
                painterResource(R.drawable.avoqado_logo_inline_black),
                painterResource(R.drawable.avoqado_logo_inline_white),
            ).map { it.intrinsicSize.width }
        }
        waitForIdle()
        assertTrue(anchos.size == 4 && anchos.all { it > 0f }, "$anchos")
    }
}
