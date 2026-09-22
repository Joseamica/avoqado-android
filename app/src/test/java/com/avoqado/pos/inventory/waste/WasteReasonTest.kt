package com.avoqado.pos.inventory.waste

import com.avoqado.pos.inventory.waste.domain.WasteReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WasteReasonTest {

    /**
     * Los códigos y su ORDEN son contrato con el servidor (spec §4.1). El orden
     * además es el de los chips en pantalla, y tiene que ser el mismo en iOS.
     */
    @Test
    fun `los siete motivos del POS, en orden y con el codigo exacto del servidor`() {
        assertEquals(
            listOf("EXPIRED", "SPOILED", "DROPPED", "PREP_ERROR", "DEFECTIVE", "MISSING", "OTHER"),
            WasteReason.delPos.map { it.codigo },
        )
    }

    @Test
    fun `las etiquetas son las del servidor, en espanol y con acentos`() {
        assertEquals(
            listOf(
                "Caducó",
                "Se echó a perder",
                "Se cayó / derramó",
                "Error de preparación",
                "Dañado / roto",
                "Robo o faltante",
                "Otro",
            ),
            WasteReason.delPos.map { it.etiqueta },
        )
    }

    /** OTHER exige nota (spec §4.1); ningún otro la exige. */
    @Test
    fun `solo OTHER exige nota`() {
        assertEquals(listOf(WasteReason.OTHER), WasteReason.delPos.filter { it.exigeNota })
    }

    /**
     * Un motivo del dashboard que NO es de mostrador nunca puede llegar al POS.
     * `UNSPECIFIED` existe en el servidor sólo para el adaptador del dashboard.
     */
    @Test
    fun `UNSPECIFIED no existe en el catalogo del POS`() {
        assertTrue(WasteReason.delPos.none { it.codigo == "UNSPECIFIED" })
        assertNull(WasteReason.porCodigo("UNSPECIFIED"))
    }
}
