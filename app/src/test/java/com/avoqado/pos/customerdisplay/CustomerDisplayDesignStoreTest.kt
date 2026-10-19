package com.avoqado.pos.customerdisplay

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CustomerDisplayDesignStoreTest {

    @get:Rule val carpeta = TemporaryFolder()

    private val huella = "0123456789abcdef0123456789abcdef"
    private fun dir() = File(carpeta.root, "customer-display")
    private fun store() = CustomerDisplayDesignStore(dir())

    @Test
    fun `la instalacion nace una vez y sobrevive a otra instancia`() {
        val primera = store().load().installationId

        assertTrue(primera.isNotBlank())
        assertTrue(File(dir(), "state.json").exists())
        assertEquals(primera, store().load().installationId)
        assertEquals(primera, store().load().installationId)
    }

    @Test
    fun `update es atomico y se relee igual`() {
        val store = store()
        val llave = store.manifestKey("prod", "venue-1", "t1")
        val acuse = CustomerDisplayAckBody(
            installationId = store.load().installationId, fingerprint = huella, seq = 1, storedAt = "2026-10-18T12:00:00Z",
            shownAt = null, surface = null, imagesReady = 0, imagesPending = 1, imagesFailed = 0, unsupportedSchema = false,
        )
        val manifiesto = DesignManifest(
            ficha = FichaDto(schemaVersion = 1, fingerprint = huella, theme = "DARK"),
            imageOrigins = listOf("https://storage.googleapis.com"),
            storedAt = "2026-10-18T12:00:00Z",
            pendingAck = acuse,
        )

        val escrito = store.update {
            it.copy(
                nextSeq = it.nextSeq + 1,
                lastTerminalByVenue = mapOf(store.venueKey("prod", "venue-1") to "t1"),
                manifests = mapOf(llave to manifiesto),
            )
        }

        assertEquals(2L, escrito.nextSeq)
        assertEquals(escrito, store().load())
        assertEquals(acuse, store().load().manifests.getValue(llave).pendingAck)
        assertEquals(listOf("state.json"), dir().list()!!.toList())
    }

    @Test
    fun `archivo ilegible arranca de cero y lo aparta`() {
        dir().mkdirs()
        File(dir(), "state.json").writeText("{esto no es json")

        val estado = store().load()

        assertTrue(estado.manifests.isEmpty())
        assertTrue(estado.installationId.isNotBlank())
        assertEquals("{esto no es json", File(dir(), "state.corrupt").readText())
        assertEquals(estado.installationId, store().load().installationId)
    }

    @Test
    fun `temporal huerfano de una muerte a medias se ignora y se borra`() {
        val valido = store().update { it.copy(nextSeq = 7) }
        val huerfano = File(dir(), "x.tmp").apply { writeText("basura a medias") }

        val estado = store().load()

        assertEquals(valido, estado)
        assertFalse(huerfano.exists())
    }

    @Test
    fun `seq nunca retrocede entre instancias`() {
        val store = store()
        val asignados = (1..3).map { asignarSeq(store) }

        assertEquals(listOf(1L, 2L, 3L), asignados)
        assertEquals(4L, asignarSeq(store()))
    }

    private fun asignarSeq(store: CustomerDisplayDesignStore): Long {
        var seq = 0L
        store.update { seq = it.nextSeq; it.copy(nextSeq = it.nextSeq + 1) }
        return seq
    }
}
