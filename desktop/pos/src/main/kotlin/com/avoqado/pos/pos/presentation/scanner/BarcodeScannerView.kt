package com.avoqado.pos.pos.presentation.scanner

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.theme.AvoqadoTheme

/**
 * Reemplazo de escritorio: la misma firma, sin cámara (CameraX + ML Kit no existen en la PC). Lo dice en pantalla y
 * deja el campo de código manual del original (copiado tal cual), que también recibe un lector USB porque esos
 * lectores teclean el código y mandan Enter. `cameraHint` se conserva por firma; aquí no hay cámara que apuntar.
 */
@Composable
fun BarcodeScannerView(
    onBarcodeScanned: (String) -> Unit,
    onDismiss: () -> Unit,
    @Suppress("UNUSED_PARAMETER") cameraHint: String = "Apunta la cámara al código de barras",
    manualTitle: String = "Escanea con pistola o escribe el código",
    manualLabel: String = "Código de vale o producto",
    actionText: String = "Consultar código",
) {
    var hasScanned by remember { mutableStateOf(false) }
    var manualCode by remember { mutableStateOf("") }
    val manualCodeFocusRequester = remember { FocusRequester() }
    val softwareKeyboardController = LocalSoftwareKeyboardController.current

    fun submitCode(rawCode: String) {
        val code = rawCode.trim()
        if (code.isNotEmpty() && !hasScanned) {
            hasScanned = true
            Log.d("📷", "Barcode submitted from keyboard/manual entry")
            onBarcodeScanned(code)
        }
    }

    LaunchedEffect(Unit) {
        Log.w("Escritorio", "No disponible en Windows todavía: cámara para escanear")
        manualCodeFocusRequester.requestFocus()
        softwareKeyboardController?.hide()
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // Donde Android pone la cámara: el aviso y la salida (mismo acomodo que el estado «sin permiso» del original).
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = AvoqadoTheme.spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "La cámara no está disponible en Windows todavía. Escribe el código o usa un lector USB.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))
            PrimaryButton(
                text = "Cancelar",
                onClick = onDismiss,
            )
        }

        // Keep manual entry at the top: the Samsung landscape keyboard occupies
        // the lower half of the scanner and previously hid both the value and CTA.
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .zIndex(10f)
                .padding(
                    start = AvoqadoTheme.spacing.xl,
                    top = 72.dp,
                    end = AvoqadoTheme.spacing.xl,
                    bottom = 0.dp,
                )
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.lg))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
                .padding(AvoqadoTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
        ) {
            Text(
                text = manualTitle,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            OutlinedTextField(
                value = manualCode,
                onValueChange = { manualCode = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(manualCodeFocusRequester)
                    .onPreviewKeyEvent { event ->
                        val isSubmitKey = event.key == Key.Enter || event.key == Key.Tab
                        if (isSubmitKey && event.type == KeyEventType.KeyUp) {
                            submitCode(manualCode)
                            true
                        } else {
                            false
                        }
                    },
                label = { Text(manualLabel) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submitCode(manualCode) }),
            )
            PrimaryButton(
                text = actionText,
                enabled = manualCode.isNotBlank() && !hasScanned,
                onClick = { submitCode(manualCode) },
            )
        }
    }
}
