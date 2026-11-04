package com.avoqado.pos.escritorio

import android.view.View
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.swipe
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import com.avoqado.escritorio.ActividadDeEscritorio
import com.avoqado.escritorio.Bitacora
import com.avoqado.escritorio.Escritorio
import com.avoqado.escritorio.configuracionDeAndroid
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.util.formatMoney
import com.avoqado.pos.customers.data.model.Customer
import com.avoqado.pos.designsystem.components.LocalEsEscritorio
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.payment.data.TerminalPaymentService
import com.avoqado.pos.transactions.data.DesenlaceDeSaldo
import com.avoqado.pos.transactions.data.RefundRepository
import com.avoqado.pos.transactions.data.model.StoreCreditOption
import com.avoqado.pos.transactions.data.model.Transaction
import com.avoqado.pos.transactions.data.model.TransactionItem
import com.avoqado.pos.transactions.presentation.ConexionDeDevolucionViewModel
import com.avoqado.pos.transactions.presentation.IssueRefundSheet
import com.avoqado.pos.transactions.presentation.SaldoAFavorDelReembolsoViewModel
import java.nio.file.Files
import kotlin.math.floor
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred

/**
 * «Emitir reembolso» de verdad, con los tamaños medidos: 1007×601 dp es la tablet de 8.7" acostada y 601×1007, parada; 1007×400 es
 * esa tablet acostada con el teclado abierto. Es lo que las guardas de fuente de ANDROID sólo pueden leer. La rama de Android de la
 * hoja se prueba en `HojaConXEnLaEscenaTest`; aquí importa lo de adentro, que es lo mismo en las dos.
 */
@OptIn(ExperimentalTestApi::class)
class EmitirReembolsoEnLaEscenaTest {
    /** Un inyector por prueba: `TpvSettingsRepository` lee de `SecureStorage` al nacer y no debe heredar lo de otra prueba. */
    @BeforeTest fun inyectorPropio() {
        val carpeta = Files.createTempDirectory("emitir-reembolso")
        Bitacora.iniciar(carpeta)
        val actividad = ActividadDeEscritorio(carpeta)
        Escritorio.instalar(actividad, Inyector.crear(actividad))
    }

    private fun articulos(cuantos: Int) = List(cuantos) { i ->
        TransactionItem(id = "a${i + 1}", productName = "Artículo ${i + 1}", unitPrice = 100.0, amount = 100.0, remainingQty = 1)
    }

    /** Con propina y selector: lo más alto que le pasa a la derecha sin saldo a favor. */
    private fun venta(cuantos: Int) = Transaction(
        id = "venta", method = "CASH", amount = cuantos * 100.0, tipAmount = 50.0, items = articulos(cuantos),
        remainingRefundable = cuantos * 100.0 + 50.0, remainingRefundableSale = cuantos * 100.0, remainingRefundableTip = 50.0,
        canChooseRefundMethod = true,
    )

    private val ventaDeOcho = venta(8)

    /** Un cobro con tarjeta de la terminal: su devolución se abre allá. */
    private val ventaEnTerminal = Transaction(
        id = "terminal", method = "CREDIT_CARD", amount = 300.0, items = articulos(3), remainingRefundable = 300.0,
        remainingRefundableSale = 300.0, refundOnTerminal = true,
    )

    /** Tarjeta registrada a mano + efectivo + transferencia + saldo a favor: los cuatro medios que más piden a «Devolver con». */
    private val ventaConCuatroMedios = Transaction(
        id = "cuatro", method = "CREDIT_CARD", amount = 300.0, remainingRefundable = 300.0, remainingRefundableSale = 300.0,
        refundOnTerminal = false, canChooseRefundMethod = true, storeCreditOption = StoreCreditOption(available = true),
    )

    /** Lo más alto que le pasa al pie de un celular: propina y los cuatro medios, con la lista completa arriba. */
    private val ventaConTodo = ventaConCuatroMedios.copy(
        items = articulos(8), amount = 800.0, tipAmount = 50.0, remainingRefundable = 850.0, remainingRefundableSale = 800.0, remainingRefundableTip = 50.0,
    )

    private lateinit var saldo: SaldoAFavorDelReembolsoViewModel

    /** A 1 la escena mide en dp lo que mide en px; a 2, la mitad. Cambiarla es como arrastrar la ventana de WINDOWS: no monta nada de nuevo. */
    private val densidad = mutableFloatStateOf(1f)

    /** El tamaño de letra del sistema. */
    private val letra = mutableFloatStateOf(1f)

    private fun pantalla(
        ancho: Int,
        alto: Int,
        operacion: Transaction = ventaDeOcho,
        soloCompleto: Boolean = false,
        saldoDisponible: Boolean = false,
        soloSaldo: Boolean = false,
        antesDeReembolsar: suspend () -> String? = { null },
        prueba: ComposeUiTest.() -> Unit,
    ) = runDesktopComposeUiTest(ancho, alto) {
        val inyector = Escritorio.inyector
        val almacen = inyector.getInstance(SecureStorage::class.java)
        if (saldoDisponible) almacen.storeCreditAvailable = true
        if (soloSaldo) almacen.storeCreditOnly = true
        val conexion = Escritorio.crearViewModel(ConexionDeDevolucionViewModel::class.java, SavedStateHandle())
        saldo = Escritorio.crearViewModel(SaldoAFavorDelReembolsoViewModel::class.java, SavedStateHandle())
        setContent {
            CompositionLocalProvider(
                LocalContext provides Escritorio.contexto,
                LocalConfiguration provides configuracionDeAndroid(ancho.dp, alto.dp, 1f),
                LocalView provides View(Escritorio.contexto),
                LocalEsEscritorio provides true,
                LocalDensity provides Density(densidad.floatValue, letra.floatValue),
            ) {
                AvoqadoTheme(darkTheme = false) {
                    IssueRefundSheet(
                        transaction = operacion,
                        maxRefundable = operacion.remainingRefundable,
                        refundRepository = inyector.getInstance(RefundRepository::class.java),
                        terminalPaymentService = inyector.getInstance(TerminalPaymentService::class.java),
                        puedeDevolverEnEfectivo = true,
                        codigoDeEncargadoActivo = false,
                        onDismiss = {},
                        onRefunded = {},
                        soloCompleto = soloCompleto,
                        enviarASaldo = { _, _ -> CompletableDeferred<DesenlaceDeSaldo>() },
                        antesDeReembolsar = antesDeReembolsar,
                        conexion = conexion,
                        saldo = saldo,
                    )
                }
            }
        }
        waitForIdle()
        prueba()
    }

    /** Sin recortar: `getBoundsInRoot` recorta contra lo que lo contiene, y un botón fuera de la vista saldría «adentro». */
    private fun ComposeUiTest.bordes(texto: String) = onNodeWithText(texto).getUnclippedBoundsInRoot()

    /** Nada lo recorta: ni la ventana ni el deslizamiento que lo contiene. */
    private fun ComposeUiTest.seVeEntero(texto: String) = onNodeWithText(texto).let { it.getBoundsInRoot() == it.getUnclippedBoundsInRoot() }

    private fun ComposeUiTest.aLaVista(vararg textos: String) = textos.forEach {
        assertTrue(seVeEntero(it), "«$it» no se ve entero: ${bordes(it)}")
    }

    private fun ComposeUiTest.aLaIzquierda(de: Dp, vararg textos: String) = textos.forEach {
        assertTrue(bordes(it).right <= de, "«$it» debería ir a la izquierda de $de: ${bordes(it)}")
    }

    private fun ComposeUiTest.aLaDerecha(de: Dp, vararg textos: String) = textos.forEach {
        assertTrue(bordes(it).left >= de, "«$it» debería ir a la derecha de $de: ${bordes(it)}")
    }

    /** Un dedo en coordenadas de la ventana. Parte del título porque el encabezado no se mueve. */
    private fun ComposeUiTest.deslizar(x: Dp, desdeY: Dp, hastaY: Dp) {
        val titulo = bordes("Emitir reembolso")
        onNodeWithText("Emitir reembolso").performTouchInput {
            swipe(
                Offset((x - titulo.left).toPx(), (desdeY - titulo.top).toPx()),
                Offset((x - titulo.left).toPx(), (hastaY - titulo.top).toPx()),
                400,
            )
        }
        waitForIdle()
    }

    /** Desliza de abajo hacia arriba hasta que [texto] se ve entero. Revienta si no llega. */
    private fun ComposeUiTest.deslizarHastaVer(texto: String, x: Dp, alto: Dp) {
        var intentos = 0
        while (!seVeEntero(texto) && intentos++ < 8) deslizar(x, alto - 40.dp, 120.dp)
        aLaVista(texto)
    }

    /** Dónde está la señal de «hay más», en dp: a estas pruebas les basta densidad 1. */
    private fun ComposeUiTest.senales() = onAllNodesWithTag("reembolso-hay-mas").fetchSemanticsNodes().map { it.boundsInRoot }

    // MARK: - Dos columnas

    @Test fun `la tablet de 8 7 acostada va en dos columnas y con 8 articulos Reembolsar queda a la vista`() = pantalla(1007, 601) {
        val mitad = 1007.dp / 2
        aLaIzquierda(mitad, "Reembolsar artículos", "Reembolsos por importe", "Artículo 1")
        aLaDerecha(mitad, "Devolver con", "Motivo del reembolso", "Importe a reembolsar", "Cancelar", "Reembolsar")
        aLaVista("Motivo del reembolso", "Importe a reembolsar", "Cancelar", "Reembolsar")
        assertEquals(bordes("Efectivo de la caja").top, bordes("Transferencia").top, "con dos medios caben en un renglón")

        // La lista usa todo el alto: en la ventana de antes, con su pie abajo, enseñaba 2 o 3.
        val aLaVistaDeLaLista = (1..8).count { seVeEntero("Artículo $it") }
        assertTrue(aLaVistaDeLaLista >= 6, "con todo el alto caben al menos 6 artículos y se ven $aLaVistaDeLaLista")
        assertTrue(!seVeEntero("Artículo 8"), "montaje: el último no cabe sin deslizar: ${bordes("Artículo 8")}")

        assertEquals(1, senales().count { it.right <= mitad.value }, "la lista no cabe y la izquierda no lo avisa")
        assertEquals(0, senales().count { it.left >= mitad.value }, "lo de la derecha sí cabe: no debe avisar")

        // Lo que no cabe se alcanza deslizando la izquierda, y el botón no se mueve con ella.
        val elBoton = bordes("Reembolsar")
        deslizar(250.dp, 500.dp, 150.dp)
        aLaVista("Artículo 8")
        assertEquals(elBoton, bordes("Reembolsar"), "el botón se movió con la lista")
        assertEquals(0, senales().size, "al llegar al final ya no hay más")
    }

    @Test fun `con un articulo el pie sigue fijo abajo de la derecha`() = pantalla(1007, 601, venta(1)) {
        aLaVista("Reembolsar")
        assertTrue(bordes("Reembolsar").bottom >= 601.dp - 40.dp, "el pie no quedó abajo: ${bordes("Reembolsar")}")
    }

    @Test fun `por importe el campo y su casilla van a la izquierda y el reparto junto al boton`() = pantalla(1007, 601) {
        onNodeWithText("Reembolsos por importe").performClick()
        waitForIdle()
        val mitad = 1007.dp / 2
        aLaIzquierda(mitad, "Selecciona el importe a reembolsar", "Incluir propina en el reembolso")
        aLaDerecha(mitad, "Motivo del reembolso", "Importe a reembolsar", "Incluye la propina del mesero", "Reembolsar")
        aLaVista("Selecciona el importe a reembolsar", "Incluir propina en el reembolso", "Incluye la propina del mesero", "Reembolsar")

        onNode(hasSetTextAction()).performTextInput("12.50")
        waitForIdle()
        aLaDerecha(mitad, formatMoney(12.5))
    }

    @Test fun `con el teclado abierto siguen las dos columnas y Reembolsar se alcanza deslizando la derecha`() = pantalla(1007, 400) {
        val mitad = 1007.dp / 2
        aLaIzquierda(mitad, "Reembolsar artículos")
        aLaDerecha(mitad, "Motivo del reembolso", "Reembolsar")
        assertTrue(bordes("Reembolsar").bottom > 400.dp, "montaje: sin deslizar, el botón no cabe: ${bordes("Reembolsar")}")

        val laIzquierda = bordes("Reembolsar artículos")
        deslizar(750.dp, 380.dp, 80.dp)
        aLaVista("Reembolsar")
        assertEquals(laIzquierda, bordes("Reembolsar artículos"), "la izquierda se movió con la derecha")
    }

    /** Prueba el camino completo en la pantalla real: lo que enciende el botón sigue siendo lo mismo, aunque cambie de lugar. */
    private fun ComposeUiTest.escogerArticuloYMotivo() {
        onNodeWithText("Artículo 1").performClick()
        onNodeWithText("Selecciona un motivo").performClick()
        waitForIdle()
        onNodeWithText("Productos devueltos").performClick()
        waitForIdle()
    }

    @Test fun `en dos columnas escoger articulo y motivo enciende Reembolsar`() = pantalla(1007, 601) {
        onNodeWithText("Reembolsar").assertIsNotEnabled()
        escogerArticuloYMotivo()
        onNodeWithText("Reembolsar").assertIsEnabled()
    }

    @Test fun `en dos columnas el error del envio sale en lo fijo, arriba de los botones`() =
        pantalla(1007, 601, antesDeReembolsar = { "Un saldo a favor sigue sin confirmar." }) {
            escogerArticuloYMotivo()
            onNodeWithText("Reembolsar").performClick()
            waitForIdle()
            aLaDerecha(1007.dp / 2, "Un saldo a favor sigue sin confirmar.")
            aLaVista("Un saldo a favor sigue sin confirmar.")
            assertTrue(bordes("Importe a reembolsar").bottom <= bordes("Un saldo a favor sigue sin confirmar.").top, "el error va abajo del importe")
            assertTrue(bordes("Un saldo a favor sigue sin confirmar.").bottom <= bordes("Reembolsar").top, "el error va arriba de los botones")
        }

    /** Arriba, con todo deslizado o con el teclado abierto, nadie lo veía: el botón sólo brincaba y la cajera volvía a tocar (SAMSUNG, 3-nov). */
    @Test fun `en una columna el error del envio sale pegado a los botones`() =
        pantalla(601, 1007, antesDeReembolsar = { "Un saldo a favor sigue sin confirmar." }) {
            escogerArticuloYMotivo()
            onNodeWithText("Reembolsar").performClick()
            waitForIdle()
            aLaVista("Un saldo a favor sigue sin confirmar.")
            assertTrue(bordes("Incluir propina en el reembolso").bottom <= bordes("Un saldo a favor sigue sin confirmar.").top, "el error va abajo del importe")
            assertTrue(bordes("Un saldo a favor sigue sin confirmar.").bottom <= bordes("Reembolsar").top, "el error va arriba de los botones")
        }

    @Test fun `con el teclado abierto en un celular, deslizado hasta Reembolsar, el error del envio queda a la vista`() =
        pantalla(360, 490, ventaConTodo, antesDeReembolsar = { "Un saldo a favor sigue sin confirmar." }) {
            onNodeWithText("Reembolsos por importe").performClick()
            waitForIdle()
            onNode(hasSetTextAction()).performTextInput("2")
            waitForIdle()
            deslizarHastaVer("Selecciona un motivo", 180.dp, 490.dp)
            onNodeWithText("Selecciona un motivo").performClick()
            waitForIdle()
            onNodeWithText("Productos devueltos").performClick()
            waitForIdle()
            deslizarHastaVer("Reembolsar", 180.dp, 490.dp)
            onNodeWithText("Reembolsar").performClick()
            waitForIdle()
            aLaVista("Un saldo a favor sigue sin confirmar.")
        }

    @Test fun `el aviso de la terminal va a la izquierda y lo que no devuelve junto al boton`() = pantalla(1007, 601, ventaEnTerminal) {
        val mitad = 1007.dp / 2
        aLaIzquierda(mitad, "Este cobro fue con tarjeta", "Abrir en la terminal")
        aLaDerecha(mitad, "Esto no devuelve el dinero a la tarjeta: eso se hace en la terminal.", "Reembolsar")
        aLaVista("Abrir en la terminal", "Esto no devuelve el dinero a la tarjeta: eso se hace en la terminal.")
        assertTrue(bordes("Abrir en la terminal").left > bordes("Este cobro fue con tarjeta").left, "con 503 dp de columna van en un renglón")
    }

    @Test fun `en la columna mas angosta el aviso de la terminal se apila`() = pantalla(840, 700, ventaEnTerminal) {
        assertEquals(bordes("Este cobro fue con tarjeta").left, bordes("Abrir en la terminal").left, "con 420 dp de columna el texto quedaría en un hilo")
    }

    @Test fun `por importe con el teclado abierto en la 8 7 acostada se ve el campo y a la derecha se llega a todo`() = pantalla(1007, 335) {
        onNodeWithText("Reembolsos por importe").performClick()
        waitForIdle()
        onNode(hasSetTextAction()).performTextInput("12.50")
        waitForIdle()
        aLaVista("12.50")
        onNode(hasSetTextAction()).assertIsFocused()
        assertTrue(!seVeEntero("Reembolsar"), "montaje: sin deslizar, el botón no cabe: ${bordes("Reembolsar")}")

        repeat(3) { deslizar(750.dp, 250.dp, 80.dp) }
        aLaVista("Selecciona un motivo", "Incluye la propina del mesero", "Reembolsar", "12.50")
        onNode(hasSetTextAction()).assertIsFocused()
    }

    /** Con el teléfono obligatorio no cabe. La señal va arriba del pie fijo, porque ahí se corta lo que se desliza. */
    @Test fun `a la derecha sale la senal de hay mas cuando no cabe, con el pie fijo`() =
        pantalla(1007, 601, ventaConTodo, saldoDisponible = true, soloSaldo = true) {
            saldo.escoger(Customer(id = "ana", firstName = "Ana"))
            waitForIdle()
            val mitad = 1007f / 2
            val derecha = senales().filter { it.left >= mitad }
            assertEquals(1, derecha.size, "lo de la derecha no cabe y no lo avisa: ${senales()}")
            assertTrue(derecha.single().bottom <= bordes("Importe a reembolsar").top.value, "la señal va arriba del pie fijo: $derecha")

            repeat(2) { deslizar(750.dp, 350.dp, 100.dp) }
            aLaVista("Selecciona un motivo")
            assertEquals(0, senales().count { it.left >= mitad }, "al llegar al final ya no hay más")
        }

    @Test fun `a la derecha sale la senal de hay mas cuando no cabe, sin pie fijo`() = pantalla(1007, 400) {
        val mitad = 1007f / 2
        assertEquals(1, senales().count { it.left >= mitad }, "el botón no se ve y no lo avisa: ${senales()}")
        deslizar(750.dp, 380.dp, 80.dp)
        assertEquals(0, senales().count { it.left >= mitad }, "al llegar al final ya no hay más")
    }

    @Test fun `el corte es por ancho y nada mas`() {
        pantalla(839, 700) { assertTrue(bordes("Motivo del reembolso").left < 839.dp / 2, "a 839 va una columna") }
        pantalla(840, 700) { aLaDerecha(840.dp / 2, "Motivo del reembolso") }
    }

    // MARK: - Una columna: lo mismo de antes

    @Test fun `la tablet de 8 7 parada va en una columna con el orden de hoy`() = pantalla(601, 1007) {
        val orden = listOf("Reembolsar artículos", "Artículo 1", "Incluir propina en el reembolso", "Devolver con", "Motivo del reembolso", "Importe a reembolsar", "Reembolsar")
        val arriba = orden.map { bordes(it).top }
        assertEquals(arriba.sortedBy { it.value }, arriba, "cambió el orden de: $orden")
        orden.forEach { assertTrue(bordes(it).left < 601.dp / 2, "«$it» se salió de la columna: ${bordes(it)}") }
        aLaVista("Reembolsar")
    }

    // Con pie fijo, el pie toca el fondo y lo que sobra queda entre la lista y el pie (OrderPAD 3 parado, 3-nov).

    @Test fun `a 800x1280 con 2 articulos Reembolsar queda abajo y lo que sobra entre la lista y el pie`() = pantalla(800, 1280, venta(2)) {
        aLaVista("Reembolsar", "Artículo 1", "Artículo 2")
        assertTrue(bordes("Reembolsar").bottom >= 1280.dp - 40.dp, "el pie no tocó el fondo: ${bordes("Reembolsar")}")
        assertTrue(bordes("Artículo 1").top < 300.dp, "la lista se fue de arriba: ${bordes("Artículo 1")}")
        assertTrue(bordes("Incluir propina en el reembolso").top - bordes("Artículo 2").bottom > 300.dp, "lo que sobra va entre la lista y el pie")
    }

    @Test fun `cambiar de pestana no mueve Reembolsar`() = pantalla(800, 1280, venta(2)) {
        val elBoton = bordes("Reembolsar")
        onNodeWithText("Reembolsos por importe").performClick()
        waitForIdle()
        assertEquals(elBoton, bordes("Reembolsar"), "el botón brincó al cambiar de pestaña")
    }

    // «Por importe», igual: lo que se escribe arriba, lo que sobra en medio y lo demás abajo.

    @Test fun `a 800x1280 por importe el campo queda arriba, lo que sobra en medio y Reembolsar abajo`() = pantalla(800, 1280, venta(2)) {
        onNodeWithText("Reembolsos por importe").performClick()
        waitForIdle()
        aLaVista("Selecciona el importe a reembolsar", "Incluir propina en el reembolso", "Reembolsar")
        assertTrue(bordes("Incluir propina en el reembolso").bottom < 640.dp, "el campo y su casilla se fueron de la mitad de arriba: ${bordes("Incluir propina en el reembolso")}")
        assertTrue(bordes("Reembolsar").bottom >= 1280.dp - 40.dp, "el pie no tocó el fondo: ${bordes("Reembolsar")}")
        assertTrue(bordes("Devolver con").top - bordes("Incluir propina en el reembolso").bottom > 300.dp, "lo que sobra va abajo del campo")
    }

    /** 800×1280 px: a densidad 1 el pie es fijo; a 2.5 (320×512 dp) se desliza todo junto, y no cabe. Es el mismo cambio de alto que abrir y cerrar el teclado. */
    @Test fun `por importe abrir y cerrar el teclado cruzando el corte no pierde foco ni texto y el campo sigue arriba`() = pantalla(800, 1280, venta(2)) {
        onNodeWithText("Reembolsos por importe").performClick()
        waitForIdle()
        onNode(hasSetTextAction()).performTextInput("12.50")
        waitForIdle()
        repeat(2) {
            assertTrue(bordes("12.50").bottom < 640.dp, "el campo se fue de la mitad de arriba: ${bordes("12.50")}")
            aLaVista("12.50", "Reembolsar")
            onNode(hasSetTextAction()).assertIsFocused()

            densidad.floatValue = 2.5f
            waitForIdle()
            assertTrue(bordes("Reembolsar").bottom > 512.dp, "montaje: ya se desliza todo junto: ${bordes("Reembolsar")}")
            aLaVista("12.50")
            onNode(hasSetTextAction()).assertIsFocused()

            densidad.floatValue = 1f
            waitForIdle()
            onNode(hasSetTextAction()).assertIsFocused()
            onNodeWithText("12.50").assertExists()
        }
    }

    @Test fun `la tarjeta de regalo no cambia con pie fijo`() {
        val regalo = Transaction(id = "regalo", method = "CASH", amount = 500.0, remainingRefundable = 500.0)
        pantalla(800, 1280, regalo, soloCompleto = true) {
            assertTrue(bordes("Motivo del reembolso").top < 150.dp, "se fue de arriba: ${bordes("Motivo del reembolso")}")
            assertTrue(bordes("Reembolsar").bottom < 400.dp, "el pie se fue abajo: ${bordes("Reembolsar")}")
        }
    }

    // Con poco alto, en una columna se desliza todo junto: lo mismo en Artículos que en «Por importe».

    @Test fun `en un celular chico se ven articulos y Reembolsar se alcanza deslizando`() = pantalla(360, 640, ventaConTodo, saldoDisponible = true) {
        aLaVista("Artículo 1")
        assertTrue(!seVeEntero("Reembolsar"), "montaje: sin deslizar, el botón no cabe: ${bordes("Reembolsar")}")
        assertEquals(1, senales().size, "el botón no se ve y nada lo avisa: ${senales()}")

        deslizarHastaVer("Reembolsar", 180.dp, 640.dp)
        assertEquals(0, senales().size, "al llegar al final ya no hay más")
    }

    @Test fun `en un celular chico se puede completar el reembolso deslizando`() = pantalla(360, 640, ventaConTodo, saldoDisponible = true) {
        onNodeWithText("Artículo 1").performClick()
        // Con el dedo y con tope: `performScrollTo` se queda dando vueltas si lo que busca no se puede alcanzar.
        deslizarHastaVer("Selecciona un motivo", 180.dp, 640.dp)
        onNodeWithText("Selecciona un motivo").performClick()
        waitForIdle()
        onNodeWithText("Productos devueltos").performClick()
        waitForIdle()
        deslizarHastaVer("Reembolsar", 180.dp, 640.dp)
        onNodeWithText("Reembolsar").assertIsEnabled()
    }

    @Test fun `con el teclado abierto en un celular el telefono del saldo queda a la vista con su foco y lo escrito`() =
        pantalla(360, 490, ventaConTodo, saldoDisponible = true, soloSaldo = true) {
            saldo.escoger(Customer(id = "ana", firstName = "Ana"))
            waitForIdle()
            onNode(hasSetTextAction()).performTextInput("5512345678")
            waitForIdle()
            aLaVista("5512345678")
            onNode(hasSetTextAction()).assertIsFocused()

            deslizarHastaVer("Guardar teléfono", 180.dp, 490.dp)
            onNode(hasSetTextAction()).assertIsFocused()
            onNodeWithText("5512345678").assertExists()
        }

    /**
     * 600×1000 px: a densidad 1 el pie es fijo; a 1.5 (400×667 dp) se desliza todo junto. Es el mismo cambio de alto que abrir y
     * cerrar el teclado, de un solo salto. Si el campo cambiara de padre perdería el foco, y con un teclado de verdad, el teclado.
     */
    @Test fun `abrir y cerrar el teclado cruzando el corte no pierde foco ni texto del telefono`() =
        pantalla(600, 1000, ventaConTodo, saldoDisponible = true, soloSaldo = true) {
            saldo.escoger(Customer(id = "ana", firstName = "Ana"))
            waitForIdle()
            onNode(hasSetTextAction()).performTextInput("5512345678")
            waitForIdle()
            repeat(2) {
                aLaVista("Reembolsar", "5512345678")

                densidad.floatValue = 1.5f
                waitForIdle()
                assertTrue(bordes("Reembolsar").bottom > 667.dp, "montaje: ya se desliza todo junto: ${bordes("Reembolsar")}")
                aLaVista("5512345678")
                onNode(hasSetTextAction()).assertIsFocused()
                assertTrue(senales().size <= 1, "la banda no se desliza y su señal se quedó prendida: ${senales()}")

                densidad.floatValue = 1f
                waitForIdle()
                onNode(hasSetTextAction()).assertIsFocused()
            }
        }

    @Test fun `abrir y cerrar el teclado cruzando el corte no pierde foco ni texto del importe`() = pantalla(600, 1000) {
        onNodeWithText("Reembolsos por importe").performClick()
        waitForIdle()
        onNode(hasSetTextAction()).performTextInput("12.50")
        waitForIdle()
        repeat(2) {
            aLaVista("Reembolsar", "12.50")
            listOf(1.5f, 2f).forEach {
                densidad.floatValue = it
                waitForIdle()
                aLaVista("12.50")
                onNode(hasSetTextAction()).assertIsFocused()
            }
            densidad.floatValue = 1f
            waitForIdle()
            onNode(hasSetTextAction()).assertIsFocused()
        }
    }

    /** Deslizar sobre el pie no lo mueve: lo que no cabe se queda en la lista. */
    private fun ComposeUiTest.elPieSigueFijo(x: Dp, alto: Dp) {
        aLaVista("Reembolsar")
        val elBoton = bordes("Reembolsar")
        deslizar(x, alto - 100.dp, alto - 300.dp)
        assertEquals(elBoton, bordes("Reembolsar"), "el pie se movió con el dedo")
        assertEquals(0, senales().count { it.bottom >= (alto - 25.dp).value }, "con pie fijo, abajo de la ventana no hay nada que avisar")
    }

    @Test fun `a 360x780 con cuatro medios a la lista no le alcanza, se desliza todo y Reembolsar se alcanza`() =
        pantalla(360, 780, ventaConTodo, saldoDisponible = true) {
            aLaVista("Artículo 1")
            assertTrue(bordes("Reembolsar").bottom > 780.dp, "montaje: se desliza todo junto: ${bordes("Reembolsar")}")
            deslizarHastaVer("Reembolsar", 180.dp, 780.dp)
        }

    @Test fun `a 360x780 con el telefono obligatorio el campo se ve y Reembolsar tambien se alcanza`() =
        pantalla(360, 780, ventaConTodo, saldoDisponible = true, soloSaldo = true) {
            saldo.escoger(Customer(id = "ana", firstName = "Ana"))
            waitForIdle()
            onNode(hasSetTextAction()).performTextInput("5512345678")
            waitForIdle()
            aLaVista("5512345678")
            onNode(hasSetTextAction()).assertIsFocused()
            deslizarHastaVer("Reembolsar", 180.dp, 780.dp)
            onNode(hasSetTextAction()).assertIsFocused()
        }

    // Aunque sobre alto: si con el pie fijo a la lista no le quedarían 160 dp, también se desliza todo junto.

    private fun ComposeUiTest.seEligeUnArticuloYLuegoSeAlcanzaReembolsar(alto: Dp) {
        assertTrue(bordes("Reembolsar").bottom > alto, "montaje: se desliza todo junto: ${bordes("Reembolsar")}")
        aLaVista("Artículo 1")
        onNodeWithText("\$0.00").assertExists()
        onNodeWithText("Artículo 1").performClick()
        waitForIdle()
        onNodeWithText("\$0.00").assertDoesNotExist()
        deslizarHastaVer("Reembolsar", 180.dp, alto)
    }

    @Test fun `a 360x780 con propina, saldo y telefono obligatorio se elige un articulo y luego se alcanza Reembolsar`() =
        pantalla(360, 780, ventaConTodo, saldoDisponible = true, soloSaldo = true) {
            saldo.escoger(Customer(id = "ana", firstName = "Ana"))
            waitForIdle()
            seEligeUnArticuloYLuegoSeAlcanzaReembolsar(780.dp)
        }

    @Test fun `a 360x780 con letra grande tambien se elige un articulo y luego se alcanza Reembolsar`() =
        pantalla(360, 780, ventaConTodo, saldoDisponible = true, soloSaldo = true) {
            letra.floatValue = 1.3f
            saldo.escoger(Customer(id = "ana", firstName = "Ana"))
            waitForIdle()
            seEligeUnArticuloYLuegoSeAlcanzaReembolsar(780.dp)
        }

    @Test fun `a 360x780 con dos medios y sin saldo el pie sigue fijo y se ven la lista y Reembolsar`() = pantalla(360, 780) {
        aLaVista("Artículo 1")
        elPieSigueFijo(180.dp, 780.dp)
    }

    @Test fun `a 360x780 abrir y cerrar el teclado no pierde foco ni texto del telefono`() =
        pantalla(360, 780, ventaConTodo, saldoDisponible = true, soloSaldo = true) {
            saldo.escoger(Customer(id = "ana", firstName = "Ana"))
            waitForIdle()
            onNode(hasSetTextAction()).performTextInput("5512345678")
            waitForIdle()
            repeat(2) {
                aLaVista("5512345678")
                onNode(hasSetTextAction()).assertIsFocused()

                densidad.floatValue = 1.5f
                waitForIdle()
                aLaVista("5512345678")
                onNode(hasSetTextAction()).assertIsFocused()

                densidad.floatValue = 1f
                waitForIdle()
            }
        }

    /** 360×1000 px: a densidad 1 el pie es fijo; a 1.25 (288×800 dp) el alto pasa el corte, pero a la lista no le alcanza. Cruzar sólo esta regla es igual que cruzar el corte. */
    @Test fun `cruzar lo minimo de la lista no pierde foco ni texto del telefono`() =
        pantalla(360, 1000, ventaConTodo, saldoDisponible = true, soloSaldo = true) {
            saldo.escoger(Customer(id = "ana", firstName = "Ana"))
            waitForIdle()
            onNode(hasSetTextAction()).performTextInput("5512345678")
            waitForIdle()
            repeat(2) {
                aLaVista("Reembolsar", "5512345678")
                onNode(hasSetTextAction()).assertIsFocused()

                densidad.floatValue = 1.25f
                waitForIdle()
                assertTrue(bordes("Reembolsar").bottom > 800.dp, "montaje: ya se desliza todo junto: ${bordes("Reembolsar")}")
                aLaVista("5512345678")
                onNode(hasSetTextAction()).assertIsFocused()

                densidad.floatValue = 1f
                waitForIdle()
            }
        }

    /** Sin propina, el pie mide igual en las dos pestañas y `onSizeChanged` calla al volver: lo medido antes de escoger el saldo ya no vale. */
    @Test fun `escoger el saldo por importe y volver a articulos no deja a la lista sin sitio`() =
        pantalla(
            360,
            780,
            ventaConCuatroMedios.copy(items = articulos(8), amount = 800.0, remainingRefundable = 800.0, remainingRefundableSale = 800.0),
            saldoDisponible = true,
        ) {
            assertTrue(bordes("Reembolsar").bottom <= 780.dp, "montaje: sin saldo el pie es fijo: ${bordes("Reembolsar")}")
            aLaVista("Artículo 1")

            onNodeWithText("Reembolsos por importe").performClick()
            waitForIdle()
            onNodeWithText("Saldo a favor").performClick()
            waitForIdle()
            saldo.escoger(Customer(id = "ana", firstName = "Ana"))
            waitForIdle()
            onNodeWithText("Reembolsar artículos").performClick()
            waitForIdle()

            seEligeUnArticuloYLuegoSeAlcanzaReembolsar(780.dp)
        }

    @Test fun `a 601x1007 el pie sigue fijo`() = pantalla(601, 1007, ventaConTodo, saldoDisponible = true) { elPieSigueFijo(300.dp, 1007.dp) }

    /** El aviso de la terminal ya no se esconde con poco alto: su botón se queda, y se alcanza deslizando también después de escribir el importe. */
    @Test fun `a poco alto por importe Abrir en la terminal se alcanza`() = pantalla(360, 500, ventaEnTerminal) {
        onNodeWithText("Reembolsos por importe").performClick()
        waitForIdle()
        aLaVista("Abrir en la terminal")
        onNode(hasSetTextAction()).performTextInput("12.50")
        waitForIdle()
        aLaVista("12.50")
        onNode(hasSetTextAction()).assertIsFocused()

        repeat(3) { deslizar(180.dp, 150.dp, 450.dp) }
        aLaVista("Abrir en la terminal")
        onNode(hasSetTextAction()).assertIsFocused()
    }

    @Test fun `en una columna con poco alto por importe se desliza todo y Reembolsar se alcanza`() = pantalla(601, 400) {
        onNodeWithText("Reembolsos por importe").performClick()
        waitForIdle()
        aLaVista("Selecciona el importe a reembolsar")
        assertTrue(bordes("Reembolsar").bottom > 400.dp, "montaje: sin deslizar, el botón no cabe: ${bordes("Reembolsar")}")
        repeat(2) { deslizar(300.dp, 380.dp, 80.dp) }
        aLaVista("Reembolsar")
    }

    @Test fun `la tarjeta de regalo va en una columna centrada aunque la pantalla sea ancha`() {
        val regalo = Transaction(id = "regalo", method = "CASH", amount = 500.0, remainingRefundable = 500.0)
        pantalla(1280, 720, regalo, soloCompleto = true) {
            onNodeWithText("Reembolsar artículos").assertDoesNotExist()
            val borde = (1280.dp - 640.dp) / 2
            listOf("Motivo del reembolso", "Importe a reembolsar", "Cancelar").forEach {
                assertTrue(bordes(it).left in borde..(1280.dp / 2), "«$it» no va en la columna centrada: ${bordes(it)}")
            }
            assertTrue(bordes("Reembolsar").right <= 1280.dp - borde, "«Reembolsar» se pasa del tope: ${bordes("Reembolsar")}")
        }
    }

    // MARK: - «Devolver con» en la columna de ~420 dp

    /** El texto cabe entero en un renglón: su nodo mide al menos lo que pide. `hasVisualOverflow` no sirve aquí (ver `HojaDeVariantesTest`). */
    private fun ComposeUiTest.enUnRenglon(texto: String) {
        val nodo = onNodeWithText(texto, useUnmergedTree = true)
        val dibujos = mutableListOf<TextLayoutResult>()
        nodo.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(dibujos)
        val pide = floor(dibujos.single().multiParagraph.intrinsics.maxIntrinsicWidth)
        assertTrue(nodo.fetchSemanticsNode().size.width >= pide, "«$texto» se cortó: mide ${nodo.fetchSemanticsNode().size.width} y pide $pide")
    }

    private val cuatroMedios = listOf("Tarjeta de crédito", "Efectivo de la caja", "Transferencia", "Saldo a favor")

    @Test fun `en la columna mas angosta los medios de Devolver con no cortan texto y escoger uno no reacomoda la fila`() =
        pantalla(840, 700, ventaConCuatroMedios, saldoDisponible = true) {
            aLaDerecha(840.dp / 2, *cuatroMedios.toTypedArray())
            cuatroMedios.forEach { enUnRenglon(it) }

            // Escogerlo le pone palomita y negritas.
            val antes = cuatroMedios.map { bordes(it) }
            onNodeWithText("Transferencia").performClick()
            waitForIdle()
            cuatroMedios.forEach { enUnRenglon(it) }
            assertEquals(antes, cuatroMedios.map { bordes(it) }, "escoger un medio reacomodó la fila")
        }

    // MARK: - Abrir el teclado

    /**
     * Abrir el teclado baja el alto y el pie deja de ser fijo. Eso no debe recrear el campo donde se está escribiendo: perdería el
     * foco y el teclado se cerraría, y con él volvería el pie fijo. 1900×640 px son dos columnas con pie fijo a densidad 1 y dos
     * columnas sin pie fijo a 1.5 (1266×426 dp): es el mismo cambio de alto, sin teclado de verdad.
     */
    private fun ComposeUiTest.elCampoSigueConElFoco(texto: String) {
        onNode(hasSetTextAction()).performTextInput(texto)
        waitForIdle()
        onNode(hasSetTextAction()).assertIsFocused()
        assertTrue(seVeEntero("Reembolsar"), "montaje: con pie fijo, el botón se ve sin deslizar")

        densidad.floatValue = 1.5f
        waitForIdle()
        aLaDerecha(1266.dp / 2, "Motivo del reembolso")
        assertTrue(bordes("Reembolsar").top > bordes("Motivo del reembolso").top, "montaje: el cierre sigue abajo del motivo")
        onNode(hasSetTextAction()).assertIsFocused()
        onNodeWithText(texto).assertExists()
    }

    @Test fun `abrir el teclado no le quita el foco al importe`() = pantalla(1900, 640) {
        onNodeWithText("Reembolsos por importe").performClick()
        waitForIdle()
        elCampoSigueConElFoco("12.50")
    }

    @Test fun `abrir el teclado no le quita el foco al telefono del saldo`() =
        pantalla(1900, 640, ventaConCuatroMedios.copy(items = articulos(2)), saldoDisponible = true, soloSaldo = true) {
            saldo.escoger(Customer(id = "ana", firstName = "Ana"))
            waitForIdle()
            elCampoSigueConElFoco("5512345678")
        }

    // MARK: - Cruzar el corte de 840 en WINDOWS

    private fun ComposeUiTest.cruzarElCorte(aDensidad: Float) {
        densidad.floatValue = aDensidad
        waitForIdle()
    }

    @Test fun `cruzar el corte conserva el importe y los articulos`() = pantalla(1280, 1600) {
        aLaDerecha(640.dp, "Motivo del reembolso")
        onNodeWithText("Artículo 2").performClick()
        onNodeWithText("Reembolsos por importe").performClick()
        onNode(hasSetTextAction()).performTextInput("12.50")
        waitForIdle()

        cruzarElCorte(2f)
        assertTrue(bordes("Motivo del reembolso").left < 320.dp, "montaje: con 640 dp ya es una columna")
        onNodeWithText("12.50").assertExists()

        cruzarElCorte(1f)
        aLaDerecha(640.dp, "Motivo del reembolso")
        onNodeWithText("12.50").assertExists()
        onNodeWithText("Reembolsar artículos").performClick()
        waitForIdle()
        onNodeWithText("Elige el motivo del reembolso.").assertExists()   // sin el artículo diría «Selecciona al menos un artículo.»
    }

    @Test fun `cruzar el corte conserva el telefono a medio escribir`() =
        pantalla(1280, 1600, ventaConCuatroMedios.copy(items = articulos(2)), saldoDisponible = true, soloSaldo = true) {
            saldo.escoger(Customer(id = "ana", firstName = "Ana"))
            waitForIdle()
            onNode(hasSetTextAction()).performTextInput("5512345678")
            waitForIdle()

            cruzarElCorte(2f)
            assertTrue(bordes("Motivo del reembolso").left < 320.dp, "montaje: con 640 dp ya es una columna")
            onNodeWithText("5512345678").assertExists()

            cruzarElCorte(1f)
            onNodeWithText("5512345678").assertExists()
        }
}
