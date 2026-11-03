package com.avoqado.pos.escritorio

import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.avoqado.escritorio.ContextoDeEscritorio
import com.avoqado.pos.announcements.data.model.AnnouncementDetail
import com.avoqado.pos.announcements.data.model.ContentBlock
import com.avoqado.pos.announcements.presentation.AnnouncementSheet
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.pos.presentation.product.MiniaturaConIniciales
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * D16 (3-nov), en la pantalla: la miniatura de un artículo enseña sus iniciales sin foto, mientras carga y si la foto falla (sin
 * red falla la que no está guardada); sólo la foto lista las tapa. La imagen de un anuncio que no carga se esconde con todo y
 * su pie. La foto buena es un archivo local y la rota un archivo que no existe: nada sale a la red.
 */
@OptIn(ExperimentalTestApi::class)
class FotosQueNoCarganEnLaEscenaTest {
    private val carpeta = Files.createTempDirectory("fotos-que-no-cargan")
    private val contexto = ContextoDeEscritorio(carpeta)
    @AfterTest fun limpiar() { carpeta.toFile().deleteRecursively() }

    @Composable private fun superficie(content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalContext provides contexto, LocalConfiguration provides contexto.resources.configuration,
            LocalView provides View(contexto)) { AvoqadoTheme(darkTheme = false, content = content) }
    }

    /** Un PNG de verdad en disco, como `file:`: el sustituto de Coil lo carga igual que una foto bajada. */
    private fun fotoBuena(nombre: String): String {
        val lienzo = Surface.makeRasterN32Premul(8, 8)
        lienzo.canvas.clear(0xFF2E7D32.toInt())
        val archivo = carpeta.resolve(nombre).toFile()
        archivo.writeBytes(lienzo.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes)
        return archivo.toURI().toString()
    }

    private fun fotoRota(): String = carpeta.resolve("no-existe.png").toUri().toString()

    @Test fun `sin foto la miniatura muestra las iniciales`() = runComposeUiTest {
        setContent { superficie { MiniaturaConIniciales(url = null, nombre = "Agua mineral", modifier = Modifier.size(44.dp)) } }
        onNodeWithText("AM").assertExists()
    }

    @Test fun `la foto lista tapa las iniciales y la que falla las deja`() = runComposeUiTest {
        val buena = fotoBuena("agua.png")
        val rota = fotoRota()
        setContent {
            superficie {
                Column {
                    MiniaturaConIniciales(url = buena, nombre = "Agua mineral", modifier = Modifier.size(44.dp))
                    MiniaturaConIniciales(url = rota, nombre = "Latte", modifier = Modifier.size(104.dp))
                }
            }
        }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("AM").fetchSemanticsNodes().isEmpty() }
        onNodeWithText("LA").assertExists()
    }

    @Test fun `la imagen de un anuncio que no carga se esconde con su pie`() = runComposeUiTest {
        val buena = fotoBuena("anuncio.png")
        val rota = fotoRota()
        setContent {
            superficie {
                AnnouncementSheet(
                    anuncio = AnnouncementDetail(id = "anuncio-1", title = "Novedades", body = "Lo nuevo de este mes"),
                    bloques = listOf(
                        ContentBlock.Image(url = rota, alt = "foto rota", caption = "Pie de la rota"),
                        ContentBlock.Image(url = buena, alt = "foto buena", caption = "Pie de la buena"),
                    ),
                    onCerrar = {},
                    onAccion = {},
                )
            }
        }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Pie de la rota").fetchSemanticsNodes().isEmpty() }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription("foto buena").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Pie de la buena").assertExists()
    }
}
