package androidx.compose.ui.res

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import com.avoqado.pos.escritorio.rutaDeRecurso
import org.xml.sax.InputSource

/** painterResource(Int) de Android: busca el archivo copiado de ../app/src/main/res (tarea 1, processResources). */
@Composable
fun painterResource(id: Int): Painter {
    val ruta = checkNotNull(rutaDeRecurso(id)) { "Recurso $id sin archivo en escritorio" }
    val flujo = { checkNotNull(Thread.currentThread().contextClassLoader.getResourceAsStream(ruta)) { "falta $ruta" } }
    return if (ruta.endsWith(".xml")) {
        val densidad = LocalDensity.current
        rememberVectorPainter(remember(ruta) { flujo().use { loadXmlImageVector(InputSource(it), densidad) } })
    } else {
        remember(ruta) { BitmapPainter(flujo().use { org.jetbrains.skia.Image.makeFromEncoded(it.readBytes()).toComposeImageBitmap() }) }
    }
}
