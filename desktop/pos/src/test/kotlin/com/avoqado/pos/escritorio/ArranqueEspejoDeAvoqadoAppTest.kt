package com.avoqado.pos.escritorio

import com.avoqado.escritorio.ActividadDeEscritorio
import com.avoqado.escritorio.Bitacora
import com.avoqado.escritorio.Escritorio
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.printing.data.PrinterService
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `Arranque.iniciar` es el espejo de `AvoqadoApp.onCreate`: lo que Android arranca con el proceso, escritorio también.
 * Android agregó `vigilanteDeImpresoras.start()` el 2-oct (3cef2f4, «la impresora que se encuentra sola»).
 */
class ArranqueEspejoDeAvoqadoAppTest {
    @Test fun `iniciar arranca el vigilante de impresoras, como AvoqadoApp onCreate`() {
        val carpeta = Files.createTempDirectory("Avoqado POS arranque ñ")
        Bitacora.iniciar(carpeta)
        val actividad = ActividadDeEscritorio(carpeta)
        val inyector = Inyector.crear(actividad)
        Escritorio.instalar(actividad, inyector)   // lo mismo que Arranque.abrir antes de iniciar
        val impresoras = inyector.getInstance(PrinterService::class.java)
        inyector.getInstance(SecureStorage::class.java).venueId = "venue-del-arranque"
        assertNull(impresoras.venueActual(), "antes de iniciar nadie le dijo al servicio de impresión de qué negocio es")

        Arranque.iniciar(inyector, RaizDeEscritorio.lifecycle)

        // Sólo VigilanteDeImpresoras.start() le enseña a PrinterService a leer el negocio: sin él, los avisos de
        // «la impresora cambió de dirección» no se guardan ni llegan al servidor.
        assertEquals("venue-del-arranque", impresoras.venueActual())
    }
}
