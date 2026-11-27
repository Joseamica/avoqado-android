package com.avoqado.pos.pos.presentation.cart

import com.avoqado.pos.core.data.network.FalloDeLectura
import com.avoqado.pos.payment.presentation.RECOMPENSAS_SIN_PERMISO
import com.avoqado.pos.tables.data.CustomerLoyalty
import com.avoqado.pos.tables.data.RECOMPENSAS_SIN_RED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** D39 · atajo «Usar puntos» → «Pagar después» (promesa v2, `docs/audit/2026-11-25-defectos-lealtad-referidos/d39/promesa-atajo.md`). */
class AtajoDePuntosTest {
    private fun saldo(puntos: Int, minimo: Int = 10, activo: Boolean = true) = CustomerLoyalty(
        customerId = "ana", customerName = "Ana", active = activo, balance = puntos, redemptionRate = 0.10, balanceValue = puntos * 0.10,
        minPointsRedeem = minimo, maxRedeemablePoints = puntos, maxRedeemableValue = puntos * 0.10, canRedeem = activo && puntos >= minimo,
    )

    private fun motivo(
        conProductos: Boolean = true,
        cliente: String? = "ana",
        puedeCanjear: Boolean = true,
        llavePrevia: Boolean = false,
        sinFila: Boolean = false,
        noSeDifiere: String? = null,
        lectura: LecturaDelAtajo? = LecturaDelAtajo("ana", saldo(200), null),
        enLinea: Boolean = true,
    ) = motivoSinUsarPuntos(conProductos, cliente, puedeCanjear, llavePrevia, sinFila, noSeDifiere, lectura, enLinea)

    @Test fun `con cliente, productos, red y puntos que alcanzan se puede`() = assertNull(motivo())

    @Test fun `sin productos`() = assertEquals(USAR_PUNTOS_SIN_PRODUCTOS, motivo(conProductos = false))

    @Test fun `sin cliente`() = assertEquals(USAR_PUNTOS_SIN_CLIENTE, motivo(cliente = null))

    @Test fun `sin permiso de canjear`() = assertEquals(RECOMPENSAS_SIN_PERMISO, motivo(puedeCanjear = false))

    // Codex r1 #2: una llave previa puede devolver el folio de otra versión de la venta.
    @Test fun `una venta que ya se intento dejar para despues`() = assertEquals(USAR_PUNTOS_YA_SE_INTENTO, motivo(llavePrevia = true))

    // Codex r1 #1: cupón, premio de sellos o descuento con aprobación van por el camino sin fila.
    @Test fun `cupon, premio de sellos o aprobacion`() = assertEquals(USAR_PUNTOS_SIN_FILA, motivo(sinFila = true))

    @Test fun `lo que Pagar despues no admite se dice igual`() = assertEquals("No se puede con vales", motivo(noSeDifiere = "No se puede con vales"))

    @Test fun `sin red`() = assertEquals(RECOMPENSAS_SIN_RED, motivo(enLinea = false))

    @Test fun `leyendo los puntos`() = assertEquals(USAR_PUNTOS_LEYENDO, motivo(lectura = null))

    // Codex r1 #4: la lectura es de OTRO cliente (cambió el seleccionado): no se usa.
    @Test fun `la lectura de otro cliente no cuenta`() =
        assertEquals(USAR_PUNTOS_LEYENDO, motivo(lectura = LecturaDelAtajo("beto", saldo(500), null)))

    @Test fun `no alcanza el minimo`() =
        assertEquals("Ana tiene 5 puntos; se necesitan al menos 10 para canjear.", motivo(lectura = LecturaDelAtajo("ana", saldo(5), null)))

    @Test fun `programa apagado`() =
        assertEquals("El programa de lealtad está apagado en esta sucursal. Se prende desde el panel web.",
            motivo(lectura = LecturaDelAtajo("ana", saldo(200, activo = false), null)))

    @Test fun `la lectura fallo sin red`() = assertEquals(RECOMPENSAS_SIN_RED, motivo(lectura = LecturaDelAtajo("ana", null, FalloDeLectura.SIN_RED)))

    // --- La puerta (Codex r1 #5): la cuenta por abrir viaja con su sucursal y su sesión, y se atiende una sola vez.

    @Test fun `la puerta entrega la solicitud a la misma sesion y sucursal`() {
        val puerta = PuertaDeRecompensas()
        puerta.pedir(SolicitudDeRecompensas("o1", "v1", 7))
        assertEquals("o1", puerta.vigente("v1", 7)?.orderId)
        assertEquals("o1", puerta.vigente("v1", 7)?.orderId) // ver no la consume
        puerta.atendida("o1")
        assertNull(puerta.vigente("v1", 7))
    }

    @Test fun `otra sesion u otra sucursal la descartan`() {
        val puerta = PuertaDeRecompensas()
        puerta.pedir(SolicitudDeRecompensas("o1", "v1", 7))
        assertNull(puerta.vigente("v1", 8))
        assertNull(puerta.vigente("v1", 7)) // ya se descartó: no revive
        puerta.pedir(SolicitudDeRecompensas("o2", "v1", 7))
        assertNull(puerta.vigente("v2", 7))
        assertNull(puerta.vigente("v1", 7))
    }

    @Test fun `atender otra cuenta no la consume`() {
        val puerta = PuertaDeRecompensas()
        puerta.pedir(SolicitudDeRecompensas("o1", "v1", 7))
        puerta.atendida("o2")
        assertEquals("o1", puerta.vigente("v1", 7)?.orderId)
    }

    // --- Cuándo se abre «Recompensas» (Codex r1 #6): al quedar lista, o con una razón final y aviso con el folio.

    @Test fun `lista y sin motivo se abre`() =
        assertEquals(AperturaDeRecompensas.Abrir, aperturaDeRecompensas(ofrece = true, consultando = false, motivo = null, folio = "ORD-14"))

    @Test fun `leyendo espera`() {
        assertEquals(AperturaDeRecompensas.Esperar, aperturaDeRecompensas(true, consultando = true, motivo = null, folio = "ORD-14"))
        assertEquals(AperturaDeRecompensas.Esperar, aperturaDeRecompensas(true, false, motivo = "Leyendo los puntos del cliente…", folio = "ORD-14"))
        assertEquals(AperturaDeRecompensas.Esperar, aperturaDeRecompensas(true, false, motivo = "Actualizando la cuenta…", folio = "ORD-14"))
        assertEquals(AperturaDeRecompensas.Esperar, aperturaDeRecompensas(true, false, motivo = "Leyendo la cuenta…", folio = "ORD-14"))
    }

    @Test fun `una razon final avisa con el folio`() = assertEquals(
        AperturaDeRecompensas.Avisar("La cuenta ORD-14 quedó en Pedidos: Ana tiene 5 puntos; se necesitan al menos 10 para canjear."),
        aperturaDeRecompensas(true, false, motivo = "Ana tiene 5 puntos; se necesitan al menos 10 para canjear.", folio = "ORD-14"),
    )

    @Test fun `la cuenta que ya no admite puntos tambien avisa`() = assertEquals(
        AperturaDeRecompensas.Avisar("La cuenta ORD-14 quedó en Pedidos, pero ya no admite puntos: revísala antes de cobrar."),
        aperturaDeRecompensas(ofrece = false, consultando = false, motivo = null, folio = "ORD-14"),
    )

    @Test fun `sin red espera a que vuelva`() =
        assertEquals(AperturaDeRecompensas.Esperar, aperturaDeRecompensas(true, false, motivo = RECOMPENSAS_SIN_RED, folio = "ORD-14"))
}
