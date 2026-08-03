package com.avoqado.pos.core.data.local.database

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
        ).build().also { roomDb ->
            roomDb.openHelper.writableDatabase
        }
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
