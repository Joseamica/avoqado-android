package com.avoqado.pos.tables.presentation

import com.avoqado.pos.auth.data.AuthRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `hz:033d44ca:D9` (ensayo de bloqueos del restaurante, 16-nov): los textos menores que confundían en una caída. Los grandes (A3, D1/A4
 * y D2) tienen su prueba junto a su código: `MesaDelServidorSinRedTest`, `Q15HistorialViewModelTest` y `CorteTicketBuilderTest`.
 */
class AvisosDeLaCaidaTest {

    /** A2: la caja sin la cuenta leída y sin nada de ella a la vista no enseña el último total del plano como si fuera la cuenta. */
    @Test fun `A2 sin la cuenta leida y sin renglones a la vista el total es por confirmar`() {
        assertTrue(cuentaSinNadaALaVista(cuentaCompletaAqui = false, renglonesALaVista = 0, cargando = false))
        assertFalse("cargando dice «actualizando», no «por confirmar»", cuentaSinNadaALaVista(false, 0, cargando = true))
        assertFalse("con renglones a la vista, como antes", cuentaSinNadaALaVista(false, renglonesALaVista = 2, cargando = false))
        assertFalse("la cuenta completa (leída o abierta aquí) se ve con su total", cuentaSinNadaALaVista(true, 0, false))
    }

    /** C2: entrar sin internet no es un error rojo; otra falla sí es «no se pudo». */
    @Test fun `C2 entrar sin internet dice sin conexion y otra falla no culpa al internet`() {
        assertEquals(AuthRepository.SIN_CONEXION_PARA_ENTRAR, AuthRepository.textoDeFalloAlEntrar(java.net.ConnectException("refused")))
        assertEquals(AuthRepository.SIN_CONEXION_PARA_ENTRAR, AuthRepository.textoDeFalloAlEntrar(java.net.UnknownHostException("api")))
        assertEquals(AuthRepository.SIN_CONEXION_PARA_ENTRAR, AuthRepository.textoDeFalloAlEntrar(java.net.SocketTimeoutException()))
        assertEquals(AuthRepository.NO_SE_PUDO_ENTRAR, AuthRepository.textoDeFalloAlEntrar(IllegalStateException("json")))
        assertFalse(AuthRepository.SIN_CONEXION_PARA_ENTRAR.contains("Error"))
    }
}
