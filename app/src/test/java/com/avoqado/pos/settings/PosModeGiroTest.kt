package com.avoqado.pos.settings

import com.avoqado.pos.settings.domain.PosMode
import com.avoqado.pos.settings.domain.modoSugeridoPorGiro
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * El giro del negocio SUGIERE un modo. Estas pruebas fijan dos cosas que cuestan
 * caro si se rompen: que la llave persistida no cambió al renombrar las etiquetas,
 * y que un giro que no dice nada NO produce una sugerencia inventada.
 */
class PosModeGiroTest {

    @Test
    fun `renombrar las etiquetas no movio las llaves persistidas`() {
        // Si una llave cambia, todo aparato en la calle vuelve a Mostrador al
        // actualizar: su modo guardado deja de encontrarse.
        assertEquals("retail", PosMode.RETAIL.key)
        assertEquals("restaurant", PosMode.RESTAURANT.key)
        assertEquals("reservations", PosMode.RESERVATIONS.key)
    }

    @Test
    fun `las etiquetas son las del mercado`() {
        assertEquals("Mostrador", PosMode.RETAIL.displayName)
        assertEquals("Mesas", PosMode.RESTAURANT.displayName)
        assertEquals("Citas", PosMode.RESERVATIONS.displayName)
    }

    @Test
    fun `come en la mesa sugiere Mesas`() {
        assertEquals(PosMode.RESTAURANT, modoSugeridoPorGiro("RESTAURANT"))
        assertEquals(PosMode.RESTAURANT, modoSugeridoPorGiro("BAR"))
        assertEquals(PosMode.RESTAURANT, modoSugeridoPorGiro("HOTEL_RESTAURANT"))
    }

    @Test
    fun `se agenda antes de llegar sugiere Citas`() {
        assertEquals(PosMode.RESERVATIONS, modoSugeridoPorGiro("SALON"))
        assertEquals(PosMode.RESERVATIONS, modoSugeridoPorGiro("SPA"))
        assertEquals(PosMode.RESERVATIONS, modoSugeridoPorGiro("FITNESS_STUDIO"))
    }

    @Test
    fun `cafeteria y comida rapida son mostrador, no mesas`() {
        // El caso que decide el diseno: Testarudo es cafeteria y cobra en la caja.
        assertEquals(PosMode.RETAIL, modoSugeridoPorGiro("CAFE"))
        assertEquals(PosMode.RETAIL, modoSugeridoPorGiro("FAST_FOOD"))
        assertEquals(PosMode.RETAIL, modoSugeridoPorGiro("BAKERY"))
        assertEquals(PosMode.RETAIL, modoSugeridoPorGiro("RETAIL_STORE"))
    }

    @Test
    fun `un giro que no dice nada no sugiere nada`() {
        // 61 de 96 venues activos estan en OTHER: etiquetar "sugerido para tu
        // giro" ahi seria una etiqueta que miente.
        assertNull(modoSugeridoPorGiro("OTHER"))
        assertNull(modoSugeridoPorGiro(null))
        assertNull(modoSugeridoPorGiro(""))
        assertNull(modoSugeridoPorGiro("   "))
        assertNull(modoSugeridoPorGiro("GIRO_QUE_NO_EXISTE_TODAVIA"))
    }

    @Test
    fun `el giro se compara sin importar mayusculas ni espacios`() {
        assertEquals(PosMode.RESTAURANT, modoSugeridoPorGiro("restaurant"))
        assertEquals(PosMode.RETAIL, modoSugeridoPorGiro("  Cafe  "))
    }
}
