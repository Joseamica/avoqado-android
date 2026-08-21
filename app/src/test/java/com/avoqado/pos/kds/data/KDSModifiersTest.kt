package com.avoqado.pos.kds.data

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * La comanda nunca debe pintarle JSON crudo a un cocinero.
 *
 * `KdsOrderItem.modifiers` la escriben DOS productores en el server —el POS y la ingesta de
 * marketplace— y hasta el 2026-08-20 cada uno guardaba una forma distinta: el POS
 * `["Sin cebolla"]`, delivery `[{"name":"Extra queso","quantity":1}]`. El parser hacía
 * `getString()`, que sobre un objeto de JSON devuelve su **texto JSON**: verificado en una
 * Sunmi D3 con un pedido real de Uber, la pantalla de cocina mostró literalmente
 * `{"name":"Extra queso","quantity":1}` bajo la hamburguesa.
 *
 * El server ya normaliza al escribir, pero un aparato que no se actualizó sigue leyendo filas
 * viejas — por eso el cliente tolera las dos formas. Espejo de `KDSModifiersTests` en iOS y de
 * `kds.modifiers-shape.test.ts` en el server.
 */
class KDSModifiersTest {

    private fun arr(json: String) = JSONArray(json)

    @Test
    fun `la forma del POS se lee tal cual`() {
        assertEquals(
            listOf("Sin cebolla", "Extra queso"),
            parseKdsModifiers(arr("""["Sin cebolla","Extra queso"]""")),
        )
    }

    @Test
    fun `la forma de delivery se lee como texto, no como JSON`() {
        assertEquals(
            listOf("Extra queso"),
            parseKdsModifiers(arr("""[{"name":"Extra queso","quantity":1}]""")),
        )
    }

    @Test
    fun `un modificador repetido dice cuantos, porque la cocina prepara esa cantidad`() {
        assertEquals(
            listOf("3x Extra queso"),
            parseKdsModifiers(arr("""[{"name":"Extra queso","quantity":3}]""")),
        )
    }

    @Test
    fun `las dos formas mezcladas en la misma comanda`() {
        assertEquals(
            listOf("Sin cebolla", "2x Tocino"),
            parseKdsModifiers(arr("""["Sin cebolla",{"name":"Tocino","quantity":2}]""")),
        )
    }

    @Test
    fun `sin modificadores da lista vacia`() {
        assertEquals(emptyList<String>(), parseKdsModifiers(null))
        assertEquals(emptyList<String>(), parseKdsModifiers(arr("[]")))
    }

    @Test
    fun `descarta lo que no tiene nombre en vez de escribir basura en la comanda`() {
        assertEquals(
            listOf("Bien cocido"),
            parseKdsModifiers(arr("""[{"quantity":2},{"name":"  "},"","Bien cocido",null]""")),
        )
    }
}
