package androidx.compose.ui.platform

import android.content.Context
import android.view.View
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import com.avoqado.escritorio.Escritorio

/** En Android es la Activity; aquí, el contexto único de escritorio. */
val LocalContext = staticCompositionLocalOf<Context> { Escritorio.contexto }

/** Lo llena ConfiguracionDeAndroid con el tamaño real de la ventana (14 pantallas deciden tablet/teléfono con esto). */
val LocalConfiguration = compositionLocalOf { Escritorio.contexto.resources.configuration }

val LocalView = staticCompositionLocalOf { View(Escritorio.contexto) }
