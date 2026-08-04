package com.avoqado.pos.core.data.network

import com.avoqado.pos.BuildConfig

object ApiConstants {
    val BASE_URL: String = BuildConfig.BASE_URL

    /** Base del dashboard (dominio distinto al del API). Ahí vive la página de recibo del cliente. */
    val DASHBOARD_URL: String = BuildConfig.DASHBOARD_URL
}

/**
 * Resuelve la URL del recibo digital que va en el QR del ticket y de la pantalla del cliente.
 *
 * 🔴 El destino correcto es SIEMPRE el dashboard (`/receipts/public/<key>`), porque esa página
 * tiene calificación **y autofactura (CFDI)**. La página vieja del backend
 * (`/api/v1/public/receipt/<key>`) NO tiene facturación: un cliente que caía ahí no se podía
 * facturar solo. Hasta agosto 2026 esto se armaba concatenando la base del API — y de una base
 * de API sólo sale una URL de API, así que TODOS los tickets de Android salieron con la vieja.
 *
 * Orden de resolución:
 *  1. [serverUrl] — la que el backend ya manda armada en `digitalReceipt.receiptUrl`. Es la
 *     preferida: respeta el ambiente (prod / develop) sin que el cliente adivine nada.
 *  2. [accessKey] + [ApiConstants.DASHBOARD_URL] — respaldo por si la respuesta no la trae
 *     (p. ej. una TPV vieja que sólo reporta la llave).
 *  3. `null` — sin llave no hay QR. Mejor nada que un código que no lleva a ningún lado.
 */
fun resolveReceiptUrl(serverUrl: String?, accessKey: String?): String? {
    serverUrl?.takeIf { it.isNotBlank() }?.let { return it }
    val key = accessKey?.takeIf { it.isNotBlank() } ?: return null
    return ApiConstants.DASHBOARD_URL.trimEnd('/') + "/receipts/public/" + key
}
