package com.avoqado.pos.inventory

import com.avoqado.pos.inventory.data.ConteoEnCurso
import com.avoqado.pos.inventory.data.ConteoNoAplicado
import com.avoqado.pos.inventory.data.MotivoNoAplicado
import com.avoqado.pos.inventory.data.NoAplicadoDelConfirm
import com.avoqado.pos.inventory.data.RespuestaHttp
import com.avoqado.pos.inventory.data.model.StockCount
import com.avoqado.pos.inventory.data.model.StockCountItem
import com.avoqado.pos.inventory.data.model.StockCountSummary
import com.avoqado.pos.inventory.data.model.StockCountsResponse
import com.avoqado.pos.printing.data.ComprobanteDeConteo
import com.avoqado.pos.printing.data.ESCPOSPrinter
import com.avoqado.pos.printing.data.model.PaperWidth
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C12 del conector Shopify: el servidor NO aplica la línea de un producto con un envío a Shopify en camino o
 * con una duda abierta, y lo dice de dos maneras — `noAplicados` en la respuesta del confirm y `shopifyHeld` en
 * cada línea del GET. Hasta hoy las dos apps enseñaban esas líneas como aplicadas.
 *
 * Lo que no puede fallar: que un servidor viejo (sin los campos) siga decodificando, que un motivo que esta
 * versión no conoce caiga en el conservador («resuélvelo primero»), y que un reintento del confirm (que el
 * servidor contesta SIN `noAplicados`) no se lea como «todo se aplicó».
 */
class ConteoNoAplicadoTest {

    /** El MISMO Json que usa el repositorio para leer la lista de conteos. */
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private fun item(raw: String): StockCountItem = json.decodeFromString(StockCountItem.serializer(), raw)

    private fun linea(
        id: String,
        productId: String = "p-$id",
        nombre: String = "Prod $id",
        contada: Boolean = true,
        rawMaterialId: String? = null,
    ) = StockCountItem(
        id = id,
        productId = productId,
        productName = nombre,
        expected = 10.0,
        counted = 8.0,
        countedAt = if (contada) "2026-10-09T15:00:00Z" else null,
        rawMaterialId = rawMaterialId,
        itemType = if (rawMaterialId != null) "RAW_MATERIAL" else "PRODUCT",
    )

    // MARK: - Los textos (los mismos en iOS, palabra por palabra)

    @Test
    fun `los textos son los del brief, con acentos`() {
        assertEquals("No se aplicó", ConteoNoAplicado.ETIQUETA)
        assertEquals(
            "Había un envío a Shopify en camino: vuelve a contar este producto en unos minutos.",
            MotivoNoAplicado.ENVIO_EN_CAMINO.texto,
        )
        assertEquals(
            "Hay una diferencia con Shopify por revisar: pídele al dueño que la resuelva en el dashboard (Integraciones → Shopify → Por revisar).",
            MotivoNoAplicado.DUDA_POR_REVISAR.texto,
        )
        assertEquals("1 producto no se aplicó", ConteoNoAplicado.titulo(1))
        assertEquals("3 productos no se aplicaron", ConteoNoAplicado.titulo(3))
        assertEquals("El resto del conteo sí se aplicó.", ConteoNoAplicado.RESTO_APLICADO)
    }

    @Test
    fun `el motivo se lee tolerante - lo desconocido cae en la duda`() {
        assertEquals(MotivoNoAplicado.ENVIO_EN_CAMINO, MotivoNoAplicado.desde("ENVIO_EN_CAMINO"))
        assertEquals(MotivoNoAplicado.DUDA_POR_REVISAR, MotivoNoAplicado.desde("DUDA_POR_REVISAR"))
        assertEquals(MotivoNoAplicado.DUDA_POR_REVISAR, MotivoNoAplicado.desde("UN_MOTIVO_NUEVO"))
        assertEquals(MotivoNoAplicado.DUDA_POR_REVISAR, MotivoNoAplicado.desde(""))
        assertEquals(MotivoNoAplicado.DUDA_POR_REVISAR, MotivoNoAplicado.desde(null))
    }

    // MARK: - shopifyHeld en la línea del GET

    @Test
    fun `una linea retenida por un envio en camino se lee con su motivo`() {
        val l = item("""{"id":"i1","productId":"p1","productName":"Café","shopifyHeld":{"at":"2026-10-09T15:00:00.000Z","motivo":"ENVIO_EN_CAMINO"}}""")
        assertTrue(l.noSeAplico)
        assertEquals(MotivoNoAplicado.ENVIO_EN_CAMINO, l.motivoNoAplicado)
    }

    @Test
    fun `una linea sin retener (null o sin el campo) se aplico`() {
        val conNull = item("""{"id":"i1","productId":"p1","productName":"Café","shopifyHeld":null}""")
        assertFalse(conNull.noSeAplico)
        assertNull(conNull.motivoNoAplicado)
        // Servidor anterior a C9b: el campo no viene.
        val viejo = item("""{"id":"i1","productId":"p1","productName":"Café","expected":3,"counted":2}""")
        assertFalse(viejo.noSeAplico)
        assertNull(viejo.motivoNoAplicado)
    }

    @Test
    fun `un motivo desconocido, ausente o que no es texto se lee como la duda, nunca como aplicada`() {
        val desconocido = item("""{"id":"i1","shopifyHeld":{"at":"2026-10-09T15:00:00.000Z","motivo":"OTRA_COSA"}}""")
        assertEquals(MotivoNoAplicado.DUDA_POR_REVISAR, desconocido.motivoNoAplicado)
        val sinMotivo = item("""{"id":"i1","shopifyHeld":{"at":"2026-10-09T15:00:00.000Z"}}""")
        assertEquals(MotivoNoAplicado.DUDA_POR_REVISAR, sinMotivo.motivoNoAplicado)
        val numero = item("""{"id":"i1","shopifyHeld":{"at":"2026-10-09T15:00:00.000Z","motivo":7}}""")
        assertEquals(MotivoNoAplicado.DUDA_POR_REVISAR, numero.motivoNoAplicado)
        // Una forma que esta versión no espera (no es objeto) sigue diciendo «retenida».
        val raro = item("""{"id":"i1","shopifyHeld":true}""")
        assertEquals(MotivoNoAplicado.DUDA_POR_REVISAR, raro.motivoNoAplicado)
    }

    @Test
    fun `una linea con una forma rara no tumba la lista entera de conteos`() {
        val body = """{"success":true,"counts":[{"id":"c1","status":"COMPLETED","items":[
            {"id":"i1","productId":"p1","productName":"Café","shopifyHeld":{"motivo":42}},
            {"id":"i2","productId":"p2","productName":"Té","shopifyHeld":"x"},
            {"id":"i3","productId":"p3","productName":"Agua"}]}]}"""
        val r = json.decodeFromString(StockCountsResponse.serializer(), body)
        assertEquals(listOf(true, true, false), r.counts.single().items.map { it.noSeAplico })
    }

    @Test
    fun `el borrador en disco sigue leyendose con el campo nuevo`() {
        val conRetencion = item("""{"id":"i1","productId":"p1","productName":"Café","shopifyHeld":{"at":"t","motivo":"ENVIO_EN_CAMINO"}}""")
        val ida = json.encodeToString(StockCountItem.serializer(), conRetencion)
        assertEquals(MotivoNoAplicado.ENVIO_EN_CAMINO, item(ida).motivoNoAplicado)
        val sinRetencion = linea("a")
        assertFalse(json.encodeToString(StockCountItem.serializer(), sinRetencion).contains("shopifyHeld"))
    }

    // MARK: - noAplicados en la respuesta del confirm

    @Test
    fun `noAplicados del confirm se decodifica con su motivo`() {
        val body = """{"success":true,"revision":6,"noAplicados":[{"productId":"p1","motivo":"ENVIO_EN_CAMINO"},{"productId":"p2","motivo":"DUDA_POR_REVISAR"}]}"""
        assertEquals(
            listOf(
                NoAplicadoDelConfirm("p1", MotivoNoAplicado.ENVIO_EN_CAMINO),
                NoAplicadoDelConfirm("p2", MotivoNoAplicado.DUDA_POR_REVISAR),
            ),
            RespuestaHttp(200, body).noAplicados,
        )
    }

    @Test
    fun `sin noAplicados (servidor viejo, todo aplicado o reintento) es lista vacia`() {
        assertEquals(emptyList<NoAplicadoDelConfirm>(), RespuestaHttp(200, """{"success":true,"revision":6}""").noAplicados)
        assertEquals(emptyList<NoAplicadoDelConfirm>(), RespuestaHttp(200, "").noAplicados)
        assertEquals(emptyList<NoAplicadoDelConfirm>(), RespuestaHttp(0, "timeout").noAplicados)
        assertEquals(emptyList<NoAplicadoDelConfirm>(), RespuestaHttp(200, """{"noAplicados":"x"}""").noAplicados)
    }

    @Test
    fun `noAplicados tolera motivos desconocidos y elementos que no son objetos`() {
        val body = """{"noAplicados":[{"productId":"p1","motivo":"NUEVO"},"basura",{"productId":"p2","motivo":3},{"productId":"p3"}]}"""
        assertEquals(
            listOf(
                NoAplicadoDelConfirm("p1", MotivoNoAplicado.DUDA_POR_REVISAR),
                NoAplicadoDelConfirm("p2", MotivoNoAplicado.DUDA_POR_REVISAR),
                NoAplicadoDelConfirm("p3", MotivoNoAplicado.DUDA_POR_REVISAR),
            ),
            RespuestaHttp(200, body).noAplicados,
        )
    }

    // MARK: - El estado de la pantalla de resultado

    @Test
    fun `del confirm - nombra cada producto con la linea local y dice que lo demas si se aplico`() {
        val locales = listOf(linea("a", "p1", "Café de olla"), linea("b", "p2", "Té"), linea("c", "p3", "Agua"))
        val r = ConteoNoAplicado.desdeConfirm(
            venueId = "v",
            countId = "c1",
            noAplicados = listOf(NoAplicadoDelConfirm("p1", MotivoNoAplicado.ENVIO_EN_CAMINO)),
            lineasLocales = locales,
        )!!
        assertEquals("1 producto no se aplicó", r.titulo)
        assertEquals(listOf("Café de olla"), r.lineas.map { it.nombre })
        assertEquals(MotivoNoAplicado.ENVIO_EN_CAMINO, r.lineas.single().motivo)
        assertTrue(r.hayRestoAplicado)
    }

    @Test
    fun `del confirm - sin noAplicados no hay pantalla de resultado`() {
        assertNull(ConteoNoAplicado.desdeConfirm("v", "c1", emptyList(), listOf(linea("a"))))
    }

    @Test
    fun `del confirm - un producto que no esta en las lineas locales se nombra igual, nunca se pierde`() {
        val r = ConteoNoAplicado.desdeConfirm("v", "c1", listOf(NoAplicadoDelConfirm("p9", MotivoNoAplicado.DUDA_POR_REVISAR)), listOf(linea("a", "p1")))!!
        assertEquals(1, r.lineas.size)
        assertEquals(ConteoNoAplicado.PRODUCTO_SIN_NOMBRE, r.lineas.single().nombre)
    }

    @Test
    fun `del confirm - un insumo con el mismo id no presta su nombre`() {
        val locales = listOf(linea("rm", productId = "x1", nombre = "Leche (insumo)", rawMaterialId = "x1"), linea("a", "x1", "Leche en caja"))
        val r = ConteoNoAplicado.desdeConfirm("v", "c1", listOf(NoAplicadoDelConfirm("x1", MotivoNoAplicado.ENVIO_EN_CAMINO)), locales)!!
        assertEquals("Leche en caja", r.lineas.single().nombre)
    }

    @Test
    fun `si TODO lo contado quedo retenido no se dice que el resto se aplico`() {
        val r = ConteoNoAplicado.desdeConfirm("v", "c1", listOf(NoAplicadoDelConfirm("p-a", MotivoNoAplicado.ENVIO_EN_CAMINO)), listOf(linea("a"), linea("b", contada = false)))!!
        assertFalse(r.hayRestoAplicado)
    }

    @Test
    fun `del GET - las lineas con shopifyHeld forman el resultado, es la verdad tras un reintento`() {
        val conteo = StockCount(
            id = "c1",
            status = "COMPLETED",
            items = listOf(
                item("""{"id":"i1","productId":"p1","productName":"Café","countedAt":"t","shopifyHeld":{"at":"t","motivo":"DUDA_POR_REVISAR"}}"""),
                linea("b", "p2", "Té"),
            ),
        )
        val r = ConteoNoAplicado.desdeConteo("v", conteo)!!
        assertEquals(listOf("Café"), r.lineas.map { it.nombre })
        assertEquals(MotivoNoAplicado.DUDA_POR_REVISAR, r.lineas.single().motivo)
        assertTrue(r.hayRestoAplicado)
        assertNull(ConteoNoAplicado.desdeConteo("v", conteo.copy(items = listOf(linea("b")))))
    }

    @Test
    fun `reintento - el confirm no trae noAplicados pero el GET si, y gana el GET`() {
        val delConfirm = ConteoNoAplicado.desdeConfirm("v", "c1", emptyList(), listOf(linea("a", "p1", "Café")))
        val conteo = StockCount(
            id = "c1",
            status = "COMPLETED",
            items = listOf(item("""{"id":"a","productId":"p1","productName":"Café","countedAt":"t","shopifyHeld":{"at":"t","motivo":"ENVIO_EN_CAMINO"}}""")),
        )
        val r = ConteoNoAplicado.combinar(delConfirm, ConteoNoAplicado.desdeConteo("v", conteo))!!
        assertEquals(listOf("p1"), r.lineas.map { it.productId })
    }

    @Test
    fun `combinar - el GET que falla no borra lo que dijo el confirm, y no se duplica un producto`() {
        val delConfirm = ConteoNoAplicado.desdeConfirm(
            "v",
            "c1",
            listOf(NoAplicadoDelConfirm("p1", MotivoNoAplicado.ENVIO_EN_CAMINO), NoAplicadoDelConfirm("p2", MotivoNoAplicado.ENVIO_EN_CAMINO)),
            listOf(linea("a", "p1", "Café"), linea("b", "p2", "Té"), linea("c", "p3", "Agua")),
        )
        assertEquals(delConfirm, ConteoNoAplicado.combinar(delConfirm, null))
        val delGet = ConteoNoAplicado.desdeConteo(
            "v",
            StockCount(id = "c1", status = "COMPLETED", items = listOf(item("""{"id":"a","productId":"p1","productName":"Café","countedAt":"t","shopifyHeld":{"motivo":"DUDA_POR_REVISAR"}}"""))),
        )
        val r = ConteoNoAplicado.combinar(delConfirm, delGet)!!
        assertEquals(listOf("p1", "p2"), r.lineas.map { it.productId })
        // En el producto que dicen los dos, gana el motivo del GET (lo que el servidor guardó).
        assertEquals(MotivoNoAplicado.DUDA_POR_REVISAR, r.lineas.first().motivo)
        assertNull(ConteoNoAplicado.combinar(null, null))
    }

    // MARK: - El historial (lista y comprobante impreso)

    @Test
    fun `la fila de la lista de un conteo con lineas no aplicadas lo dice, el resto no cambia`() {
        val retenida = item("""{"id":"i1","productId":"p1","countedAt":"t","shopifyHeld":{"motivo":"ENVIO_EN_CAMINO"}}""")
        val completo = StockCount(id = "c3", status = "COMPLETED", itemCount = 5, summary = StockCountSummary(itemCount = 5, countedCount = 5), items = listOf(retenida, linea("b")))
        assertEquals("Completado - 5 artículos · 1 no se aplicó", ConteoEnCurso.lineaDeEstado(completo))
        val dos = completo.copy(items = listOf(retenida, retenida.copy(id = "i2")))
        assertEquals("Completado - 5 artículos · 2 no se aplicaron", ConteoEnCurso.lineaDeEstado(dos))
        assertEquals("Completado - 5 artículos", ConteoEnCurso.lineaDeEstado(completo.copy(items = listOf(linea("b")))))
    }

    @Test
    fun `el comprobante impreso marca la linea que no se aplico y las cuenta`() {
        val retenida = item("""{"id":"i1","productId":"p1","productName":"Cafe","expected":10,"counted":8,"countedAt":"t","shopifyHeld":{"motivo":"ENVIO_EN_CAMINO"}}""")
        val conteo = StockCount(id = "cmtabc12345678xyz", status = "COMPLETED", items = listOf(retenida, linea("b", nombre = "Te")))
        val c = ComprobanteDeConteo.desde(conteo, negocio = "Testarudo", fecha = "09/10/2026 09:30")
        assertEquals(listOf(true, false), c.renglones.map { it.noAplicado })
        val t = ESCPOSPrinter(PaperWidth.MM58).generateCountReceipt(c).toString(Charsets.ISO_8859_1)
        assertTrue(t.contains("No se aplic"))
        assertTrue(t.contains("No se aplicaron: 1"))
        // Sin retenidas el papel es el de siempre.
        val limpio = ComprobanteDeConteo.desde(conteo.copy(items = listOf(linea("b"))), negocio = null, fecha = "x")
        assertFalse(ESCPOSPrinter(PaperWidth.MM58).generateCountReceipt(limpio).toString(Charsets.ISO_8859_1).contains("No se aplic"))
    }

    // MARK: - Fix round 1 (M1): releer el GET que falla no se lee como «todo se aplicó»

    @Test
    fun `el texto de no comprobado es el mismo de iOS`() {
        assertEquals(
            "No se pudo comprobar si todo se aplicó; revisa este conteo en el historial cuando vuelva la red.",
            ConteoNoAplicado.SIN_COMPROBAR,
        )
    }

    @Test
    fun `tras releer - sin noAplicados y el GET falla, no se puede decir que todo se aplico`() {
        val t = ConteoNoAplicado.trasReleer("v", "c1", delConfirm = null, releyo = false, conteos = emptyList())
        assertNull(t.resultado)
        assertTrue(t.sinComprobar)
    }

    @Test
    fun `tras releer - con noAplicados y el GET falla, se queda lo del confirm y no hace falta el aviso`() {
        val delConfirm = ConteoNoAplicado.desdeConfirm("v", "c1", listOf(NoAplicadoDelConfirm("p-a", MotivoNoAplicado.ENVIO_EN_CAMINO)), listOf(linea("a")))
        val t = ConteoNoAplicado.trasReleer("v", "c1", delConfirm, releyo = false, conteos = emptyList())
        assertEquals(delConfirm, t.resultado)
        assertFalse(t.sinComprobar)
    }

    @Test
    fun `tras releer - el GET trae el conteo cerrado y manda`() {
        val retenida = item("""{"id":"a","productId":"p1","productName":"Café","countedAt":"t","shopifyHeld":{"motivo":"DUDA_POR_REVISAR"}}""")
        val conteos = listOf(StockCount(id = "otro", status = "COMPLETED"), StockCount(id = "c1", status = "COMPLETED", items = listOf(retenida)))
        val t = ConteoNoAplicado.trasReleer("v", "c1", delConfirm = null, releyo = true, conteos = conteos)
        assertEquals(listOf("p1"), t.resultado!!.lineas.map { it.productId })
        assertFalse(t.sinComprobar)
        // Todo aplicado de verdad: el GET lo trae sin retenidas.
        val limpio = ConteoNoAplicado.trasReleer("v", "c1", null, releyo = true, conteos = listOf(StockCount(id = "c1", status = "COMPLETED", items = listOf(linea("b")))))
        assertNull(limpio.resultado)
        assertFalse(limpio.sinComprobar)
    }

    @Test
    fun `tras releer - el GET contesto pero sin el conteo tampoco comprueba nada`() {
        val t = ConteoNoAplicado.trasReleer("v", "c1", delConfirm = null, releyo = true, conteos = listOf(StockCount(id = "otro", status = "COMPLETED")))
        assertNull(t.resultado)
        assertTrue(t.sinComprobar)
    }
}
