package com.avoqado.pos.core.di

import com.avoqado.pos.printing.routing.PrintJobDto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 🔴 EL CONVERTER COMPARTIDO SÍ MANDA `null` EXPLÍCITO, Y TIENE QUE SEGUIR HACIÉNDOLO.
 *
 * Este guard existe porque el 2026-09-12 estuve a punto de romperlo. Arreglando el reembolso
 * del POS —que caía con 400 porque el servidor validaba con `if (x !== undefined && ...)` y
 * en JSON no hay `undefined`— la tentación era poner `explicitNulls = false` aquí, en el
 * `Json` que Hilt le da al converter de Retrofit, y así cubrir de una vez los 36 `@Body` de
 * `ApiService`.
 *
 * Una auditoría adversarial (Codex gpt-6-astra, xhigh) demostró que eso ROMPE una señal
 * viva, con el servidor documentándola en su propio comentario (`print.mobile.service.ts:75`):
 *
 *     // Solo al avanzar: null explícito limpia un error viejo al recuperarse;
 *     // undefined lo deja intacto.
 *     error: advancing ? (job.error === undefined ? undefined : job.error) : undefined
 *
 * `ReporteDeComandas` manda `status = DONE` con `error = null` justo cuando una comanda que
 * había fallado por fin SALE: ese nulo ES el borrado. Omitir la llave dejaba el «Sin papel»
 * pegado a una comanda que ya se imprimió, y el dashboard seguiría reportándola como fallida.
 *
 * 🔑 La lección, que vale más que el arreglo: **«null ≡ ausente» es una propiedad de CADA
 * endpoint, no del cliente.** Por eso el arreglo del reembolso vive en su propio
 * serializador (`RefundRepository.jsonReembolsos`) y no aquí. Si alguien vuelve a cambiar
 * este `Json` de golpe, esta prueba cae y explica por qué.
 */
class NetworkJsonNullsTest {

    private val json: Json = NetworkModule.provideJson()

    @Test
    fun `el nulo de PrintJob error VIAJA — es como el servidor borra un error viejo`() {
        val cuerpo = json.encodeToString(
            PrintJobDto.serializer(),
            PrintJobDto(
                id = "job-1",
                eventId = "order-1:station-1",
                reason = "ORIGINAL",
                seq = 1,
                type = "KITCHEN_TICKET",
                status = "DONE",
                error = null,
            ),
        )

        assertTrue(
            "`error: null` dejó de viajar en el converter compartido. El servidor conservará " +
                "el error viejo y una comanda que SÍ salió seguirá marcada como fallida. Si " +
                "buscabas arreglar un guard de `undefined`, hazlo en el serializador de ESE " +
                "cuerpo, no aquí. Cuerpo: $cuerpo",
            cuerpo.contains("\"error\":null"),
        )
    }

    @Test
    fun `una comanda que falla manda su causa`() {
        val cuerpo = json.encodeToString(
            PrintJobDto.serializer(),
            PrintJobDto(
                id = "job-1",
                eventId = "order-1:station-1",
                reason = "ORIGINAL",
                seq = 1,
                type = "KITCHEN_TICKET",
                status = "FAILED",
                error = "Sin papel",
            ),
        )
        assertTrue(cuerpo.contains("\"error\":\"Sin papel\""))
    }

    /** `encodeDefaults` se conserva: hay cuerpos cuyo default el servidor necesita recibir. */
    @Test
    fun `los valores por defecto siguen viajando`() {
        assertTrue(json.configuration.encodeDefaults)
    }
}
