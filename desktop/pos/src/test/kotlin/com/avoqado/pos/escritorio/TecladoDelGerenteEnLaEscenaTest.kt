package com.avoqado.pos.escritorio

import android.view.View
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.avoqado.escritorio.ActividadDeEscritorio
import com.avoqado.escritorio.Bitacora
import com.avoqado.escritorio.ContextoDeEscritorio
import com.avoqado.escritorio.Escritorio
import com.avoqado.escritorio.configuracionDeAndroid
import com.avoqado.pos.core.data.network.ManagerOverrideCoordinator
import com.avoqado.pos.core.data.network.OverrideResult
import com.avoqado.pos.designsystem.components.LocalEsEscritorio
import com.avoqado.pos.designsystem.components.ManagerOverrideSheet
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import kotlinx.coroutines.CompletableDeferred
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** El teclado del PIN del gerente (retiro con gerente, menor de la auditoría final): dos toques en «Autorizar» = una sola petición. */
@OptIn(ExperimentalTestApi::class)
class TecladoDelGerenteEnLaEscenaTest {
    private val folder = Files.createTempDirectory("teclado-del-gerente")
    private val context = ContextoDeEscritorio(folder)
    @AfterTest fun cleanup() { folder.toFile().deleteRecursively() }

    /** La rama de Android de los diálogos llama `ImmersiveWindow`, que pide el contexto de escritorio, como en Main. */
    @BeforeTest fun contextoDeEscritorio() {
        if (runCatching { Escritorio.contexto }.isSuccess) return
        val carpeta = Files.createTempDirectory("Avoqado POS teclado")
        Bitacora.iniciar(carpeta)
        val actividad = ActividadDeEscritorio(carpeta)
        Escritorio.instalar(actividad, Inyector.crear(actividad))
    }

    @Test fun `dos toques en Autorizar piden la autorizacion una sola vez`() = runComposeUiTest {
        var peticiones = 0
        val enCamino = CompletableDeferred<OverrideResult>()
        setContent {
            CompositionLocalProvider(
                LocalContext provides context, LocalConfiguration provides configuracionDeAndroid(1280.dp, 800.dp, 1f),
                LocalView provides View(context), LocalEsEscritorio provides true,
            ) {
                AvoqadoTheme(darkTheme = false) {
                    ManagerOverrideSheet(
                        prompt = ManagerOverrideCoordinator.Prompt(id = 1L, permission = "cash-drawer:pay-out", actionLabel = "Retiro de $500.00 · Bajada"),
                        onSubmit = { peticiones++; enCamino.await() },
                        onDismiss = {},
                    )
                }
            }
        }
        listOf("1", "2", "3", "4").forEach { onNodeWithText(it).performClick() }

        // Los dos toques en el MISMO cuadro, antes de que el botón se vuelva a pintar deshabilitado (un dedo rápido en la tableta).
        val tocar = onNodeWithText("Autorizar").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        runOnUiThread { tocar(); tocar() }
        waitForIdle()

        assertEquals(1, peticiones, "dos toques pidieron dos autorizaciones")
        enCamino.complete(OverrideResult.WrongPin)
        waitForIdle()
    }
}
