package com.avoqado.pos.inventory

import com.avoqado.pos.inventory.data.BorradorDeConteo
import com.avoqado.pos.inventory.data.ConteoEnCurso
import com.avoqado.pos.inventory.data.DestinoDeLaCancelacion
import com.avoqado.pos.inventory.data.DestinoDelEnvio
import com.avoqado.pos.inventory.data.model.StockCount
import com.avoqado.pos.inventory.data.model.StockCountItem
import com.avoqado.pos.inventory.data.model.StockCountSummary
import com.avoqado.pos.inventory.data.model.StockCountType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConteoEnCursoTest {

    private fun linea(id: String, expected: Double = 10.0, counted: Double = 0.0, countedAt: String? = null) =
        StockCountItem(id = id, productId = "p-$id", productName = "Prod $id", expected = expected, counted = counted, difference = counted - expected, countedAt = countedAt)

    @Test
    fun `P1 fusionar - lo contado en el aparato gana sobre el servidor y lo del servidor se conserva`() {
        val servidor = listOf(linea("a"), linea("b", counted = 7.0, countedAt = "2026-09-07T10:00:00Z"), linea("c"))
        // 🔴 El borrador trae un `expected` RANCIO (4.0) distinto del que manda el servidor (10.0).
        // Con los dos en 10.0, `l.counted - s.expected`, `l.difference` y `l.counted - l.expected`
        // daban los tres lo mismo y la aserción no distinguía cuál está implementada.
        val local = listOf(linea("a", expected = 4.0, counted = 3.0, countedAt = "2026-09-07T11:00:00Z"), linea("zz", counted = 1.0, countedAt = "2026-09-07T11:00:00Z"))
        val r = ConteoEnCurso.fusionar(servidor, local)
        assertEquals(listOf("a", "b", "c"), r.map { it.id })          // la línea local desconocida se descarta
        assertEquals(3.0, r[0].counted, 0.0); assertTrue(r[0].yaSeConto) // local gana
        assertEquals(7.0, r[1].counted, 0.0); assertTrue(r[1].yaSeConto) // servidor (otro aparato) se conserva
        assertEquals(false, r[2].yaSeConto)
        // La diferencia se RECALCULA contra el `expected` del SERVIDOR, que es la verdad de la
        // existencia esperada; el del borrador no se usa ni se conserva.
        assertEquals(3.0 - 10.0, r[0].difference, 0.0)
        assertEquals(10.0, r[0].expected, 0.0)
    }

    @Test
    fun `P1 fusionar - una linea YA reconocida por el servidor cede ante el valor mas nuevo`() {
        // A cuenta 5 y recibe 200 (la línea deja de estar pendiente). B la corrige a 8. A retoma:
        // ganando siempre lo local, el 5 resucitaba y al confirmar se sellaba — la corrección de
        // B desaparecía sin un solo aviso. Lo ya subido deja de ser «mío».
        val servidor = listOf(linea("a", counted = 8.0, countedAt = "2026-09-08T12:00:00Z"))
        val local = listOf(linea("a", counted = 5.0, countedAt = "2026-09-08T11:00:00Z"))

        val reconocida = ConteoEnCurso.fusionar(servidor, local, pendientes = emptySet())
        assertEquals(8.0, reconocida[0].counted, 0.0)
        assertEquals("2026-09-08T12:00:00Z", reconocida[0].countedAt)

        // Y lo que TODAVÍA no ha salido de este aparato sigue ganando: es lo único que el
        // servidor no puede conocer.
        val pendiente = ConteoEnCurso.fusionar(servidor, local, pendientes = setOf("a"))
        assertEquals(5.0, pendiente[0].counted, 0.0)
        assertEquals(5.0 - 10.0, pendiente[0].difference, 0.0)
    }

    @Test
    fun `fusionar sin borrador local devuelve el servidor tal cual`() {
        val servidor = listOf(linea("a"), linea("b"))
        assertEquals(servidor, ConteoEnCurso.fusionar(servidor, null))
    }

    @Test
    fun `primerPendiente elige la primera linea sin contar y cae a 0 si todas estan contadas`() {
        assertEquals(1, ConteoEnCurso.primerPendiente(listOf(linea("a", countedAt = "x"), linea("b"), linea("c"))))
        assertEquals(0, ConteoEnCurso.primerPendiente(listOf(linea("a", countedAt = "x"))))
        assertEquals(-1, ConteoEnCurso.primerPendiente(emptyList()))
    }

    @Test
    fun `P1 lineasParaEnviar manda solo pendientes contadas y nunca ids vacios`() {
        val lineas = listOf(linea("a", counted = 2.0, countedAt = "x"), linea("b"), linea("", counted = 4.0, countedAt = "x"), linea("d", counted = 1.0, countedAt = "x"))
        val r = ConteoEnCurso.lineasParaEnviar(lineas, setOf("a", "b", ""))
        assertEquals(listOf("a"), r.map { it.id })
    }

    @Test
    fun `clasificarEnvio - 2xx enviado, 404 conflicto, rechazos explicitos, lo demas se reintenta`() {
        assertEquals(DestinoDelEnvio.ENVIADO, ConteoEnCurso.clasificarEnvio(200))
        assertEquals(DestinoDelEnvio.CONFLICTO, ConteoEnCurso.clasificarEnvio(404))
        for (c in listOf(400, 403, 409, 422)) assertEquals("$c", DestinoDelEnvio.RECHAZADO, ConteoEnCurso.clasificarEnvio(c))
        for (c in listOf(0, 401, 408, 429, 500, 503, 418)) assertEquals("$c", DestinoDelEnvio.REINTENTAR, ConteoEnCurso.clasificarEnvio(c))
    }

    @Test
    fun `clasificarCancelacion - el estado del servidor gana en 404 de dominio y en 409`() {
        val dominio = """{"message":"Conteo no encontrado"}"""
        assertEquals(DestinoDeLaCancelacion.HECHA, ConteoEnCurso.clasificarCancelacion(200, "{}"))
        assertEquals(DestinoDeLaCancelacion.HECHA, ConteoEnCurso.clasificarCancelacion(404, dominio))
        assertEquals(DestinoDeLaCancelacion.HECHA, ConteoEnCurso.clasificarCancelacion(409, """{"message":"Este conteo ya estaba cancelado"}"""))
        for (c in listOf(400, 403, 422)) assertEquals("$c", DestinoDeLaCancelacion.RECHAZADA, ConteoEnCurso.clasificarCancelacion(c, "{}"))
        for (c in listOf(0, 401, 500)) assertEquals("$c", DestinoDeLaCancelacion.REINTENTAR, ConteoEnCurso.clasificarCancelacion(c, "{}"))
    }

    @Test
    fun `P1 un 404 de RUTA no consume la cancelacion - la cola espera al despliegue`() {
        // 🔴 App nueva contra un servidor sin la fase 1: `POST …/cancel` no existe todavía y
        // Express contesta su HTML por defecto (`avoqado-server/src/app.ts` no monta ningún
        // manejador de 404). Tratarlo como «hecha» sacaba la cancelación de la cola sin haber
        // cancelado nada: ese conteo se quedaba IN_PROGRESS para siempre y nadie volvía a
        // intentarlo. Con esto, el ORDEN DE DESPLIEGUE deja de ser un riesgo silencioso.
        val ruta = "<!DOCTYPE html>\n<html><head><title>Error</title></head>" +
            "<body><pre>Cannot POST /api/v1/mobile/venues/v1/inventory/stock-counts/c1/cancel</pre></body></html>"
        assertEquals(DestinoDeLaCancelacion.REINTENTAR, ConteoEnCurso.clasificarCancelacion(404, ruta))
        // Un proxy o el edge de un túnel tampoco hablan de conteos.
        assertEquals(DestinoDeLaCancelacion.REINTENTAR, ConteoEnCurso.clasificarCancelacion(404, "404 page not found"))
        assertEquals(DestinoDeLaCancelacion.REINTENTAR, ConteoEnCurso.clasificarCancelacion(404, ""))

        // Control positivo: los DOS cuerpos que el servidor sí escribe sobre este dominio
        // (`inventory.mobile.service.ts`) siguen siendo terminales — si no, la cola nunca se
        // vaciaría y el aviso quedaría encendido para siempre.
        assertEquals(DestinoDeLaCancelacion.HECHA, ConteoEnCurso.clasificarCancelacion(404, """{"message":"Conteo no encontrado"}"""))
        assertEquals(
            DestinoDeLaCancelacion.HECHA,
            ConteoEnCurso.clasificarCancelacion(404, """{"message":"Conteo no encontrado o ya completado"}"""),
        )
    }

    @Test
    fun `el borrador sobrevive un viaje por JSON`() {
        val b = BorradorDeConteo(
            venueId = "v1", countId = null, type = StockCountType.CYCLE,
            lineas = listOf(linea("uuid-1", counted = 2.5, countedAt = "2026-09-07T11:00:00Z")),
            nota = "estante 3", notaPendienteDeEnviar = true,
            pendientesDeEnviar = setOf("uuid-1"), actualizadoEn = 1_700_000_000_000,
        )
        val vuelta = ConteoEnCurso.decodificar(ConteoEnCurso.codificar(b))
        assertEquals(b, vuelta)
        assertNull(ConteoEnCurso.decodificar("esto no es json"))
    }

    @Test
    fun `P1 un borrador ciclico sin countId sobrevive el viaje por JSON`() {
        // Un CÍCLICO todavía sin crear en el servidor no tiene `countId`. Es el borrador que
        // más fácil se pierde: nadie más sabe que existe.
        val b = BorradorDeConteo(
            venueId = "v1", countId = null, type = StockCountType.CYCLE,
            lineas = listOf(linea("uuid-1", counted = 2.0, countedAt = "2026-09-07T11:00:00Z"), linea("uuid-2")),
            actualizadoEn = 1_700_000_000_000,
        )
        val json = ConteoEnCurso.codificar(b)

        // `explicitNulls = false`: los nulos no viajan. Es lo que hace barata la escritura por
        // tecla. Lo que esta prueba guarda es ESO —que la llave no se escriba y que aun así
        // vuelva—, no el `= null` de `countId`: quitarlo no rompe nada (medido, 2026-09-08).
        assertFalse(json, json.contains("countId"))
        assertFalse(json, json.contains("null"))
        assertEquals(b, ConteoEnCurso.decodificar(json))
    }

    @Test
    fun `P1 un JSON sin countId se lee igual`() {
        // El contrato OBSERVABLE, no el mecanismo. Un borrador escrito por iOS o por una versión
        // anterior puede no traer la llave, y tiene que LEERSE — si no, el conteo desaparece sin
        // un solo error.
        // 🔴 MEDIDO el 2026-09-08, no razonado: quitar el `= null` de `countId` y correr esta
        // suite NO rompió nada, porque `explicitNulls = false` relaja también la DECODIFICACIÓN.
        // O sea que el default no es lo que sostiene esto; esta prueba fija el resultado, venga
        // la garantía del default o del flag.
        val json = """{"venueId":"v1","type":"CYCLE","lineas":[],"nota":"","pendientesDeEnviar":[],"actualizadoEn":1700000000000}"""
        val b = ConteoEnCurso.decodificar(json)
        assertNotNull(json, b)
        assertNull(b!!.countId)
        assertEquals("v1", b.venueId)
        assertEquals(StockCountType.CYCLE, b.type)
        assertNull("un JSON viejo conserva el marcador desconocido", b.notaPendienteDeEnviar)
    }

    @Test
    fun `textos - espejo exacto de iOS`() {
        assertEquals("Todavía no has contado ningún artículo.", ConteoEnCurso.descripcionSalir(0, 138))
        assertEquals("Llevas 12 de 138 artículos contados. Si guardas el avance, puedes continuar después desde este aparato.", ConteoEnCurso.descripcionSalir(12, 138))
        assertEquals("Sin conexión — 12 líneas guardadas en este aparato", ConteoEnCurso.avisoSinRed(12))
        assertEquals("Sin conexión — 1 línea guardada en este aparato", ConteoEnCurso.avisoSinRed(1))
        assertEquals("12 líneas guardadas en este aparato sin subir", ConteoEnCurso.avisoPendienteDeSubir(12))
        assertEquals("1 línea guardada en este aparato sin subir", ConteoEnCurso.avisoPendienteDeSubir(1))
        assertEquals("Conteo cíclico sin terminar en este aparato", ConteoEnCurso.tituloBorrador(StockCountType.CYCLE))
        assertEquals("Conteo completo sin terminar en este aparato", ConteoEnCurso.tituloBorrador(StockCountType.FULL))
        assertEquals("12 de 138 contados", ConteoEnCurso.avanceTexto(12, 138))

        // Las CONSTANTES, no sólo las funciones: son las que salen en el diálogo de salida y
        // tienen que decir lo mismo, letra por letra, que `ConteoEnCurso.swift`. Una errata
        // aquí no la caza el compilador ni ninguna otra prueba.
        assertEquals("¿Qué hacemos con este conteo?", ConteoEnCurso.TITULO_SALIR)
        assertEquals("Guardar el avance", ConteoEnCurso.GUARDAR_EL_AVANCE)
        assertEquals("Descartar el conteo", ConteoEnCurso.DESCARTAR)
        assertEquals("Seguir contando", ConteoEnCurso.SEGUIR_CONTANDO)
        assertEquals("Continuar", ConteoEnCurso.CONTINUAR)
        assertEquals(
            "Este conteo ya se cerró desde otro aparato. Lo que contaste aquí se conserva sólo para consulta.",
            ConteoEnCurso.CONFLICTO,
        )
        assertEquals(
            "Tienes un conteo sin terminar en este aparato. Continúalo o descártalo antes de empezar otro.",
            ConteoEnCurso.HAY_OTRO_BORRADOR,
        )
        assertEquals(
            "Sin conexión: el conteo quedó guardado en este aparato. Confirma cuando vuelva la red.",
            ConteoEnCurso.CONFIRMAR_SIN_RED,
        )
        assertEquals(
            "No hay un conteo activo para confirmar. Vuelve a abrirlo e intenta de nuevo.",
            ConteoEnCurso.SIN_CONTEO_ACTIVO,
        )
        // El conteo vive en la sucursal donde se abrió: el aviso tiene que decir a DÓNDE volver.
        assertEquals(
            "Cambiaste de sucursal: vuelve a la sucursal del conteo para seguir con este conteo.",
            ConteoEnCurso.CAMBIASTE_DE_SUCURSAL,
        )
        assertEquals(
            "Cambiaste de sucursal: vuelve a Testarudo Centro para seguir con este conteo.",
            ConteoEnCurso.cambiasteDeSucursal("Testarudo Centro"),
        )
        // Un `venueId` es un cuid: sin nombre a mano, mejor el texto sin nombre que un id.
        assertEquals(ConteoEnCurso.CAMBIASTE_DE_SUCURSAL, ConteoEnCurso.cambiasteDeSucursal(null))
        assertEquals(ConteoEnCurso.CAMBIASTE_DE_SUCURSAL, ConteoEnCurso.cambiasteDeSucursal("  "))
        // Singular y plural cambian TRES palabras (artículo/artículos, entró/entraron,
        // cuenta/cuentan): no basta con una "s" pegada al final.
        assertEquals("1 artículo no entró al conteo: no se cuenta en inventario", ConteoEnCurso.lineasNoIncluidas(1))
        assertEquals("3 artículos no entraron al conteo: no se cuentan en inventario", ConteoEnCurso.lineasNoIncluidas(3))
    }

    @Test
    fun `contadas e idsContadas cuentan sólo las líneas con countedAt`() {
        // Un 0 contado ES un dato (la línea "d" se contó y salió en cero); un 0 sin contar, no.
        val lineas = listOf(
            linea("a", counted = 3.0, countedAt = "2026-09-07T11:00:00Z"),
            linea("b"),
            linea("d", counted = 0.0, countedAt = "2026-09-07T11:05:00Z"),
        )
        assertEquals(2, ConteoEnCurso.contadas(lineas))
        assertEquals(setOf("a", "d"), ConteoEnCurso.idsContadas(lineas))
        assertEquals(0, ConteoEnCurso.contadas(emptyList()))
        assertEquals(emptySet<String>(), ConteoEnCurso.idsContadas(emptyList()))
    }

    @Test
    fun `una nota vieja se considera pendiente y un marcador explicito distingue la sincronizada`() {
        val base = BorradorDeConteo(
            venueId = "v1",
            countId = "c1",
            type = StockCountType.FULL,
            lineas = emptyList(),
            nota = "nota",
            actualizadoEn = 1L,
        )
        assertTrue(ConteoEnCurso.notaPendienteDeEnviar(base))
        val sincronizada = base.copy(notaPendienteDeEnviar = false)
        val borradaLocalmente = base.copy(nota = "", notaPendienteDeEnviar = true)
        assertFalse(ConteoEnCurso.notaPendienteDeEnviar(sincronizada))
        assertTrue(ConteoEnCurso.notaPendienteDeEnviar(borradaLocalmente))
        assertEquals(sincronizada, ConteoEnCurso.decodificar(ConteoEnCurso.codificar(sincronizada)))
        assertEquals(borradaLocalmente, ConteoEnCurso.decodificar(ConteoEnCurso.codificar(borradaLocalmente)))

        val viejo = """{"venueId":"v1","countId":"c1","type":"FULL","lineas":[],"nota":"nota vieja","pendientesDeEnviar":[],"actualizadoEn":1}"""
        val decodificado = ConteoEnCurso.decodificar(viejo)!!
        assertNull(decodificado.notaPendienteDeEnviar)
        assertTrue(ConteoEnCurso.notaPendienteDeEnviar(decodificado))
    }

    @Test
    fun `lineaDeEstado - en progreso con resumen dice cuantas van, lo demas conserva el texto viejo`() {
        val enProgreso = StockCount(id = "c1", status = "IN_PROGRESS", itemCount = 138, summary = StockCountSummary(itemCount = 138, countedCount = 12))
        assertEquals("En progreso · 12 de 138 contados", ConteoEnCurso.lineaDeEstado(enProgreso))
        val viejo = StockCount(id = "c2", status = "IN_PROGRESS", itemCount = 5)
        assertEquals("En progreso - 5 artículos", ConteoEnCurso.lineaDeEstado(viejo))
        val completo = StockCount(id = "c3", status = "COMPLETED", itemCount = 5, summary = StockCountSummary(itemCount = 5, countedCount = 5))
        assertEquals("Completado - 5 artículos", ConteoEnCurso.lineaDeEstado(completo))
    }
}
