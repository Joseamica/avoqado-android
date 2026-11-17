package com.avoqado.pos.timeclock.presentation

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * La selfie al marcar Entrada, cuando el negocio prendió «Foto de entrada» (Equipo → Asistencia en el panel).
 *
 * - Con cámara: la foto es obligatoria. Sin permiso, se pide (y si se negó para siempre, se manda a Ajustes); si no abre
 *   o no toma la foto, «Reintentar». No hay «saltar»: un PIN se presta, una foto deja huella. Si la cámara está
 *   descompuesta, el gerente apaga el interruptor en el panel.
 * - Sin cámara (aparato que no tiene ninguna): la entrada se registra igual y el panel la marca «Sin foto».
 * - La foto sale de aquí ya chica (720 px, JPEG): unos 100 KB, para que viaje con la checada y quepa en el servidor.
 *
 * Sin red no se toma: el checador entero necesita internet (el PIN lo valida el servidor) y lo dice la hoja.
 *
 * 🔴 WINDOWS compila un reemplazo con la misma firma (`desktop/pos/.../timeclock/presentation/SelfieDeEntrada.kt`): si
 * cambias la firma aquí, cámbiala allá (lo vigila `desktop/pos/excluidos.txt`).
 */
@Composable
fun SelfieDeEntrada(
    enviando: Boolean,
    onFoto: (ByteArray) -> Unit,
    onSinCamara: () -> Unit,
    onCancelar: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val tieneCamara = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
    var permiso by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var pedido by remember { mutableStateOf(false) }
    var falloLaCamara by remember { mutableStateOf(false) }
    var foto by remember { mutableStateOf<ByteArray?>(null) }
    var tomando by remember { mutableStateOf(false) }
    // «Tomar foto» sólo con la cámara ya atada: antes se podía tocar mientras se ataba y CameraX contestaba
    // ERROR_INVALID_CAMERA, que abría «sin foto» en un aparato que SÍ tiene cámara (Codex r2 #5).
    var camaraLista by remember { mutableStateOf(false) }
    var fallosSeguidos by remember { mutableStateOf(0) }
    var avisoDeCaptura by remember { mutableStateOf<String?>(null) }

    /** Un tropiezo al tomar la foto se reintenta; sólo tres seguidos cuentan como cámara que no funciona. */
    fun capturaFallida() {
        tomando = false
        fallosSeguidos += 1
        if (fallosSeguidos >= 3) falloLaCamara = true else avisoDeCaptura = "No se pudo tomar la foto. Intenta otra vez."
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { concedido ->
        permiso = concedido
        pedido = true
    }

    LaunchedEffect(tieneCamara) {
        if (tieneCamara && !permiso) launcher.launch(Manifest.permission.CAMERA)
    }

    // Quien fue a Ajustes a dar el permiso vuelve aquí: se revisa otra vez al regresar.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permiso = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
    ) {
        when {
            !tieneCamara -> {
                Aviso("Este aparato no tiene cámara. Tu entrada se registra y tu gerente la verá marcada «Sin foto».")
                PrimaryButton(text = "Marcar entrada sin foto", onClick = onSinCamara, isLoading = enviando, fullWidth = true)
            }

            // 🔴 Con cámara, un fallo NO abre «sin foto» (Codex r3 #1): sería la puerta para saltarla. Se reintenta; si la
            // cámara está descompuesta de verdad, el gerente apaga «Foto de entrada» en el panel.
            falloLaCamara -> {
                Aviso(
                    "No se pudo usar la cámara. Intenta otra vez; si sigue fallando, pide a tu gerente que apague " +
                        "«Foto de entrada» en el panel.",
                )
                PrimaryButton(
                    text = "Reintentar",
                    onClick = {
                        falloLaCamara = false
                        fallosSeguidos = 0
                        avisoDeCaptura = null
                        camaraLista = false
                    },
                    fullWidth = true,
                )
            }

            !permiso -> {
                Aviso("Para tomarla, permite que la app use la cámara.")
                PrimaryButton(
                    text = "Permitir cámara",
                    onClick = {
                        // Tras el primer «No permitir» Android todavía pregunta otra vez (rationale = true); tras el
                        // segundo ya no muestra la ventana: entonces se abre la página de la app en Ajustes.
                        val actividad = context.actividad()
                        val negadoParaSiempre = pedido && actividad != null &&
                            !ActivityCompat.shouldShowRequestPermissionRationale(actividad, Manifest.permission.CAMERA)
                        if (negadoParaSiempre) {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        } else {
                            launcher.launch(Manifest.permission.CAMERA)
                        }
                    },
                    fullWidth = true,
                )
            }

            foto != null -> {
                val bitmap = remember(foto) { foto?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
                bitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "Tu foto de entrada",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(260.dp).clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.xl)),
                    )
                }
                PrimaryButton(
                    text = "Usar esta foto y marcar entrada",
                    onClick = { foto?.let(onFoto) },
                    isLoading = enviando,
                    fullWidth = true,
                )
                TextButton(
                    onClick = {
                        foto = null
                        camaraLista = false
                    },
                    enabled = !enviando,
                ) { Text("Tomar otra") }
            }

            else -> {
                val captura = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
                VistaDeCamara(captura = captura, onLista = { camaraLista = true }, onFallo = { falloLaCamara = true })
                avisoDeCaptura?.let { Aviso(it) }
                PrimaryButton(
                    text = "Tomar foto",
                    onClick = {
                        tomando = true
                        avisoDeCaptura = null
                        captura.takePicture(
                            ContextCompat.getMainExecutor(context),
                            object : ImageCapture.OnImageCapturedCallback() {
                                override fun onCaptureSuccess(image: ImageProxy) {
                                    val jpeg = runCatching { comprimirSelfie(image) }.getOrNull()
                                    image.close()
                                    if (jpeg != null) {
                                        tomando = false
                                        fallosSeguidos = 0
                                        foto = jpeg
                                    } else {
                                        capturaFallida()
                                    }
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    Log.e("⏰", "Selfie de entrada: la cámara no tomó la foto (${exception.imageCaptureError})")
                                    capturaFallida()
                                }
                            },
                        )
                    },
                    enabled = camaraLista,
                    isLoading = tomando,
                    fullWidth = true,
                )
            }
        }
        TextButton(onClick = onCancelar, enabled = !enviando) { Text("Cancelar") }
    }
}

/** La Activity detrás del contexto de Compose (puede venir envuelto en ContextWrapper). */
private tailrec fun Context.actividad(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.actividad()
    else -> null
}

@Composable
private fun Aviso(texto: String) {
    Text(
        text = texto,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

/** La vista previa de la cámara frontal (la trasera si el aparato no tiene frontal), atada a esta pantalla. */
@Composable
private fun VistaDeCamara(captura: ImageCapture, onLista: () -> Unit, onFallo: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val proveedor = remember { ProcessCameraProvider.getInstance(context) }
    val preview = remember { Preview.Builder().build() }
    // Se cerró la pantalla antes de que la cámara terminara de atarse: ya no se ata (mismo cuidado que el escáner).
    val cerrada = remember { AtomicBoolean(false) }

    DisposableEffect(Unit) {
        onDispose {
            cerrada.set(true)
            proveedor.addListener(
                { runCatching { proveedor.get().unbind(preview, captura) } },
                ContextCompat.getMainExecutor(context),
            )
        }
    }

    Box(
        modifier = Modifier
            .size(260.dp)
            .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.xl))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        AndroidView(
            factory = { ctx ->
                val vista = PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
                proveedor.addListener({
                    if (cerrada.get()) return@addListener
                    try {
                        val camaras = proveedor.get()
                        val selector = if (camaras.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                            CameraSelector.DEFAULT_FRONT_CAMERA
                        } else {
                            CameraSelector.DEFAULT_BACK_CAMERA
                        }
                        preview.surfaceProvider = vista.surfaceProvider
                        camaras.unbind(preview, captura)
                        camaras.bindToLifecycle(lifecycleOwner, selector, preview, captura)
                        onLista()
                    } catch (e: Exception) {
                        Log.e("⏰", "Selfie de entrada: no se pudo abrir la cámara (${e.javaClass.simpleName})")
                        onFallo()
                    }
                }, ContextCompat.getMainExecutor(ctx))
                vista
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** Lado mayor de la selfie que se manda. 720 px basta para reconocer a alguien y deja la foto en ~100 KB. */
private const val LADO_MAXIMO = 720

/** Tope que se intenta respetar; el servidor rechaza más de 600 KB. */
private const val BYTES_MAXIMOS = 400 * 1024

/** ImageProxy → JPEG derecho (según la rotación del sensor), de [LADO_MAXIMO] px y calidad que baja hasta caber. */
private fun comprimirSelfie(image: ImageProxy): ByteArray {
    val original = image.toBitmap()
    val escala = minOf(1f, LADO_MAXIMO.toFloat() / maxOf(original.width, original.height))
    val matriz = Matrix().apply {
        postScale(escala, escala)
        postRotate(image.imageInfo.rotationDegrees.toFloat())
    }
    val derecha = Bitmap.createBitmap(original, 0, 0, original.width, original.height, matriz, true)
    var calidad = 80
    var bytes: ByteArray
    do {
        bytes = ByteArrayOutputStream().use { salida ->
            derecha.compress(Bitmap.CompressFormat.JPEG, calidad, salida)
            salida.toByteArray()
        }
        calidad -= 15
    } while (bytes.size > BYTES_MAXIMOS && calidad >= 35)
    if (derecha !== original) derecha.recycle()
    original.recycle()
    return bytes
}
