package com.avoqado.pos.transactions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 🔴 EL FORMULARIO DEL REEMBOLSO USA EL COMPONENTE DE LA CASA.
 *
 * Medido en la D3 el 2026-08-17: los campos "Importe" y "Motivo del reembolso"
 * usaban `OutlinedTextField` crudo de Material3. Su etiqueta FLOTANTE se monta
 * sobre el borde y, dentro de la hoja apretada del reembolso, se veía CORTADA y
 * con dos contornos encimados. El design system del repo lo prohíbe explícitamente:
 *
 *     | Form input inside dialog | `AvoqadoPillTextField` (48dp, rounded 50) | Raw `OutlinedTextField` |
 *
 * Este guard mira el CÓDIGO FUENTE porque no hay otra forma: el módulo no tiene
 * Robolectric ni `compose.ui.test`, así que ningún test puede renderizar la hoja
 * y mirar el pixel. Es la misma técnica que ya usa
 * `RefundCashDrawerOwnershipTest` para vigilar que el cliente no escriba en el
 * cajón — un guard de fuente vale más que ninguno cuando la alternativa es que
 * el defecto sólo se vea en una tablet.
 *
 * ⚠️ Alcance: SÓLO esta hoja. Quedan 37 archivos con `OutlinedTextField` crudo
 * (incluido `UnassociatedRefundSheet.kt`, la otra hoja de reembolso, con 4).
 * Es deuda declarada, no un descuido de este guard: ampliarlo hoy lo dejaría
 * rojo desde el primer día y alguien lo borraría.
 */
class RefundSheetDesignSystemGuardTest {

    private val hoja = "app/src/main/java/com/avoqado/pos/transactions/presentation/IssueRefundSheet.kt"

    @Test
    fun `la hoja de reembolso no usa OutlinedTextField crudo`() {
        val codigo = leerCodigoSinComentarios(hoja)

        assertFalse(
            "IssueRefundSheet volvió a usar `OutlinedTextField` crudo: su etiqueta " +
                "flotante se ve cortada sobre el borde. Usa `AvoqadoPillTextField`.",
            codigo.contains("OutlinedTextField"),
        )
    }

    @Test
    fun `la hoja de reembolso usa el campo de la casa`() {
        val codigo = leerCodigoSinComentarios(hoja)

        assertTrue(
            "IssueRefundSheet dejó de usar `AvoqadoPillTextField`.",
            codigo.contains("AvoqadoPillTextField("),
        )
    }

    /**
     * El importe se teclea con punto decimal, y el motivo NO se teclea: lo elige
     * un menú. Si el componente de la casa perdiera cualquiera de las dos cosas,
     * la migración habría cambiado el comportamiento en vez de sólo la pintura.
     */
    @Test
    fun `el importe sigue con teclado decimal y el motivo sigue sin teclearse`() {
        val codigo = leerCodigoSinComentarios(hoja)

        assertTrue(
            "El campo de importe perdió el teclado decimal.",
            codigo.contains("KeyboardType.Decimal"),
        )
        assertTrue(
            "El campo de motivo dejó de ser de sólo lectura: ahora se puede teclear " +
                "un motivo que el server no conoce.",
            codigo.contains("readOnly = true"),
        )
    }

    /**
     * Lo que el componente de la casa TUVO que aprender para poder recibir estos
     * dos campos: `readOnly`, contenido al inicio (el "$") y al final (el chevron
     * del desplegable). Si alguien los quita, esta hoja se queda sin salida y el
     * siguiente que la toque volverá al crudo.
     */
    @Test
    fun `AvoqadoPillTextField soporta lo que estos campos necesitan`() {
        val componente = leerCodigoSinComentarios(
            "app/src/main/java/com/avoqado/pos/designsystem/components/AvoqadoDialog.kt",
        )

        listOf("readOnly: Boolean", "leading: (@Composable () -> Unit)?", "trailing: (@Composable () -> Unit)?")
            .forEach { parametro ->
                assertTrue(
                    "`AvoqadoPillTextField` perdió `$parametro`; sin él el reembolso no " +
                        "puede usar el componente de la casa.",
                    componente.contains(parametro),
                )
            }
    }

    /**
     * 🔴 LA BANDA DE EN MEDIO SE DESPLAZA.
     *
     * Medido en una tablet el 2026-09-02: esa banda toma el alto sobrante con
     * `weight(1f)` y NO tenía `verticalScroll`. Compose recorta el desbordamiento
     * sin error y sin aviso, así que el selector de motivo quedaba dibujado fuera
     * de la pantalla — y como `canSubmit` exige motivo, el botón "Reembolsar"
     * jamás se encendía. La devolución era imposible y nada lo explicaba.
     *
     * Quitar esta línea NO rompe la compilación ni ninguna prueba de lógica: sólo
     * vuelve a esconder controles en pantallas bajas. Por eso hay guard.
     */
    @Test
    fun `la banda de en medio del reembolso se puede desplazar`() {
        val codigo = leerCodigoSinComentarios(hoja)

        assertTrue(
            "La banda con `weight(1f)` de IssueRefundSheet perdió `verticalScroll`: " +
                "lo que no quepa se recorta en silencio y deja controles inalcanzables.",
            codigo.contains("verticalScroll("),
        )
    }

    /**
     * 🔴 EL MOTIVO VIVE FUERA DEL ÁREA DESPLAZABLE.
     *
     * Es REQUISITO para habilitar "Reembolsar". Un requisito escondido detrás de
     * un scroll produce el peor de los estados: un botón apagado que el cajero lee
     * como "la app está rota". El guard comprueba el orden en el archivo: el
     * selector de motivo aparece DESPUÉS del cierre de la banda desplazable.
     *
     * Este es el único guard que lee la fuente CON comentarios: el separador
     * `// === PIE FIJO` es el mojón que marca la frontera entre las dos bandas, y
     * por eso forma parte del contrato — si alguien lo borra, la prueba lo dice.
     */
    @Test
    fun `el motivo del reembolso vive en el pie fijo, no dentro del scroll`() {
        val codigo = leerFuente(hoja)

        val finDeLaBanda = codigo.indexOf("=== PIE FIJO")
        val selectorDeMotivo = codigo.indexOf("Selecciona un motivo")

        assertTrue(
            "Desapareció el separador `// === PIE FIJO` de IssueRefundSheet: es el " +
                "mojón que separa la banda desplazable del pie siempre visible.",
            finDeLaBanda > 0,
        )
        assertTrue("No se encontró el selector de motivo en la hoja.", selectorDeMotivo > 0)
        assertTrue(
            "El selector de motivo volvió a quedar dentro del área desplazable. Es " +
                "obligatorio para poder reembolsar: tiene que estar siempre a la vista.",
            selectorDeMotivo > finDeLaBanda,
        )
    }

    /**
     * 🔴 EL DINERO NO SE FORMATEA CON EL LOCALE DEL APARATO.
     *
     * `"%.2f".format(x)` sin locale toma el del aparato: una tablet dejada en
     * español de España pintaba "$70,00" en esta hoja mientras la lista de atrás
     * decía "$231.00" — dos formatos del mismo dinero en la misma pantalla.
     * El formato depende de la MONEDA del negocio, no del idioma del aparato.
     *
     * ⚠️ Alcance: SÓLO esta hoja, igual que el guard de `OutlinedTextField` de
     * arriba. Quedan ~40 usos crudos de `%.2f` repartidos en el resto de la app
     * (no todos son dinero); ampliarlo hoy dejaría el guard rojo desde el primer
     * día y alguien lo borraría. Es deuda declarada, no un descuido.
     */
    @Test
    fun `la hoja de reembolso formatea el dinero con el helper de la plataforma`() {
        val codigo = leerCodigoSinComentarios(hoja)

        assertFalse(
            "IssueRefundSheet volvió a formatear dinero con `%.2f`, que usa el locale " +
                "del aparato. Usa `formatMoney(...)` de core/util/Money.kt.",
            codigo.contains("\"%.2f\".format"),
        )
        assertTrue(
            "IssueRefundSheet dejó de usar `formatMoney`.",
            codigo.contains("formatMoney("),
        )
    }

    // MARK: - Utilidades (mismo patrón que RefundCashDrawerOwnershipTest)

    /** El guard mira CÓDIGO, no prosa: los comentarios se quitan antes de buscar. */
    private fun leerCodigoSinComentarios(rutaRelativa: String): String =
        leerFuente(rutaRelativa)
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("""//[^\n]*"""), " ")

    /**
     * Los tests corren con el working dir en `app/` o en la raíz del repo según
     * cómo se invoque Gradle. Un guard que no encuentra su archivo y pasa en
     * silencio no vale nada: aquí se busca hacia arriba y se revienta si no aparece.
     */
    private fun leerFuente(rutaRelativa: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidato = File(dir, rutaRelativa)
            if (candidato.isFile) return candidato.readText()
            val sinModulo = File(dir, rutaRelativa.removePrefix("app/"))
            if (sinModulo.isFile) return sinModulo.readText()
            dir = dir.parentFile
        }
        throw AssertionError("No se encontró $rutaRelativa desde ${System.getProperty("user.dir")}")
    }
}
