package com.avoqado.pos.inventory.waste

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.util.VenueDateTimeFormatter
import com.avoqado.pos.inventory.waste.data.FolioDeHistorial
import com.avoqado.pos.inventory.waste.data.HistorialDeMerma
import com.avoqado.pos.inventory.waste.data.PaginaDeHistorial
import com.avoqado.pos.inventory.waste.data.ResultadoDeHistorial
import com.avoqado.pos.inventory.waste.data.WasteApi
import com.avoqado.pos.inventory.waste.data.WasteCatalogDao
import com.avoqado.pos.inventory.waste.data.WasteCatalogEntity
import com.avoqado.pos.inventory.waste.data.WasteRepository
import com.avoqado.pos.inventory.waste.domain.TextosMerma
import com.avoqado.pos.inventory.waste.presentation.HistorialDeMermaViewModel
import com.avoqado.pos.inventory.waste.presentation.filaDeHistorial
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Historial de mermas del POS (spec 2026-09-23). El servidor decide el alcance (`scope`): el mesero ve
 * sólo lo suyo y el gerente lo de todos; el aparato sólo lo pinta. Nunca hay pesos. Espejo de
 * `HistorialDeMermaTests.swift` de avoqado-ios.
 */
class HistorialDeMermaTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val cuerpo = """
        {"scope":"ALL","items":[{"id":"rep1","itemType":"RAW_MATERIAL","name":"Leche","sku":"LEC-1","unit":"LITER",
        "reasonCode":"CONTAMINATED","reasonLabel":"Contaminado","declaredQuantity":"2","deductedQuantity":"1.5",
        "unrecordedQuantity":"0.5","note":null,"createdAt":"2026-09-23T15:00:00.000Z","reportedByName":"Ana Pérez"}],
        "total":31,"page":1,"pageSize":30,"nextCursor":"c-siguiente"}
    """.trimIndent()

    private fun folio(
        id: String = "rep1",
        declarada: String = "2",
        sinExistencia: String = "0",
        autor: String? = "Ana Pérez",
        etiqueta: String? = "Se echó a perder",
    ) = FolioDeHistorial(
        id = id, name = "Leche", unit = "LITER", reasonCode = "SPOILED", reasonLabel = etiqueta,
        declared = declarada, unrecorded = sinExistencia, note = null,
        createdAt = "2026-09-23T15:00:00.000Z", reportedByName = autor,
    )

    @Test
    fun `la ruta pide paginas de 30 al venue, con el cursor tal cual`() {
        assertEquals(
            "https://x/api/v1/mobile/venues/v1/inventory/waste-reports?pageSize=30",
            WasteApi.urlDeHistorial("https://x/api/v1/mobile/venues/v1", cursor = null),
        )
        assertEquals(
            "https://x/api/v1/mobile/venues/v1/inventory/waste-reports?pageSize=30&cursor=a%2Bb",
            WasteApi.urlDeHistorial("https://x/api/v1/mobile/venues/v1", cursor = "a+b"),
        )
    }

    @Test
    fun `lee la pagina del servidor con su alcance`() {
        val pagina = WasteApi.parsearHistorial(cuerpo)!!
        assertTrue(pagina.todos)
        assertEquals(31, pagina.total)
        assertEquals("c-siguiente", pagina.nextCursor)
        val f = pagina.folios.single()
        assertEquals("Leche", f.name)
        assertEquals("Contaminado", f.reasonLabel)
        assertEquals("2", f.declared)
        assertEquals("0.5", f.unrecorded)
        assertNull(f.note)
        assertEquals("Ana Pérez", f.reportedByName)
    }

    /** Un alcance que no se entiende se lee como «sólo lo mío»: nunca se presume la vista del gerente. */
    @Test
    fun `un alcance desconocido o ausente se lee como MINE`() {
        assertFalse(WasteApi.parsearHistorial(cuerpo.replace("\"ALL\"", "\"OTRO\""))!!.todos)
        assertFalse(WasteApi.parsearHistorial(cuerpo.replace("\"scope\":\"ALL\",", ""))!!.todos)
    }

    @Test
    fun `sin items o con HTML no hay pagina`() {
        assertNull(WasteApi.parsearHistorial("""{"scope":"MINE","total":0}"""))
        assertNull(WasteApi.parsearHistorial("<html>portal</html>"))
    }

    @Test
    fun `sin declarada se muestra la descontada`() {
        val pagina = WasteApi.parsearHistorial(cuerpo.replace("\"declaredQuantity\":\"2\"", "\"declaredQuantity\":null"))!!
        assertEquals("1.5", pagina.folios.single().declared)
    }

    // ── El renglón que ve el cajero ────────────────────────────────────────────────────────────

    @Test
    fun `el renglon dice cantidad con unidad, motivo y hora`() {
        val fila = filaDeHistorial(folio(), todos = false) { "23/09 09:00" }
        assertEquals("Leche", fila.articulo)
        assertEquals("2 L · Se echó a perder", fila.detalle)
        assertEquals("23/09 09:00", fila.hora)
        assertNull(fila.autor)
        assertNull(fila.sinExistencia)
    }

    @Test
    fun `quien registro sale solo en la vista del negocio`() {
        assertEquals("Registró: Ana Pérez", filaDeHistorial(folio(), todos = true) { "" }.autor)
        assertNull(filaDeHistorial(folio(), todos = false) { "" }.autor)
        assertNull(filaDeHistorial(folio(autor = null), todos = true) { "" }.autor)
    }

    @Test
    fun `lo que no pudo descontarse se dice`() {
        assertEquals("0.5 L sin existencia", filaDeHistorial(folio(sinExistencia = "0.5"), todos = false) { "" }.sinExistencia)
        assertNull(filaDeHistorial(folio(sinExistencia = "0.000"), todos = false) { "" }.sinExistencia)
    }

    @Test
    fun `sin etiqueta del servidor se usa el codigo`() {
        assertEquals("2 L · SPOILED", filaDeHistorial(folio(etiqueta = null), todos = false) { "" }.detalle)
    }

    // ── La pantalla ────────────────────────────────────────────────────────────────────────────

    private class FuenteFalsa : HistorialDeMerma {
        val respuestas = ArrayDeque<ResultadoDeHistorial>()
        val pedidas = mutableListOf<Pair<String, String?>>()
        override suspend fun historial(venueId: String, cursor: String?): ResultadoDeHistorial {
            pedidas += venueId to cursor
            return respuestas.removeFirst()
        }
    }

    private val fuente = FuenteFalsa()
    private fun vm() = HistorialDeMermaViewModel(
        fuente,
        mockk<SecureStorage> { every { venueId } returns "venue-centro" },
        mockk<VenueDateTimeFormatter> { every { formatShort(any<String>()) } returns "hora" },
    )

    @Test
    fun `el titulo sale del alcance que decide el servidor`() = runTest {
        fuente.respuestas += ResultadoDeHistorial.Pagina(PaginaDeHistorial(todos = false, folios = listOf(folio()), total = 1))
        val v = vm().also { it.cargar().join() }
        assertEquals(TextosMerma.MIS_MERMAS, v.estado.value.titulo)

        fuente.respuestas += ResultadoDeHistorial.Pagina(PaginaDeHistorial(todos = true, folios = listOf(folio()), total = 1))
        val g = vm().also { it.cargar().join() }
        assertEquals(TextosMerma.MERMAS_DEL_NEGOCIO, g.estado.value.titulo)
    }

    @Test
    fun `cargar mas agrega la siguiente pagina y se apaga al llegar al total`() = runTest {
        fuente.respuestas += ResultadoDeHistorial.Pagina(PaginaDeHistorial(false, listOf(folio("a")), total = 2, nextCursor = "c1"))
        fuente.respuestas += ResultadoDeHistorial.Pagina(PaginaDeHistorial(false, listOf(folio("b")), total = 2, nextCursor = null))
        val v = vm()
        v.cargar().join()
        assertTrue(v.estado.value.hayMas)
        v.cargarMas().join()
        assertEquals(listOf("a", "b"), v.estado.value.filas.map { it.id })
        assertFalse(v.estado.value.hayMas)
        assertEquals(listOf("venue-centro" to null, "venue-centro" to "c1"), fuente.pedidas)
    }

    /** 🔴 Visto en la D3: sin red, el dueño leía «Mis mermas». Mientras el servidor no diga el alcance, el título es neutro. */
    @Test
    fun `sin saber el alcance el titulo no presume ninguno`() = runTest {
        fuente.respuestas += ResultadoDeHistorial.SinRed
        assertEquals(TextosMerma.HISTORIAL_TITULO, vm().also { it.cargar().join() }.estado.value.titulo)
    }

    /**
     * Codex (historial, r2 P2): a media lectura le quitan `inventory:adjust`; la página siguiente viene como «mías»
     * pero es de OTRO conjunto. Mezclarla dejaba ajenas bajo «Mis mermas» y se saltaba las propias: se empieza de nuevo.
     */
    @Test
    fun `si el alcance cambia a media lectura la lista vuelve a empezar`() = runTest {
        fuente.respuestas += ResultadoDeHistorial.Pagina(PaginaDeHistorial(true, listOf(folio("ajena")), total = 3, nextCursor = "c1"))
        fuente.respuestas += ResultadoDeHistorial.Pagina(PaginaDeHistorial(false, listOf(folio("mia-vieja")), total = 2))
        fuente.respuestas += ResultadoDeHistorial.Pagina(PaginaDeHistorial(false, listOf(folio("mia-nueva"), folio("mia-vieja")), total = 2))
        val v = vm()
        v.cargar().join()
        v.cargarMas().join()
        assertEquals(listOf("mia-nueva", "mia-vieja"), v.estado.value.filas.map { it.id })
        assertEquals(TextosMerma.MIS_MERMAS, v.estado.value.titulo)
        assertEquals(listOf("venue-centro" to null, "venue-centro" to "c1", "venue-centro" to null), fuente.pedidas)
    }

    /**
     * Codex (r3, P2): al terminar de leer, si lo mostrado no cuadra con el total vigente (llegó o se fue un folio a
     * media lectura) se DICE y se ofrece actualizar, en vez de quedarse en «31 de 32» sin salida.
     */
    @Test
    fun `si la lista cambio mientras se leia se dice y se puede actualizar`() = runTest {
        fuente.respuestas += ResultadoDeHistorial.Pagina(PaginaDeHistorial(false, listOf(folio("a")), total = 2, nextCursor = "c1"))
        fuente.respuestas += ResultadoDeHistorial.Pagina(PaginaDeHistorial(false, listOf(folio("b")), total = 3))
        val v = vm()
        v.cargar().join()
        assertFalse(v.estado.value.desactualizada)
        v.cargarMas().join()
        assertTrue(v.estado.value.desactualizada)
    }

    /** 🔴 Sin red NO es un error: se dice en palabras del cajero y lo ya cargado se conserva. */
    @Test
    fun `sin red lo dice y conserva lo cargado`() = runTest {
        fuente.respuestas += ResultadoDeHistorial.Pagina(PaginaDeHistorial(false, listOf(folio("a")), total = 2, nextCursor = "c1"))
        fuente.respuestas += ResultadoDeHistorial.SinRed
        val v = vm()
        v.cargar().join()
        v.cargarMas().join()
        assertEquals(TextosMerma.HISTORIAL_SIN_RED, v.estado.value.aviso)
        assertEquals(listOf("a"), v.estado.value.filas.map { it.id })
    }

    @Test
    fun `sin plan y fallo dicen cada uno lo suyo`() = runTest {
        fuente.respuestas += ResultadoDeHistorial.SinPlan
        assertEquals(TextosMerma.HISTORIAL_SIN_PLAN, vm().also { it.cargar().join() }.estado.value.aviso)
        fuente.respuestas += ResultadoDeHistorial.Fallo
        assertEquals(TextosMerma.HISTORIAL_FALLO, vm().also { it.cargar().join() }.estado.value.aviso)
    }

    // ── El transporte contra un servidor HTTP de verdad ──────────────────────────────────────────

    private class CatalogoVacio : WasteCatalogDao {
        override suspend fun insertar(items: List<WasteCatalogEntity>) = Unit
        override suspend fun borrarDelVenue(venueId: String) = Unit
        override suspend fun catalogoDelVenue(venueId: String) = emptyList<WasteCatalogEntity>()
        override suspend fun actualizadoEn(venueId: String): Long? = null
    }

    private fun conServidor(bloque: suspend (MockWebServer, WasteRepository) -> Unit) = runTest {
        val server = MockWebServer().apply { start() }
        System.setProperty("avoqado.test.baseUrl", server.url("/api/v1").toString().trimEnd('/'))
        try {
            val client = OkHttpClient.Builder().readTimeout(1, TimeUnit.SECONDS).build()
            bloque(server, WasteRepository(client, CatalogoVacio()))
        } finally {
            System.clearProperty("avoqado.test.baseUrl")
            runCatching { server.shutdown() }
        }
    }

    @Test
    fun `el repositorio distingue pagina, sin plan, fallo y sin red`() = conServidor { server, repo ->
        server.enqueue(MockResponse().setResponseCode(200).setBody(cuerpo))
        assertTrue(repo.historial("venue-centro", null) is ResultadoDeHistorial.Pagina)
        assertEquals("/api/v1/mobile/venues/venue-centro/inventory/waste-reports?pageSize=30", server.takeRequest().path)

        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"featureCode":"INVENTORY_TRACKING"}"""))
        assertEquals(ResultadoDeHistorial.SinPlan, repo.historial("venue-centro", null))

        server.enqueue(MockResponse().setResponseCode(500).setBody("{}"))
        assertEquals(ResultadoDeHistorial.Fallo, repo.historial("venue-centro", null))

        server.shutdown()
        assertEquals(ResultadoDeHistorial.SinRed, repo.historial("venue-centro", null))
    }
}
