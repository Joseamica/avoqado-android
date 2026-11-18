package com.avoqado.pos.customerdisplay

import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.JUnitCore
import org.junit.runner.Request

/**
 * TEMPORAL (encargo 5053f3 de la nube, 18-nov-2026): mide las dos pruebas de la pantalla del cliente que salían rojas al azar
 * en el CI, corriéndolas muchas veces con la máquina ocupada (hilos que giran en todos los núcleos, como un runner cargado).
 * Siempre termina en rojo A PROPÓSITO para que el CI suba el reporte con la cuenta. Se borra antes de unir.
 */
class MedicionInestablesTest {

    @Test
    fun medicion() {
        val pruebas = listOf(
            "logo en vuelo y luego 404 se queda en fabrica",
            "r6 2 - A logout A con la fila ocupada cancela la descarga de A y pasa por fabrica",
        )
        val vueltas = 150
        val parar = AtomicBoolean(false)
        val carga = List(Runtime.getRuntime().availableProcessors() * 2) {
            Thread {
                var x = 0L
                while (!parar.get()) x++
                if (x == -1L) println(x)
            }.apply { isDaemon = true; start() }
        }
        val resumen = StringBuilder()
        try {
            for (prueba in pruebas) {
                val fallas = mutableListOf<String>()
                val inicio = System.currentTimeMillis()
                repeat(vueltas) { i ->
                    val r = JUnitCore().run(Request.method(CustomerDisplaySyncTest::class.java, prueba))
                    r.failures.forEach { fallas += "vuelta $i: ${it.exception}" }
                }
                resumen.append("MEDICION «$prueba»: ${fallas.size} rojas de $vueltas vueltas con carga (${System.currentTimeMillis() - inicio} ms)\n")
                fallas.take(3).forEach { resumen.append("  ").append(it).append('\n') }
            }
        } finally {
            parar.set(true)
            carga.forEach { it.join() }
        }
        println(resumen)
        fail(resumen.toString())
    }
}
