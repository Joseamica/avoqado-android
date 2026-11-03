package com.avoqado.pos.giftcards

import com.avoqado.pos.core.data.local.database.*
import com.avoqado.pos.giftcards.data.*
import com.avoqado.pos.giftcards.domain.SolicitudDeTarjeta
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.sql.DriverManager

class CobrosLocalesDeLaCuentaTest {
    // R2: SQLite JDBC + el SQL real de forVenue; no es runtime Room.
    @Test fun todosLosEstadosDurablesYVenue() = runBlocking {
        val source = File("src/main/java/com/avoqado/pos/core/data/local/database/PendingPaymentDao.kt").readText()
        val sql = Regex("@Query\\(\"([^\"]+)\"\\)\\s*suspend fun forVenue").find(source)!!.groupValues[1].replace(":venueId", "?")
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            db.createStatement().use { it.execute("CREATE TABLE pending_payments(id TEXT, venueId TEXT, orderId TEXT, syncStatus TEXT)") }
            val dao = mockk<PendingPaymentDao>()
            coEvery { dao.forVenue(any()) } coAnswers {
                db.prepareStatement(sql).use { statement ->
                    statement.setString(1, firstArg()); statement.executeQuery().use { rows ->
                        buildList { while (rows.next()) add(PendingPaymentEntity(rows.getString("id"), rows.getString("venueId"), "staff", 8000, 0, "CASH", "ORDER", orderId = rows.getString("orderId"), syncStatus = rows.getString("syncStatus"))) }
                    }
                }
            }
            val storage = SolicitudesDeTarjetaTest.Preferencias().almacen()
            val helper = CobrosLocalesDeLaCuenta(dao, storage, SolicitudesDeTarjetaStore(storage))
            for (status in listOf("PENDING", "SYNCING", "EN_VUELO", "FAILED", "SYNCED")) {
                db.createStatement().use { it.executeUpdate("DELETE FROM pending_payments"); it.executeUpdate("INSERT INTO pending_payments VALUES('p','a','o','$status')") }
                assertEquals(status, status != "SYNCED", helper.hayPendiente("a", "o"))
                assertFalse("Nunca otra cuenta", helper.hayPendiente("a", "otra"))
                assertFalse("Nunca otro negocio", helper.hayPendiente("b", "o"))
            }
        }
    }
    @Test fun tarjetaYRedeemConAlmacenReconstruido() = runBlocking {
        val dao = mockk<PendingPaymentDao>(); coEvery { dao.forVenue(any()) } returns emptyList()
        val storage = SolicitudesDeTarjetaTest.Preferencias().almacen()
        assertTrue(storage.persistPendingCardCharge("r", "o", """{"venueId":"a"}"""))
        fun nuevo() = CobrosLocalesDeLaCuenta(dao, storage, SolicitudesDeTarjetaStore(storage))
        assertTrue(nuevo().hayPendiente("a", "o")); assertFalse(nuevo().hayPendiente("b", "o"))
        storage.removePendingCardCharge("r")
        val store = SolicitudesDeTarjetaStore(storage)
        assertTrue(store.guardar(SolicitudDeTarjeta(tipo = "GIFT_CARD_REDEEM", llave = "k", venueId = "a", creadaEn = 1, orderId = "o", cuerpo = "{}", mandado = true)))
        assertTrue(nuevo().hayPendiente("a", "o")); assertFalse(nuevo().hayPendiente("b", "o"))
        assertTrue(store.dejarPorTerminar("k", "o", "n", 5000))
        assertFalse(nuevo().hayPendiente("a", "o"))
    }
}
