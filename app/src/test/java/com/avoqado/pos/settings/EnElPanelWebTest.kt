package com.avoqado.pos.settings

import com.avoqado.pos.settings.domain.EnElPanelWeb
import com.avoqado.pos.settings.domain.GrupoDelPanel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Candado de los atajos al panel web.
 *
 * Lo que protege: que el atajo lleve al negocio CORRECTO o no exista. Mandar a un cajero a la
 * sucursal equivocada —o a una pantalla en blanco— cuesta más que no ofrecerle el atajo.
 */
class EnElPanelWebTest {

    @Test
    fun `P1 sin slug no hay atajo, en ninguno`() {
        EnElPanelWeb.entries.forEach { destino ->
            assertNull("${destino.etiqueta} armó una URL sin slug", destino.url(null))
            assertNull("${destino.etiqueta} aceptó un slug vacío", destino.url(""))
            assertNull("${destino.etiqueta} aceptó un slug en blanco", destino.url("   "))
        }
    }

    @Test
    fun `P1 la URL lleva al venue y a la seccion`() {
        val url = EnElPanelWeb.DISENO_DEL_TICKET.url("testarudo-cafe")!!
        assertTrue("falta el venue en $url", url.contains("/venues/testarudo-cafe/"))
        assertTrue("falta la sección en $url", url.endsWith("/receipt-layout"))
    }

    @Test
    fun `P2 nunca se cuela una doble barra al unir las partes`() {
        EnElPanelWeb.entries.forEach { destino ->
            val url = destino.url("testarudo-cafe")!!
            val sinEsquema = url.substringAfter("://")
            assertTrue("doble barra en $url", !sinEsquema.contains("//"))
        }
    }

    @Test
    fun `P2 cada destino apunta a una seccion distinta`() {
        val rutas = EnElPanelWeb.entries.map { it.ruta }
        assertTrue("hay rutas repetidas: $rutas", rutas.size == rutas.toSet().size)
    }

    @Test
    fun `P1 un cajero no ve lo que no puede configurar`() {
        val deCajero = EnElPanelWeb.porGrupo(esAdministrador = false).flatMap { it.second }
        assertTrue("al cajero se le ofreció algo de administrador", deCajero.none { it.soloAdministradores })
        // Y no se le muestra un grupo vacío: si nada de esa sección es suyo, la sección no existe.
        EnElPanelWeb.porGrupo(esAdministrador = false).forEach { (grupo, destinos) ->
            assertTrue("grupo vacío: ${grupo.titulo}", destinos.isNotEmpty())
        }
    }

    @Test
    fun `P1 el administrador ve todo el catalogo, sin perder ninguno`() {
        val todos = EnElPanelWeb.porGrupo(esAdministrador = true).flatMap { it.second }
        assertEquals(EnElPanelWeb.entries.size, todos.size)
        assertEquals(EnElPanelWeb.entries.toSet(), todos.toSet())
    }

    @Test
    fun `P2 cada destino pertenece a un grupo y ningun grupo queda huerfano`() {
        val gruposUsados = EnElPanelWeb.entries.map { it.grupo }.toSet()
        assertEquals(
            "hay grupos declarados que nadie usa",
            GrupoDelPanel.entries.toSet(),
            gruposUsados,
        )
    }

    @Test
    fun `P2 toda entrada se explica antes de que la toquen`() {
        EnElPanelWeb.entries.forEach { destino ->
            assertTrue("${destino.etiqueta} sin etiqueta", destino.etiqueta.isNotBlank())
            assertTrue("${destino.etiqueta} sin subtítulo que diga qué hay del otro lado", destino.subtitulo.isNotBlank())
        }
    }
}
