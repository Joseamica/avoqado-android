package com.avoqado.pos.escritorio

import android.view.View
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.avoqado.escritorio.ContextoDeEscritorio
import com.avoqado.pos.core.data.network.FalloDeLectura
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.reports.data.model.ConsultaDeInforme
import com.avoqado.pos.reports.data.model.InformeConsultado
import com.avoqado.pos.reports.data.model.ReportData
import com.avoqado.pos.reports.data.model.ReportPeriod
import com.avoqado.pos.reports.data.model.SalesSummaryReport
import com.avoqado.pos.reports.presentation.CuerpoDeInformes
import com.avoqado.pos.reports.presentation.TextosDeInformes
import com.avoqado.pos.reports.presentation.estadoDelInforme
import com.avoqado.pos.transactions.presentation.ContenidoDeVentas
import com.avoqado.pos.transactions.presentation.EstadoDeLaListaDeVentas
import com.avoqado.pos.transactions.presentation.ListaDeVentasVacia
import com.avoqado.pos.transactions.presentation.TextosDeVentas
import com.avoqado.pos.transactions.presentation.estadoDeLaListaDeVentas
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D18 (3-nov), en la pantalla: sin red, Ventas e Informes dicen «Sin conexión» con «Reintentar» —antes «Sin
 * transacciones» y «No hay datos para este periodo»—, y el informe de «Hoy» no se ve bajo «1M». Lo mismo corre en
 * WINDOWS: compila las mismas fuentes que ANDROID.
 */
@OptIn(ExperimentalTestApi::class)
class VentasEInformesSinRedEnLaEscenaTest {
    private val carpeta = Files.createTempDirectory("ventas-informes-sin-red")
    private val contexto = ContextoDeEscritorio(carpeta)
    @AfterTest fun limpiar() { carpeta.toFile().deleteRecursively() }

    @Composable private fun superficie(content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalContext provides contexto, LocalConfiguration provides contexto.resources.configuration,
            LocalView provides View(contexto)) { AvoqadoTheme(darkTheme = false, content = content) }
    }

    private val hoy = ConsultaDeInforme("venue", "2026-11-03T06:00:00.000Z", null, "hours")
    private val mes = ConsultaDeInforme("venue", "2026-11-01T06:00:00.000Z", null, "days")
    private val informeDeHoy = InformeConsultado(
        hoy,
        ReportData(
            summary = SalesSummaryReport(grossSales = 1234.5, netSales = 1234.5, transactionCount = 3),
            paymentMethods = emptyList(),
            hourlySales = emptyList(),
            periodSales = emptyList(),
            topProducts = emptyList(),
            categories = emptyList(),
        ),
    )

    private fun ComposeUiTest.informes(elegida: ConsultaDeInforme, periodo: ReportPeriod, fallo: FalloDeLectura?, onReintentar: () -> Unit = {}) =
        setContent {
            superficie {
                CuerpoDeInformes(
                    estado = estadoDelInforme(elegida, informeDeHoy, cargando = false, fallo = fallo),
                    selectedPeriod = periodo,
                    onPeriodSelected = {},
                    historyLocked = false,
                    lockedTierLabel = "Pro",
                    showCustomDatePicker = false,
                    customStartDate = 0L,
                    customEndDate = 0L,
                    onStartDateChanged = {},
                    onEndDateChanged = {},
                    onApplyCustomDates = {},
                    onCancelCustomDates = {},
                    showDetailedSummary = false,
                    onToggleDetailedSummary = {},
                    onReintentar = onReintentar,
                )
            }
        }

    @Test fun `Ventas vacia sin red dice Sin conexion con Reintentar, aunque haya una busqueda`() = runComposeUiTest {
        var reintentos = 0
        setContent {
            superficie {
                ListaDeVentasVacia(fallo = FalloDeLectura.SIN_RED, hasFilters = false, searchText = "café", onReintentar = { reintentos++ })
            }
        }
        onNodeWithText("Sin conexión").assertExists()
        onNodeWithText(TextosDeVentas.SIN_RED_TEXTO).assertExists()
        onNodeWithText("Sin transacciones").assertDoesNotExist()
        onNodeWithText("No se encontraron resultados", substring = true).assertDoesNotExist()

        onNodeWithText("Reintentar").performClick()
        runOnIdle { assertEquals(1, reintentos) }
    }

    @Test fun `Ventas vacia porque el servidor contesto mal lo dice distinto`() = runComposeUiTest {
        setContent {
            superficie { ListaDeVentasVacia(fallo = FalloDeLectura.SERVIDOR, hasFilters = true, searchText = "", onReintentar = {}) }
        }
        onNodeWithText("No se pudieron cargar las ventas").assertExists()
        onNodeWithText("Vuelve a intentarlo en un momento.").assertExists()
        onNodeWithText("Reintentar").assertExists()
        onNodeWithText("Sin conexión").assertDoesNotExist()
    }

    @Test fun `Ventas sin fallo sigue diciendo Sin transacciones`() = runComposeUiTest {
        setContent { superficie { ListaDeVentasVacia(fallo = null, hasFilters = false, searchText = "", onReintentar = {}) } }
        onNodeWithText("Sin transacciones").assertExists()
        onNodeWithText("Reintentar").assertDoesNotExist()
    }

    // MARK: - D22 ronda 7 (Codex código r6): la píldora también en la lista vacía por filtros o búsqueda

    /** Varias páginas cargadas y un filtro que las esconde todas: el cuerpo que enseña la pantalla. */
    private val vaciaPorElFiltro = estadoDeLaListaDeVentas(deLaConsulta = true, cargadas = 60, visibles = 0, cargando = false, fallo = null)

    @Test fun `D22 r7 - varias paginas, un filtro que las esconde todas y un aviso - la pildora se ve y tocarla pide la actualizacion`() = runComposeUiTest {
        assertEquals(EstadoDeLaListaDeVentas.Vacia, vaciaPorElFiltro)
        var pedidas = 0
        setContent {
            superficie {
                ContenidoDeVentas(
                    estado = vaciaPorElFiltro, hayVentasNuevas = true, hasFilters = true, searchText = "",
                    onReintentar = {}, onActualizar = { pedidas++ },
                ) { BasicText("la lista") }
            }
        }
        onNodeWithText("No se encontraron resultados con los filtros seleccionados").assertExists()
        onNodeWithText(TextosDeVentas.HAY_VENTAS_NUEVAS).performClick()
        runOnIdle { assertEquals(1, pedidas) }
    }

    @Test fun `D22 r7 - la lista vacia sin avisos no ensena la pildora`() = runComposeUiTest {
        setContent {
            superficie {
                ContenidoDeVentas(
                    estado = vaciaPorElFiltro, hayVentasNuevas = false, hasFilters = true, searchText = "",
                    onReintentar = {}, onActualizar = {},
                ) { BasicText("la lista") }
            }
        }
        onNodeWithText("No se encontraron resultados con los filtros seleccionados").assertExists()
        onNodeWithText(TextosDeVentas.HAY_VENTAS_NUEVAS).assertDoesNotExist()
    }

    @Test fun `Informes sin red bajo 1M dice Sin conexion y no ensena el informe de Hoy`() = runComposeUiTest {
        var reintentos = 0
        informes(elegida = mes, periodo = ReportPeriod.THIS_MONTH, fallo = FalloDeLectura.SIN_RED, onReintentar = { reintentos++ })

        onNodeWithText("Sin conexión").assertExists()
        onNodeWithText(TextosDeInformes.SIN_RED_TEXTO).assertExists()
        onNodeWithText("RESUMEN DE VENTAS").assertDoesNotExist()
        onAllNodesWithText("\$1,234.50").assertCountEquals(0)
        onNodeWithText("No hay datos para este periodo").assertDoesNotExist()

        onNodeWithText("Reintentar").performClick()
        runOnIdle { assertEquals(1, reintentos) }
    }

    @Test fun `Informes cuando el servidor contesto mal lo dice distinto`() = runComposeUiTest {
        informes(elegida = mes, periodo = ReportPeriod.THIS_MONTH, fallo = FalloDeLectura.SERVIDOR)

        onNodeWithText("No se pudo cargar el informe").assertExists()
        onNodeWithText("Reintentar").assertExists()
        onAllNodesWithText("\$1,234.50").assertCountEquals(0)
    }

    @Test fun `Informes bajo su periodo si ensena el informe, aunque la recarga no tenga red`() = runComposeUiTest {
        informes(elegida = hoy, periodo = ReportPeriod.TODAY, fallo = FalloDeLectura.SIN_RED)

        onNodeWithText("RESUMEN DE VENTAS").assertExists()
        assertTrue(onAllNodesWithText("\$1,234.50").fetchSemanticsNodes().isNotEmpty())
        onNodeWithText("Sin conexión").assertDoesNotExist()
    }
}
