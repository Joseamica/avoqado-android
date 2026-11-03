package com.avoqado.pos.giftcards

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.payment.PaymentFlowConSaldoTest
import com.avoqado.pos.giftcards.domain.*
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*

class ConsultarAlCobroTest {
    @get:Rule val main=MainDispatcherRule()
    private val f=PaymentFlowConSaldoTest()
    @Before fun preparar()=f.setUp()
    @After fun cerrar()=f.tearDown()
    @Test fun pruebaPreseleccionadaNoSaltaT4NiT6()=runTest {
        var aperturas=0
        val vm=f.vm()
        assertTrue(vm.abrirSaldoDesdeConsultar(f.identified){aperturas++})
        assertEquals(1,aperturas)
        f.cart=f.cart.copy(creacionIncierta="x")
        assertFalse(vm.abrirSaldoDesdeConsultar(f.identified){aperturas++})
        f.cart=f.cart.copy(creacionIncierta=null,vinoDeCarritoGuardado=true)
        assertFalse(vm.abrirSaldoDesdeConsultar(f.identified){aperturas++})
        f.cart=f.cart.copy(vinoDeCarritoGuardado=false)
        f.connected.value=false
        assertFalse(vm.abrirSaldoDesdeConsultar(f.identified){aperturas++})
        assertEquals(1,aperturas)
    }
}
