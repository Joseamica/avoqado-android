package com.avoqado.pos.transactions

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source guards catch accidental removals, not rendering, clipping or touch accessibility. */
class ExchangeLayoutGuardTest {
    @Test fun `payment blocks header and system back and disabled settlement shows reason`() {
        val screen = source("transactions/presentation/exchange/ExchangeScreen.kt")
        assertTrue(screen.contains("navEnabled = !busy"))
        assertTrue(screen.contains("BackHandler { if (!busy)"))
        assertTrue(screen.contains("enabled = !busy && option.disabledReason == null"))
        assertTrue(screen.contains("option.disabledReason?.let"))
        val header = source("designsystem/components/AvoqadoFullscreenHeader.kt")
        assertTrue(header.contains("CloseButton(onClick = onNav, enabled = navEnabled)"))
        assertTrue(header.contains("BackButton(onClick = onNav, enabled = navEnabled"))
        val button = source("designsystem/components/CircularIconButton.kt")
        assertTrue(button.contains(".clickable(enabled = enabled, onClick = onClick)"))
    }
    private fun source(file: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        val path = "app/src/main/java/com/avoqado/pos/$file"
        while (dir != null) {
            for (candidate in listOf(File(dir, path), File(dir, path.removePrefix("app/")))) {
                if (candidate.isFile) return candidate.readText()
                    .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
                    .replace(Regex("//[^\\n]*"), "")
            }
            dir = dir.parentFile
        }
        throw AssertionError("Missing $path")
    }
    @Test fun `phone has view exchange bar and cart with fixed footer and safe insets`() {
        val s = source("transactions/presentation/exchange/ExchangeScreen.kt")
        assertTrue(s.contains("maxWidth >= 600.dp"))
        assertTrue(s.contains("TextosCambio.VER_CAMBIO"))
        assertTrue(s.contains("AvoqadoFullscreenHeader("))
        assertTrue(s.contains("navigationBarsPadding()"))
        val cart = s.substringAfter("private fun ExchangeCart(")
        assertTrue(cart.indexOf("ExchangeBottomBar(") > cart.indexOf("ExchangeReturnedList("))
        assertTrue(cart.contains(".weight(1f)"))
    }
    @Test fun `navigation registers exchange in both layouts and hides tab bars`() {
        val s = source("navigation/AvoqadoNavGraph.kt")
        assertEquals(2, Regex("composable\\(EXCHANGE_ROUTE\\)").findAll(s).count())
        assertEquals(2, Regex("!isPaying && !isExchanging").findAll(s).count())
    }
    @Test fun `phone cart back uses a plain accessibility label`() {
        val screen = source("transactions/presentation/exchange/ExchangeScreen.kt")
        assertTrue(screen.contains("backContentDescription = TextosCambio.REGRESAR"))
        assertTrue(source("designsystem/components/AvoqadoFullscreenHeader.kt")
            .contains("contentDescription = backContentDescription"))
        assertTrue(source("designsystem/components/CircularIconButton.kt")
            .contains("contentDescription = contentDescription"))
    }

}
