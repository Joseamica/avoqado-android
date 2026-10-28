package com.avoqado.pos.giftcards

import com.avoqado.pos.payment.presentation.cerrarDialogoDeRecibo
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Guards de cableado: sin arnés Compose. La visibilidad final queda para ROOT R1 en aparato. */
class RegresionesDeRedR4Test {
    private fun fuente(nombre: String, carpeta: String = "payment"): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        val ruta = "app/src/main/java/com/avoqado/pos/$carpeta/presentation/$nombre.kt"
        while (dir != null) {
            val file = File(dir, ruta)
            if (file.isFile) return file.readText().replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("//[^\\n]*"), "")
            dir = dir.parentFile
        }
        throw AssertionError("No se encontró $ruta")
    }
    @Test fun `monitor con detalle y dialogo no vuelve a cargar ni desmonta`() {
        val codigo = fuente("CobroDeCuentaScreen")
        val claves = Regex("LaunchedEffect\\(([^)]*)\\)").find(codigo)!!.groupValues[1].split(',').map { it.trim() }
        for ((ruta, servidor) in listOf(false to true, true to false)) {
            fun clave(r: Boolean, s: Boolean) = claves.map { when(it) { "conectado" -> r; "servidor" -> s; else -> "cuenta" } }
            var anterior = clave(true, true)
            var cargas = 0
            var detalle: String? = "cuenta cargada"
            var dialogo = "cancelar"
            val motivo = "El cliente cambió de opinión"
            for ((r, s) in listOf(ruta to servidor, true to true)) {
                val siguiente = clave(r, s)
                if (siguiente != anterior && r && s) { cargas++; detalle = null; dialogo = "" }
                anterior = siguiente
            }
            assertEquals("Volver un monitor no llama cargar", 0, cargas)
            assertEquals("cuenta cargada", detalle)
            assertEquals("cancelar", dialogo)
            assertEquals("El cliente cambió de opinión", motivo)
        }
        for ((nombre, carpeta) in listOf("CobroDeCuentaScreen" to "payment", "VenderTarjetaScreen" to "giftcards",
            "ResultadoDeVentaScreen" to "giftcards", "CuentaDeTarjetasScreen" to "giftcards")) {
            val efectos = Regex("LaunchedEffect\\(([^)]*)\\)").findAll(fuente(nombre, carpeta)).map { it.groupValues[1] }.toList()
            assertFalse("$nombre reinicia por monitor", efectos.any { it.contains("conectado") || it.contains("servidor") })
        }
    }
    @Test fun `recibos despachados cierran aunque la respuesta se pierda o falle`() {
        val codigo = fuente("PaymentResultScreen").replace(Regex("\\s+"), "")
        for (estado in listOf("éxito", "respuesta perdida", "rechazo")) {
            assertTrue(estado, cerrarDialogoDeRecibo(true))
            for (dialogo in listOf("showWhatsAppInput", "showEmailInput", "showTerminalPrintDialog")) {
                assertTrue("$estado/$dialogo: cierre independiente del resultado y del default", codigo.contains("if(cerrarDialogoDeRecibo(true))$dialogo=false"))
            }
        }
        assertFalse("Guarda previa sin petición conserva lo escrito", cerrarDialogoDeRecibo(false))
        assertFalse("No espera mensajes de éxito para cerrar", codigo.contains("LaunchedEffect(whatsAppResultMessage,emailResultMessage,printResultMessage)"))
    }
    @Test fun `cancelar cuenta no ofrece repetir una peticion despachada`() {
        val codigo = fuente("CobroDeCuentaScreen").replace(Regex("\\s+"), "")
        assertFalse("El Boolean del modelo no prueba que el POST nunca salió", codigo.contains("confirmar=cancelarCuentaDesdeDialogo"))
        assertTrue("Sin red actual se conserva el diálogo antes de entregar el envío", codigo.contains("if(!viewModel.conectado.value||!viewModel.servidor.value)return@PrimaryButton"))
        assertTrue("Al entregar el envío se cierra como r2", codigo.contains("confirmar=falseenviando=true"))
    }
    @Test fun `declaracion despachada cierra y deja visible el rechazo ambar`() {
        val codigo = fuente("PaymentResultScreen").replace(Regex("\\s+"), "")
        assertTrue("La declaración cierra antes de entregar el envío al modelo", codigo.contains("if(cerrarDialogoDeRecibo(true))pedirConfirmacionDeclaracion=falseonDeclararNoCobrado()"))
        assertTrue(codigo.contains("text=message"))
        assertTrue(codigo.contains("color=if(declaracionRechazada)WarningelseMaterialTheme.colorScheme.onSurfaceVariant"))
        val flujo = fuente("PaymentFlowScreen").replace(Regex("\\s+"), "")
        assertTrue("Publica el rechazo del modelo", flujo.contains("declaracionRechazada=currentState.declaracionRechazada"))
    }
}
