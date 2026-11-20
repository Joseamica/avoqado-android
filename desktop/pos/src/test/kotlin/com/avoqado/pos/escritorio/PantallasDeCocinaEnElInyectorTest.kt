package com.avoqado.pos.escritorio

import com.avoqado.escritorio.ActividadDeEscritorio
import com.avoqado.pos.kds.data.EstadoDePantallasDeCocina
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertSame

/**
 * D6 de Flautas (20-nov, opción B): la franja «La pantalla de cocina de X no está conectada» sale de [EstadoDePantallasDeCocina],
 * que `AppState` recibe por miembro. Si el inyector de escritorio (Guice) no la pudiera armar, WINDOWS nunca diría que la
 * pantalla está desconectada aunque Android sí: esta prueba la arma con el inyector REAL.
 */
class PantallasDeCocinaEnElInyectorTest {
    @Test
    fun `el inyector de escritorio arma el estado de las pantallas de cocina, una sola vez`() {
        val carpeta = Files.createTempDirectory("Avoqado POS pantallas de cocina")
        try {
            val inyector = Inyector.crear(ActividadDeEscritorio(carpeta))

            assertSame(
                inyector.getInstance(EstadoDePantallasDeCocina::class.java),
                inyector.getInstance(EstadoDePantallasDeCocina::class.java),
                "es @Singleton: AppState y quien la lea ven la misma",
            )
        } finally {
            carpeta.toFile().deleteRecursively()
        }
    }
}
