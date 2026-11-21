package com.avoqado.pos.maquina

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.Normalizer
import java.util.Random

/**
 * El protocolo de la QuickPay Mini (manual de integración 2.0.0 de APS, 18-nov-2026). Los JSON de abajo son los del
 * manual, con la sangría y los tipos con que los manda el equipo: `Due`/`Credit` son número en `status` y `sockets`, y
 * texto en `updateCredit`.
 */
class ProtocoloQuickPayTest {

    private val fecha = "2026-11-18T10:30:00.000Z"

    // MARK: - Lo que manda la máquina

    @Test
    fun `el status que acepta el cobro (manual 4_2) es nuestro pero no cierra`() {
        val m = leerMensaje(STATUS_ACEPTADO) as MensajeDeLaMaquina.Status

        assertEquals(
            MensajeDeLaMaquina.Status(
                cobrando = true, estado = null, referenceCC = "VENTA-000123",
                creditCents = 0, dueCents = 12_500, changeDispensedCents = 0, changeBar = "022210221000",
                fecha = "2026-11-18 15:48:19",
            ),
            m,
        )
        assertNull(m.cierre())
    }

    @Test
    fun `P1 Codex r1 #15 la hora fisica es la fecha del status en la zona del negocio, y sin ella no hay hora`() {
        val mexico = java.time.ZoneId.of("America/Mexico_City")
        val m = leerMensaje(STATUS_PAGO_COMPLETADO) as MensajeDeLaMaquina.Status
        assertEquals(java.time.Instant.parse("2026-11-18T21:48:20Z").toEpochMilli(), horaDeLaMaquina(m.fecha, mexico))
        assertNull(horaDeLaMaquina(null, mexico))
        assertNull("ilegible: nunca el reloj de la PC", horaDeLaMaquina("18/11/2026 15:48", mexico))
        assertNull(horaDeLaMaquina("2026-11-31 15:48:20", mexico))
    }

    @Test
    fun `P1 el cierre Pago Completado (manual 4_2) es PAGADO con sus importes`() {
        val m = leerMensaje(STATUS_PAGO_COMPLETADO) as MensajeDeLaMaquina.Status

        assertEquals(Cierre.PAGADO, m.cierre())
        assertEquals(15_000, m.creditCents)
        assertEquals(12_500, m.dueCents)
        assertEquals(2_500, m.changeDispensedCents)
        assertEquals("VENTA-000123", m.referenceCC)
    }

    @Test
    fun `P1 Entregando Cambio todavía no cierra`() {
        val m = leerMensaje(STATUS_ENTREGANDO_CAMBIO) as MensajeDeLaMaquina.Status

        assertEquals("Entregando Cambio", m.estado)
        assertNull(m.cierre())
    }

    @Test
    fun `P1 Transacción Cancelada con acento (manual 4_3) es CANCELADO`() {
        val m = leerMensaje(STATUS_CANCELADO) as MensajeDeLaMaquina.Status

        assertEquals(Cierre.CANCELADO, m.cierre())
        assertEquals(5_000, m.creditCents)
        assertEquals(8_000, m.dueCents)
        assertEquals(5_000, m.changeDispensedCents)
    }

    @Test
    fun `P1 Transaccion Cancelada sin acento no cierra`() {
        val m = leerMensaje(STATUS_CANCELADO.replace("Transacción", "Transaccion")) as MensajeDeLaMaquina.Status

        assertNull(m.cierre())
    }

    @Test
    fun `el acento descompuesto (NFD) es el mismo texto y también cierra`() {
        val nfd = Normalizer.normalize("Transacción Cancelada", Normalizer.Form.NFD)
        val m = leerMensaje(STATUS_CANCELADO.replace("Transacción Cancelada", nfd)) as MensajeDeLaMaquina.Status

        assertEquals(Cierre.CANCELADO, m.cierre())
    }

    @Test
    fun `P1 un texto de cierre con cobrando true no cierra`() {
        val m = leerMensaje(STATUS_PAGO_COMPLETADO.replace("\"cobrando\": false", "\"cobrando\": true"))
            as MensajeDeLaMaquina.Status

        assertNull(m.cierre())
    }

    @Test
    fun `mayúsculas distintas no cierran`() {
        val m = leerMensaje(STATUS_PAGO_COMPLETADO.replace("Pago Completado", "pago completado"))
            as MensajeDeLaMaquina.Status

        assertNull(m.cierre())
    }

    @Test
    fun `el sockets de la reconexión (manual 2_4) trae el cobro en curso`() {
        assertEquals(
            MensajeDeLaMaquina.Sockets(cobrando = true, dueCents = 12_500, creditCents = 10_000, changeBar = "022210222000"),
            leerMensaje(SOCKETS_EN_CURSO),
        )
    }

    @Test
    fun `el updateCredit (manual 4_2) trae los montos como texto`() {
        assertEquals(MensajeDeLaMaquina.UpdateCredit(creditCents = 10_000), leerMensaje(UPDATE_CREDIT))
    }

    @Test
    fun `el pong del manual 2_0_0 no trae cobrando`() {
        assertEquals(MensajeDeLaMaquina.Pong(cobrando = null), leerMensaje(PONG))
        assertEquals(
            MensajeDeLaMaquina.Pong(cobrando = true),
            leerMensaje("""{"fecha":"x","comando":"pong","conexiones":"1","tarjeta":true,"cobrando":true}"""),
        )
    }

    @Test
    fun `un comando que no conocemos es Otro`() {
        assertEquals(
            MensajeDeLaMaquina.Otro("getpayment"),
            leerMensaje("""{"fecha":"x","comando":"getpayment","duepayment":150,"reference":"4521","method":"2"}"""),
        )
    }

    @Test
    fun `lo mal formado se lee como null`() {
        assertNull(leerMensaje("{no es json"))
        assertNull(leerMensaje("[1,2]"))
        assertNull(leerMensaje(""))
        assertNull(leerMensaje("""{"fecha":"x"}"""))
        assertNull(leerMensaje("""{"fecha":"x","comando":null}"""))
    }

    @Test
    fun `P1 un status con un importe ilegible o sin cobrando no se lee`() {
        assertNull(leerMensaje(STATUS_PAGO_COMPLETADO.replace("\"Credit\": 150.0", "\"Credit\": \"abc\"")))
        assertNull(leerMensaje(STATUS_PAGO_COMPLETADO.replace("\"Credit\": 150.0,", "")))
        assertNull(leerMensaje(STATUS_PAGO_COMPLETADO.replace("\"cobrando\": false,", "")))
    }

    @Test
    fun `P1 los importes se pasan a centavos exactos, como número o como texto`() {
        assertEquals(150_000, credito("\"\$1,500.00\""))
        assertEquals(12_550, credito("\"125.50\""))
        assertEquals(10_000, credito("\"100\""))
        assertEquals(12_550, credito("125.5"))
        assertEquals(18_750, credito("187.50"))
        assertEquals(30, credito("0.3"))
        assertEquals(1_001, credito("10.01"))
        assertEquals(10_000_000, credito("100000.0"))
        assertNull(credito("\"1.005\""))   // medio centavo: no se inventa
        assertNull(credito("\"\""))
        assertNull(credito("null"))
        assertNull(credito("true"))
    }

    private fun credito(crudo: String): Int? =
        (leerMensaje("""{"fecha":"x","comando":"updateCredit","Due":"1","Credit":$crudo}""") as? MensajeDeLaMaquina.UpdateCredit)
            ?.creditCents

    // MARK: - Lo que manda la app

    @Test
    fun `P1 pedirCobro manda el monto con punto decimal y nuestra referencia`() {
        val o = JSONObject(pedirCobro(18_750, "AQ0123456789AB", fecha))

        assertEquals("paycash", o.getString("comando"))
        assertEquals(fecha, o.getString("fecha"))
        assertEquals("187.50", o.getString("due"))
        assertEquals("AQ0123456789AB", o.getString("referenceCC"))
        assertEquals("0.50", JSONObject(pedirCobro(50, "AQ0123456789AB", fecha)).getString("due"))
        assertEquals("1000.00", JSONObject(pedirCobro(100_000, "AQ0123456789AB", fecha)).getString("due"))
    }

    @Test
    fun `P1 pedirCobro no manda un monto que la máquina no puede cobrar`() {
        for (cents in listOf(18_735, 0, -50, 5, 1)) {
            assertThrows(IllegalArgumentException::class.java) { pedirCobro(cents, "AQ0123456789AB", fecha) }
        }
    }

    @Test
    fun `P1 pedirCobro no manda una referencia que no es nuestra`() {
        for (ref in listOf("NDF4521", "VENTA-000123", "aq0123456789ab", "AQ0123456789A", "AQ0123456789ABC", "")) {
            assertThrows(IllegalArgumentException::class.java) { pedirCobro(18_750, ref, fecha) }
        }
    }

    @Test
    fun `ningún mensaje pasa de 1024 bytes`() {
        val larga = "x".repeat(1_100)
        assertThrows(IllegalArgumentException::class.java) { pedirCobro(18_750, "AQ0123456789AB", larga) }
        assertThrows(IllegalArgumentException::class.java) { cancelar(true, larga) }
        assertThrows(IllegalArgumentException::class.java) { ping(larga) }
    }

    @Test
    fun `cancelar devuelve con 1 y guarda con 2`() {
        val devolver = JSONObject(cancelar(devolver = true, fecha = fecha))
        val guardar = JSONObject(cancelar(devolver = false, fecha = fecha))

        assertEquals("cancel", devolver.getString("comando"))
        assertEquals(fecha, devolver.getString("fecha"))
        assertEquals("1", devolver.getString("cancelvalue"))
        assertEquals("2", guardar.getString("cancelvalue"))
    }

    @Test
    fun `ping lleva comando en minúsculas y fecha`() {
        val o = JSONObject(ping(fecha))

        assertEquals("ping", o.getString("comando"))
        assertEquals(fecha, o.getString("fecha"))
    }

    @Test
    fun `P1 la referencia es AQ más 12, nunca NDF ni con forma de fecha`() {
        val aleatorio = Random(7)
        val formato = Regex("^AQ[0-9A-Z]{12}$")
        val fechaIso = Regex("\\d{4}-\\d{2}-\\d{2}")
        var ahora = 1_763_481_000_000L
        repeat(1_000) {
            val ref = nuevaReferencia(ahora, aleatorio)
            assertTrue(ref, formato.matches(ref))
            assertFalse(ref, ref.startsWith("NDF"))
            assertFalse(ref, fechaIso.containsMatchIn(ref))
            // la que generamos la acepta pedirCobro
            pedirCobro(50, ref, fecha)
            ahora += aleatorio.nextInt(5_000)
        }
    }

    @Test
    fun `dos referencias de milisegundos distintos son distintas`() {
        val a = nuevaReferencia(1_763_481_000_000L, Random(1))
        val b = nuevaReferencia(1_763_481_000_001L, Random(1))

        assertFalse(a == b)
    }

    private companion object {
        val SOCKETS_EN_CURSO = """
            {
                "fecha": "2026-11-18 15:48:19",
                "comando": "sockets",
                "conexiones": "2",
                "cobrando": true,
                "Due": 125.00,
                "Credit": 100.0,
                "changeBar": "022210222000",
                "tarjeta": true,
                "formaPago": "EFECTIVO"
            }
        """.trimIndent()

        val STATUS_ACEPTADO = """
            {
                "fecha": "2026-11-18 15:48:19",
                "comando": "status",
                "conexiones": "1",
                "cobrando": true,
                "bvError": "",
                "caError": "",
                "tarjeta": true,
                "Status": null,
                "referenceCC": "VENTA-000123",
                "Credit": 0.0,
                "Due": 125.00,
                "changeDispensed": 0.0,
                "tubesSW": "${'$'}320.00",
                "coinCashboxAmount": "${'$'}57.50",
                "coinCashboxQty": 12.0,
                "billsCashboxAmount": "${'$'}1,500.00",
                "billsCashboxQty": 7.0,
                "changeBar": "022210221000"
            }
        """.trimIndent()

        val UPDATE_CREDIT = """
            {
                "fecha": "2026-11-18 15:48:19",
                "comando": "updateCredit",
                "Due": "125.00",
                "Credit": "100",
                "Status": "Ingrese monedas o billetes",
                "changeBar": "022210222000"
            }
        """.trimIndent()

        val STATUS_ENTREGANDO_CAMBIO = """
            {
                "fecha": "2026-11-18 15:48:19",
                "comando": "status",
                "conexiones": "1",
                "cobrando": true,
                "bvError": "",
                "caError": "",
                "tarjeta": true,
                "Status": "Entregando Cambio",
                "referenceCC": "VENTA-000123",
                "Credit": 150.0,
                "Due": 125.00,
                "changeDispensed": 0.0,
                "tubesSW": "${'$'}320.00",
                "coinCashboxAmount": "${'$'}57.50",
                "coinCashboxQty": 12.0,
                "billsCashboxAmount": "${'$'}1,500.00",
                "billsCashboxQty": 7.0,
                "changeBar": "022210222000"
            }
        """.trimIndent()

        val STATUS_PAGO_COMPLETADO = """
            {
                "fecha": "2026-11-18 15:48:20",
                "comando": "status",
                "conexiones": "1",
                "cobrando": false,
                "bvError": "",
                "caError": "",
                "tarjeta": true,
                "Status": "Pago Completado",
                "referenceCC": "VENTA-000123",
                "Credit": 150.0,
                "Due": 125.00,
                "changeDispensed": 25.0,
                "tubesSW": "${'$'}315.00",
                "coinCashboxAmount": "${'$'}57.50",
                "coinCashboxQty": 12.0,
                "billsCashboxAmount": "${'$'}1,500.00",
                "billsCashboxQty": 7.0,
                "changeBar": "022210222000"
            }
        """.trimIndent()

        val STATUS_CANCELADO = """
            {
                "fecha": "2026-11-18 15:48:20",
                "comando": "status",
                "conexiones": "1",
                "cobrando": false,
                "bvError": "",
                "caError": "",
                "tarjeta": true,
                "Status": "Transacción Cancelada",
                "referenceCC": "VENTA-000124",
                "Credit": 50.0,
                "Due": 80.00,
                "changeDispensed": 50.0,
                "tubesSW": "${'$'}315.00",
                "coinCashboxAmount": "${'$'}57.50",
                "coinCashboxQty": 12.0,
                "billsCashboxAmount": "${'$'}1,500.00",
                "billsCashboxQty": 7.0,
                "changeBar": "022210222000"
            }
        """.trimIndent()

        val PONG = """
            {
                "fecha": "2026-11-18 15:48:19",
                "comando": "pong",
                "conexiones": "1",
                "tarjeta": true
            }
        """.trimIndent()
    }
}
