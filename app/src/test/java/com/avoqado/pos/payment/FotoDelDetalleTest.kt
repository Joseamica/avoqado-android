package com.avoqado.pos.payment

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.giftcards.data.CobrosLocalesDeLaCuenta
import com.avoqado.pos.tables.data.OrderDetail
import com.avoqado.pos.payment.domain.DineroConocido
import com.avoqado.pos.payment.presentation.CobroDeCuentaViewModel
import com.avoqado.pos.tables.data.TableServiceRepository
import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 🔴 D13: «Pedidos → detalle → Cobrar» toma la foto de la puerta del efectivo ANTES de leer la cuenta del SERVIDOR: lo que suba
 * después pudo no contarse en ese saldo, y la puerta lo ve como nuevo. Esa foto es la que viaja al cobro.
 */
class FotoDelDetalleTest {
    private val storage = mockk<SecureStorage> { every { venueId } returns "a" }
    private val repo = mockk<TableServiceRepository>()
    private val locales = mockk<CobrosLocalesDeLaCuenta>()
    private val foto = DineroConocido(setOf("efectivo:subido-antes"))

    @Test fun `la foto se toma antes de leer la cuenta y es la que se le da al cobro`() = runTest {
        coEvery { locales.dineroConocido("a", "o") } returns foto
        coEvery { locales.hayPendiente("a", "o") } returns false
        coEvery { repo.getOrderDetail("a", "o") } returns Result.success(OrderDetail("o", esDeMesa = false, total = 100.0))
        val m = CobroDeCuentaViewModel(repo, storage, mockk<ConnectivityMonitor>(), mockk(relaxed = true), mockk(relaxed = true), locales)

        m.ofertaParaCobrar("o")

        coVerifyOrder {
            locales.dineroConocido("a", "o")
            repo.getOrderDetail("a", "o")
        }
        assertEquals(foto, m.dineroConocido)
    }

    @Test fun `si la foto no se puede leer queda vacia (frena de mas, nunca de menos)`() = runTest {
        coEvery { locales.dineroConocido("a", "o") } throws IllegalStateException("disco")
        coEvery { locales.hayPendiente("a", "o") } returns false
        coEvery { repo.getOrderDetail("a", "o") } returns Result.success(OrderDetail("o", esDeMesa = false, total = 100.0))
        val m = CobroDeCuentaViewModel(repo, storage, mockk<ConnectivityMonitor>(), mockk(relaxed = true), mockk(relaxed = true), locales)

        m.ofertaParaCobrar("o")

        assertEquals(DineroConocido.NADA, m.dineroConocido)
    }
}
