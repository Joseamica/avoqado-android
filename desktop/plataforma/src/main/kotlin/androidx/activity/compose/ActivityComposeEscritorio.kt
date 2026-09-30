package androidx.activity.compose

import androidx.activity.result.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/** No hay botón de «atrás» del sistema en escritorio. */
@Composable fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {}

/** Contesta en el acto con lo que el contrato diría en escritorio (permisos: concedidos; elegir contacto/archivo: cancelado). */
@Composable
fun <I, O> rememberLauncherForActivityResult(
    contract: ActivityResultContract<I, O>,
    onResult: (O) -> Unit,
): ManagedActivityResultLauncher<I, O> {
    val alResultado = rememberUpdatedState(onResult)
    return remember(contract) { ManagedActivityResultLauncher(contract) { alResultado.value(it) } }
}

class ManagedActivityResultLauncher<I, O>(
    private val contract: ActivityResultContract<I, O>,
    private val onResult: (O) -> Unit,
) {
    fun launch(input: I) = onResult(contract.resultadoEnEscritorio(input))
}
