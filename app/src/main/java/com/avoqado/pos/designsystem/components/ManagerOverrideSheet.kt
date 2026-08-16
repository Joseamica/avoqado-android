package com.avoqado.pos.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.avoqado.pos.core.data.network.ManagerOverrideCoordinator
import com.avoqado.pos.core.data.network.OverrideResult
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.timeclock.presentation.PinPadView
import kotlinx.coroutines.launch

/**
 * "Se necesita autorización": el teclado donde un encargado teclea SU código
 * para dejar pasar UNA acción.
 *
 * 🔴 Espejo EXACTO de `ManagerOverrideSheet.swift`. Mismos textos en español.
 *
 * Cerrar = cancelar: la acción falla como fallaba antes. Nunca se pinta como
 * éxito ni se encola.
 *
 * El error del código va INLINE, aquí dentro: un flujo que va bien —alguien
 * tecleó mal y va a volver a teclear— no puede terminar en una pantalla de
 * error.
 */
@Composable
fun ManagerOverrideSheet(
    prompt: ManagerOverrideCoordinator.Prompt,
    onSubmit: suspend (String) -> OverrideResult,
    onDismiss: () -> Unit,
) {
    // 🔴 Keyed por el teclado, no por el slot de composición.
    //
    // Sin la key, el estado sobrevivía al cambio de prompt: dos acciones en fila
    // y el `StateFlow` conflatando el null intermedio hacían que el teclado de
    // la SEGUNDA apareciera con el PIN que el encargado había tecleado en la
    // primera, con "Autorizar" ya habilitado. Limpiarlo sólo en `Granted` no
    // bastaba —el teclado también se cierra al vencer y al cancelarse, y por
    // esos dos caminos el PIN seguía ahí—, así que la limpieza vive en la key.
    var pin by remember(prompt.id) { mutableStateOf("") }
    var error by remember(prompt.id) { mutableStateOf<String?>(null) }
    var isLoading by remember(prompt.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AvoqadoDialog(
        title = "Se necesita autorización",
        description = "Para ${prompt.actionLabel}. Pide a un encargado su código.",
        onDismiss = onDismiss,
        // Tocar fuera NO cancela: hay una acción esperando del otro lado y el
        // encargado suele estar tecleando sobre una tablet compartida.
        dismissOnClickOutside = false,
        actionButton = {
            PrimaryButton(
                text = "Autorizar",
                onClick = {
                    scope.launch {
                        isLoading = true
                        error = null
                        val result = onSubmit(pin)
                        isLoading = false
                        when (result) {
                            // El coordinator cierra el diálogo, pero el código
                            // se borra AQUÍ igual: si otra acción venía en la
                            // fila, su teclado reusa este mismo slot de
                            // composición y el `remember` conservaba el PIN del
                            // encargado ya tecleado, con "Autorizar" habilitado
                            // — el mesero podía aprobar solo una segunda acción
                            // que el encargado nunca vio.
                            is OverrideResult.Granted -> pin = ""
                            OverrideResult.WrongPin -> {
                                error = "Código incorrecto"
                                pin = ""
                            }
                            OverrideResult.Insufficient -> {
                                error = "Ese código tampoco tiene este permiso"
                                pin = ""
                            }
                            OverrideResult.TooManyAttempts -> {
                                error = "Demasiados intentos. Espera 15 minutos."
                                pin = ""
                            }
                            is OverrideResult.Failed -> {
                                error = result.message
                                pin = ""
                            }
                        }
                    }
                },
                enabled = pin.length in 4..10 && !isLoading,
                isLoading = isLoading,
                fullWidth = true,
            )
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
        ) {
            PinPadView(
                pin = pin,
                onPinChange = {
                    pin = it
                    error = null
                },
                maxLength = 10,
                minLength = 4,
                compact = true,
            )
            error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
