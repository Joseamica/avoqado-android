package com.avoqado.pos.escritorio.teclado

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TeclasTest {
    private val L = Tecla::Letra
    private fun txt(s: String) = Accion.Escribir(s)

    @Test fun `una letra se escribe tal cual`() = assertEquals(txt("a"), EstadoDeTeclas().presionar(L('a')))

    @Test fun `mayus escribe mayuscula y se apaga sola`() {
        val e = EstadoDeTeclas()
        assertNull(e.presionar(Tecla.Mayus))
        assertEquals(txt("A"), e.presionar(L('a')))
        assertEquals(txt("a"), e.presionar(L('a')))
    }

    @Test fun `acento cae en la vocal`() {
        val e = EstadoDeTeclas()
        assertNull(e.presionar(Tecla.Acento))
        assertEquals(txt("é"), e.presionar(L('e')))
        assertEquals(txt("e"), e.presionar(L('e')))
    }

    @Test fun `mayus mas acento da mayuscula acentuada`() {
        val e = EstadoDeTeclas()
        e.presionar(Tecla.Mayus); e.presionar(Tecla.Acento)
        assertEquals(txt("É"), e.presionar(L('e')))
    }

    @Test fun `el acento no cae en una consonante y se consume`() {
        val e = EstadoDeTeclas()
        e.presionar(Tecla.Acento)
        assertEquals(txt("n"), e.presionar(L('n')))
        assertEquals(txt("e"), e.presionar(L('e')))
    }

    @Test fun `la ene va tal cual`() = assertEquals(txt("ñ"), EstadoDeTeclas().presionar(L('ñ')))

    @Test fun `pagina alterna y no escribe`() {
        val e = EstadoDeTeclas()
        assertNull(e.presionar(Tecla.Pagina)); assertEquals(1, e.pagina)
        assertNull(e.presionar(Tecla.Pagina)); assertEquals(0, e.pagina)
    }

    @Test fun `borrar enter espacio y ocultar`() {
        val e = EstadoDeTeclas()
        assertEquals(Accion.Borrar, e.presionar(Tecla.Borrar))
        assertEquals(Accion.Enter, e.presionar(Tecla.Enter))
        assertEquals(txt(" "), e.presionar(Tecla.Espacio))
        assertNull(e.presionar(Tecla.Ocultar))
    }
}
