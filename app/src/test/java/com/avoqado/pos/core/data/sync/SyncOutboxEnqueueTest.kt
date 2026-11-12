package com.avoqado.pos.core.data.sync

import android.content.Context
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.SyncIntentDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * D16 (`hz:e5ee98bc:D16`): si `enqueue` lanza, el llamador entiende «no se guardó» y lo vuelve a intentar con otra llave. Por eso,
 * una vez escrita la fila, nada posterior (contar pendientes) puede convertir el desenlace en error.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncOutboxEnqueueTest {

    private val dao = mockk<SyncIntentDao>(relaxed = true)

    private val outbox = SyncOutbox(dao, mockk(relaxed = true), mockk(relaxed = true), mockk<SecureStorage>(relaxed = true),
        mockk(relaxed = true), mockk<Context>(relaxed = true), mockk(relaxed = true))
        .also { it.usarContextoDeSincronizacion(StandardTestDispatcher()) } // el replay no corre en esta prueba

    @Test fun `D16 la fila ya escrita no se vuelve error aunque falle contar pendientes`() = runTest {
        coEvery { dao.insertWithNextSeq(any()) } returns 7L
        coEvery { dao.pendingCount(any()) } throws IllegalStateException("disco")

        assertEquals("ronda-1", outbox.enqueue("venue-1", SyncIntentTypes.ADD_ITEMS, buildJsonObject {}, id = "ronda-1"))
        coVerify(exactly = 1) { dao.insertWithNextSeq(any()) }
    }
}
