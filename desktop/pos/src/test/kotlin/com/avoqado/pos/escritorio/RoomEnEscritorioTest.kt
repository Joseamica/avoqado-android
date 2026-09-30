package com.avoqado.pos.escritorio

import com.avoqado.escritorio.ContextoDeEscritorio
import com.avoqado.pos.core.data.local.database.PendingPaymentEntity
import com.avoqado.pos.core.di.DatabaseModule
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoomEnEscritorioTest {
    @Test fun `un cobro en efectivo encolado sobrevive a cerrar y reabrir la base`() = runBlocking {
        val carpeta = Files.createTempDirectory("Avoqado POS José ñ")
        try {
            val primera = DatabaseModule.provideDatabase(ContextoDeEscritorio(carpeta))
            DatabaseModule.providePendingPaymentDao(primera).insert(
                PendingPaymentEntity(
                    id = "pago-1", venueId = "venue-1", staffId = "staff-1", amountCents = 8732, tipCents = 0,
                    method = "CASH", paymentType = "FAST",
                ),
            )
            primera.close()
            // La base vive en la carpeta de datos (la que sobrevive a actualizar la app), no en un temporal de Room.
            assertTrue(Files.exists(carpeta.resolve("databases/avoqado_db")), "la base no quedó en <carpeta>/databases")

            val segunda = DatabaseModule.provideDatabase(ContextoDeEscritorio(carpeta))
            val fila = DatabaseModule.providePendingPaymentDao(segunda).getPendingPayments().single()
            assertEquals("pago-1", fila.id)
            assertEquals(8732, fila.amountCents)
            assertEquals("PENDING", fila.syncStatus)
            segunda.close()
        } finally {
            carpeta.toFile().deleteRecursively()
        }
    }
}
