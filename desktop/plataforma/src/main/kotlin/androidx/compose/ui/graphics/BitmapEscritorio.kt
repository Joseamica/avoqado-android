package androidx.compose.ui.graphics

/** Sustituto de la conversión de Android: el Bitmap de escritorio es un BufferedImage por dentro. */
fun android.graphics.Bitmap.asImageBitmap(): ImageBitmap = imagen().toComposeImageBitmap()
