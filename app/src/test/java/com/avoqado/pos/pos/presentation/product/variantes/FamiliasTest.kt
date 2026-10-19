package com.avoqado.pos.pos.presentation.product.variantes

import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.pos.data.model.VariantOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Variantes de producto, bloque 3 (spec 2026-10-14 §11): la lógica pura de familias de `Familias.kt`.
 * Los casos A1–B8 (salvo B7) son LOS MISMOS (y con los mismos nombres) que `ProductGroupTests.swift` de avoqado-ios; B7 es sólo de Android
 * (Android hoy no recorta espacios al buscar y se conserva, Codex 8).
 */
class FamiliasTest {

    // ---- Montaje ----

    private fun op(nombre: String, valor: String, np: Int, vp: Int) = VariantOption(nombre, valor, np, vp)

    private fun prod(
        id: String,
        nombre: String,
        precio: Double,
        sku: String? = null,
        gtin: String? = null,
        grupo: String? = null,
        nombreGrupo: String? = null,
        opciones: List<VariantOption>? = null,
        agotado: Boolean = false,
        imagen: String? = null,
        imagenGrupo: String? = null,
        porPeso: Boolean = false,
    ) = Product(
        id = id,
        name = nombre,
        priceValue = precio,
        sku = sku,
        gtin = gtin,
        imageUrl = imagen,
        trackInventory = if (agotado) true else null,
        availableQuantity = if (agotado) 0 else null,
        productGroupId = grupo,
        productGroupName = nombreGrupo,
        productGroupImageUrl = imagenGrupo,
        variantOptions = opciones,
        soldByWeight = porPeso,
    )

    /** `Playera · S · Azul` y compañía: familia `g1` «Playera». */
    private fun playera(
        id: String,
        talla: Pair<String, Int>,
        color: Pair<String, Int>,
        precio: Double,
        sku: String,
        gtin: String? = null,
        agotado: Boolean = false,
    ) = prod(
        id, "Playera · ${talla.first} · ${color.first}", precio, sku = sku, gtin = gtin, grupo = "g1", nombreGrupo = "Playera",
        opciones = listOf(op("Talla", talla.first, 0, talla.second), op("Color", color.first, 1, color.second)),
        agotado = agotado,
    )

    /** El catálogo de `casos-compartidos.md`, en este orden. */
    private fun catalogo() = listOf(
        prod("agua", "Agua", 20.0),
        playera("pl-s-az", "S" to 0, "Azul" to 0, 199.0, "PL-S-AZ"),
        prod("gorra", "Gorra", 150.0, sku = "GO-1"),
        playera("pl-m-az", "M" to 1, "Azul" to 0, 199.0, "PL-M-AZ"),
        playera("pl-s-ro", "S" to 0, "Rojo" to 1, 219.0, "PL-S-RO", gtin = "7501000000017", agotado = true),
        prod("taza", "Taza · Grande", 99.0, grupo = "g2", nombreGrupo = "Taza", opciones = listOf(op("Tamaño", "Grande", 0, 1))),
    )

    private fun por(id: String, productos: List<Product>) = productos.first { it.id == id }

    // ---- Formas legibles para comparar ----

    private fun formaEntradas(entradas: List<EntradaDeCatalogo>) = entradas.map {
        when (it) {
            is EntradaDeCatalogo.Suelto -> "suelto:${it.producto.id}"
            is EntradaDeCatalogo.DeFamilia -> "familia:${it.familia.groupId}[${it.familia.variantes.joinToString(",") { v -> v.id }}]"
        }
    }

    private fun formaResultados(resultados: List<ResultadoDeBusqueda>) = resultados.map {
        when (it) {
            is ResultadoDeBusqueda.DeProducto -> "producto:${it.producto.id}"
            is ResultadoDeBusqueda.DeFamilia -> "familia:${it.familia.groupId}"
        }
    }

    private fun agrupar(x: List<Product>) = agruparCatalogo(x, familiasDe(catalogo()))

    private fun buscar(x: List<Product>, q: String) = buscarEnCatalogo(x, familiasDe(x), q)

    private fun familiaG1() = familiasDe(catalogo()).getValue("g1")

    private val todasSueltas =
        listOf("suelto:agua", "suelto:pl-s-az", "suelto:gorra", "suelto:pl-m-az", "suelto:pl-s-ro", "suelto:taza")

    // ---- A · agrupar el catálogo ----

    @Test
    fun `A1 sin familias la lista sale identica y en el mismo orden`() {
        val c = catalogo()
        val sueltos = listOf(por("agua", c), por("gorra", c))
        assertEquals(
            listOf(EntradaDeCatalogo.Suelto(por("agua", c)), EntradaDeCatalogo.Suelto(por("gorra", c))),
            agrupar(sueltos),
        )
    }

    @Test
    fun `A2 la familia va donde aparece su primera variante`() {
        assertEquals(
            listOf("suelto:agua", "familia:g1[pl-s-az,pl-m-az,pl-s-ro]", "suelto:gorra", "suelto:taza"),
            formaEntradas(agrupar(catalogo())),
        )
    }

    @Test
    fun `A3 una variante sin nombre de familia deja sueltas las tres de su familia`() {
        val c = catalogo().toMutableList()
        c[3] = prod(
            "pl-m-az", "Playera · M · Azul", 199.0, sku = "PL-M-AZ", grupo = "g1", nombreGrupo = "",
            opciones = listOf(op("Talla", "M", 0, 1), op("Color", "Azul", 1, 0)),
        )
        assertEquals(todasSueltas, formaEntradas(agruparCatalogo(c, familiasDe(c))))
    }

    @Test
    fun `A4 una variante sin opciones deja sueltas las tres de su familia`() {
        val c = catalogo().toMutableList()
        c[3] = prod("pl-m-az", "Playera · M · Azul", 199.0, sku = "PL-M-AZ", grupo = "g1", nombreGrupo = "Playera", opciones = null)
        assertNull(c[3].variantOptions)
        assertEquals(todasSueltas, formaEntradas(agruparCatalogo(c, familiasDe(c))))
    }

    @Test
    fun `A5 ejes distintos entre variantes dejan sueltas las tres`() {
        val c = catalogo().toMutableList()
        c[3] = prod(
            "pl-m-az", "Playera · M", 199.0, sku = "PL-M-AZ", grupo = "g1", nombreGrupo = "Playera",
            opciones = listOf(op("Talla", "M", 0, 1)),
        )
        assertEquals(todasSueltas, formaEntradas(agruparCatalogo(c, familiasDe(c))))
    }

    @Test
    fun `A6 una opcion con nombre o valor en blanco deja sueltas las tres`() {
        val valorEnBlanco = catalogo().toMutableList()
        valorEnBlanco[3] = prod(
            "pl-m-az", "Playera · M · Azul", 199.0, sku = "PL-M-AZ", grupo = "g1", nombreGrupo = "Playera",
            opciones = listOf(op("Talla", " ", 0, 1), op("Color", "Azul", 1, 0)),
        )
        assertEquals(todasSueltas, formaEntradas(agruparCatalogo(valorEnBlanco, familiasDe(valorEnBlanco))))

        val nombreEnBlanco = catalogo().toMutableList()
        nombreEnBlanco[3] = prod(
            "pl-m-az", "Playera · M · Azul", 199.0, sku = "PL-M-AZ", grupo = "g1", nombreGrupo = "Playera",
            opciones = listOf(op(" ", "M", 0, 1), op("Color", "Azul", 1, 0)),
        )
        assertEquals(todasSueltas, formaEntradas(agruparCatalogo(nombreEnBlanco, familiasDe(nombreEnBlanco))))
    }

    @Test
    fun `A7 la familia se valida contra el catalogo completo aunque la lista traiga una sola variante`() {
        val completo = catalogo()
        val mostrada = listOf(por("pl-m-az", completo), por("gorra", completo))
        assertEquals(
            listOf("familia:g1[pl-s-az,pl-m-az,pl-s-ro]", "suelto:gorra"),
            formaEntradas(agruparCatalogo(mostrada, familiasDe(completo))),
        )
    }

    @Test
    fun `A8 un eje con el mismo nombre en dos posiciones deja sueltas sus variantes`() {
        // Misma firma de ejes (Talla, Color) en las dos, pero «Color» llega con namePosition 1 y 2: ejesDe daría Color dos veces.
        val c = listOf(
            prod("gx-1", "Camisa · S · Azul", 100.0, grupo = "gx", nombreGrupo = "Camisa",
                opciones = listOf(op("Talla", "S", 0, 0), op("Color", "Azul", 1, 0))),
            prod("gx-2", "Camisa · M · Azul", 100.0, grupo = "gx", nombreGrupo = "Camisa",
                opciones = listOf(op("Talla", "M", 0, 1), op("Color", "Azul", 2, 0))),
        )
        assertEquals(listOf("suelto:gx-1", "suelto:gx-2"), formaEntradas(agruparCatalogo(c, familiasDe(c))))
    }

    // ---- E · ejes y selección inicial ----

    @Test
    fun `E1 los ejes van por posicion y sus valores por posicion`() {
        val f = familiaG1()
        assertEquals(
            listOf(EjeDeFamilia("Talla", listOf("S", "M")), EjeDeFamilia("Color", listOf("Azul", "Rojo"))),
            f.ejes,
        )
        assertEquals(f.ejes, ejesDe(f.variantes))
    }

    @Test
    fun `E2 el eje de un solo valor viene elegido`() {
        val c = listOf(
            prod("t1", "Taza · Grande · Té", 80.0, grupo = "g2", nombreGrupo = "Taza",
                opciones = listOf(op("Tamaño", "Grande", 0, 0), op("Sabor", "Té", 1, 0))),
            prod("t2", "Taza · Grande · Café", 85.0, grupo = "g2", nombreGrupo = "Taza",
                opciones = listOf(op("Tamaño", "Grande", 0, 0), op("Sabor", "Café", 1, 1))),
        )
        val f = familiasDe(c).getValue("g2")
        assertEquals(
            listOf(EjeDeFamilia("Tamaño", listOf("Grande")), EjeDeFamilia("Sabor", listOf("Té", "Café"))),
            f.ejes,
        )
        assertEquals(mapOf("Tamaño" to "Grande"), seleccionInicial(f))
        assertEquals(emptyMap<String, String>(), seleccionInicial(familiaG1()))
    }

    // ---- S · selección ----

    @Test
    fun `S1 la eleccion completa da la variante`() {
        assertEquals("pl-s-ro", varianteElegida(familiaG1(), mapOf("Talla" to "S", "Color" to "Rojo"))?.id)
    }

    @Test
    fun `S2 una eleccion incompleta no da variante`() {
        assertNull(varianteElegida(familiaG1(), mapOf("Talla" to "M")))
    }

    @Test
    fun `S3 un valor que no combina con lo elegido no esta disponible`() {
        val f = familiaG1()
        assertFalse(valorDisponible(f, mapOf("Talla" to "M"), "Color", "Rojo"))
        assertTrue(valorDisponible(f, mapOf("Talla" to "M"), "Color", "Azul"))
        assertTrue(valorDisponible(f, mapOf("Talla" to "S"), "Color", "Rojo"))
    }

    @Test
    fun `S4 elegir un valor que no combina suelta la eleccion del otro eje`() {
        assertEquals(
            mapOf("Color" to "Rojo"),
            elegir(familiaG1(), mapOf("Talla" to "M", "Color" to "Azul"), "Color", "Rojo"),
        )
    }

    @Test
    fun `S5 elegir un valor que combina conserva lo ya elegido`() {
        assertEquals(
            mapOf("Talla" to "S", "Color" to "Rojo"),
            elegir(familiaG1(), mapOf("Talla" to "S"), "Color", "Rojo"),
        )
    }

    @Test
    fun `S6 con tres ejes elegir conserva en orden de eje lo que sigue combinando`() {
        // g3 = (S,Azul,Algodon), (S,Rojo,Poliester), (M,Azul,Poliester); ejes Talla(0), Color(1), Material(2).
        fun camiseta(id: String, talla: Pair<String, Int>, color: Pair<String, Int>, material: Pair<String, Int>) = prod(
            id, "Camiseta · ${talla.first} · ${color.first} · ${material.first}", 120.0, grupo = "g3", nombreGrupo = "Camiseta",
            opciones = listOf(
                op("Talla", talla.first, 0, talla.second),
                op("Color", color.first, 1, color.second),
                op("Material", material.first, 2, material.second),
            ),
        )
        val c = listOf(
            camiseta("c-s-az-al", "S" to 0, "Azul" to 0, "Algodón" to 0),
            camiseta("c-s-ro-po", "S" to 0, "Rojo" to 1, "Poliéster" to 1),
            camiseta("c-m-az-po", "M" to 1, "Azul" to 0, "Poliéster" to 1),
        )
        val g3 = familiasDe(c).getValue("g3")
        // S·Azul·Poliéster no existe, pero S·Poliéster sí: Talla:S se conserva y sólo Color:Azul se suelta.
        assertEquals(
            mapOf("Material" to "Poliéster", "Talla" to "S"),
            elegir(g3, mapOf("Talla" to "S", "Color" to "Azul"), "Material", "Poliéster"),
        )
    }

    // ---- P · precio ----

    @Test
    fun `P1 el precio es el minimo y no todos son iguales`() {
        assertEquals(PrecioDeFamilia(minimo = 199.0, todosIguales = false), precioDeFamilia(familiaG1()))
    }

    @Test
    fun `P2 con todos los precios iguales el boton dice el precio solo`() {
        val c = listOf(
            playera("a", "S" to 0, "Azul" to 0, 199.0, "A"),
            playera("b", "M" to 1, "Azul" to 0, 199.0, "B"),
        )
        assertEquals(PrecioDeFamilia(minimo = 199.0, todosIguales = true), precioDeFamilia(familiasDe(c).getValue("g1")))
    }

    // ---- Texto de precio del botón de la familia (Familia.textoDePrecio) ----

    private fun familiaDePrecios(vararg precios: Pair<Double, Boolean>): Familia {
        val c = precios.mapIndexed { i, (precio, porPeso) ->
            prod("v$i", "Corte $i", precio, grupo = "g4", nombreGrupo = "Corte", opciones = listOf(op("Corte", "C$i", 0, i)), porPeso = porPeso)
        }
        return familiasDe(c).getValue("g4")
    }

    @Test
    fun `la familia con precios distintos dice desde el minimo`() {
        assertEquals("desde \$199.00", familiaG1().textoDePrecio)
    }

    @Test
    fun `la familia con todos los precios iguales dice el precio solo`() {
        assertEquals("\$199.00", familiaDePrecios(199.0 to false, 199.0 to false).textoDePrecio)
    }

    @Test
    fun `la familia toda por peso con el mismo precio dice el precio por kilo`() {
        assertEquals("\$180.00/kg", familiaDePrecios(180.0 to true, 180.0 to true).textoDePrecio)
    }

    @Test
    fun `la familia toda por peso con precios distintos dice desde el minimo por kilo`() {
        assertEquals("desde \$180.00/kg", familiaDePrecios(220.0 to true, 180.0 to true).textoDePrecio)
    }

    @Test
    fun `la familia con una variante por peso y otra por pieza no lleva por kilo`() {
        assertEquals("desde \$50.00", familiaDePrecios(180.0 to true, 50.0 to false).textoDePrecio)
    }

    // ---- G · agotada ----

    @Test
    fun `G1 la familia solo esta agotada si todas sus variantes lo estan`() {
        assertFalse(agotada(familiaG1()))
        val todas = listOf(
            playera("a", "S" to 0, "Azul" to 0, 199.0, "A", agotado = true),
            playera("b", "M" to 1, "Azul" to 0, 199.0, "B", agotado = true),
        )
        assertTrue(agotada(familiasDe(todas).getValue("g1")))
    }

    // ---- B · búsqueda ----

    @Test
    fun `B1 por nombre sale la familia una sola vez`() {
        assertEquals(listOf("familia:g1"), formaResultados(buscar(catalogo(), "play")))
    }

    @Test
    fun `B2 por sku sale la variante y no la familia`() {
        assertEquals(listOf("producto:pl-s-az", "producto:pl-s-ro"), formaResultados(buscar(catalogo(), "PL-S")))
    }

    @Test
    fun `B3 por gtin sale la variante`() {
        assertEquals(listOf("producto:pl-s-ro"), formaResultados(buscar(catalogo(), "7501000000017")))
    }

    @Test
    fun `B4 un suelto sigue saliendo como producto`() {
        assertEquals(listOf("producto:gorra"), formaResultados(buscar(catalogo(), "gor")))
    }

    @Test
    fun `B5 un texto en blanco no busca nada`() {
        assertEquals(emptyList<String>(), formaResultados(buscar(catalogo(), "  ")))
        assertEquals(emptyList<String>(), formaResultados(buscar(catalogo(), "")))
    }

    @Test
    fun `B6 sin familias la busqueda es la de hoy`() {
        val c = catalogo()
        assertEquals(listOf("producto:gorra"), formaResultados(buscar(listOf(por("agua", c), por("gorra", c)), "go-1")))
    }

    @Test
    fun `B8 por barcode sale la variante`() {
        val c = catalogo().toMutableList().also { it[1] = it[1].copy(barcode = "BARRA-0042") }
        assertEquals(listOf("producto:pl-s-az"), formaResultados(buscar(c, "barra-0042")))
    }

    /** Sólo Android: hoy no recorta espacios al buscar (Codex 8); iOS sí y lo prueba por su lado. */
    @Test
    fun `B7 los espacios de los lados no se recortan como hoy`() {
        val c = catalogo()
        assertEquals(emptyList<String>(), formaResultados(buscar(listOf(por("agua", c), por("gorra", c)), " gor ")))
    }

    // ---- Más allá de la tabla ----

    @Test
    fun `el nombre de la familia tambien encuentra su familia aunque la variante no lo lleve`() {
        val c = listOf(
            prod("a", "Chica azul", 199.0, grupo = "g1", nombreGrupo = "Playera", opciones = listOf(op("Talla", "S", 0, 0))),
            prod("b", "Mediana azul", 199.0, grupo = "g1", nombreGrupo = "Playera", opciones = listOf(op("Talla", "M", 0, 1))),
        )
        assertEquals(listOf("familia:g1"), formaResultados(buscar(c, "playera")))
    }

    @Test
    fun `una familia invalida se busca como sus variantes sueltas`() {
        val c = listOf(playera("solo", "S" to 0, "Azul" to 0, 199.0, "PL-S-AZ"))
        assertTrue(familiasDe(c).isEmpty())
        assertEquals(listOf("producto:solo"), formaResultados(buscar(c, "play")))
    }

    @Test
    fun `familiasDe no cuenta una familia de una sola variante`() {
        assertEquals(setOf("g1"), familiasDe(catalogo()).keys)
    }

    @Test
    fun `la imagen de la familia es la del grupo o la de la primera variante con imagen`() {
        val a = prod("a", "Playera · S", 199.0, grupo = "g1", nombreGrupo = "Playera", opciones = listOf(op("Talla", "S", 0, 0)), imagen = "https://x/a.jpg")
        val b = prod("b", "Playera · M", 199.0, grupo = "g1", nombreGrupo = "Playera", opciones = listOf(op("Talla", "M", 0, 1)))
        assertEquals("https://x/a.jpg", familiasDe(listOf(a, b)).getValue("g1").imagenUrl)

        val c = b.copy(productGroupImageUrl = "https://x/grupo.jpg")
        assertEquals("https://x/grupo.jpg", familiasDe(listOf(a, c)).getValue("g1").imagenUrl)
    }

    @Test
    fun `el empate de valuePosition conserva el orden de aparicion`() {
        val c = listOf("Chica", "Mediana", "Grande").mapIndexed { i, t ->
            prod("p$i", "Playera · $t", 1.0, grupo = "g1", nombreGrupo = "Playera", opciones = listOf(op("Talla", t, 0, 0)))
        }
        assertEquals(listOf(EjeDeFamilia("Talla", listOf("Chica", "Mediana", "Grande"))), ejesDe(c))
    }

    @Test
    fun `valoresDe arma el mapa eje a valor de una variante`() {
        assertEquals(mapOf("Talla" to "S", "Color" to "Rojo"), valoresDe(por("pl-s-ro", catalogo())))
        assertEquals(emptyMap<String, String>(), valoresDe(por("agua", catalogo())))
    }

    /**
     * Sanidad del O(n) (Codex 6), no medición del aparato: 5,000 variantes en 2,500 familias se validan, se acomodan en la lista y
     * se buscan en una fracción de segundo. El tope (3 s, con holgura para una Mac cargada) vigila contra regresiones grandes: en la
     * JVM el recorrido ingenuo por grupo (O(n·g)) tarda ~70 ms, así que esta prueba NO distingue O(n) de O(n·g).
     */
    @Test
    fun `escala cinco mil variantes en dos mil quinientas familias en menos de tres segundos`() {
        val c = (0 until 2500).flatMap { g ->
            (0 until 2).map { v ->
                prod("p$g-$v", "Familia $g · $v", 10.0, sku = "SKU-$g-$v", grupo = "g$g", nombreGrupo = "Familia $g",
                    opciones = listOf(op("Talla", "T$v", 0, v)))
            }
        }
        val t0 = System.nanoTime()
        val familias = familiasDe(c)
        val lista = agruparCatalogo(c, familias)
        val hallados = buscarEnCatalogo(c, familias, "familia 1249")
        val segundos = (System.nanoTime() - t0) / 1e9
        assertEquals(2500, familias.size)
        assertEquals(2500, lista.size)
        assertEquals(listOf("familia:g1249"), formaResultados(hallados))
        assertTrue("tardó $segundos s", segundos < 3.0)
        assertNotNull(familias["g0"])
    }
}
