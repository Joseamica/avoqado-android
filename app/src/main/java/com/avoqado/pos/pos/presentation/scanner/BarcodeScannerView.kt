package com.avoqado.pos.pos.presentation.scanner

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

@OptIn(ExperimentalGetImage::class)
@Composable
fun BarcodeScannerView(
    onBarcodeScanned: (String) -> Unit,
    onDismiss: () -> Unit,
    cameraHint: String = "Apunta la cámara al código de barras",
    manualTitle: String = "Escanea con pistola o escribe el código",
    manualLabel: String = "Código de vale o producto",
    actionText: String = "Consultar código",
    /**
     * El aparato trae lector integrado de Sunmi ([com.avoqado.pos.pos.data.LectorIntegradoSunmi]).
     * Ese lector no lee con una cámara prendida, así que se abre con la cámara APAGADA y el
     * cajero la prende si quiere. Sólo para quien escucha los códigos del lector (Cobrar): esta
     * pantalla no los recibe.
     */
    lectorIntegrado: Boolean = false,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var camera by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }
    var torchOn by remember { mutableStateOf(false) }
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var usarCamara by remember { mutableStateOf(!lectorIntegrado) }
    var hasScanned by remember { mutableStateOf(false) }
    var manualCode by remember { mutableStateOf("") }

    fun submitCode(rawCode: String) {
        val code = rawCode.trim()
        if (code.isNotEmpty() && !hasScanned) {
            hasScanned = true
            Log.d("📷", "Barcode submitted from keyboard/manual entry")
            onBarcodeScanned(code)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCameraPermission = granted
    }

    // El permiso se pide al prender la cámara, no al abrir: con lector integrado puede no usarse.
    LaunchedEffect(usarCamara) {
        if (usarCamara && !hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Sin foco automático en el campo del código: abría el teclado en pantalla al entrar
    // (founder, 20-oct). La pistola USB/Bluetooth no lo necesita: entra por `LectorHidBus`.

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (!usarCamara) {
            LectorListo(onUsarCamara = { usarCamara = true }, onDismiss = onDismiss)
        } else if (hasCameraPermission) {
            // Camera preview with barcode analysis
            val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
            val barcodeScanner = remember { BarcodeScanning.getClient() }
            val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
            val preview = remember { Preview.Builder().build() }
            val imageAnalysis = remember {
                ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
            }

            // Se cerró la pantalla (o se pasó a «Usar lector»): lo que aún no se ataba ya no se ata.
            val cerrada = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

            DisposableEffect(Unit) {
                onDispose {
                    // 🔴 La cámara va atada al ciclo de vida de la Activity, no a esta pantalla: sin
                    // soltarla aquí seguía prendida al cerrar (OrderPAD, 20-oct: 7 min prendida con la
                    // pantalla cerrada), y el lector integrado de Sunmi, que se duerme con cualquier
                    // cámara prendida, dejaba de leer en Cobrar. El orden entre este listener y el que
                    // ata NO está garantizado (Codex r1 #1): por eso el que ata revisa `cerrada`.
                    cerrada.set(true)
                    cameraProviderFuture.addListener(
                        { runCatching { cameraProviderFuture.get().unbind(preview, imageAnalysis) } },
                        ContextCompat.getMainExecutor(context),
                    )
                    camera = null
                    cameraExecutor.shutdown()
                    barcodeScanner.close()
                }
            }

            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx)

                    cameraProviderFuture.addListener({
                        if (cerrada.get()) return@addListener
                        val cameraProvider = cameraProviderFuture.get()

                        preview.surfaceProvider = previewView.surfaceProvider

                        imageAnalysis
                            .also { analysis ->
                                analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                                    val mediaImage = imageProxy.image
                                    if (mediaImage != null && !hasScanned) {
                                        val inputImage = InputImage.fromMediaImage(
                                            mediaImage,
                                            imageProxy.imageInfo.rotationDegrees,
                                        )
                                        barcodeScanner.process(inputImage)
                                            .addOnSuccessListener { barcodes ->
                                                barcodes.firstOrNull()?.rawValue?.let { value ->
                                                    if (!hasScanned) {
                                                        hasScanned = true
                                                        Log.d("📷", "Barcode scanned: $value")
                                                        onBarcodeScanned(value)
                                                    }
                                                }
                                            }
                                            .addOnCompleteListener {
                                                imageProxy.close()
                                            }
                                    } else {
                                        imageProxy.close()
                                    }
                                }
                            }

                        try {
                            cameraProvider.unbindAll()
                            camera = cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                imageAnalysis,
                            )
                        } catch (e: Exception) {
                            Log.e("📷", "Camera bind failed: ${e.message}")
                        }
                    }, ContextCompat.getMainExecutor(ctx))

                    previewView
                },
                modifier = Modifier.fillMaxSize(),
            )

            // Overlay: scanning indicator + close button
            Box(
                modifier = Modifier.fillMaxSize(),
            ) {
                // Torch toggle (top-left) — low-light barcode scanning was
                // impossible before (no flashlight control existed).
                if (camera?.cameraInfo?.hasFlashUnit() == true) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(AvoqadoTheme.spacing.xl)
                            .size(44.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color.Black.copy(alpha = 0.5f))
                            .clickable {
                                torchOn = !torchOn
                                camera?.cameraControl?.enableTorch(torchOn)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (torchOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                            contentDescription = "Linterna",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }

                // Close button (top-right)
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(AvoqadoTheme.spacing.xl)
                        .size(44.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.5f))
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Cerrar escaner",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }

                // Scanning hint (bottom-center)
                Text(
                    text = cameraHint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 188.dp)
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.md),
                )

                // Con la cámara prendida el lector integrado no lee: se dice y se ofrece volver.
                if (lectorIntegrado) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = AvoqadoTheme.spacing.xxxl),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
                    ) {
                        Text(
                            text = "Con la cámara prendida, el botón naranja no lee",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White,
                        )
                        PrimaryButton(text = "Usar lector", onClick = { usarCamara = false })
                    }
                }
            }
        } else {
            // Permission denied state
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "Se necesita acceso a la camara para escanear códigos de barras",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                )
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))
                PrimaryButton(
                    text = "Conceder permiso",
                    onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                )
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))
                PrimaryButton(
                    text = "Cancelar",
                    onClick = onDismiss,
                )
            }
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

/** Cámara apagada: el lector integrado lee con el botón naranja y el código llega por el aviso de Sunmi. */
@Composable
private fun LectorListo(onUsarCamara: () -> Unit, onDismiss: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(AvoqadoTheme.spacing.xl)
                .size(44.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.15f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Cerrar escaner",
                tint = Color.White,
                modifier = Modifier.size(24.dp),
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(top = 160.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.lg),
        ) {
            Icon(
                Icons.Outlined.QrCodeScanner,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(64.dp),
            )
            Text(
                text = "Apunta a la etiqueta y aprieta el botón naranja",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
            PrimaryButton(text = "Usar cámara", onClick = onUsarCamara)
        }
    }
}
