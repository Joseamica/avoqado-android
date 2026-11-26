package com.avoqado.pos.loyalty.data

import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiService
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import retrofit2.HttpException
import java.io.IOException
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

/**
 * Un premio ganado y sin cobrar. `rewardType`/`rewardValue` (aditivos: un servidor viejo no
 * los manda) dejan que el carrito muestre el descuento ANTES de cobrar — ver [PremioPorAplicar].
 */
@Serializable
data class ScannedReward(
    val id: String,
    val rewardLabel: String,
    /** `FIXED_AMOUNT` · `PERCENTAGE` · `FREE_PRODUCT`. */
    val rewardType: String? = null,
    /** Pesos para `FIXED_AMOUNT`, porcentaje para `PERCENTAGE`, null para `FREE_PRODUCT`. */
    val rewardValue: Double? = null,
)

/**
 * D50 (hz:b37dd7ea:D50): si una venta de AHORA le suma sello, según el servidor (la misma regla que usa al sellar: plan,
 * interruptor de sellos y tope del día). `reason`: `DAILY_LIMIT_REACHED` · `STAMPS_DISABLED`. Es un pronóstico, no una reserva.
 */
@Serializable
data class StampThisSale(
    val willStamp: Boolean = true,
    val reason: String? = null,
)

@Serializable
data class WalletScanResponse(
    /** 🔴 Sin valor por omisión (Codex D53 r1 #5): una respuesta sin el veredicto es «No se pudo leer», no «no encontrada». */
    val found: Boolean,
    val customer: ScannedCustomer? = null,
    val stampsEarned: Int = 0,
    val stampsRequired: Int = 0,
    val rewardLabel: String? = null,
    val rewardsToClaim: List<ScannedReward> = emptyList(),
    /** D50, aditivo: un servidor viejo no lo manda y el aviso dice lo de siempre. */
    val stampThisSale: StampThisSale? = null,
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

/**
 * D56 (hz:b37dd7ea:D56): la tarjeta digital, sola o pegada al FINAL de otro texto. En WINDOWS la pistola teclea dentro del campo
 * con foco (el buscador con F3, el campo manual de «Escanear»), y si ya había algo escrito los 48 caracteres se le pegan: salía
 * «Crear producto» con el QR como nombre. El lector de teclas no lo separa a propósito (retener teclas en un campo le comía
 * dígitos a la persona: docs/audit/2026-11-17-cantidad-tecleada/). @return la tarjeta (los últimos 48), o null.
 */
fun tarjetaDeClienteEn(texto: String): String? = texto.trim().takeLast(48).takeIf(::pareceTarjetaDeCliente)

/**
 * D53 (hz:b37dd7ea:D53): por qué no se leyó la tarjeta digital. Decide el aviso de la caja; 🔴 ninguno ofrece crear producto
 * (antes caía en «Producto no encontrado · Crear nuevo» y se podía dar de alta un producto con el código de una tarjeta).
 */
enum class TarjetaSinLeer(val titulo: String, val aviso: String) {
    /** Nadie contestó (o contestó un 502-504: el servidor no está). La tarjeta sólo se lee con el SERVIDOR. */
    SIN_RED("Sin conexión", "La tarjeta del cliente se lee cuando vuelva la red. Mientras, puedes elegir al cliente a mano desde el carrito."),

    /** `found:false`: de otro negocio, revocada o basura. El SERVIDOR no distingue entre ellas a propósito. */
    NO_ENCONTRADA("Tarjeta no encontrada", "Esa tarjeta no es de un cliente de este negocio. Puedes elegir al cliente a mano desde el carrito."),

    /** El SERVIDOR contestó con un rechazo (permiso, sesión, 500, la renovación que tropezó) o algo que no se pudo leer. */
    FALLO("No se pudo leer la tarjeta", "Vuelve a escanearla o elige al cliente a mano desde el carrito."),
}

sealed interface LecturaDeTarjeta {
    data class Leida(val tarjeta: WalletScanResponse) : LecturaDeTarjeta
    data class SinLeer(val motivo: TarjetaSinLeer) : LecturaDeTarjeta
}

@Singleton
class WalletScanRepository @Inject constructor(
    private val apiService: ApiService,
    private val secureStorage: SecureStorage,
) {
    /**
     * La caja no se detiene por una tarjeta sin leer — el cajero sigue cobrando y, si acaso, el cliente pierde su sello, no
     * su compra. Pero el aviso dice por qué (D53): «sin conexión» lo decide la falla de ESTA petición, no la bandera global.
     */
    suspend fun escanear(qrToken: String): LecturaDeTarjeta {
        // `venueId` es una propiedad, no un getter (ver SecureStorage.kt:64).
        val venueId = secureStorage.venueId
        if (venueId.isNullOrBlank()) return LecturaDeTarjeta.SinLeer(TarjetaSinLeer.FALLO)

        val respuesta = try {
            apiService.scanWalletPass(venueId, WalletScanRequest(qrToken))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("WalletScan", "No se pudo resolver la tarjeta escaneada: ${e.message}")
            // La renovación que el SERVIDOR sí contestó (5xx/429: `servidor:<código>`) no es falta de red (Codex D53 r1 #5).
            val sinRed = (e is IOException && !(e is com.avoqado.pos.core.data.network.SesionSinRenovar && e.motivo.startsWith("servidor"))) ||
                (e is HttpException && e.code() in 502..504)
            return LecturaDeTarjeta.SinLeer(if (sinRed) TarjetaSinLeer.SIN_RED else TarjetaSinLeer.FALLO)
        }
        if (!respuesta.found) return LecturaDeTarjeta.SinLeer(TarjetaSinLeer.NO_ENCONTRADA)
        return LecturaDeTarjeta.Leida(respuesta)
    }
}
