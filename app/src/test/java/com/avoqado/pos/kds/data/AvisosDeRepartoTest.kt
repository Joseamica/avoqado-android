package com.avoqado.pos.kds.data

import com.avoqado.pos.printing.data.TrabajoPendiente
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** La pizarra de avisos (Codex código c1): nada se pierde, nada resucita y «Reimprimir» no se encima. */
class AvisosDeRepartoTest {

    private val trabajo = mockk<TrabajoPendiente>(relaxed = true)
    private fun aviso(id: String, createdAt: Long = 1_000, porReimprimir: List<TrabajoPendiente> = emptyList()) =
        ComandaImpresa(kdsId = id, orderNumber = "N-$id", createdAt = createdAt, salioEnPapel = porReimprimir.isEmpty(), porReimprimir = porReimprimir)

    @Test
    fun `publicar reemplaza por pedido`() {
        val p = AvisosDeReparto()
        p.publicar(aviso("a", porReimprimir = listOf(trabajo)), p.epoca())
        p.publicar(aviso("a"), p.epoca())
        assertEquals(listOf(true), p.avisos.value.map { it.salioEnPapel })
    }

    @Test
    fun `Entendido cierra la puerta a lo que estaba en la franja y no a un pedido nuevo`() {
        val p = AvisosDeReparto()
        p.publicar(aviso("a", porReimprimir = listOf(trabajo)), p.epoca())
        val epocaDelIntento = p.epoca() // una reimpresión de «a» y la impresión de «b» empiezan aquí
        p.descartar()
        p.publicar(aviso("a"), epocaDelIntento)
        assertTrue(p.avisos.value.isEmpty())
        p.publicar(aviso("b"), epocaDelIntento)
        assertEquals(listOf("b"), p.avisos.value.map { it.kdsId })
        // Un intento NUEVO de «a» (empezó después de «Entendido») sí se apunta.
        p.publicar(aviso("a"), p.epoca())
        assertEquals(setOf("a", "b"), p.avisos.value.map { it.kdsId }.toSet())
    }

    @Test
    fun `cambiar de sucursal quita lo de las demas`() {
        val p = AvisosDeReparto()
        p.publicar(aviso("a").copy(venueId = "v1"), p.epoca())
        p.publicar(aviso("b").copy(venueId = "v2"), p.epoca())
        p.retirarDeOtrasSucursales("v2")
        assertEquals(listOf("b"), p.avisos.value.map { it.kdsId })
    }

    @Test
    fun `reimprimir se aparta por pedido hasta soltarlo`() {
        val p = AvisosDeReparto()
        p.publicar(aviso("a", porReimprimir = listOf(trabajo)), p.epoca())
        val (c, _) = p.tomarParaReimprimir("a")!!
        assertEquals("a", c.kdsId)
        assertNull(p.tomarParaReimprimir("a"))
        p.soltarReimpresion("a")
        assertEquals("a", p.tomarParaReimprimir("a")!!.first.kdsId)
    }

    @Test
    fun `sin nada que reimprimir no se aparta`() {
        val p = AvisosDeReparto()
        p.publicar(aviso("a"), p.epoca())
        assertNull(p.tomarParaReimprimir("a"))
        assertNull(p.tomarParaReimprimir("no-existe"))
    }

    @Test
    fun `retirar ausentes respeta los mas viejos que la lista`() {
        val p = AvisosDeReparto()
        p.publicar(aviso("cancelado", createdAt = 2_000), p.epoca())
        p.publicar(aviso("viejo", createdAt = 10), p.epoca())
        p.publicar(aviso("vivo", createdAt = 3_000), p.epoca())
        p.retirarAusentes(setOf("vivo", "otro"), masVieja = 1_000)
        assertEquals(setOf("viejo", "vivo"), p.avisos.value.map { it.kdsId }.toSet())
        p.retirarAusentes(emptySet(), masVieja = null)
        assertTrue(p.avisos.value.isEmpty())
    }

    @Test
    fun `olvidar todo cierra la puerta a lo que estaba en vuelo aunque fuera un pedido nuevo`() {
        val p = AvisosDeReparto()
        val epocaDelIntento = p.epoca() // la primera impresión de «nuevo», que todavía no está en la franja
        p.olvidarTodo() // se perdió el permiso, el plan o la sesión
        p.publicar(aviso("nuevo"), epocaDelIntento)
        assertTrue(p.avisos.value.isEmpty())
        // Lo que empieza después sí se apunta.
        p.publicar(aviso("despues"), p.epoca())
        assertEquals(listOf("despues"), p.avisos.value.map { it.kdsId })
    }
}
