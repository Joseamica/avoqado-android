package com.avoqado.pos.core.data.local.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object AvoqadoDatabaseMigrations {
    // v1 only persisted pending payments. v2 added cash drawer offline state.
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
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
                """.trimIndent(),
            )

            database.execSQL(
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
                """.trimIndent(),
            )
        }
    }

    // v4 added pending reservation action queue for offline state transitions.
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `pending_reservation_action` (
                    `rowId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `reservationId` TEXT NOT NULL,
                    `action` TEXT NOT NULL,
                    `payloadJson` TEXT,
                    `attemptCount` INTEGER NOT NULL DEFAULT 0,
                    `createdAt` INTEGER NOT NULL
                )
                """.trimIndent(),
            )
        }
    }

    // v3 added inventory purchase orders and transfer drafts for offline workflows.
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `purchase_orders` (
                    `id` TEXT NOT NULL,
                    `venueId` TEXT NOT NULL,
                    `supplierName` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `notes` TEXT,
                    `expectedDate` TEXT,
                    `itemsJson` TEXT,
                    `createdAt` TEXT NOT NULL,
                    `createdByName` TEXT NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )

            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `inventory_transfers` (
                    `id` TEXT NOT NULL,
                    `venueId` TEXT NOT NULL,
                    `fromLocationName` TEXT NOT NULL,
                    `toLocationName` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `notes` TEXT,
                    `itemsJson` TEXT,
                    `createdAt` TEXT NOT NULL,
                    `createdByName` TEXT NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
        }
    }

    // v5 — offline-first Corte A: espejo en disco de payloads de solo-lectura
    // (catálogo, mesas, menús) para que un reinicio en modo avión cargue todo.
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `cached_payloads` (
                    `cache_key` TEXT NOT NULL,
                    `venue_id` TEXT NOT NULL,
                    `json` TEXT NOT NULL,
                    `updated_at` INTEGER NOT NULL,
                    PRIMARY KEY(`cache_key`)
                )
                """.trimIndent(),
            )
        }
    }

    // v6 — offline-first Corte B: outbox de intents (comandas/mesas offline)
    // reproducido contra POST /mobile/venues/:id/sync/intents al reconectar.
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `pos_sync_intents` (
                    `id` TEXT NOT NULL,
                    `venue_id` TEXT NOT NULL,
                    `seq` INTEGER NOT NULL,
                    `type` TEXT NOT NULL,
                    `payload_json` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `error_code` TEXT,
                    `message` TEXT,
                    `result_json` TEXT,
                    `created_at` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
        }
    }
}
