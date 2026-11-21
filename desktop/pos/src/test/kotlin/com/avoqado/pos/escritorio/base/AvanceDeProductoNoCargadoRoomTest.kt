package com.avoqado.pos.escritorio.base

import com.avoqado.escritorio.ActividadDeEscritorio
import com.avoqado.pos.core.data.local.database.AvoqadoDatabase
import com.avoqado.pos.core.data.local.database.CachedPayloadEntity
import com.avoqado.pos.escritorio.Inyector
import com.avoqado.pos.kds.data.PreparationPeerPersistence
import com.avoqado.pos.kds.data.ProductoNoCargadoAqui
import com.avoqado.pos.kds.domain.PreparationAction
import com.avoqado.pos.kds.domain.PreparationCounts
import com.avoqado.pos.kds.domain.PreparationLine
import com.avoqado.pos.kds.domain.PreparationLocalLine
import com.avoqado.pos.kds.domain.PreparationPeerItem
import com.avoqado.pos.kds.domain.PreparationPeerProgress
import com.avoqado.pos.kds.domain.PreparationPeerProtocol
import com.avoqado.pos.pos.data.model.ServiceCourseSnapshot
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

/**
 * D16 (3.ª pasada con aparatos), con la base REAL de WINDOWS: la pantalla de cocina trabajó sin red sobre una cuenta que la caja
 * nunca abrió. Un avance de un producto que este aparato no tiene cargado se dice con su propio tipo (el receptor lo toma como
 * recibido en silencio) y no deja recibo; un avance que choca con lo que sí está cargado sigue siendo un error de los de siempre.
 */
class AvanceDeProductoNoCargadoRoomTest {
    private val carpeta = Files.createTempDirectory("avance-no-cargado")
    private val inyector = Inyector.crear(ActividadDeEscritorio(carpeta))
    private val base = inyector.getInstance(AvoqadoDatabase::class.java)
    private val persistencia = PreparationPeerPersistence(base)
    private val json = PreparationPeerProtocol.json

    @AfterTest fun cerrar() {
        runCatching { base.close() }
        carpeta.toFile().deleteRecursively()
    }

    private val item = PreparationPeerItem("round:r:s", "sync:r:0", "s", 1, expectedRevision = 0, before = PreparationCounts(PENDING = 1))
    private val avance = PreparationPeerProgress(venueId = VENUE, deviceId = "pantalla", staffId = "cocina", deliveryId = "i:0",
        intentId = "i", action = PreparationAction.START, quantity = 1, items = listOf(item))

    @Test fun `un avance de un producto que no esta cargado aqui tiene su propio tipo y no deja recibo`() = runBlocking {
        base.cachedPayloadDao().upsert(CachedPayloadEntity("preparation_capabilities:$VENUE", VENUE, """{"version":1,"enabled":true,"urgencyVersion":1}""", 1))
        assertFailsWith<ProductoNoCargadoAqui> { persistencia.receive(avance) }
        assertNull(base.cachedPayloadDao().get("preparation:$VENUE:receipt:i:0"))
    }

    @Test fun `un avance que choca con lo cargado sigue siendo un error de los de siempre`() = runBlocking {
        val dao = base.cachedPayloadDao()
        dao.upsert(CachedPayloadEntity("preparation_capabilities:$VENUE", VENUE, """{"version":1,"enabled":true,"urgencyVersion":1}""", 1))
        // Aquí el producto sigue RETENIDO (la pantalla lo creía pendiente): eso sí es un desacuerdo.
        val linea = PreparationLine("local:x", orderId = "order", externalId = "sync:r:0", sourceKey = "round:r:s", stationId = "s",
            orderNumber = "24", productName = "Jugo", quantity = 1,
            serviceCourse = ServiceCourseSnapshot("drinks", "Aperitivos", "STANDARD", preparationVersion = 1), preparation = PreparationCounts(HELD = 1))
        dao.upsert(CachedPayloadEntity("preparation:$VENUE:${linea.stableKey}", VENUE,
            json.encodeToString(PreparationLocalLine.serializer(), PreparationLocalLine(linea)), 1))
        val error = runCatching { persistencia.receive(avance) }.exceptionOrNull()
        assertFalse(error == null || error is ProductoNoCargadoAqui, "esperaba un desacuerdo, no «no cargado»: $error")
    }

    private companion object { const val VENUE = "v-no-cargado" }
}
