package com.avoqado.pos.escritorio

import com.avoqado.escritorio.ActividadDeEscritorio
import com.avoqado.pos.core.domain.printing.ComandaDispatcher
import com.avoqado.pos.kds.data.EstadoDePantallasDeCocina
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertSame

/**
 * D6 de Flautas (20-nov, opción B): la franja «La pantalla de cocina de X no está conectada a internet» y el silencio del
 * «No salió» por orden salen de [EstadoDePantallasDeCocina] (lo que dice el servidor). Si el inyector de escritorio (Guice)
 * armara el despachador SIN ella, WINDOWS seguiría avisando en cada orden aunque Android no: esta prueba lo arma con el
 * inyector REAL y comprueba que es la misma instancia (@Singleton) que recibe `AppState`.
 */
class PantallasDeCocinaEnElInyectorTest {
    @Test
    fun `el inyector de escritorio arma el despachador con el estado de las pantallas de cocina`() {
        val carpeta = Files.createTempDirectory("Avoqado POS pantallas de cocina")
        try {
            val inyector = Inyector.crear(ActividadDeEscritorio(carpeta))
            val estado = inyector.getInstance(EstadoDePantallasDeCocina::class.java)
            val despachador = inyector.getInstance(ComandaDispatcher::class.java)
            val campo = ComandaDispatcher::class.java.getDeclaredField("estadoDePantallas").apply { isAccessible = true }

            assertSame(estado, campo.get(despachador), "sin ella, WINDOWS nunca callaría el aviso falso")
            assertSame(estado, inyector.getInstance(EstadoDePantallasDeCocina::class.java), "es @Singleton")
        } finally {
            carpeta.toFile().deleteRecursively()
        }
    }
}
