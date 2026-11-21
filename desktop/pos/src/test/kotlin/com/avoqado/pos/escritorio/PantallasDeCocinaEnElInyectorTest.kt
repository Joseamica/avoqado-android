package com.avoqado.pos.escritorio

import com.avoqado.escritorio.ActividadDeEscritorio
import com.avoqado.pos.core.domain.printing.ComandaDispatcher
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.kds.data.EstadoDePantallasDeCocina
import com.avoqado.pos.kds.data.PreparationDeliveryService
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

    /**
     * D16 de Flautas: al liberar un tiempo, la caja espera el «sí» del servidor sólo si sabe qué estaciones son pantalla (el
     * mismo estado) y si está conectada, y la cola que la despierta. Sin ellas, WINDOWS seguiría avisando «Esa estación no tiene impresora».
     */
    @Test
    fun `el inyector de escritorio arma la liberacion de tiempos con el estado de las pantallas y la conexion`() {
        val carpeta = Files.createTempDirectory("Avoqado POS liberar tiempo")
        try {
            val inyector = Inyector.crear(ActividadDeEscritorio(carpeta))
            val servicio = inyector.getInstance(PreparationDeliveryService::class.java)
            fun campo(nombre: String) = PreparationDeliveryService::class.java.getDeclaredField(nombre).apply { isAccessible = true }.get(servicio)

            assertSame(inyector.getInstance(EstadoDePantallasDeCocina::class.java), campo("pantallas"))
            assertSame(inyector.getInstance(ConnectivityMonitor::class.java), campo("conexion"))
            // Opción B′: el ACK/REJECT de su intent la despierta. Armarla aquí también prueba que no hay ciclo en Guice.
            assertSame(inyector.getInstance(SyncOutbox::class.java), campo("outbox"))
        } finally {
            carpeta.toFile().deleteRecursively()
        }
    }
}
