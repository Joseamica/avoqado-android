package com.avoqado.pos.payment

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.giftcards.domain.TextosDeTarjetas
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class FilaDeSaldoReactivaR1Test {
    @get:Rule val main=MainDispatcherRule()
    private lateinit var f: PaymentFlowConSaldoTest
    @Before fun preparar() { f=PaymentFlowConSaldoTest(); f.setUp() }
    @After fun cerrar()=f.tearDown()
    @Test fun `fila saldo reacciona al volver y perder ruta sin salir del cobro`()=runTest {
        f.connected.value=false
        val vm=f.vm(); vm.actualizarFilaDeSaldo()
        assertFalse(vm.conductaDelSaldo.value.habilitada)
        f.connected.value=true; runCurrent()
        assertTrue("Debe habilitarse sin otra acción de pantalla",vm.conductaDelSaldo.value.habilitada)
        f.connected.value=false; runCurrent()
        assertFalse(vm.conductaDelSaldo.value.habilitada)
        assertEquals(TextosDeTarjetas.SIN_RED,vm.conductaDelSaldo.value.motivo)
    }
    @Test fun `fila saldo reacciona al perder y volver servidor con ruta viva`()=runTest {
        @Suppress("UNCHECKED_CAST")
        val servidor=PaymentFlowConSaldoTest::class.java.getDeclaredField("reachable").apply {isAccessible=true}.get(f) as MutableStateFlow<Boolean>
        val vm=f.vm(); vm.actualizarFilaDeSaldo()
        assertTrue(vm.conductaDelSaldo.value.habilitada)
        servidor.value=false; runCurrent()
        assertFalse("Ruta de WiFi no sustituye servidor alcanzable",vm.conductaDelSaldo.value.habilitada)
        assertEquals(TextosDeTarjetas.SIN_RED,vm.conductaDelSaldo.value.motivo)
        servidor.value=true; runCurrent()
        assertTrue(vm.conductaDelSaldo.value.habilitada)
    }
}
