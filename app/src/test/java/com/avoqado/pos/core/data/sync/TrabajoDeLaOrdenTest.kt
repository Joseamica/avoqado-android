package com.avoqado.pos.core.data.sync

import android.content.Context
import com.avoqado.pos.core.data.local.database.INTENTS_SIN_RESOLVER_SQL
import com.avoqado.pos.core.data.local.database.IntentSinResolver
import com.avoqado.pos.core.data.local.database.SyncIntentDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.sql.DriverManager

class TrabajoDeLaOrdenTest {
    private val ids = setOf("o1", "L-1")
    private fun fila(tipo: String, estado: String, payload: String) = IntentSinResolver(tipo, estado, payload)

    @Test fun `incluye retenidos y rechazados por el id real y local`() {
        val t = SyncOutbox.resumirTrabajo(listOf(
            fila("ADD_ITEMS", "PENDING", """{"orderId":"o1"}"""),
            fila("ADD_ITEMS", "HELD", """{"orderId":"o1"}"""),
            fila("APPLY_SERVICE_CHARGE", "REJECTED", """{"orderId":"o1"}"""),
            fila("ADD_ITEMS", "PENDING", """{"localOrderId":"L-1"}"""),
            fila("ADD_ITEMS", "PENDING", """{"orderId":"otra"}"""),
        ), ids)
        assertEquals(4, t.intents); assertEquals(1, t.rechazados); assertEquals(3, t.enCamino)
        assertEquals(setOf("ADD_ITEMS", "APPLY_SERVICE_CHARGE"), t.tipos)
    }

    @Test fun `merge y split buscan valores anidados completos y no nombres de campos`() {
        assertTrue(SyncOutbox.tocaLaOrden("""{"sourceOrderIds":["o1"]}""", ids))
        assertTrue(SyncOutbox.tocaLaOrden("""{"split":{"groups":[{"localOrderId":"L-1"}]}}""", ids))
        assertFalse(SyncOutbox.tocaLaOrden("""{"o1":"otra","orderId":"prefix-o1"}""", ids))
        assertFalse(SyncOutbox.tocaLaOrden("""{"orderId":1}""", setOf("1")))
    }

    @Test fun `payload ilegible bloquea porque no sabemos a que orden pertenece`() {
        assertTrue(SyncOutbox.tocaLaOrden("{roto", ids))
        assertEquals(1, SyncOutbox.resumirTrabajo(listOf(fila("ADD_ITEMS", "HELD", "{roto")), ids).enCamino)
    }

    @Test fun `suma el efectivo capturado incluso rechazado sin sumar propinas ni otra orden`() {
        val t = SyncOutbox.resumirTrabajo(listOf(
            fila("PAY_CASH", "PENDING", """{"localOrderId":"L-1","amountCents":5000,"tipCents":500}"""),
            fila("PAY_CASH", "REJECTED", """{"orderId":"o1","amountCents":1250.0}"""),
            fila("PAY_CASH", "PENDING", """{"orderId":"otra","amountCents":9900}"""),
        ), ids)
        assertEquals(6250, t.cobradoEnColaCents); assertEquals(1, t.rechazados)
    }

    @Test fun `importe corrupto no se convierte en cero ni en dinero inventado`() {
        for (importe in listOf("-1", "1.5", "true", "\"5000\"", "null")) {
            assertThrows(Exception::class.java) {
                SyncOutbox.resumirTrabajo(listOf(fila("PAY_CASH", "PENDING", """{"orderId":"o1","amountCents":$importe}""")), ids)
            }
        }
        assertThrows(Exception::class.java) { SyncOutbox.resumirTrabajo(listOf(fila("PAY_CASH", "PENDING", "{roto")), ids) }
    }

    @Test fun `suma fuera del rango de centavos falla en vez de envolver el saldo`() {
        assertThrows(Exception::class.java) {
            SyncOutbox.resumirTrabajo(listOf(
                fila("PAY_CASH", "PENDING", """{"orderId":"o1","amountCents":2147483647}"""),
                fila("PAY_CASH", "PENDING", """{"orderId":"o1","amountCents":1}"""),
            ), ids)
        }
    }

    private fun outbox(dao: SyncIntentDao) = SyncOutbox(
        dao, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        mockk(relaxed = true), mockk<Context>(relaxed = true), mockk(relaxed = true),
    )

    @Test fun `lee la cola persistida de esta sucursal`() = runTest {
        val dao = mockk<SyncIntentDao>(relaxed = true)
        coEvery { dao.sinResolver("v1") } returns listOf(fila("ADD_ITEMS", "HELD", """{"orderId":"o1"}"""))
        assertEquals(1, outbox(dao).trabajoSinResolverDeLaOrden("v1", ids).enCamino)
        coVerify(exactly = 1) { dao.sinResolver("v1") }
    }

    @Test fun `un fallo de disco se propaga y no dice que la cuenta esta limpia`() = runTest {
        val dao = mockk<SyncIntentDao>(relaxed = true)
        coEvery { dao.sinResolver(any()) } throws IllegalStateException("disco")
        assertTrue(runCatching { outbox(dao).trabajoSinResolverDeLaOrden("v1", ids) }.exceptionOrNull() is IllegalStateException)
    }

    @Test fun `SQL real incluye toda la cola pendiente sin mezclar sucursales ni ACKED`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            db.createStatement().use { it.execute("CREATE TABLE pos_sync_intents (venue_id TEXT, type TEXT, status TEXT, payload_json TEXT)") }
            db.prepareStatement("INSERT INTO pos_sync_intents VALUES (?, 'ADD_ITEMS', ?, ?)").use { insert ->
                for (index in 0..1002) {
                    insert.setString(1, if (index == 1002) "otra" else "v1")
                    insert.setString(2, if (index == 1001) "ACKED" else listOf("PENDING", "HELD", "REJECTED")[index % 3])
                    insert.setString(3, """{"orderId":"o1"}""")
                    insert.addBatch()
                }
                insert.executeBatch()
            }
            db.prepareStatement(INTENTS_SIN_RESOLVER_SQL.replace(":venueId", "?")).use { query ->
                query.setString(1, "v1")
                query.executeQuery().use { rows ->
                    val projected = buildList {
                        while (rows.next()) add(fila(rows.getString("type"), rows.getString("status"), rows.getString("payload_json")))
                    }
                    val t = SyncOutbox.resumirTrabajo(projected, ids)
                    assertEquals(1001, t.intents); assertEquals(333, t.rechazados); assertEquals(668, t.enCamino)
                }
            }
        }
    }
}
