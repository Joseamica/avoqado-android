package com.avoqado.pos.pos.presentation.product

import com.avoqado.pos.tables.data.VenueMenu
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regla de la cuadrícula de «Menú del aparato». Gemela de `MenuDelAparatoTests` de iOS: mismos 14 casos, mismos nombres. */
class MenuDelAparatoTest {
    private fun menu(id: String, cats: List<String> = emptyList(), horario: Boolean = false, ahora: Boolean = false) =
        VenueMenu(
            id = id, name = id, availableFrom = if (horario) "07:00" else null,
            availableUntil = if (horario) "12:00" else null, categoryIds = cats, appliesNow = ahora,
        )

    @Test fun sinFiltro() {
        assertNull(MenuDelAparato.filtroDeCobrar(null, false, setOf("a")))
    }

    @Test fun listaVaciaEsTodo() {
        assertNull(MenuDelAparato.filtroDeCobrar(emptyList(), false, setOf("a")))
    }

    @Test fun filtra() {
        assertEquals(setOf("a", "b"), MenuDelAparato.filtroDeCobrar(listOf("a", "b"), false, setOf("a", "b", "c")))
    }

    @Test fun idsViejosNoDejanEnBlanco() {
        assertNull(MenuDelAparato.filtroDeCobrar(listOf("x"), false, setOf("a")))
    }

    @Test fun verTodoGana() {
        assertNull(MenuDelAparato.filtroDeCobrar(listOf("a"), true, setOf("a")))
    }

    @Test fun menuElegidoGana() {
        val c = menu("C", listOf("c"))
        assertEquals(setOf("c"), MenuDelAparato.filtroDeMesas("C", listOf(c), null, listOf("a"), false, setOf("a", "c")))
    }

    @Test fun menuVacioCaeAlAparato() {
        val e = menu("E", emptyList())
        assertEquals(setOf("a"), MenuDelAparato.filtroDeMesas("E", listOf(e), null, listOf("a"), false, setOf("a")))
    }

    @Test fun menuSinProductosCaeAlAparato() {
        val e = menu("E", listOf("p"))
        assertEquals(setOf("a"), MenuDelAparato.filtroDeMesas("E", listOf(e), null, listOf("a"), false, setOf("a")))
    }

    @Test fun menuQueYaNoEsDelAparatoSeIgnora() {
        val a = menu("A", listOf("a"))
        val b = menu("B", listOf("b"))
        assertEquals(
            setOf("b"),
            MenuDelAparato.filtroDeMesas("A", listOf(a, b), listOf("B"), listOf("b"), false, setOf("a", "b")),
        )
    }

    @Test fun menusDelAparatoFiltra() {
        val ms = listOf(menu("A"), menu("B"), menu("C"))
        assertEquals(listOf("B"), MenuDelAparato.menusDelAparato(ms, listOf("B")).map { it.id })
    }

    @Test fun menusDelAparatoIdsViejos() {
        assertEquals(listOf("A"), MenuDelAparato.menusDelAparato(listOf(menu("A")), listOf("Z")).map { it.id })
    }

    @Test fun preseleccionSoloDelAparato() {
        val a = menu("A", horario = true)
        val b = menu("B", horario = true, ahora = true)
        assertEquals("B", MenuDelAparato.menuPreseleccionado(listOf(a, b), "A", listOf("B")))
    }

    @Test fun preseleccionSinAparatoComoHoy() {
        val a = menu("A", horario = true)
        val b = menu("B", horario = true, ahora = true)
        assertEquals("A", MenuDelAparato.menuPreseleccionado(listOf(a, b), "A", null))
    }

    @Test fun categoriaAbiertaFueraRegresaALaRaiz() {
        assertFalse(MenuDelAparato.categoriaAbiertaSigue("a", setOf("b")))
        assertTrue(MenuDelAparato.categoriaAbiertaSigue("a", null))
        assertTrue(MenuDelAparato.categoriaAbiertaSigue(null, setOf("b")))
    }
}
