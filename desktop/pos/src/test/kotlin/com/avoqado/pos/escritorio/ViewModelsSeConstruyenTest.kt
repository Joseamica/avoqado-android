package com.avoqado.pos.escritorio

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.CreationExtras
import com.avoqado.escritorio.ActividadDeEscritorio
import com.avoqado.escritorio.Bitacora
import com.avoqado.escritorio.Escritorio
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.nio.file.Files
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Atrapa un binding faltante ANTES de abrir la app: construye cada @HiltViewModel con el inyector de escritorio.
 * La API apunta a un puerto cerrado (build.gradle.kts), así que ningún ViewModel llega a un servidor.
 */
class ViewModelsSeConstruyenTest {
    @Test fun `todos los HiltViewModel se construyen con el inyector de escritorio`() {
        val carpeta = Files.createTempDirectory("Avoqado POS prueba ñ")
        Bitacora.iniciar(carpeta)
        val actividad = ActividadDeEscritorio(carpeta)
        Escritorio.instalar(actividad, Inyector.crear(actividad))

        val clases = System.getProperty("avoqado.clasesMain").split(File.pathSeparator).map(::File).filter { it.isDirectory }
            .flatMap { raiz ->
                raiz.walk().filter { it.name.endsWith(".class") && '$' !in it.name }
                    .map { it.relativeTo(raiz).path.removeSuffix(".class").replace(File.separatorChar, '.') }
                    .map { Class.forName(it, false, javaClass.classLoader) }
                    .filter { it.isAnnotationPresent(HiltViewModel::class.java) }
                    .toList()
            }
        assertTrue(clases.size >= 40, "sólo encontré ${clases.size} @HiltViewModel: el escaneo está mal")

        val almacen = ViewModelStore()
        val proveedor = ViewModelProvider.create(almacen, object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T =
                Escritorio.crearViewModel(modelClass.java, argumentosDeNavegacion())
        })
        val fallas = clases.mapNotNull { c ->
            @Suppress("UNCHECKED_CAST")
            runCatching { proveedor[(c as Class<ViewModel>).kotlin] }.exceptionOrNull()
                ?.let { "${c.simpleName}: ${it.message?.lines()?.take(4)?.joinToString(" | ")}" }
        }
        almacen.clear()
        assertTrue(fallas.isEmpty(), "No se construyeron ${fallas.size} de ${clases.size}:\n" + fallas.joinToString("\n"))
        println("ViewModels construidos: ${clases.size}")
    }

    /**
     * Lo que la navegación le da a un ViewModel de detalle. Medido el 29-sep: la app lee de SavedStateHandle
     * sólo "reservationId" (ReservationDetailViewModel, con checkNotNull) y "sessionId". Si un ViewModel falla
     * por un argumento que falta, se agrega su llave AQUÍ (leída de su código), nunca un binding falso.
     */
    private fun argumentosDeNavegacion() = SavedStateHandle(mapOf("reservationId" to "prueba", "sessionId" to "prueba"))
}
