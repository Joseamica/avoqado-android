package com.avoqado.pos.escritorio.base
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlin.test.Test
import kotlin.test.assertEquals

class Q15IdentidadMigracionTest {
    @Test fun `migracion 15 a 16 lee referencias originales sin JSON1 ni inventar captura`() {
        BundledSQLiteDriver().open(":memory:").use { db ->
            db.execSQL("CREATE TABLE pending_payments(id TEXT PRIMARY KEY,venueId TEXT NOT NULL,orderId TEXT)")
            db.execSQL("CREATE TABLE pos_sync_intents(id TEXT PRIMARY KEY,venue_id TEXT NOT NULL,type TEXT NOT NULL,payload_json TEXT NOT NULL)")
            db.execSQL("INSERT INTO pending_payments VALUES('p1','FULLTEST-A','R-1')")
            db.execSQL("INSERT INTO pos_sync_intents VALUES('i1','FULLTEST-A','PAY_CASH','{\"localOrderId\":\"L-1\",\"orderId\":\"R-1\"}')")
            migracionesDeAndroid().single { it.startVersion==15 }.paraRoom().migrate(db)
            db.prepare("SELECT capturedOrderRef,q15CaptureJson FROM pending_payments WHERE id='p1'").use { it.step();assertEquals("R-1",it.getText(0));assertEquals(true,it.isNull(1)) }
            db.prepare("SELECT capturedOrderRef,q15CaptureJson FROM pos_sync_intents WHERE id='i1'").use { it.step();assertEquals("L-1",it.getText(0));assertEquals(true,it.isNull(1)) }
        }
    }
}
