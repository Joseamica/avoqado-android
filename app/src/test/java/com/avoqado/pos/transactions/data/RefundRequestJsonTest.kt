package com.avoqado.pos.transactions.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1 EL POS NO MANDA `null` EXPLÍCITO EN EL CUERPO DEL REEMBOLSO.
 *
 * Esto es la causa raíz del lado cliente del incidente del 2026-09-11 en Testarudo: 18
 * respuestas 400 en 10 minutos y el cajero sin poder devolverle a nadie.
 *
 * En kotlinx.serialization, `encodeDefaults = true` con el `explicitNulls` por defecto
 * (true) **emite la llave con `null`** para cada campo opcional que no aplica. Un
 * reembolso por importe salía así:
 *
 *     {"amount":5000,"items":null,"restockItemIds":null,"reason":"...","note":null,...}
 *
 * y uno por artículos con `"amount":null`. El servidor validaba con
 * `if (x !== undefined && !valido(x))`, y **en JSON no existe `undefined`**: el `null`
 * explícito entraba al guard y reventaba con 400. El dashboard nunca lo sufrió (manda un
 * objeto plano) ni iOS (arma el diccionario con llaves condicionales) — sólo Android.
 *
 * El servidor ya tolera el nulo desde el 2026-09-12 (`vieneAusente`), así que esto es la
 * SEGUNDA capa: el cliente tampoco debe mandar lo que no aplica. Las dos hacen falta —
 * el arreglo del servidor destraba a los aparatos ya instalados sin publicar un APK, y
 * éste evita que el próximo guard escrito con esa forma vuelva a tumbar el reembolso.
 */
class RefundRequestJsonTest {

    /** Lo que de verdad viaja: el serializador del repositorio, no una copia. */
    private fun cuerpo(peticion: AssociatedRefundRequest): String =
        jsonReembolsos.encodeToString(AssociatedRefundRequest.serializer(), peticion)

    @Test
    fun `un reembolso por IMPORTE no manda items ni las demas llaves que no aplican`() {
        val body = cuerpo(
            AssociatedRefundRequest(
                amount = 5_000,
                reason = "RETURNED_GOODS",
                tipRefundCents = 0,
            ),
        )

        assertFalse(
            "El cuerpo lleva `null` explícito y eso es lo que tumbó el reembolso en producción: $body",
            body.contains("null"),
        )
        // Lo que NO aplica se omite…
        assertFalse(body.contains("\"items\""))
        assertFalse(body.contains("\"restockItemIds\""))
        assertFalse(body.contains("\"note\""))
        // …y lo que sí aplica viaja entero.
        val obj = Json.parseToJsonElement(body) as kotlinx.serialization.json.JsonObject
        assertEquals("5000", obj["amount"]!!.jsonPrimitive.content)
        assertEquals("RETURNED_GOODS", obj["reason"]!!.jsonPrimitive.content)
    }

    /**
     * El cero NO es «ausente» y no se puede omitir: `tipRefundCents = 0` significa
     * «devuelve sólo la venta, la propina del mesero no se toca», mientras que omitirlo
     * le pide al servidor el reparto proporcional — que SÍ toma propina. Confundirlos es
     * dinero del mesero.
     */
    @Test
    fun `tipRefundCents = 0 VIAJA, porque no es lo mismo que omitirlo`() {
        val body = cuerpo(
            AssociatedRefundRequest(amount = 5_000, reason = "RETURNED_GOODS", tipRefundCents = 0),
        )
        val obj = Json.parseToJsonElement(body) as kotlinx.serialization.json.JsonObject
        assertEquals("0", obj["tipRefundCents"]!!.jsonPrimitive.content)
    }

    @Test
    fun `un reembolso por ARTICULOS no manda amount en null`() {
        val body = cuerpo(
            AssociatedRefundRequest(
                items = listOf(AssociatedRefundItem(orderItemId = "oi-1", quantity = 2)),
                reason = "RETURNED_GOODS",
            ),
        )

        assertFalse("El cuerpo lleva `null` explícito: $body", body.contains("null"))
        assertFalse(body.contains("\"amount\""))
        assertTrue(body.contains("\"orderItemId\":\"oi-1\""))
    }

    /**
     * El MISMO defecto vivía en el segundo cuerpo del archivo — el reembolso NO asociado,
     * que va a `POST /mobile/venues/:id/refunds`. Arreglar sólo el que dolió habría dejado
     * el otro esperando a que alguien le escribiera un guard con la misma forma.
     */
    @Test
    fun `el reembolso NO asociado tampoco manda items en null`() {
        val body = jsonReembolsos.encodeToString(
            RefundRequest.serializer(),
            RefundRequest(amount = 50.0, reason = "RETURNED_GOODS"),
        )

        assertFalse("El cuerpo lleva `null` explícito: $body", body.contains("null"))
        assertFalse(body.contains("\"items\""))
        // `method` tiene default "CASH" y DEBE viajar: el servidor lo usa para el cajón.
        assertTrue(body.contains("\"method\":\"CASH\""))
    }

    /**
     * El arreglo no puede cambiar cómo se LEEN las respuestas. Con `explicitNulls = false`
     * un `null` explícito del servidor cae al default de la propiedad; aquí todos los
     * defaults son `null`, así que «llave ausente» y «llave nula» siguen significando lo
     * mismo. Se fija para que nadie introduzca un default distinto de null sin darse cuenta.
     */
    @Test
    fun `leer la respuesta no cambia- un dato nulo sigue siendo nulo`() {
        val conNull = jsonReembolsos.decodeFromString(
            RefundResult.serializer(),
            """{"refundId":null,"message":"ok"}""",
        )
        val sinLlave = jsonReembolsos.decodeFromString(RefundResult.serializer(), """{"message":"ok"}""")

        assertEquals(null, conNull.refundId)
        assertEquals(null, sinLlave.refundId)
        assertEquals("ok", conNull.message)
    }
}
