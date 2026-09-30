package com.avoqado.pos.escritorio

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.res.painterResource
import com.avoqado.pos.R
import kotlin.test.Test
import kotlin.test.assertTrue

class RecursosTest {
    @OptIn(ExperimentalTestApi::class)
    @Test fun `los dos recursos de Android se cargan (png y vector)`() = runDesktopComposeUiTest {
        var anchos = listOf<Float>()
        setContent {
            anchos = listOf(painterResource(R.drawable.avoqado_logo_mark), painterResource(R.drawable.ic_whatsapp))
                .map { it.intrinsicSize.width }
        }
        waitForIdle()
        assertTrue(anchos.size == 2 && anchos.all { it > 0f }, "$anchos")
    }
}
