package com.avoqado.pos.escritorio.calendario

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.avoqado.escritorio.calendario.CalendarioDeEscritorio
import com.avoqado.escritorio.calendario.CalendarioPendiente
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** El calendario de Reportes en la escena real de Compose: aparece, Aceptar devuelve la fecha, Cancelar no. */
@OptIn(ExperimentalTestApi::class)
class CalendarioEnLaEscenaTest {
    @AfterTest fun limpiar() = CalendarioDeEscritorio.cancelar()

    /** Guarda y restaura los TRES valores del locale (general, pantalla, formatos): restaurar sólo el general pisa las categorías. */
    private fun conLocaleRestaurado(bloque: () -> Unit) {
        val general = Locale.getDefault()
        val pantalla = Locale.getDefault(Locale.Category.DISPLAY)
        val formatos = Locale.getDefault(Locale.Category.FORMAT)
        try { bloque() } finally {
            Locale.setDefault(general)
            Locale.setDefault(Locale.Category.DISPLAY, pantalla)
            Locale.setDefault(Locale.Category.FORMAT, formatos)
        }
    }

    @Test fun `show abre el calendario y Aceptar devuelve la fecha inicial con el mes de 0 a 11`() = runComposeUiTest {
        val vistas = mutableListOf<Triple<Int, Int, Int>>()
        setContent { MaterialTheme { CalendarioPendiente() } }
        runOnIdle { android.app.DatePickerDialog(null, { _, y, m, d -> vistas += Triple(y, m, d) }, 2026, 9, 15).show() }
        onNodeWithText("Aceptar").performClick()
        waitForIdle()
        assertEquals(listOf(Triple(2026, 9, 15)), vistas)
        assertNull(CalendarioDeEscritorio.pendiente)
        onNodeWithText("Aceptar").assertDoesNotExist()
    }

    @Test fun `Cancelar cierra sin tocar la fecha`() = runComposeUiTest {
        var llamado = false
        setContent { MaterialTheme { CalendarioPendiente() } }
        runOnIdle { android.app.DatePickerDialog(null, { _, _, _, _ -> llamado = true }, 2026, 9, 15).show() }
        onNodeWithText("Cancelar").performClick()
        waitForIdle()
        assertEquals(false, llamado)
        onNodeWithText("Aceptar").assertDoesNotExist()
    }

    @Test fun `con Windows en ingles y formatos de Mexico el calendario dice Seleccionar fecha`() = conLocaleRestaurado {
        Locale.setDefault(
            com.avoqado.pos.escritorio.idiomaDeLaApp(
                pantalla = Locale.forLanguageTag("en-US"),
                formatos = Locale.forLanguageTag("es-MX"),
            ),
        )
        runComposeUiTest {
            setContent { MaterialTheme { CalendarioPendiente() } }
            runOnIdle { android.app.DatePickerDialog(null, { _, _, _, _ -> }, 2026, 9, 15).show() }
            onNodeWithText("Seleccionar fecha").assertExists()
            onNodeWithText("Select date").assertDoesNotExist()
        }
    }

    @Test fun `control - sin el arreglo, con locale en-US, el calendario sale en ingles`() = conLocaleRestaurado {
        Locale.setDefault(Locale.forLanguageTag("en-US"))
        runComposeUiTest {
            setContent { MaterialTheme { CalendarioPendiente() } }
            runOnIdle { android.app.DatePickerDialog(null, { _, _, _, _ -> }, 2026, 9, 15).show() }
            onNodeWithText("Select date").assertExists()
        }
    }

    // El arreglo del P1 deja FORMAT como lo tiene Windows: el calendario debe seguir en español aunque los formatos sean
    // de otro idioma (Material3 lee Locale.getDefault(), no la categoría FORMAT). Con la pantalla en-US y formatos en-GB.
    @Test fun `con el idioma aplicado y formatos en-GB el calendario sigue en espanol`() = conLocaleRestaurado {
        Locale.setDefault(Locale.forLanguageTag("en-US"))
        Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag("en-GB"))
        com.avoqado.pos.escritorio.aplicarIdiomaDeLaApp()
        runComposeUiTest {
            setContent { MaterialTheme { CalendarioPendiente() } }
            runOnIdle { android.app.DatePickerDialog(null, { _, _, _, _ -> }, 2026, 9, 15).show() }
            onNodeWithText("Seleccionar fecha").assertExists()
            onAllNodes(hasText("October", substring = true), useUnmergedTree = true).assertCountEquals(0)
        }
    }

    // Codex #6: un calendario que reemplaza a otro YA visible debe abrir con SU fecha, no con lo que se eligió en el anterior.
    // Sin key(solicitud) el estado del DatePicker se conserva entre solicitudes y B devolvería el 20 de octubre de A.
    @Test fun `un calendario nuevo reemplaza al visible con su fecha y solo su listener recibe la eleccion`() = conLocaleRestaurado {
        Locale.setDefault(Locale.forLanguageTag("en-US"))   // textos predecibles para el encabezado («Oct 20, 2026»)
        runComposeUiTest {
            val deA = mutableListOf<Triple<Int, Int, Int>>()
            val deB = mutableListOf<Triple<Int, Int, Int>>()
            setContent { MaterialTheme { CalendarioPendiente() } }
            runOnIdle { android.app.DatePickerDialog(null, { _, y, m, d -> deA += Triple(y, m, d) }, 2026, 9, 15).show() }
            // La celda del día es un botón fusionado cuyo texto es la fecha completa («Tuesday, October 20, 2026»); el «20»
            // visible lleva clearAndSetSemantics, así que hasText("20") no encuentra nada.
            onNode(hasText("October 20, 2026", substring = true) and hasClickAction(), useUnmergedTree = true).performClick()
            waitForIdle()
            onNodeWithText("Oct 20, 2026").assertExists()   // A conserva el 20 tras recomponer
            runOnIdle { android.app.DatePickerDialog(null, { _, y, m, d -> deB += Triple(y, m, d) }, 2026, 10, 3).show() }
            onNodeWithText("Aceptar").performClick()
            waitForIdle()
            assertEquals(listOf(Triple(2026, 10, 3)), deB)
            assertEquals(emptyList<Triple<Int, Int, Int>>(), deA)
            assertNull(CalendarioDeEscritorio.pendiente)
        }
    }

    // Codex #7: una fecha inicial fuera de 1900-2100 abre el calendario (no truena), sin selección y con Cancelar operativo.
    @Test fun `una fecha inicial fuera de rango abre el calendario sin seleccion y Cancelar lo cierra`() = runComposeUiTest {
        var llamado = false
        setContent { MaterialTheme { CalendarioPendiente() } }
        runOnIdle { android.app.DatePickerDialog(null, { _, _, _, _ -> llamado = true }, 2200, 0, 1).show() }
        onNodeWithText("Cancelar").assertExists()
        onNodeWithText("Aceptar").assertIsNotEnabled()
        onNodeWithText("Cancelar").performClick()
        waitForIdle()
        assertEquals(false, llamado)
        assertNull(CalendarioDeEscritorio.pendiente)
        onNodeWithText("Cancelar").assertDoesNotExist()
    }
}
