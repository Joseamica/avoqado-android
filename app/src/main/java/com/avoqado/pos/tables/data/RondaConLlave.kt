package com.avoqado.pos.tables.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * La ronda de mesa con llave (spec 2026-09-27 §5; patrón probado de avoqado-tpv `TablesRepository.addItems`, más el
 * estado retenido que allá no hay). Espejo de `RondaConLlave.swift` de avoqado-ios.
 *
 * Orden que no se negocia: 1) la red de seguridad se escribe RETENIDA antes de tocar la red; 2) la ronda en línea
 * con las MISMAS llaves; 3) éxito o rechazo ⇒ se descarta; red caída o cancelación ⇒ se suelta y el outbox la repite;
 * 4) si el proceso muere en medio, el primer arranque la suelta y el servidor deduplica por llave.
 */
object RondaConLlave {

    /** Espejo EXACTO de la llave que inyecta el reducer del servidor (`sync:<intentId>:<idx>`). */
    fun llave(roundKey: String, idx: Int): String = "sync:$roundKey:$idx"

    fun conLlaves(items: List<AddOrderItemRequest>, roundKey: String): List<AddOrderItemRequest> =
        items.mapIndexed { idx, item -> item.copy(externalId = llave(roundKey, idx)) }

    sealed interface Desenlace<out T> {
        data class Enviada<T>(val valor: T) : Desenlace<T>

        /** Sin red: la red de seguridad quedó suelta en la cola y se repite sola. */
        data object Encolada : Desenlace<Nothing>

        /** El servidor dijo que no: la red de seguridad se descartó y el error va tal cual al mesero. */
        data class Rechazada(val error: Throwable) : Desenlace<Nothing>

        /** No se pudo escribir la red de seguridad: no se intentó nada en línea. */
        data class NoSeGuardo(val error: Throwable) : Desenlace<Nothing>
    }

    suspend fun <T> enviar(
        guardarRetenido: suspend () -> Unit,
        enLinea: suspend () -> Result<T>,
        soltar: suspend () -> Unit,
        descartar: suspend () -> Unit,
        esErrorDeRed: (Throwable) -> Boolean,
    ): Desenlace<T> {
        try {
            guardarRetenido()
        } catch (e: CancellationException) {
            // Cerraron la mesa mientras se escribía: la fila pudo quedar escrita, y retenida sería barrera para todo lo
            // demás (incluido un cobro en efectivo) hasta reabrir la app. Se suelta (no hace nada si no se escribió).
            // Si el propio `soltar()` revienta (Room), tampoco debe tumbar la app — se queda HELD hasta el
            // siguiente arranque, que la suelta igual (Ronda 2, revisión final de fase 3.3).
            withContext(NonCancellable) { runCatching { soltar() } }
            throw e
        } catch (e: Exception) {
            // Escritura a medias (insertó y falló después): se descarta, porque nada salió en línea y el mesero va a
            // reintentar con otra llave.
            withContext(NonCancellable) { runCatching { descartar() } }
            return Desenlace.NoSeGuardo(e)
        }
        val resultado = try {
            enLinea()
        } catch (e: CancellationException) {
            // Cerraron la mesa a medio envío: la ronda no puede quedarse retenida (sería barrera para todo lo demás).
            withContext(NonCancellable) { runCatching { soltar() } }
            throw e
        } catch (e: Exception) {
            // `enLinea` no debería lanzar (el repositorio usa `runCatching`), pero si algún día lo hace, una
            // excepción sin capturar aquí dejaría la ronda HELD para siempre — barrera hasta reiniciar la app, con
            // el PAY_CASH detrás. Se trata como "no se sabe": entra al mismo `fold` de abajo, donde `esErrorDeRed`
            // decide soltar/descartar igual que con cualquier otro fallo (Task 7 review, 2026-09-28).
            Result.failure(e)
        }
        return resultado.fold(
            onSuccess = { valor ->
                // La ronda ya está en el servidor: si el DELETE local revienta (disco lleno, base corrupta — raro),
                // NO debe tumbar la app. La fila HELD sobrante se suelta sola en el siguiente arranque y el servidor
                // deduplica por llave — sin daño de datos (M2, revisión final de fase 3.3).
                withContext(NonCancellable) { runCatching { descartar() } }
                Desenlace.Enviada(valor)
            },
            onFailure = { e ->
                // `runCatching` del repositorio también envuelve la cancelación: cuenta como «no se sabe», se suelta.
                if (e is CancellationException || esErrorDeRed(e)) {
                    withContext(NonCancellable) { runCatching { soltar() } }
                    Desenlace.Encolada
                } else {
                    withContext(NonCancellable) { runCatching { descartar() } }
                    Desenlace.Rechazada(e)
                }
            },
        )
    }
}
