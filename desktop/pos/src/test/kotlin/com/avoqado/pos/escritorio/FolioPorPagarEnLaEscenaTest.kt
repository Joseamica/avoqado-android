package com.avoqado.pos.escritorio

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.avoqado.escritorio.ContextoDeEscritorio
import com.avoqado.escritorio.configuracionDeAndroid
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.designsystem.components.LocalEsEscritorio
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.onlineorders.data.FolioCobradoConTarjeta
import com.avoqado.pos.onlineorders.data.FoliosCobradosConTarjeta
import com.avoqado.pos.payment.domain.PendientesDeTarjeta
import com.avoqado.pos.onlineorders.domain.OnlineOrder
import com.avoqado.pos.onlineorders.domain.OnlineOrderStatus
import com.avoqado.pos.onlineorders.presentation.FolioPorPagarCard
import java.nio.file.Files
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tienda híbrida (A5): la tarjeta de un folio en la escena de verdad. R98: en «Por entregar» un folio READY lleva la etiqueta «Listo»
 * y sólo «Entregado»; uno ACCEPTED, los dos botones. Codex r1 #1: lo cobrado con tarjeta en este aparato dice «Pagado con tarjeta».
 * Codex r1 #5: con abono no hay cuenta regresiva ni «Venció». Y la marca de tarjeta sobrevive a reabrir la app (disco real).
 */
@OptIn(ExperimentalTestApi::class)
class FolioPorPagarEnLaEscenaTest {
    private val folder = Files.createTempDirectory("folio-por-pagar")
    private val context = ContextoDeEscritorio(folder)
    @AfterTest fun cleanup() { folder.toFile().deleteRecursively() }

    private val ahora = Instant.parse("2026-11-06T18:10:00Z")

    private fun folio(status: OnlineOrderStatus, total: Long = 13400, falta: Long = 0) = OnlineOrder(
        orderId = "f1", onlineOrderId = "oo-f1", orderNumber = "W7K3P", status = status, paymentMode = "PAY_AT_PICKUP",
        paymentStatus = if (falta == 0L) "PAID" else "PARTIAL", customerName = "Ana", customerPhone = null, note = null,
        createdAt = Instant.parse("2026-11-06T18:00:00Z"), acceptDeadlineAt = Instant.parse("2026-11-06T18:30:00Z"), estimatedReadyAt = null,
        totalCents = total, remainingCents = falta, needsPrint = false, items = emptyList(), requiresAcceptance = false, channel = "QR",
    )

    @Composable private fun escena(contenido: @Composable () -> Unit) {
        CompositionLocalProvider(
            LocalContext provides context, LocalConfiguration provides configuracionDeAndroid(1280.dp, 800.dp, 1f),
            LocalView provides View(context), LocalEsEscritorio provides true,
        ) { AvoqadoTheme(darkTheme = false) { Box(Modifier.width(480.dp)) { contenido() } } }
    }

    @Test fun `a READY folio in Por entregar shows the Listo tag and only Entregado`() = runComposeUiTest {
        setContent {
            escena {
                FolioPorPagarCard(folio(OnlineOrderStatus.READY), porEntregar = true, ahora = ahora, sinConexion = false, puedeActualizar = true,
                    ocupado = false, onListo = {}, onEntregado = {})
            }
        }
        onNodeWithTag("folio-listo-chip-f1").assertExists()
        onNodeWithTag("folio-listo-f1").assertDoesNotExist()
        onNodeWithTag("folio-entregado-f1").assertIsEnabled()
    }

    @Test fun `an ACCEPTED folio in Por entregar keeps Listo and Entregado`() = runComposeUiTest {
        setContent {
            escena {
                FolioPorPagarCard(folio(OnlineOrderStatus.ACCEPTED), porEntregar = true, ahora = ahora, sinConexion = false, puedeActualizar = true,
                    ocupado = false, onListo = {}, onEntregado = {})
            }
        }
        onNodeWithTag("folio-listo-chip-f1").assertDoesNotExist()
        onNodeWithTag("folio-listo-f1").assertIsEnabled()
        onNodeWithTag("folio-entregado-f1").assertIsEnabled()
    }

    @Test fun `a folio charged by card on this device says so and waits for the server`() = runComposeUiTest {
        setContent {
            escena {
                // La lista vieja lo trae «por pagar»; la pantalla lo movió a «Por entregar» con lo que cobró la terminal.
                // Con red pero sin releer todavía: «Listo» y «Entregado» esperan a que el SERVIDOR lo cuente.
                FolioPorPagarCard(folio(OnlineOrderStatus.PENDING_ACCEPTANCE, falta = 13400), porEntregar = true, ahora = ahora, sinConexion = false,
                    puedeActualizar = true, ocupado = false, onListo = {}, onEntregado = {}, cobradoConTarjetaCents = 13400)
            }
        }
        onNodeWithText("Pagado con tarjeta $134.00").assertExists()
        onNodeWithTag("folio-tarjeta-f1").assertExists()
        onNodeWithTag("folio-listo-f1").assertIsNotEnabled()
        onNodeWithTag("folio-entregado-f1").assertIsNotEnabled()
    }

    @Test fun `a folio with a partial payment shows what is missing and never counts down`() = runComposeUiTest {
        setContent {
            escena {
                FolioPorPagarCard(folio(OnlineOrderStatus.PENDING_ACCEPTANCE, total = 18500, falta = 8500), porEntregar = false, ahora = ahora,
                    sinConexion = false, puedeActualizar = true, ocupado = false, onListo = {}, onEntregado = {}, puedeCobrar = true)
            }
        }
        onNodeWithTag("vence-f1").assertDoesNotExist()
        onNodeWithText("Ya pagó $100.00 de $185.00").assertExists()
        onNodeWithText("Falta $85.00").assertExists()
    }

    @Test fun `the card marker survives reopening the app`() {
        val marca = FolioCobradoConTarjeta("f1", 13400, 13400, enMs = 7, venueId = "venue-1", requestId = "req-1")
        FoliosCobradosConTarjeta(SecureStorage(ContextoDeEscritorio(folder))).anotar(marca)
        val reabierto = FoliosCobradosConTarjeta(SecureStorage(ContextoDeEscritorio(folder)))
        assertEquals(listOf(marca), reabierto.marcas())
    }

    /** R100: en disco de verdad, el positivo sale de la lista de cobros y su marca entra en el MISMO commit; al reabrir, ahí está. */
    @Test fun `releasing a card positive writes its marker in the same commit on the real disk`() {
        val disco = SecureStorage(ContextoDeEscritorio(folder))
        assertEquals(true, disco.persistCobroQueSiPaso("req-1", "f1", "{}", 7))
        val marca = FolioCobradoConTarjeta("f1", 13400, 13400, enMs = 7, venueId = "venue-1", requestId = "req-1")
        assertEquals(true, disco.quitarCobroYAnotarFolio("req-1") { FoliosCobradosConTarjeta.agregar(it, marca) })

        val reabierto = SecureStorage(ContextoDeEscritorio(folder))
        assertEquals(0, PendientesDeTarjeta.leer(reabierto.pendingCardChargesJson).size)
        assertEquals(listOf(marca), FoliosCobradosConTarjeta(reabierto).marcas())
    }
}
