package com.avoqado.pos.core.data.network

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import com.avoqado.pos.BuildConfig
import com.avoqado.pos.core.data.sync.SyncOutbox
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Manda los headers `X-Device-*` en cada request para que el server registre este
 * aparato como un dispositivo del venue (registro pasivo, estilo Square Device
 * Management: "every device signed into the Point of Sale app").
 *
 * El server los lee en `registerDevice.middleware.ts` y hace upsert de la `Terminal`.
 * Es totalmente opcional del lado del server: si estos headers no van, no pasa nada —
 * por eso el backend se puede desplegar antes que esta app.
 *
 * ── Dos decisiones que importan ───────────────────────────────────────────────────
 *
 * 1. EL deviceId SE REUSA, NO SE INVENTA. Sale de `SyncOutbox.deviceId`, el mismo que
 *    ya identifica a este POS en el outbox offline y en la elección de árbitro del hub
 *    LAN. Ver `.claude/rules/offline-first-y-hub-lan.md` §2.4: si el aparato presenta
 *    dos identidades distintas se ve como dos peers y la elección deja de ser estable.
 *
 * 2. `Provider<SyncOutbox>` EN LUGAR DE INYECCIÓN DIRECTA. `SyncOutbox` depende de
 *    `ApiService` → Retrofit → OkHttpClient → este interceptor. Inyectarlo directo
 *    sería una dependencia circular y Hilt no compilaría. El Provider difiere la
 *    construcción hasta el primer request, cuando el grafo ya está armado.
 */
/**
 * Decide la clase de aparato. Función PURA a propósito: sin `Build`, sin `Context`, sin
 * Android. Así se prueba en un test unitario normal, sin Robolectric ni emulador.
 *
 * Los valores devueltos espejan el enum `DeviceFormFactor` del server por nombre EXACTO.
 */
internal object DeviceFormFactorResolver {

    /** Umbral estándar de Android para considerar un aparato tablet. */
    const val TABLET_MIN_WIDTH_DP = 600

    fun resolve(manufacturer: String, smallestScreenWidthDp: Int): String =
        when (manufacturer.trim().uppercase()) {
            // El hardware POS se resuelve por marca: un Sunmi de mostrador reporta
            // dimensiones de tablet y NO es una tablet.
            "PAX", "NEXGO" -> "HANDHELD_POS"
            "SUNMI" -> "COUNTERTOP_POS"
            else -> if (smallestScreenWidthDp >= TABLET_MIN_WIDTH_DP) "TABLET" else "PHONE"
        }
}

@Singleton
class DeviceHeadersInterceptor @Inject constructor(
    private val syncOutbox: Provider<SyncOutbox>,
    @ApplicationContext private val context: Context,
) : Interceptor {

    private val formFactor: String by lazy {
        DeviceFormFactorResolver.resolve(
            manufacturer = Build.MANUFACTURER,
            smallestScreenWidthDp = context.resources.configuration.smallestScreenWidthDp,
        )
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // Si algo falla resolviendo la identidad, el request sigue SIN los headers.
        // Esto cuelga del camino del cobro: jamás puede tumbar una venta.
        val deviceId = runCatching { syncOutbox.get().deviceId }.getOrNull()
            ?: return chain.proceed(request)

        val builder = request.newBuilder()
            .header("x-device-id", deviceId)
            .header("x-device-platform", "ANDROID")
            .header("x-device-manufacturer", Build.MANUFACTURER)
            // Identificador CRUDO ("SM-X710", "A910S"). El server lo traduce a nombre
            // comercial con su catálogo, así que un modelo nuevo se nombra bien sin
            // necesidad de publicar una versión de esta app.
            .header("x-device-model", Build.MODEL)
            .header("x-device-form-factor", formFactor)
            // ⚠️ Los nombres de header van en INGLÉS y sin acentos, letra por letra igual
            // que en `registerDevice.middleware.ts`. Un acento de más aqui no truena
            // nada: el server simplemente nunca encuentra el header y ese campo llega
            // vacio para siempre. Falla en silencio.
            .header("x-device-os-version", "Android ${Build.VERSION.RELEASE}")
            .header("x-app-version", BuildConfig.VERSION_NAME)

        // OJO: no mandamos `X-Device-Serial`. En Android ≥10 `Build.getSerial()` exige
        // el permiso privilegiado READ_PHONE_STATE, y pedirlo sólo para nombrar un
        // aparato no se justifica. El server trata el serial como opcional (igual que
        // Square marca su `manufacturers_id` como "where available"), así que su
        // camino de enganche a una terminal ya provisionada simplemente no se activa
        // desde aquí. Si algún día hace falta para Sunmi/PAX, sale de su SDK propio,
        // no de la API de Android.

        return chain.proceed(builder.build())
    }

    private companion object {
        /** Umbral estándar de Android para considerar un aparato tablet. */
        const val TABLET_MIN_WIDTH_DP = 600
    }
}
