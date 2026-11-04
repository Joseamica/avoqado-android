package com.avoqado.pos.core.domain.refresh

import com.avoqado.pos.core.data.network.ClaseDeFallo.DENEGACION_PROPIA
import com.avoqado.pos.core.data.network.ClaseDeFallo.REINTENTABLE
import com.avoqado.pos.core.data.sync.AvisosDeVentasSubidas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** D22 (diseño r2 §B): la necesidad de recargar es un estado con generación; una carga sólo atiende lo que había al empezar. */
class NecesidadTest {
    private val avisos = AvisosDeVentasSubidas()

    /** Una carga buena de la sucursal «s», para que sus avisos cuenten. */
    private fun cargadaEn(sucursal: String) = Necesidad().let { it.alTerminarBien(it.instantanea(sucursal, avisos.de(sucursal))) }

    @Test fun `una sucursal que la pantalla nunca cargo no pide nada por sus avisos`() {
        avisos.subio("b")
        assertFalse(Necesidad().haceFalta("b", avisos.de("b")))
    }

    @Test fun `r4-2 y r1-2 - la instantanea es de antes de consultar - un aviso durante la carga sigue pendiente`() {
        var n = cargadaEn("s")
        val inst = n.instantanea("s", avisos.de("s")) // la carga empieza
        avisos.subio("s") // sube un cobro mientras va en camino
        n = n.alTerminarBien(inst) // y termina bien
        assertTrue(n.haceFalta("s", avisos.de("s")))
    }

    @Test fun `r4-1 - cada cobro que sube cuenta aunque otro siga retenido`() {
        val n = cargadaEn("s")
        avisos.subio("s") // el que subió; el retenido nunca avisa
        assertTrue(n.haceFalta("s", avisos.de("s")))
    }

    @Test fun `una carga buena que empezo despues del aviso lo apaga`() {
        var n = cargadaEn("s")
        avisos.subio("s")
        n = n.alTerminarBien(n.instantanea("s", avisos.de("s")))
        assertFalse(n.haceFalta("s", avisos.de("s")))
    }

    @Test fun `un fallo reintentable la levanta con espera de 5, 10, 20 y luego 30 s`() {
        var n = Necesidad()
        val esperas = (1..5).map { vez ->
            n = n.alFallar(n.instantanea("s", 0), listOf(REINTENTABLE), ahora = 1_000L * vez)
            n.proximoIntentoEn - 1_000L * vez
        }
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 30_000L, 30_000L), esperas)
        assertTrue(n.haceFalta("s", 0))
        n = n.alTerminarBien(n.instantanea("s", 0))
        assertFalse(n.haceFalta("s", 0))
        assertEquals(0, n.fallosSeguidos)
    }

    @Test fun `una carga buena que empezo ANTES de un fallo no lo apaga`() {
        var n = Necesidad()
        val vieja = n.instantanea("s", 0)
        n = n.alFallar(n.instantanea("s", 0), listOf(REINTENTABLE), ahora = 0) // otra carga falló mientras tanto
        n = n.alTerminarBien(vieja)
        assertTrue(n.porFallo())
    }

    @Test fun `r4-3 - una denegacion propia la detiene hasta que llega algo nuevo`() {
        var n = cargadaEn("s")
        avisos.subio("s")
        n = n.alFallar(n.instantanea("s", avisos.de("s")), listOf(DENEGACION_PROPIA), ahora = 0)
        assertFalse("detenida", n.haceFalta("s", avisos.de("s")))
        avisos.subio("s") // un cobro nuevo
        assertTrue("otra instantánea", n.haceFalta("s", avisos.de("s")))
    }

    @Test fun `al volver a verse la escalera vuelve al primer escalon, medido desde el ultimo fallo`() {
        var n = Necesidad()
        repeat(5) { vez -> n = n.alFallar(n.instantanea("s", 0), listOf(REINTENTABLE), ahora = 10_000L * vez) }
        assertEquals(40_000L + 30_000L, n.proximoIntentoEn) // el último fallo fue a los 40 s
        n = n.alVolverAVerse()
        assertEquals(1, n.fallosSeguidos)
        assertEquals(40_000L + 5_000L, n.proximoIntentoEn)
        assertTrue("la necesidad no cambia", n.porFallo())
    }

    @Test fun `al volver a verse sin fallos no cambia nada`() {
        assertEquals(Necesidad(), Necesidad().alVolverAVerse())
    }

    @Test fun `los avisos sin atender se ven aunque la automatica este detenida`() {
        var n = cargadaEn("s")
        avisos.subio("s")
        n = n.alFallar(n.instantanea("s", avisos.de("s")), listOf(DENEGACION_PROPIA), ahora = 0)
        assertFalse(n.haceFalta("s", avisos.de("s")))
        assertTrue(n.porAvisos("s", avisos.de("s")))
    }

    @Test fun `ronda 3 - un aviso despues del ultimo fallo se cuenta y uno de antes no`() {
        var n = cargadaEn("s")
        avisos.subio("s") // antes del fallo
        n = n.alFallar(n.instantanea("s", avisos.de("s")), listOf(REINTENTABLE), ahora = 0, avisosAhora = avisos.de("s"))
        assertFalse(n.avisoDespuesDelUltimoFallo("s", avisos.de("s")))
        avisos.subio("s") // después
        assertTrue(n.avisoDespuesDelUltimoFallo("s", avisos.de("s")))
        assertFalse("de otra sucursal no cuenta", n.avisoDespuesDelUltimoFallo("otra", 99))
        n = n.alTerminarBien(n.instantanea("s", avisos.de("s")))
        assertFalse("una carga buena lo limpia", n.avisoDespuesDelUltimoFallo("s", avisos.de("s")))
    }
}
