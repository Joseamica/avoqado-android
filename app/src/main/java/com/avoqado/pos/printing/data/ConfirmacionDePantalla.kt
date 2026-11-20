package com.avoqado.pos.printing.data

import android.util.Log
import com.avoqado.pos.core.data.network.ApiService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ConfirmacionDePantalla"

/** Una comanda por confirmar: su folio y las unidades que la caja mandó a esa estación (el servidor exige todas). */
@Serializable
data class ComandaPorConfirmar(val folio: String, val unidades: Int)

@Serializable
data class FoliosEnPantallaRequest(val comandas: List<ComandaPorConfirmar>)

@Serializable
data class FoliosEnPantallaData(val enPantalla: List<String> = emptyList())

/** `POST kds/folios-en-pantalla` (`kds.mobile.service.ts` → `foliosEnPantalla`). */
@Serializable
data class FoliosEnPantallaResponse(val success: Boolean = true, val data: FoliosEnPantallaData = FoliosEnPantallaData())

/**
 * D6 de Flautas (20-nov): le pregunta al SERVIDOR si estas comandas (por su folio, `KitchenDeliveryPolicy.folio`) ya están
 * en una pantalla de cocina CON sus platillos (las unidades que mandó la caja: Codex r3, una fila vacía o con sólo una parte
 * no cuenta). La usa la caja cuando una estación «sólo pantalla» no le contestó por el WiFi del local y no
 * hay impresora para el papel de respaldo: el servidor arma la comanda de pantalla ANTES de contestar la ronda y el cobro,
 * así que si la tiene, la cocina la ve y avisar «No salió» sería falso.
 *
 * Nunca lanza ni espera de más: sin red, con un error, con un servidor viejo que no conoce la ruta (404) o pasados
 * [TOPE_MS], contesta «ninguna» y la caja avisa como siempre. Es una consulta: no hay nada que guardar antes de la red.
 */
@Singleton
class ConfirmacionDePantalla @Inject constructor(
    private val apiService: ApiService,
) {
    /**
     * Los folios de [unidadesPorFolio] que el servidor confirma en una pantalla con al menos esas unidades; vacío ante
     * cualquier duda. Un folio sin unidades (≤ 0) nunca se confirma.
     */
    suspend fun enPantalla(venueId: String, unidadesPorFolio: Map<String, Int>): Set<String> {
        if (unidadesPorFolio.isEmpty() || unidadesPorFolio.size > MAX_FOLIOS || unidadesPorFolio.values.any { it <= 0 }) return emptySet()
        val comandas = unidadesPorFolio.map { (folio, unidades) -> ComandaPorConfirmar(folio, unidades) }
        return try {
            withTimeoutOrNull(TOPE_MS) {
                apiService.foliosEnPantalla(venueId, FoliosEnPantallaRequest(comandas)).data.enPantalla.toSet()
            } ?: emptySet<String>().also { Log.w(TAG, "⏱️ El servidor no contestó en $TOPE_MS ms: se avisa como siempre") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ No se pudo confirmar ${comandas.size} comanda(s) en pantalla: ${e.message}")
            emptySet()
        }
    }

    companion object {
        /** La pregunta corre ya con el aviso decidido; más de esto y el cajero ve tarde el «No salió» real. */
        const val TOPE_MS = 3_000L
        /** El mismo tope que la ruta (`FOLIOS_EN_PANTALLA_MAX`): una venta o ronda toca 1-5 estaciones. */
        const val MAX_FOLIOS = 20
    }
}
