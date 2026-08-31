package com.avoqado.pos.cashdrawer

import com.avoqado.pos.cashdrawer.data.PendingDrawerOp
import com.avoqado.pos.cashdrawer.data.DestinoDeLaOperacion
import com.avoqado.pos.cashdrawer.data.clasificarRespuestaDelServer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 🔴 QUÉ HACE LA COLA DEL CAJÓN CON CADA RESPUESTA DEL SERVIDOR.
 *
 * La prueba de campo del 27-ago cubrió el camino feliz —retiro sin red y cierre sin red,
 * los dos reproducidos en orden al volver la señal— pero NO cubrió los desenlaces en que
 * el servidor contesta algo distinto de 200. Ahí es donde se pierde dinero en silencio:
 * una operación descartada por error nunca llega al servidor y el cajero no se entera.
 *
 * La clasificación se extrajo a una función PURA justamente para poder probarla: antes
 * vivía dentro de la llamada de red y no había forma de ejercitar un 429 sin un servidor
 * que lo produjera.
 *
 * Las tres reglas que sostienen esto:
 *  - Un error TRANSITORIO (sin red, 5xx, 408, 429) se reintenta. Descartarlo pierde dinero.
 *  - Un rechazo DEFINITIVO (400, 403, 409, 422) deja de reintentarse, pero NO se borra en
 *    silencio: se marca para que la pantalla lo diga.
 *  - El 404 significa cosas OPUESTAS según la operación.
 */
class CashDrawerRespuestaDelServerTest {

    // ── Lo que el servidor aceptó ─────────────────────────────────────────────

    @Test
    fun `un 200 confirma la operacion`() {
        assertEquals(DestinoDeLaOperacion.CONFIRMADA, clasificarRespuestaDelServer("PAY_OUT", 200))
        assertEquals(DestinoDeLaOperacion.CONFIRMADA, clasificarRespuestaDelServer("CLOSE", 201))
    }

    /**
     * 🔴 El 404 significa lo CONTRARIO según la operación, y confundirlos cuesta dinero
     * en las dos direcciones.
     *
     * En un CIERRE quiere decir "esa caja ya no está abierta": ya está cerrada, no hay nada
     * que reintentar. En un RETIRO quiere decir "no conozco esa caja" — porque su apertura
     * todavía no ha llegado —, y descartarlo borraría un retiro real.
     */
    @Test
    fun `el 404 cierra el cierre pero conserva el movimiento`() {
        assertEquals(DestinoDeLaOperacion.CONFIRMADA, clasificarRespuestaDelServer("CLOSE", 404))
        assertEquals(DestinoDeLaOperacion.REINTENTAR, clasificarRespuestaDelServer("PAY_OUT", 404))
        assertEquals(DestinoDeLaOperacion.REINTENTAR, clasificarRespuestaDelServer("PAY_IN", 404))
    }

    // ── Lo transitorio: se reintenta, JAMÁS se descarta ───────────────────────

    /**
     * 🔴 El defecto que motivó esta suite. El 429 es "vas muy rápido, reintenta luego" —
     * un error TEMPORAL— y estaba cayendo en el mismo saco que un 400: se descartaba de la
     * cola y el retiro del cajero desaparecía para siempre. Es 4xx por número, transitorio
     * por significado.
     */
    @Test
    fun `el 429 se reintenta aunque sea 4xx`() {
        assertEquals(DestinoDeLaOperacion.REINTENTAR, clasificarRespuestaDelServer("PAY_OUT", 429))
        assertEquals(DestinoDeLaOperacion.REINTENTAR, clasificarRespuestaDelServer("CLOSE", 429))
    }

    /** Mismo caso: 408 es "se agotó el tiempo", no "es inválido". */
    @Test
    fun `el 408 se reintenta aunque sea 4xx`() {
        assertEquals(DestinoDeLaOperacion.REINTENTAR, clasificarRespuestaDelServer("PAY_IN", 408))
    }

    @Test
    fun `un 5xx se reintenta`() {
        for (code in listOf(500, 502, 503, 504)) {
            assertEquals("code=$code", DestinoDeLaOperacion.REINTENTAR, clasificarRespuestaDelServer("PAY_OUT", code))
        }
    }

    /** Sin red no hay código: el 0 es el que usa el repositorio cuando la llamada ni salió. */
    @Test
    fun `sin red se reintenta`() {
        assertEquals(DestinoDeLaOperacion.REINTENTAR, clasificarRespuestaDelServer("CLOSE", 0))
    }

    // ── Lo definitivo: deja de reintentarse, pero se DICE ─────────────────────

    /**
     * 🔴 El 401 es un token vencido: TRANSITORIO. Tras reautenticarse el movimiento sí habría
     * entrado, y marcarlo como rechazo definitivo lo borra para siempre (Codex, 4ª auditoría).
     * Por eso la lista de rechazos es EXPLÍCITA y no el rango `400..499`.
     */
    @Test
    fun `el 401 se reintenta - es un token vencido, no un rechazo`() {
        assertEquals(DestinoDeLaOperacion.REINTENTAR, clasificarRespuestaDelServer("PAY_OUT", 401))
        assertEquals(DestinoDeLaOperacion.REINTENTAR, clasificarRespuestaDelServer("CLOSE", 401))
    }

    /**
     * Ante un 4xx que no conocemos se REINTENTA. Quedarse atorado es ruidoso —el cierre se
     * bloquea y alguien llama— y se puede arreglar; perder un retiro es silencioso y no.
     */
    @Test
    fun `un 4xx desconocido se reintenta, no se descarta`() {
        for (code in listOf(402, 405, 410, 413, 415, 418, 451)) {
            assertEquals("code=$code", DestinoDeLaOperacion.REINTENTAR, clasificarRespuestaDelServer("PAY_OUT", code))
        }
    }

    @Test
    fun `un rechazo definitivo no se reintenta`() {
        for (code in listOf(400, 403, 409, 422)) {
            assertEquals("code=$code", DestinoDeLaOperacion.RECHAZADA, clasificarRespuestaDelServer("PAY_OUT", code))
        }
    }

    /**
     * 🔴 Lo que distingue RECHAZADA de CONFIRMADA no es que se deje de reintentar —las dos
     * lo hacen— sino que RECHAZADA sigue siendo dinero que el servidor NO tiene. Si las dos
     * salieran de la cola igual, el cajero cerraría su caja creyendo que su retiro llegó.
     * Por eso son estados distintos y no un booleano.
     */
    @Test
    fun `rechazada y confirmada no son el mismo estado`() {
        assert(DestinoDeLaOperacion.RECHAZADA != DestinoDeLaOperacion.CONFIRMADA)
    }

    /** El cierre no puede ser la excepción: un rechazo definitivo suyo tampoco gira para siempre. */
    @Test
    fun `un cierre rechazado de plano deja de reintentarse`() {
        assertEquals(DestinoDeLaOperacion.RECHAZADA, clasificarRespuestaDelServer("CLOSE", 400))
        assertEquals(DestinoDeLaOperacion.RECHAZADA, clasificarRespuestaDelServer("CLOSE", 403))
    }
}

/**
 * 🔴 QUE LA ACTUALIZACIÓN NO SE COMA UN RETIRO PENDIENTE.
 *
 * Hay aparatos en la calle AHORA MISMO con operaciones en su cola esperando red. Al instalar la
 * versión nueva, esa cola guardada tiene que seguir leyéndose: si el parseo falla, `pendientes()`
 * la descarta entera (hay un `catch` que devuelve lista vacía) y el retiro desaparece sin que
 * nadie se entere — exactamente el fallo silencioso que este trabajo vino a cerrar.
 *
 * El JSON de abajo es el formato REAL de antes del cambio: sin `rechazadaEn` ni `motivoDelRechazo`.
 */
class CashDrawerColaCompatibilidadTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Test
    fun `una cola guardada por la version anterior se sigue leyendo`() {
        val guardadoAntes = """
            [{"kind":"PAY_OUT","sessionId":"srv-1","amountCents":5000,"note":"pago a proveedor","localId":"loc-1","at":1756400000000},
             {"kind":"CLOSE","sessionId":"srv-1","amountCents":25000,"at":1756400100000}]
        """.trimIndent()

        val cola = json.decodeFromString(ListSerializer(PendingDrawerOp.serializer()), guardadoAntes)

        assertEquals(2, cola.size)
        assertEquals(5000, cola[0].amountCents)
        assertEquals("loc-1", cola[0].localId)
        // Sin el campo en el JSON, la operación se lee VIVA — que es lo correcto: nadie la rechazó.
        assertNull(cola[0].rechazadaEn)
        assertNull(cola[1].rechazadaEn)
    }

    /** Y al revés: una versión vieja no debe atragantarse con lo que escriba la nueva. */
    @Test
    fun `una operacion marcada se guarda y se vuelve a leer igual`() {
        val marcada = PendingDrawerOp("PAY_IN", "srv-9", 1234, null, "loc-9", 1756400000000L, 1756400200000L, "Monto inválido")
        val texto = json.encodeToString(ListSerializer(PendingDrawerOp.serializer()), listOf(marcada))
        val vuelta = json.decodeFromString(ListSerializer(PendingDrawerOp.serializer()), texto)

        assertEquals(1756400200000L, vuelta[0].rechazadaEn)
        assertEquals("Monto inválido", vuelta[0].motivoDelRechazo)
        assertEquals(1234, vuelta[0].amountCents)
    }
}
