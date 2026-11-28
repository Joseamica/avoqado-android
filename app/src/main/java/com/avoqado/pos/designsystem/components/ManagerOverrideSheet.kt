package com.avoqado.pos.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    /**
     * C4 (22-oct): el título. El de siempre por omisión; la declaración «no se cobró» del mesero pasa
     * `CancelacionDeCobro.PIN_DE_GERENTE_TITULO`. El teclado, los textos de error y el cuerpo no cambian.
     */
    title: String = "Se necesita autorización",
    /** El texto bajo el título. Por omisión, «Para <acción>…»; el retiro pasa el suyo («Retiro de $X · concepto…»). */
    // D38: «PIN de gerente», como el interruptor del DASHBOARD y los avisos de la app (antes «código de un encargado»).
    description: String = prompt.contexto?.let { "$it. Pide a un gerente su PIN." }
        ?: "Para ${prompt.actionLabel}. Pide a un gerente su PIN.",
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
        title = title,
        description = description,
        onDismiss = onDismiss,
        // Tocar fuera NO cancela: hay una acción esperando del otro lado y el
        // encargado suele estar tecleando sobre una tablet compartida.
        dismissOnClickOutside = false,
        actionButton = {
            PrimaryButton(
                text = "Autorizar",
                onClick = {
                    // 🔴 Se marca ANTES del `launch`: dos toques en el mismo cuadro (antes de que el botón se pinte deshabilitado)
                    // pedían dos autorizaciones con el mismo PIN.
                    if (isLoading) return@PrimaryButton
                    isLoading = true
                    scope.launch {
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
                                error = "PIN incorrecto"
                                pin = ""
                            }
                            OverrideResult.Insufficient -> {
                                error = "Ese PIN tampoco tiene este permiso"
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
        // na7673f8 (como D68 #1 en «Cambiar usuario», PR #266): el aviso iba ABAJO del teclado y, en la N86, el diálogo (tope
        // del 85 % de la pantalla) lo cortaba: el PIN se borraba sin decir nada. Ahora va en el renglón de los puntos, que tras
        // el intento queda vacío, el contenido se desplaza si no cabe, y al fallar se sube para que el aviso se vea.
        val desplazamiento = rememberScrollState()
        LaunchedEffect(error) { if (error != null) desplazamiento.animateScrollTo(0) }
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(desplazamiento),
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
                aviso = error,
            )
        }
    }
}
