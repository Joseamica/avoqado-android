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
import com.avoqado.pos.auth.data.SwitchUserResult
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.timeclock.presentation.PinPadView
import kotlinx.coroutines.launch

/**
 * «Cambiar usuario»: la persona que toma el aparato teclea SU PIN y la sesión pasa a ser suya —
 * su rol, sus permisos, lo que ve.
 *
 * Lo pidió el founder así (2026-08-29): *«en lugar de que tenga que cerrar sesión, poner su mail y
 * contraseña nuevamente… que salga el pinpad»*, y **«es como un logout login pero con pin»**.
 *
 * 🔴 NO confundir con el selector «Vendiendo: X» de la pantalla de cobro: ése atribuye la venta y
 * sigue siendo libre y sin PIN, porque cambiar de vendedor rápido no puede tener fricción. Esto
 * otro es tomar posesión del aparato.
 *
 * Espejo de `SwitchUserSheet.swift`. Mismos textos en español, y misma estructura que
 * `ManagerOverrideSheet`: el error va INLINE —alguien tecleó mal y va a volver a teclear, eso no
 * es una pantalla de error— y el PIN se borra en cada desenlace.
 */
@Composable
fun SwitchUserSheet(
    onSubmit: suspend (String) -> SwitchUserResult,
    onSuccess: (SwitchUserResult.Success) -> Unit,
    onDismiss: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AvoqadoDialog(
        title = "Cambiar usuario",
        description = "Teclea tu PIN para tomar el aparato con tus permisos.",
        onDismiss = onDismiss,
        // Tocar fuera NO cancela: se teclea sobre una tablet compartida y en el mostrador es fácil
        // rozar la pantalla con la mano que sostiene el aparato.
        dismissOnClickOutside = false,
        actionButton = {
            PrimaryButton(
                text = "Cambiar",
                onClick = {
                    scope.launch {
                        isLoading = true
                        error = null
                        val result = onSubmit(pin)
                        isLoading = false
                        // El PIN se borra pase lo que pase: si el diálogo se reusa, el siguiente
                        // no puede encontrarse el código de la persona anterior ya escrito.
                        pin = ""
                        when (result) {
                            is SwitchUserResult.Success -> onSuccess(result)
                            is SwitchUserResult.Error -> error = result.message
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
