package com.avoqado.pos.customerdisplay

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * QR para la pantalla del cliente (recibo digital).
 * Port del `rememberQrBitmapPainter` de avoqado-tpv — misma librería (zxing) y
 * mismo comportamiento, para que un QR se vea igual salga de donde salga.
 *
 * Se genera fuera del hilo principal y no dibuja NADA hasta tenerlo: un QR a
 * medias es peor que ningún QR (el cliente lo escanea y no lleva a ningún lado).
 */
@Composable
fun QrCode(
    content: String,
    size: Dp = 200.dp,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val sizePx = with(density) { size.roundToPx() }

    var bitmap by remember(content, sizePx) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(content, sizePx) {
        bitmap = withContext(Dispatchers.Default) { encodeQr(content, sizePx) }
    }

    bitmap?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = "Código QR",
            contentScale = ContentScale.Fit,
            modifier = modifier.then(Modifier),
        )
    }
}

private fun encodeQr(content: String, sizePx: Int): Bitmap? {
    val matrix = try {
        QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            sizePx,
            sizePx,
            mapOf(EncodeHintType.MARGIN to 1),
        )
    } catch (e: WriterException) {
        return null
    }

    val bmp = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    val pixels = IntArray(matrix.width * matrix.height)
    for (y in 0 until matrix.height) {
        val offset = y * matrix.width
        for (x in 0 until matrix.width) {
            pixels[offset + x] = if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
    }
    bmp.setPixels(pixels, 0, matrix.width, 0, 0, matrix.width, matrix.height)
    return bmp
}
