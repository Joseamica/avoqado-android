package com.avoqado.pos.loyalty.data

import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiService
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resuelve el QR de la tarjeta digital de un cliente, escaneada en la caja.
 *
 * 🔴 El código del QR lo puede leer cualquiera que vea la pantalla del cliente, así
 * que el servidor es quien decide: filtra por negocio y sólo devuelve el NOMBRE del
 * cliente, su avance y sus premios sin cobrar. Aquí no se toma ninguna decisión de
 * seguridad — sólo se pregunta.
 */

@Serializable
data class WalletScanRequest(val qrToken: String)

@Serializable
data class ScannedCustomer(
    val id: String,
    /** 🔴 Puede venir vacío: un cliente creado en un cobro rápido no siempre tiene nombre. */
    val firstName: String? = null,
    val lastName: String? = null,
)

@Serializable
data class ScannedReward(val id: String, val rewardLabel: String)

@Serializable
data class WalletScanResponse(
    val found: Boolean = false,
    val customer: ScannedCustomer? = null,
    val stampsEarned: Int = 0,
    val stampsRequired: Int = 0,
    val rewardLabel: String? = null,
    val rewardsToClaim: List<ScannedReward> = emptyList(),
)

/**
 * ¿Este código escaneado PARECE la tarjeta de un cliente?
 *
 * 🔴 Existe para no hacer un viaje al servidor por cada código de barras que no está
 * en el catálogo. En una caja con fila, esa latencia se nota — y la inmensa mayoría de
 * los códigos desconocidos son productos mal dados de alta, no tarjetas.
 *
 * El token del pase son 24 bytes en hexadecimal: 48 caracteres de 0-9a-f. Un código de
 * barras de producto (EAN-13, UPC, PLU) nunca tiene esa forma, así que el filtro no
 * puede confundirse con mercancía.
 */
fun pareceTarjetaDeCliente(code: String): Boolean {
    val limpio = code.trim()
    return limpio.length == 48 && limpio.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
}

@Singleton
class WalletScanRepository @Inject constructor(
    private val apiService: ApiService,
    private val secureStorage: SecureStorage,
) {
    /**
     * Devuelve null cuando no se pudo resolver, por lo que sea: sin sesión, sin red,
     * código de otro negocio o inventado. La caja no se detiene por esto — el cajero
     * sigue cobrando y, si acaso, el cliente pierde su sello, no su compra.
     */
    suspend fun escanear(qrToken: String): WalletScanResponse? {
        // `venueId` es una propiedad, no un getter (ver SecureStorage.kt:64).
        val venueId = secureStorage.venueId
        if (venueId.isNullOrBlank()) return null

        return runCatching { apiService.scanWalletPass(venueId, WalletScanRequest(qrToken)) }
            .onFailure { Log.w("WalletScan", "No se pudo resolver la tarjeta escaneada: ${it.message}") }
            .getOrNull()
            ?.takeIf { it.found }
    }
}
