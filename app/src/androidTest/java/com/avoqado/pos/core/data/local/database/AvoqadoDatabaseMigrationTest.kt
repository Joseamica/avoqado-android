package com.avoqado.pos.core.data.local.database

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking
import com.avoqado.pos.payment.data.CanalDelCobro
import com.avoqado.pos.payment.data.CapturaDelCobro
import com.avoqado.pos.payment.data.ConciliacionDelCobro

@RunWith(AndroidJUnit4::class)
class AvoqadoDatabaseMigrationTest {
    private val dbName = "avoqado-migration-test.db"

    @Before
    fun setUp() {
        ApplicationProvider.getApplicationContext<android.content.Context>().deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        ApplicationProvider.getApplicationContext<android.content.Context>().deleteDatabase(dbName)
    }

    @Test
    fun migrationFrom1To8_keepsPendingPaymentsAndCreatesAllTables() {
        createVersion1Database()

        val database = openMigratedDatabase()
        try {
            val sqlite = database.openHelper.writableDatabase
            assertEquals(1, countRows(sqlite, "pending_payments"))
            assertTrue(tableExists(sqlite, "cash_drawer_sessions"))
            assertTrue(tableExists(sqlite, "cash_drawer_events"))
            assertTrue(tableExists(sqlite, "purchase_orders"))
            assertTrue(tableExists(sqlite, "inventory_transfers"))
            assertTrue(tableExists(sqlite, "pending_reservation_action"))
            assertTrue(tableExists(sqlite, "cached_payloads"))
            assertTrue(tableExists(sqlite, "pos_sync_intents"))
            assertTrue(columnExists(sqlite, "pos_sync_intents", "staff_id"))
            assertTrue(columnExists(sqlite, "pos_sync_sequence_state", "last_seq"))
            // El motivo del rechazo que la cuarentena enseña: si la columna
            // no sobrevive a la migración, la pantalla vuelve a la guía genérica.
            assertTrue(columnExists(sqlite, "pending_reservation_action", "lastError"))
        } finally {
            database.close()
        }
    }

    @Test
    fun migrationFrom2To8_keepsCashDrawerDataAndCreatesCurrentTables() {
        createVersion2Database()

        val database = openMigratedDatabase()
        try {
            val sqlite = database.openHelper.writableDatabase
            assertEquals(1, countRows(sqlite, "pending_payments"))
            assertEquals(1, countRows(sqlite, "cash_drawer_sessions"))
            assertEquals(1, countRows(sqlite, "cash_drawer_events"))
            assertTrue(tableExists(sqlite, "purchase_orders"))
            assertTrue(tableExists(sqlite, "inventory_transfers"))
            assertTrue(tableExists(sqlite, "pending_reservation_action"))
            assertTrue(tableExists(sqlite, "cached_payloads"))
            assertTrue(tableExists(sqlite, "pos_sync_intents"))
            assertTrue(columnExists(sqlite, "pos_sync_intents", "staff_id"))
            assertTrue(columnExists(sqlite, "pos_sync_sequence_state", "last_seq"))
            // El motivo del rechazo que la cuarentena enseña: si la columna
            // no sobrevive a la migración, la pantalla vuelve a la guía genérica.
            assertTrue(columnExists(sqlite, "pending_reservation_action", "lastError"))
        } finally {
            database.close()
        }
    }

    private fun openMigratedDatabase(): AvoqadoDatabase {
        return Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AvoqadoDatabase::class.java,
            dbName,
        ).addMigrations(
            AvoqadoDatabaseMigrations.MIGRATION_1_2,
            AvoqadoDatabaseMigrations.MIGRATION_2_3,
            AvoqadoDatabaseMigrations.MIGRATION_3_4,
            AvoqadoDatabaseMigrations.MIGRATION_4_5,
            AvoqadoDatabaseMigrations.MIGRATION_5_6,
            AvoqadoDatabaseMigrations.MIGRATION_6_7,
            AvoqadoDatabaseMigrations.MIGRATION_7_8,
            AvoqadoDatabaseMigrations.MIGRATION_8_9,
            AvoqadoDatabaseMigrations.MIGRATION_9_10,
            AvoqadoDatabaseMigrations.MIGRATION_10_11,
            AvoqadoDatabaseMigrations.MIGRATION_11_12,
            AvoqadoDatabaseMigrations.MIGRATION_12_13,
            AvoqadoDatabaseMigrations.MIGRATION_13_14,
            AvoqadoDatabaseMigrations.MIGRATION_14_15,
            AvoqadoDatabaseMigrations.MIGRATION_15_16,
            AvoqadoDatabaseMigrations.MIGRATION_16_17,
            AvoqadoDatabaseMigrations.MIGRATION_17_18,
            AvoqadoDatabaseMigrations.MIGRATION_18_19,
        ).build().also { roomDb ->
            roomDb.openHelper.writableDatabase
        }
    }


    /** DDL copied exactly from the real checked-in Room 13.json, not a fabricated new export. */
    private fun createVersion13Database() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val sqlite = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(dbName), null)
        try {
            listOf(
                "CREATE TABLE IF NOT EXISTS `pending_payments` (`id` TEXT NOT NULL, `venueId` TEXT NOT NULL, `staffId` TEXT NOT NULL, `amountCents` INTEGER NOT NULL, `tipCents` INTEGER NOT NULL, `method` TEXT NOT NULL, `paymentType` TEXT NOT NULL, `orderId` TEXT, `orderNumber` TEXT, `cashTenderedCents` INTEGER, `changeCents` INTEGER, `rating` INTEGER, `itemsJson` TEXT, `orderRequestJson` TEXT, `syncStatus` TEXT NOT NULL, `retryCount` INTEGER NOT NULL, `lastError` TEXT, `createdAt` INTEGER NOT NULL, `lastRetryAt` INTEGER, `tenderTypeId` TEXT, `tenderRevision` INTEGER, `customerId` TEXT, PRIMARY KEY(`id`))",
                "CREATE TABLE IF NOT EXISTS `cash_drawer_sessions` (`id` TEXT NOT NULL, `venueId` TEXT NOT NULL, `deviceName` TEXT, `openedByStaffId` TEXT NOT NULL, `openedByName` TEXT NOT NULL, `openedAt` INTEGER NOT NULL, `startingAmountCents` INTEGER NOT NULL, `closedByStaffId` TEXT, `closedByName` TEXT, `closedAt` INTEGER, `actualAmountCents` INTEGER, `overShortCents` INTEGER, `closingNote` TEXT, `status` TEXT NOT NULL, PRIMARY KEY(`id`))",
                "CREATE TABLE IF NOT EXISTS `cash_drawer_events` (`id` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `venueId` TEXT NOT NULL, `type` TEXT NOT NULL, `amountCents` INTEGER NOT NULL, `note` TEXT, `staffId` TEXT NOT NULL, `staffName` TEXT NOT NULL, `orderId` TEXT, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                "CREATE TABLE IF NOT EXISTS `purchase_orders` (`id` TEXT NOT NULL, `venueId` TEXT NOT NULL, `supplierName` TEXT NOT NULL, `status` TEXT NOT NULL, `notes` TEXT, `expectedDate` TEXT, `itemsJson` TEXT, `createdAt` TEXT NOT NULL, `createdByName` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                "CREATE TABLE IF NOT EXISTS `inventory_transfers` (`id` TEXT NOT NULL, `venueId` TEXT NOT NULL, `fromLocationName` TEXT NOT NULL, `toLocationName` TEXT NOT NULL, `status` TEXT NOT NULL, `notes` TEXT, `itemsJson` TEXT, `createdAt` TEXT NOT NULL, `createdByName` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                "CREATE TABLE IF NOT EXISTS `pending_reservation_action` (`rowId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `reservationId` TEXT NOT NULL, `action` TEXT NOT NULL, `payloadJson` TEXT, `attemptCount` INTEGER NOT NULL, `lastError` TEXT, `createdAt` INTEGER NOT NULL)",
                "CREATE TABLE IF NOT EXISTS `cached_payloads` (`cache_key` TEXT NOT NULL, `venue_id` TEXT NOT NULL, `json` TEXT NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`cache_key`))",
                "CREATE TABLE IF NOT EXISTS `pos_sync_intents` (`id` TEXT NOT NULL, `venue_id` TEXT NOT NULL, `staff_id` TEXT, `seq` INTEGER NOT NULL, `type` TEXT NOT NULL, `payload_json` TEXT NOT NULL, `status` TEXT NOT NULL, `error_code` TEXT, `message` TEXT, `result_json` TEXT, `created_at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                "CREATE TABLE IF NOT EXISTS `waste_catalog_item` (`venueId` TEXT NOT NULL, `itemType` TEXT NOT NULL, `itemId` TEXT NOT NULL, `name` TEXT NOT NULL, `sku` TEXT NOT NULL, `unit` TEXT NOT NULL, `actualizadoEn` INTEGER NOT NULL, PRIMARY KEY(`venueId`, `itemType`, `itemId`))",
                "CREATE TABLE IF NOT EXISTS `pending_waste` (`idempotencyKey` TEXT NOT NULL, `venueId` TEXT NOT NULL, `staffId` TEXT NOT NULL, `itemType` TEXT NOT NULL, `itemId` TEXT NOT NULL, `itemName` TEXT NOT NULL, `unit` TEXT NOT NULL, `quantity` TEXT NOT NULL, `reasonCode` TEXT NOT NULL, `note` TEXT, `clientOccurredAt` TEXT NOT NULL, `estado` TEXT NOT NULL, `intentos` INTEGER NOT NULL, `ultimoCodigo` TEXT, `creadaEn` INTEGER NOT NULL, `proximoIntentoEn` INTEGER NOT NULL, `cerradaPorStaffId` TEXT, `cerradaEn` INTEGER, PRIMARY KEY(`idempotencyKey`))",
                "CREATE TABLE IF NOT EXISTS `entregas_kds_pendientes` (`entregaId` TEXT NOT NULL, `sourceKey` TEXT NOT NULL, `venueId` TEXT NOT NULL, `stationId` TEXT NOT NULL, `mensajeJson` TEXT NOT NULL, `trabajoJson` TEXT NOT NULL, `creadaEnMillis` INTEGER NOT NULL, `soltadaEnMillis` INTEGER, PRIMARY KEY(`entregaId`))",
                "CREATE TABLE IF NOT EXISTS `kds_tickets_locales` (`sourceKey` TEXT NOT NULL, `venueId` TEXT NOT NULL, `stationId` TEXT NOT NULL, `orderNumber` TEXT NOT NULL, `orderType` TEXT NOT NULL, `itemsJson` TEXT NOT NULL, `recibidaEnMillis` INTEGER NOT NULL, `listaEnMillis` INTEGER, PRIMARY KEY(`sourceKey`))",
                "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
                "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'd5fe7c02376c203cd80e74b85d8814e5')",

            ).forEach(sqlite::execSQL)
            sqlite.execSQL("INSERT INTO pos_sync_intents (id, venue_id, staff_id, seq, type, payload_json, status, created_at) VALUES ('acked', 'v1', 'staff', 7, 'PAY_CASH', '{\"amountCents\":500}', 'ACKED', 1), ('held', 'v1', 'staff', 30, 'ADD_ITEMS', '{}', 'HELD', 1), ('rejected', 'v2', 'original', 42, 'PAY_CASH', '{\"amountCents\":700}', 'REJECTED', 1)")
            sqlite.execSQL("INSERT INTO cached_payloads VALUES ('preparation:v2:line', 'v2', '{\"legacy\":true}', 1)")
            sqlite.execSQL("PRAGMA user_version = 13")
        } finally { sqlite.close() }
    }

    @Test fun migrationFrom13To14SeedsGlobalSequenceAndPreservesPendingWork() = runBlocking {
        createVersion13Database()
        val database = openMigratedDatabase()
        try {
            val dao = database.syncIntentDao()
            assertEquals(3, countRows(database.openHelper.writableDatabase, "pos_sync_intents"))
            assertEquals(42L, dao.sequenceState())
            val before = requireNotNull(dao.findById("rejected"))
            assertEquals("original", before.staffId)
            assertEquals("v2", before.venueId)
            assertEquals("REJECTED", before.status)
            assertEquals("{\"amountCents\":700}", before.payloadJson)
            assertEquals("{\"legacy\":true}", database.cachedPayloadDao().get("preparation:v2:line")?.json)
            dao.deleteOldAcked(2)
            dao.descartar("held")
            dao.dismiss("rejected")
            assertEquals(43L, dao.insertWithNextSeq(SyncIntentEntity("next", "v1", "staff", 0, "PAY_CASH", "{}", createdAt = 1)))
        } finally { database.close() }
    }

    @Test fun migrationFrom14To15PreservesEveryLegacyPaymentColumnWithoutGuessingCapture() = runBlocking {
        createVersion13Database()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val helper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context).name(dbName)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(14) {
                    override fun onCreate(db: SupportSQLiteDatabase) = error("The v13 fixture must already exist")
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        assertEquals(13, oldVersion); assertEquals(14, newVersion)
                        AvoqadoDatabaseMigrations.MIGRATION_13_14.migrate(db)
                    }
                }).build())
        fun snapshot(db: SupportSQLiteDatabase): List<Map<String, Pair<Int, String?>>> =
            db.query("SELECT * FROM pending_payments ORDER BY id").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.columnNames.indices.associate { index ->
                        cursor.columnNames[index] to (cursor.getType(index) to if (cursor.isNull(index)) null else cursor.getString(index))
                    })
                }
            }
        val before = try {
            val db = helper.writableDatabase
            for ((id, status) in listOf("legacy-pending" to "PENDING", "legacy-reserved" to "EN_VUELO")) {
                db.execSQL("""INSERT INTO pending_payments
                    (id,venueId,staffId,amountCents,tipCents,method,paymentType,orderId,orderNumber,
                     cashTenderedCents,changeCents,rating,itemsJson,orderRequestJson,syncStatus,retryCount,
                     lastError,createdAt,lastRetryAt,tenderTypeId,tenderRevision,customerId)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                    arrayOf(id,"venue-original","staff-original",10000,500,"CASH","ORDER","order-original","A-17",
                        5000,500,4,"[]","{\"total\":10500}",status,2,"sin red",100L,110L,"tender-original",3,"customer-original"))
            }
            snapshot(db)
        } finally { helper.close() }
        val database = openMigratedDatabase()
        try {
            assertEquals(19, database.openHelper.writableDatabase.version)
            val after = snapshot(database.openHelper.writableDatabase)
            val q15FinancialColumns = setOf("q15CaptureJson", "q15InitialResultJson", "q15ResultJson",
                "q15Reason", "q15DetectedAt", "q15ReviewedAt", "q15ReviewedBy", "q15ReviewRole")
            val addedColumns = q15FinancialColumns + setOf("splitType", "capturedOrderRef", "cashDrawerSessionId")
            assertEquals(2, after.size)
            after.forEach { row ->
                // Every added column is checked BEFORE projecting the unchanged legacy columns.
                assertEquals(before.first().keys + addedColumns, row.keys)
                assertEquals(Cursor.FIELD_TYPE_STRING to "FULLPAYMENT", row["splitType"])
                q15FinancialColumns.forEach { column ->
                    assertEquals("Legacy must not gain financial evidence: $column",
                        Cursor.FIELD_TYPE_NULL to null, row[column])
                }
                // The migration links an original STRING ID only; it does not invent a capture.
                assertEquals(Cursor.FIELD_TYPE_STRING to "order-original", row["capturedOrderRef"])
                // v17: la caja de la venta de una fila vieja queda nula; no se inventa.
                assertEquals(Cursor.FIELD_TYPE_NULL to null, row["cashDrawerSessionId"])
            }
            assertEquals(before, after.map { it - addedColumns })
            assertTrue(after.all { it["splitType"]?.second == "FULLPAYMENT" })
            // No se deduce una captura de 40 desde recibido/cambio ni se auto-confirma EN_VUELO.
            assertEquals(listOf(10000,10000), database.pendingPaymentDao().forVenue("venue-original").map { it.amountCents })
            assertEquals(setOf("PENDING","EN_VUELO"), database.pendingPaymentDao().forVenue("venue-original").map { it.syncStatus }.toSet())
            val captured = database.pendingPaymentDao().forVenue("venue-original").first().copy(
                id = "new-captured", amountCents = 4000, tipCents = 500, splitType = "CUSTOMAMOUNT")
            database.pendingPaymentDao().insert(captured)
            assertEquals(captured, database.pendingPaymentDao().forVenue("venue-original").single { it.id == "new-captured" })
        } finally { database.close() }
        val reopened = openMigratedDatabase()
        try {
            assertEquals("CUSTOMAMOUNT", reopened.pendingPaymentDao().forVenue("venue-original").single { it.id == "new-captured" }.splitType)
        } finally { reopened.close() }
    }

    /** Actual file-backed Room migration and reopen; no HTTP or process-kill credit. */
    @Test fun migrationFrom14To16ReopenPreservesOriginalCaptureAppliedResultAndManagerHistory() = runBlocking {
        createVersion13Database()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val helper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context).name(dbName)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(14) {
                    override fun onCreate(db: SupportSQLiteDatabase) = error("The v13 fixture must already exist")
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        assertEquals(13, oldVersion); assertEquals(14, newVersion)
                        AvoqadoDatabaseMigrations.MIGRATION_13_14.migrate(db)
                    }
                }).build())
        try { assertEquals(14, helper.writableDatabase.version) } finally { helper.close() }

        val venue = "FULLTEST-disk-A"
        val key = "FULLTEST-disk-capture"
        val request = """{"externalId":"FULLTEST-original-order","items":[{"productId":"FULLTEST-product","quantity":1}]}"""
        val capture = CapturaDelCobro(venueId = venue, key = key, orderId = "FULLTEST-order",
            amountCents = 4010, tipCents = 500, splitType = "CUSTOMAMOUNT", method = "CASH",
            cashTenderedCents = 5000, changeCents = 490, customerId = "FULLTEST-customer",
            orderRequestJson = request)
        val original = PendingPaymentEntity(id = key, venueId = venue, staffId = "FULLTEST-staff",
            amountCents = capture.amountCents, tipCents = capture.tipCents, method = "CASH", paymentType = "ORDER",
            orderId = capture.orderId, orderNumber = "FULLTEST-A-17", cashTenderedCents = capture.cashTenderedCents,
            changeCents = capture.changeCents, rating = 4, itemsJson = "[]", orderRequestJson = request,
            syncStatus = "EN_VUELO", retryCount = 2, createdAt = 100, lastRetryAt = 110,
            customerId = capture.customerId, splitType = "CUSTOMAMOUNT",
            q15CaptureJson = ConciliacionDelCobro.guardarCaptura(capture), capturedOrderRef = capture.orderId)
        val applied = ConciliacionDelCobro.evaluar(original.q15CaptureJson, venue, key, CanalDelCobro.ORDER,
            """{"success":true,"payment":{"paymentId":"FULLTEST-payment","orderId":"FULLTEST-order","venueId":"FULLTEST-disk-A","status":"COMPLETED","amount":4200,"tipAmount":500,"method":"CASH","splitType":"CUSTOMAMOUNT"}}""")
        assertTrue(applied.aplicado)
        assertEquals(ConciliacionDelCobro.DIFERENCIA, applied.motivo)
        val expected = original.copy(syncStatus = "SYNCED", q15InitialResultJson = applied.resultadoJson,
            q15ResultJson = applied.resultadoJson, q15Reason = applied.motivo, q15DetectedAt = 200)
        val migrated = openMigratedDatabase()
        try {
            assertEquals(19, migrated.openHelper.writableDatabase.version)
            migrated.pendingPaymentDao().insert(original)
            assertEquals(original, migrated.pendingPaymentDao().findForVenue(venue, key))
            assertEquals(1, migrated.pendingPaymentDao().registrarResultadoQ15(venue, key,
                applied.aplicado, applied.motivo, applied.resultadoJson, 200))
            assertEquals(expected, migrated.pendingPaymentDao().findForVenue(venue, key))
        } finally { migrated.close() }

        val reopened = openMigratedDatabase()
        val reviewed = expected.copy(q15ReviewedAt = 300, q15ReviewedBy = "FULLTEST-manager", q15ReviewRole = "MANAGER")
        try {
            val dao = reopened.pendingPaymentDao()
            assertEquals(expected, dao.findForVenue(venue, key))
            assertEquals(capture, ConciliacionDelCobro.leerCaptura(dao.findForVenue(venue, key)?.q15CaptureJson, venue, key))
            dao.deleteSynced(Long.MAX_VALUE)
            assertEquals(0, dao.deleteFailed(key))
            assertEquals(expected, dao.findForVenue(venue, key))
            assertEquals(0, dao.revisarPagoQ15("FULLTEST-disk-B", key, "FULLTEST-manager", "MANAGER", 300))
            assertEquals(0, dao.revisarPagoQ15(venue, key, "FULLTEST-cashier", "CASHIER", 300))
            assertEquals(1, dao.revisarPagoQ15(venue, key, "FULLTEST-manager", "MANAGER", 300))
            assertEquals(reviewed, dao.findForVenue(venue, key))
        } finally { reopened.close() }
        val historyReopened = openMigratedDatabase()
        try {
            assertEquals(reviewed, historyReopened.pendingPaymentDao().findForVenue(venue, key))
            assertEquals(1, historyReopened.pendingPaymentDao().contarRevisionesQ15(venue, true, ""))
            val history = historyReopened.pendingPaymentDao().paginaRevisionesQ15(venue, true, "", null, null, null).single()
            assertEquals(reviewed.q15CaptureJson, history.q15CaptureJson)
            assertEquals(reviewed.q15InitialResultJson, history.q15InitialResultJson)
            assertEquals(reviewed.q15ResultJson, history.q15ResultJson)
            assertEquals("MANAGER", history.q15ReviewRole)
        } finally { historyReopened.close() }
    }

    /**
     * v16 → v17 (efectivo sin red en el turno correcto, 21-oct): la fila de un cobro sin red guardada en v16 llega IDÉNTICA,
     * con la caja de la venta en nulo (no se inventa), y la reserva nueva con caja la conserva aunque se reemplace sin ella.
     */
    @Test fun migrationFrom16To17KeepsEveryPaymentColumnAndLeavesTheSaleDrawerNull() = runBlocking {
        createVersion13Database()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val helper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context).name(dbName)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(16) {
                    override fun onCreate(db: SupportSQLiteDatabase) = error("The v13 fixture must already exist")
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        assertEquals(13, oldVersion); assertEquals(16, newVersion)
                        AvoqadoDatabaseMigrations.MIGRATION_13_14.migrate(db)
                        AvoqadoDatabaseMigrations.MIGRATION_14_15.migrate(db)
                        AvoqadoDatabaseMigrations.MIGRATION_15_16.migrate(db)
                    }
                }).build())
        fun snapshot(db: SupportSQLiteDatabase): List<Map<String, String?>> =
            db.query("SELECT * FROM pending_payments ORDER BY id").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.columnNames.indices.associate { index ->
                        cursor.columnNames[index] to if (cursor.isNull(index)) null else cursor.getString(index)
                    })
                }
            }
        val before = try {
            val db = helper.writableDatabase
            db.execSQL("""INSERT INTO pending_payments
                (id,venueId,staffId,amountCents,tipCents,method,paymentType,orderId,orderNumber,cashTenderedCents,changeCents,
                 rating,itemsJson,orderRequestJson,syncStatus,retryCount,lastError,createdAt,lastRetryAt,tenderTypeId,tenderRevision,
                 customerId,splitType,q15CaptureJson,q15InitialResultJson,q15ResultJson,q15Reason,q15DetectedAt,q15ReviewedAt,
                 q15ReviewedBy,q15ReviewRole,capturedOrderRef)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                arrayOf("v16-pending","venue-16","staff-16",8732,450,"CASH","ORDER","order-16","A-17",10000,1268,5,"[]",
                    "{\"total\":9182}","PENDING",3,"sin red",100L,110L,"tender-16",4,"customer-16","CUSTOMAMOUNT",
                    "{\"version\":1}","{\"i\":1}","{\"r\":1}","DIFERENCIA",120L,130L,"manager-16","MANAGER","order-16"))
            snapshot(db)
        } finally { helper.close() }
        val database = openMigratedDatabase()
        try {
            assertEquals(19, database.openHelper.writableDatabase.version)
            val after = snapshot(database.openHelper.writableDatabase)
            assertEquals(1, after.size)
            assertEquals(before.single().keys + "cashDrawerSessionId", after.single().keys)
            assertEquals(null, after.single()["cashDrawerSessionId"])
            assertEquals(before, after.map { it - "cashDrawerSessionId" })
            val dao = database.pendingPaymentDao()
            val reserva = PendingPaymentEntity(id = "v17-reserva", venueId = "venue-16", staffId = "staff-16", amountCents = 10_000,
                tipCents = 0, method = "CASH", paymentType = "FAST", syncStatus = "EN_VUELO", createdAt = 200L,
                cashDrawerSessionId = "caja-A-local")
            dao.insert(reserva)
            dao.insert(reserva.copy(syncStatus = "PENDING", cashDrawerSessionId = null))
            assertEquals("caja-A-local", dao.findForVenue("venue-16", "v17-reserva")?.cashDrawerSessionId)
        } finally { database.close() }
    }

    private fun createVersion1Database() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbFile = context.getDatabasePath(dbName)
        val sqlite = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        sqlite.execSQL(CREATE_PENDING_PAYMENTS_SQL)
        sqlite.execSQL(
            """
            INSERT INTO pending_payments (
                id, venueId, staffId, amountCents, tipCents, method, paymentType, syncStatus, retryCount, createdAt
            ) VALUES (
                'pay-1', 'venue-1', 'staff-1', 1299, 0, 'CASH', 'FAST', 'PENDING', 0, 1710000000000
            )
            """.trimIndent(),
        )
        sqlite.execSQL("PRAGMA user_version = 1")
        sqlite.close()
    }

    private fun createVersion2Database() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbFile = context.getDatabasePath(dbName)
        val sqlite = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        sqlite.execSQL(CREATE_PENDING_PAYMENTS_SQL)
        sqlite.execSQL(CREATE_CASH_DRAWER_SESSIONS_SQL)
        sqlite.execSQL(CREATE_CASH_DRAWER_EVENTS_SQL)
        sqlite.execSQL(
            """
            INSERT INTO pending_payments (
                id, venueId, staffId, amountCents, tipCents, method, paymentType, syncStatus, retryCount, createdAt
            ) VALUES (
                'pay-2', 'venue-1', 'staff-2', 2500, 300, 'CASH', 'ORDER', 'PENDING', 0, 1710000000100
            )
            """.trimIndent(),
        )
        sqlite.execSQL(
            """
            INSERT INTO cash_drawer_sessions (
                id, venueId, deviceName, openedByStaffId, openedByName, openedAt, startingAmountCents, status
            ) VALUES (
                'session-1', 'venue-1', 'register-1', 'staff-2', 'Alex', 1710000000200, 15000, 'OPEN'
            )
            """.trimIndent(),
        )
        sqlite.execSQL(
            """
            INSERT INTO cash_drawer_events (
                id, sessionId, venueId, type, amountCents, note, staffId, staffName, orderId, createdAt
            ) VALUES (
                'event-1', 'session-1', 'venue-1', 'OPEN', 15000, 'Opening float', 'staff-2', 'Alex', NULL, 1710000000300
            )
            """.trimIndent(),
        )
        sqlite.execSQL("PRAGMA user_version = 2")
        sqlite.close()
    }

    private fun countRows(database: SupportSQLiteDatabase, tableName: String): Int {
        val cursor = database.query("SELECT COUNT(*) FROM $tableName")
        cursor.use {
            if (!it.moveToFirst()) return 0
            return it.getInt(0)
        }
    }

    private fun tableExists(database: SupportSQLiteDatabase, tableName: String): Boolean {
        val cursor = database.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='$tableName'",
        )
        cursor.use {
            return it.moveToFirst()
        }
    }

    private fun columnExists(database: SupportSQLiteDatabase, tableName: String, columnName: String): Boolean {
        val cursor = database.query("PRAGMA table_info(`$tableName`)")
        cursor.use {
            val nameIndex = it.getColumnIndex("name")
            while (it.moveToNext()) {
                if (nameIndex >= 0 && it.getString(nameIndex) == columnName) return true
            }
        }
        return false
    }

    companion object {
        private const val CREATE_PENDING_PAYMENTS_SQL =
            """
            CREATE TABLE IF NOT EXISTS `pending_payments` (
                `id` TEXT NOT NULL,
                `venueId` TEXT NOT NULL,
                `staffId` TEXT NOT NULL,
                `amountCents` INTEGER NOT NULL,
                `tipCents` INTEGER NOT NULL,
                `method` TEXT NOT NULL,
                `paymentType` TEXT NOT NULL,
                `orderId` TEXT,
                `orderNumber` TEXT,
                `cashTenderedCents` INTEGER,
                `changeCents` INTEGER,
                `rating` INTEGER,
                `itemsJson` TEXT,
                `orderRequestJson` TEXT,
                `syncStatus` TEXT NOT NULL,
                `retryCount` INTEGER NOT NULL,
                `lastError` TEXT,
                `createdAt` INTEGER NOT NULL,
                `lastRetryAt` INTEGER,
                PRIMARY KEY(`id`)
            )
            """

        private const val CREATE_CASH_DRAWER_SESSIONS_SQL =
            """
            CREATE TABLE IF NOT EXISTS `cash_drawer_sessions` (
                `id` TEXT NOT NULL,
                `venueId` TEXT NOT NULL,
                `deviceName` TEXT,
                `openedByStaffId` TEXT NOT NULL,
                `openedByName` TEXT NOT NULL,
                `openedAt` INTEGER NOT NULL,
                `startingAmountCents` INTEGER NOT NULL,
                `closedByStaffId` TEXT,
                `closedByName` TEXT,
                `closedAt` INTEGER,
                `actualAmountCents` INTEGER,
                `overShortCents` INTEGER,
                `closingNote` TEXT,
                `status` TEXT NOT NULL,
                PRIMARY KEY(`id`)
            )
            """

        private const val CREATE_CASH_DRAWER_EVENTS_SQL =
            """
            CREATE TABLE IF NOT EXISTS `cash_drawer_events` (
                `id` TEXT NOT NULL,
                `sessionId` TEXT NOT NULL,
                `venueId` TEXT NOT NULL,
                `type` TEXT NOT NULL,
                `amountCents` INTEGER NOT NULL,
                `note` TEXT,
                `staffId` TEXT NOT NULL,
                `staffName` TEXT NOT NULL,
                `orderId` TEXT,
                `createdAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """
    }
}
