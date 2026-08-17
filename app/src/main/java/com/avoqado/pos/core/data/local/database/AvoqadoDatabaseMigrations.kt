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

    // v7 — fija el actor original de cada intent para que un replay posterior
    // nunca quede atribuido a otra sesión del mismo dispositivo.
    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE `pos_sync_intents` ADD COLUMN `staff_id` TEXT")
        }
    }

    /**
     * Guarda POR QUÉ el server rechazó una acción de reserva encolada.
     *
     * La cuarentena sólo podía enseñar una guía genérica por tipo de acción. El
     * motivo real —medido en la tablet: "Esta reservación requiere al menos 60
     * minutos de anticipación"— se quedaba en un log que nadie lee, y quien
     * tiene que resolverlo se quedaba adivinando. iOS ya lo guardaba.
     */
    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE `pending_reservation_action` ADD COLUMN `lastError` TEXT")
        }
    }

    /**
     * La cola de pagos conserva el TIPO DE PAGO del catálogo.
     *
     * Antes sólo guardaba `method`, así que una venta con "Uber Eats" cobrada sin
     * red se reproducía como EFECTIVO —callada— y la protección anti-duplicados
     * impedía corregirla después. Aditiva y nullable: los pagos ya encolados
     * siguen subiendo igual.
     */
    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE `pending_payments` ADD COLUMN `tenderTypeId` TEXT")
            database.execSQL("ALTER TABLE `pending_payments` ADD COLUMN `tenderRevision` INTEGER")
        }
    }
}
