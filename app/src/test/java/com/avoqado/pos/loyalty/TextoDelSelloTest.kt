package com.avoqado.pos.loyalty

import com.avoqado.pos.loyalty.data.SaleStampCard
import com.avoqado.pos.loyalty.data.SaleStampOutcome
import com.avoqado.pos.loyalty.data.SaleStampReason
import com.avoqado.pos.loyalty.domain.RenglonDelSello
import com.avoqado.pos.loyalty.domain.TarjetaDelSello
import com.avoqado.pos.loyalty.domain.tamanoDeSello
import com.avoqado.pos.loyalty.domain.tarjetaDelSello
import com.avoqado.pos.loyalty.domain.TextosDelSello
import com.avoqado.pos.loyalty.domain.textoDelSello
import com.avoqado.pos.payment.data.OrderRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * El renglón del sello en «Cobro exitoso» (sellos tardíos, Testarudo 14-oct). Los textos son los del contrato
 * compartido con iOS, letra por letra: aquí se fijan para que nadie los «mejore» de un solo lado.
 */
class TextoDelSelloTest {

    private fun sello(
        stamped: Boolean = false,
        reason: String? = null,
        earned: Int = 0,
        required: Int = 7,
        reward: Boolean = false,
        enabled: Boolean = true,
        cartilla: SaleStampCard? = null,
    ) = SaleStampOutcome(
        stampsEnabled = enabled,
        stamped = stamped,
        reason = reason,
        stampsEarned = earned,
        stampsRequired = required,
        rewardReady = reward,
        saleCard = cartilla,
    )

    @Test
    fun `sello sumado dice cuantos lleva de cuantos`() {
        assertEquals("✓ +1 sello · 3 de 7", textoDelSello(sello(stamped = true, earned = 3)))
    }

    @Test
    fun `tarjeta completa con premio por cobrar`() {
        assertEquals(
            "🎁 ¡Completó su tarjeta! Tiene un premio por cobrar",
            textoDelSello(sello(stamped = true, earned = 0, reward = true)),
        )
    }

    @Test
    fun `un premio viejo sin cobrar no hace decir que completo la tarjeta`() {
        assertEquals("✓ +1 sello · 1 de 7", textoDelSello(sello(stamped = true, earned = 1, reward = true)))
    }

    @Test
    fun `cada motivo tiene el texto exacto del contrato`() {
        assertEquals("Esta venta ya sumó su sello a otro cliente", textoDelSello(sello(reason = "OTHER_CUSTOMER")))
        assertEquals("Ya recibió su sello de hoy", textoDelSello(sello(reason = "DAILY_LIMIT")))
        assertEquals("Pasaron más de 24 h: esta venta no suma sello", textoDelSello(sello(reason = "WINDOW_EXPIRED")))
        assertEquals("Esta venta no está pagada completa: no suma sello", textoDelSello(sello(reason = "NOT_PAID")))
    }

    @Test
    fun `pendiente, error o un motivo que esta version no conoce no pintan nada`() {
        assertNull(textoDelSello(sello(reason = "PENDING")))
        assertNull(textoDelSello(sello(reason = "ERROR")))
        assertNull(textoDelSello(sello(reason = "ALGO_NUEVO")))
        assertNull(sello(reason = "ALGO_NUEVO").reasonCode)
        assertEquals(SaleStampReason.PENDING, sello(reason = "PENDING").reasonCode)
    }

    @Test
    fun `P1 programa de sellos apagado o servidor viejo sin el campo no pintan nada`() {
        assertNull(textoDelSello(sello(stamped = true, earned = 3, enabled = false)))
        assertNull(textoDelSello(null))
    }

    // MARK: - Lo que la app lee DE VERDAD (`OrderRepository.leerSelloDeLaVenta`, sin `coerceInputValues`)
    // Revisión final (Minor #3): esta prueba fijaba una lectura con `coerceInputValues`, que producción no usa.

    /** La respuesta de la liga tardía con [loyalty] dentro de `data`, como la manda el servidor. */
    private fun liga(loyalty: String) = """{"success":true,"data":{"id":"orden-1","loyalty":$loyalty},"message":"ok"}"""

    private fun leerLiga(loyalty: String) = OrderRepository.leerSelloDeLaVenta(liga(loyalty), dentroDeLoyalty = true)

    @Test
    fun `P1 un campo nuevo del servidor o sin los opcionales se lee y pinta su texto`() {
        val leido = leerLiga(
            """{"stampsEnabled":true,"stamped":false,"reason":"DAILY_LIMIT","stampsEarned":2,"stampsRequired":7,"campoNuevo":1}""",
        )
        assertEquals(
            SaleStampOutcome(stampsEnabled = true, stamped = false, reason = "DAILY_LIMIT", stampsEarned = 2, stampsRequired = 7, rewardReady = false),
            leido,
        )
        assertEquals("Ya recibió su sello de hoy", textoDelSello(leido))
    }

    @Test
    fun `P1 un numero de la cartilla nulo descarta la lectura entera, no se vuelve cero`() {
        assertNull(
            leerLiga("""{"stampsEnabled":true,"stamped":false,"reason":"DAILY_LIMIT","stampsEarned":null,"stampsRequired":7}"""),
        )
    }

    // Revisión final (Minor #2): con default 0, un objeto a medias con `stamped: true` decía «🎁 ¡Completó su tarjeta!».
    @Test
    fun `P1 un sello a medias sin los numeros de la cartilla no se lee, para no decir que completo la tarjeta`() {
        assertNull(leerLiga("""{"stampsEnabled":true,"stamped":true}"""))
        assertNull(
            "la consulta del sello (GET) se lee igual",
            OrderRepository.leerSelloDeLaVenta("""{"success":true,"data":{"stampsEnabled":true,"stamped":true}}""", dentroDeLoyalty = false),
        )
    }

    @Test
    fun `un servidor viejo sin el campo loyalty se lee como null y no pinta nada`() {
        val leido = OrderRepository.leerSelloDeLaVenta("""{"success":true,"data":{"id":"orden-1"}}""", dentroDeLoyalty = true)
        assertNull(leido)
        assertNull(textoDelSello(leido))
    }

    @Test
    fun `el renglon es verde solo cuando el sello si se sumo`() {
        assertEquals(
            RenglonDelSello("✓ +1 sello · 3 de 7", positivo = true),
            RenglonDelSello.desde(sello(stamped = true, earned = 3)),
        )
        assertEquals(
            RenglonDelSello("✓ +1 sello · 3 de 7", positivo = true, tarjeta = TarjetaDelSello(3, 7, completa = false, premio = "Café")),
            RenglonDelSello.desde(sello(stamped = true, earned = 3, cartilla = SaleStampCard(3, 7, rewardLabel = "Café"))),
        )
        assertEquals(
            RenglonDelSello("Ya recibió su sello de hoy", positivo = false),
            RenglonDelSello.desde(sello(reason = "DAILY_LIMIT")),
        )
        assertNull(RenglonDelSello.desde(sello(reason = "PENDING")))
        assertEquals(
            RenglonDelSello("El sello se suma al sincronizar", positivo = false),
            RenglonDelSello.SE_SUMA_AL_SINCRONIZAR,
        )
    }

    @Test
    fun `sin red al agregar cliente el aviso lleva el plazo de 24 h`() {
        assertEquals(
            "Sin conexión: agrégalo cuando vuelva la red. Tienes 24 h para que sume el sello.",
            TextosDelSello.SIN_RED_AL_AGREGAR_CLIENTE,
        )
    }

    // MARK: - Tarjetita de sellos (21-oct). Mismos textos que iOS, letra por letra.

    @Test
    fun `la tarjetita dice cuantos lleva, cuantos faltan y el premio`() {
        val t = tarjetaDelSello(sello(stamped = true, earned = 1, cartilla = SaleStampCard(1, 7, rewardLabel = "Un café gratis")))!!
        assertEquals(TarjetaDelSello(1, 7, completa = false, premio = "Un café gratis"), t)
        assertEquals("+1 sello", t.titulo)
        assertEquals("1 de 7", t.cuenta)
        assertEquals("Faltan 6 para: Un café gratis", t.pie)
        assertEquals("+1 sello. 1 de 7 sellos. Faltan 6 para: Un café gratis", t.descripcion)
    }

    @Test
    fun `su cartilla de 7 esta llena aunque hoy se piden 10 - dice 7 de 7 y el premio ganado`() {
        val ganada = SaleStampCard(7, 7, completed = true, rewardLabel = "Café gratis", rewardPending = true)
        val t = tarjetaDelSello(sello(stamped = true, earned = 0, required = 10, reward = true, cartilla = ganada))!!
        assertEquals(TarjetaDelSello(7, 7, completa = true, premio = "Café gratis", porCobrar = true), t)
        assertEquals("¡Completó su tarjeta!", t.titulo)
        assertEquals("7 de 7", t.cuenta)
        assertEquals("Tiene un premio por cobrar: Café gratis", t.pie)
    }

    @Test
    fun `completo pero el premio ya se cobro en otro aparato - no promete por cobrar`() {
        val cobrada = SaleStampCard(7, 7, completed = true, rewardLabel = "Café gratis", rewardPending = false)
        assertEquals("Ganó: Café gratis", tarjetaDelSello(sello(stamped = true, cartilla = cobrada))!!.pie)
    }

    // QA en la D3 (22-oct): con 6 de 7 decía «Faltan 1 para…»
    @Test
    fun `con un sello por llenar dice falta en singular`() {
        assertEquals("Falta 1 para: Un café gratis", tarjetaDelSello(sello(stamped = true, cartilla = SaleStampCard(6, 7, rewardLabel = "Un café gratis")))!!.pie)
        assertEquals("Falta 1 para el premio", tarjetaDelSello(sello(stamped = true, cartilla = SaleStampCard(6, 7)))!!.pie)
    }

    @Test
    fun `sin premio no inventa uno`() {
        assertEquals("Faltan 4 para el premio", tarjetaDelSello(sello(stamped = true, cartilla = SaleStampCard(3, 7)))!!.pie)
        assertEquals("Faltan 4 para el premio", tarjetaDelSello(sello(stamped = true, cartilla = SaleStampCard(3, 7, rewardLabel = "  ")))!!.pie)
        assertEquals(
            "Tiene un premio por cobrar",
            tarjetaDelSello(sello(stamped = true, cartilla = SaleStampCard(7, 7, completed = true, rewardPending = true)))!!.pie,
        )
    }

    @Test
    fun `sin la cartilla de la venta no hay tarjetita - nunca se deduce de la cartilla abierta hoy`() {
        assertNull(tarjetaDelSello(null))
        assertNull(tarjetaDelSello(sello(stamped = true, earned = 0, reward = true))) // servidor viejo: el «0» no prueba nada
        assertNull(tarjetaDelSello(sello(reason = "DAILY_LIMIT", cartilla = SaleStampCard(3, 7))))
        assertNull(tarjetaDelSello(sello(stamped = true, enabled = false, cartilla = SaleStampCard(3, 7))))
        assertNull(tarjetaDelSello(sello(stamped = true, cartilla = SaleStampCard(0, 0))))
    }

    @Test
    fun `cero sellos sin premio pendiente no promete premio - otra venta lleno la cartilla y ya se cobro`() {
        assertEquals("✓ +1 sello", textoDelSello(sello(stamped = true, earned = 0, reward = false)))
        assertEquals("🎁 ¡Completó su tarjeta! Tiene un premio por cobrar", textoDelSello(sello(stamped = true, earned = 0, reward = true)))
    }

    @Test
    fun `nunca pinta mas sellos llenos que la meta`() {
        assertEquals(5, tarjetaDelSello(sello(stamped = true, cartilla = SaleStampCard(8, 5)))!!.llenos)
    }

    @Test
    fun `los circulos caben o va la barra`() {
        // Teléfono de 320: 320 − 48 de margen − 32 de relleno = 240.
        assertEquals(26.5f, tamanoDeSello(240f, 7)!!, 0.01f)
        assertEquals(30f, tamanoDeSello(400f, 7)!!, 0.01f) // tablet: tope de 30
        assertNull(tamanoDeSello(240f, 12)) // 12 en 240 no caben: barra
        assertEquals(27.08f, tamanoDeSello(400f, 12)!!, 0.01f)
        assertNull(tamanoDeSello(1000f, 13)) // más de 12: siempre barra
        assertNull(tamanoDeSello(240f, 0))
    }
}
