package com.avoqado.pos.escritorio

import com.avoqado.escritorio.ActividadDeEscritorio
import com.avoqado.pos.core.domain.printing.ComandaDispatcher
import com.avoqado.pos.printing.data.ConfirmacionDePantalla
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertSame

/**
 * D6 de Flautas (20-nov): el POS de WINDOWS pregunta al SERVIDOR si la comanda ya está en la pantalla de cocina antes de
 * avisar «No salió». Si el inyector de escritorio (Guice) armara el despachador SIN esa pieza, WINDOWS nunca callaría el aviso
 * falso aunque Android sí: esta prueba arma el despachador con el inyector REAL.
 */
class ConfirmacionDePantallaEnElInyectorTest {
    @Test
    fun `el inyector de escritorio arma el despachador con la confirmacion de pantalla`() {
        val carpeta = Files.createTempDirectory("Avoqado POS confirmacion de pantalla")
        try {
            val inyector = Inyector.crear(ActividadDeEscritorio(carpeta))
            val despachador = inyector.getInstance(ComandaDispatcher::class.java)
            val campo = ComandaDispatcher::class.java.getDeclaredField("confirmacionDePantalla").apply { isAccessible = true }

            assertNotNull(campo.get(despachador), "sin la confirmación, WINDOWS nunca callaría el aviso falso")
            assertSame(inyector.getInstance(ConfirmacionDePantalla::class.java), campo.get(despachador), "es la misma (@Singleton)")
        } finally {
            carpeta.toFile().deleteRecursively()
        }
    }
}
