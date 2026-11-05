package com.avoqado.pos.transactions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 🔴 TRES INVARIANTES DE LA HOJA DE REEMBOLSO, MEDIDOS EN LA SUNMI EL 2026-09-11.
 *
 * El founder la describió como «horrible: tapaba los montos». Al recorrerla en el aparato
 * salieron tres cosas distintas, y ninguna la ve el compilador:
 *
 *   1. Un HUECO enorme entre la lista y el motivo, con los controles apretados abajo.
 *      Causa: la banda del medio usaba `weight(1f, fill = true)`, que la estira a TODO
 *      el alto sobrante aunque el contenido sea corto. (Era una hoja que medía según su
 *      contenido. A pantalla completa, desde el 3-nov, lo que sobra sí va ahí: ver la guarda.)
 *   2. Con 3 artículos sólo se veían 2, y NADA decía que hubiera más. La banda sí se
 *      desliza; simplemente no lo parecía. Un cajero reembolsa lo que alcanza a ver.
 *   3. Al abrir el teclado, la casilla «Incluir propina» salía de la pantalla. Esa
 *      casilla decide si la propina del mesero se devuelve: es DINERO confirmándose a
 *      ciegas.
 *
 * Este guard mira el CÓDIGO FUENTE porque este módulo no tiene Robolectric ni
 * `compose.ui.test`: aquí ninguna prueba puede renderizar la hoja y mirar el pixel. Misma
 * técnica que [RefundSheetDesignSystemGuardTest] y [RefundCashDrawerOwnershipTest]. La hoja de
 * verdad, con sus tamaños, se monta en WINDOWS: `desktop/pos/.../EmitirReembolsoEnLaEscenaTest`.
 *
 * ⚠️ **LO QUE ESTE GUARD NO PRUEBA, declarado para no prometer de más.** Una auditoría
 * (Codex gpt-6-astra, 2026-09-12) aplicó ocho mutaciones: **cae con la que motivó el
 * guard** —borrar la llamada de la casilla— y **pasó con estas siete**. No se ha vuelto a medir desde que las
 * guardas miden por pieza y por rama (spec 2026-11-02): algunas ya deberían caer.
 *
 * | Mutación | Qué se perdería sin que el guard lo note |
 * |---|---|
 * | Comentar la llamada (comentario de bloque) | la casilla desaparece; `includeTip` se queda en `true` |
 * | `if (false && tab == …)` | la llamada existe y nunca se pinta |
 * | Envolver la llamada en una `Column` con `verticalScroll` | la casilla vuelve a poder salirse de la pantalla |
 * | `onIncludeTipChange = { }` | la casilla se ve y el cajero no puede cambiarla |
 * | `"${if (false) transaction.items.size else ""} artículos"` | el número desaparece; el guard sólo busca el texto |
 * | Quitar `.verticalScroll(bandaScroll)` | el contenido largo deja de poder desplazarse |
 * | `weight(1f)` en el `Box` y `weight(1f, fill = false)` en un `Spacer` hijo | vuelve el hueco muerto |
 *
 * 🔑 La lección es la de la casa: **un guard que promete más de lo que comprueba es peor
 * que uno que declara su alcance.** Esto caza la reversión perezosa —que es la que de
 * verdad ocurre— y nada más. Lo de «se ve y se alcanza» lo mide la escena de WINDOWS; la
 * casilla tocada en un aparato, la QA.
 */
class RefundSheetLayoutGuardTest {

    private val hoja = "app/src/main/java/com/avoqado/pos/transactions/presentation/IssueRefundSheet.kt"

    /**
     * Antes se prohibía `fill = true`, porque la hoja medía según su contenido y abría el hueco de arriba. A pantalla completa es al
     * revés: sin él, con 2 artículos, Cancelar y Reembolsar quedan a media pantalla (OrderPAD 3, 800×1280, 3-nov).
     */
    @Test
    fun `con pie fijo la banda toma el alto sobrante y el pie toca el fondo`() {
        val banda = PiezasDeLaHoja(leerCodigoSinComentarios(hoja)).queSeDevuelve.replace(Regex("""\s"""), "")

        assertTrue(
            "La banda dejó de ocupar el alto sobrante con pie fijo, o la tarjeta de regalo dejó de ir como iba.",
            banda.contains("Box(modifier=when{!bandaSeDesliza->ModifiersoloCompleto->Modifier.weight(1f,fill=false)else->Modifier.weight(1f)}"),
        )
    }

    /**
     * En «Por importe» no hay lista que estirar: lo que se escribe va arriba, lo que sobra en medio y lo demás abajo, igual que en
     * Artículos. Por eso ahí la banda queda de su tamaño, sin deslizarse (su deslizamiento con alto infinito truena), y lo que sobra lo
     * toma un `Spacer` que siempre existe: si apareciera sólo a veces, cambiaría de lugar lo de abajo y el teclado cruza este corte.
     */
    @Test
    fun `con pie fijo por importe lo que sobra va abajo del campo y la tarjeta de regalo no cambia`() {
        val ventana = ventanaDeLaPantalla()
        val piezas = PiezasDeLaHoja(leerCodigoSinComentarios(hoja))

        assertTrue(
            "La banda ya no se desliza sólo donde tiene un alto que repartir.",
            ventana.contains("val bandaSeDesliza = !todoSeDesliza && (dosColumnas || soloCompleto || tab == RefundTab.ITEMS)"),
        )
        assertTrue(
            "El `Spacer` dejó de ir, siempre y con su peso sólo en «Por importe», entre el campo y lo demás.",
            Regex("""importeYSuPropina\(\)\s*Spacer\(if \(tab == RefundTab\.AMOUNT && !todoSeDesliza && !soloCompleto\) Modifier\.weight\(1f\) else Modifier\)\s*Column\(""")
                .containsMatchIn(piezas.pie),
        )
        assertEquals("Sólo una columna lleva ese `Spacer`.", 0, invocaciones(piezas.dosColumnas, "Spacer"))
    }

    @Test
    fun `la banda avisa cuando queda contenido por debajo`() {
        val codigo = leerCodigoSinComentarios(hoja)

        // ⚠️ Lo que de verdad avisa es el CONTEO en el subtítulo: el degradado del borde va
        // de transparente a blanco sobre una lista blanca y NO se ve (comprobado en la Sunmi).
        // Este guard exige el conteo; `canScrollForward` se comprueba aparte y se declara que
        // su sola presencia no prueba que la señal sea visible (auditoría Codex, 2026-09-11).
        assertTrue(
            "El subtítulo de la lista dejó de decir cuántos artículos tiene la venta. Es la " +
                "única señal que un cajero VE de que hay más abajo: sin ella, una venta de 3 " +
                "enseña 2 y él reembolsa lo que alcanza a ver.",
            codigo.contains("transaction.items.size") && codigo.contains("artículo"),
        )
        val queSeDevuelve = PiezasDeLaHoja(codigo).queSeDevuelve
        assertEquals("La lista ya no se invoca en `queSeDevuelve`.", 1, invocaciones(queSeDevuelve, "ItemsBody"))
        assertTrue(
            "Desapareció `canScrollForward` (el degradado del borde de la banda). Si la banda no se desliza no aplica: su estado se queda con lo último.",
            queSeDevuelve.contains("if (bandaSeDesliza && bandaScroll.canScrollForward) SenalDeHayMas(Modifier.align(Alignment.BottomCenter))"),
        )
    }

    /**
     * 🔴 El que más importa: la casilla de la propina cambia CUÁNTO dinero se devuelve, así que en una columna no puede
     * vivir en la banda que se desliza (con el teclado abierto salía de la pantalla).
     */
    @Test
    fun `en una columna la casilla de propina vive en el pie, no en la banda desplazable`() {
        val codigo = leerCodigoSinComentarios(hoja)

        // `substringAfter` a secas se lleva TODO el resto del archivo —incluido el propio
        // `FilaIncluirPropina`— y el guard acusaba en falso. El cuerpo de `AmountBody`
        // termina donde empieza el siguiente `@Composable`.
        val cuerpoDelImporte = codigo
            .substringAfter("private fun AmountBody(")
            .substringBefore("@Composable")
        assertFalse(
            "La casilla «Incluir propina» volvió dentro de `AmountBody`: con el teclado abierto sale de la pantalla y el " +
                "cajero confirma incluir la propina sin verla. Va en `FilaIncluirPropina`, en el pie.",
            cuerpoDelImporte.contains("Incluir propina en el reembolso"),
        )

        // 🔴 Se miden las INVOCACIONES: un `contains("FilaIncluirPropina(")` sobre el archivo pasaba aunque se BORRARA la
        // llamada, porque encontraba la propia definición (Codex, 2026-09-11). Sin llamada, `includeTip` se queda en `true`
        // y el cajero pierde la forma de conservarle la propina al mesero.
        val piezas = PiezasDeLaHoja(codigo)
        assertEquals("La casilla del importe ya no se invoca.", 1, invocaciones(piezas.importeYSuPropina, "FilaIncluirPropina"))
        assertEquals("La casilla por artículos ya no se invoca.", 1, invocaciones(piezas.comoSeDevuelve, "FilaIncluirPropina"))
        assertEquals("En una columna, el campo y su casilla dejaron el pie.", 1, invocaciones(piezas.pie, "importeYSuPropina"))
        assertEquals("En una columna, la propina por artículos dejó el pie.", 1, invocaciones(piezas.pie, "comoSeDevuelve"))
        assertEquals("El campo del importe no se invoca una sola vez.", 1, invocaciones(piezas.importeYSuPropina, "AmountBody"))
    }

    /** A propósito: la izquierda tiene todo el alto, y lo que decide el dinero del mesero se sigue leyendo junto al botón. */
    @Test
    fun `en dos columnas el importe y su casilla van a la izquierda y el reparto junto al boton`() {
        val piezas = PiezasDeLaHoja(leerCodigoSinComentarios(hoja))

        assertTrue(
            "`AmountBody(` sólo entra a `queSeDevuelve` con dos columnas.",
            piezas.queSeDevuelve.contains("RefundTab.AMOUNT -> if (dosColumnas) importeYSuPropina()"),
        )
        assertEquals(1, invocaciones(piezas.queSeDevuelve, "importeYSuPropina"))
        assertEquals("Los dos lados no pueden pintar el campo.", 0, invocaciones(piezas.dosColumnas, "importeYSuPropina"))
        listOf("Incluye la propina del mesero", "Sin tocar la propina del mesero", "Importe a reembolsar").forEach {
            assertTrue("«$it» dejó el cierre: tiene que leerse junto al botón.", piezas.cierre.contains(it))
        }
    }

    @Test
    fun `devolver con vive en el pie y el envio manda refundMethod`() {
        val codigo = leerCodigoSinComentarios(hoja)
        val piezas = PiezasDeLaHoja(codigo)
        assertEquals("`FilaDevolverCon(` ya no se INVOCA en `comoSeDevuelve`.", 1, invocaciones(piezas.comoSeDevuelve, "FilaDevolverCon"))
        assertEquals("En una columna, `comoSeDevuelve` dejó el pie.", 1, invocaciones(piezas.pie, "comoSeDevuelve"))
        assertTrue(
            "El envío no pasa `refundMethod = devolverCon` (sólo si hay opciones; un crédito de cambio lo omite, RE-9).",
            piezas.cierre.contains(
                "refundMethod = refundMethodForSubmit(transaction.tenderSystemKind, devolverCon.takeIf { opcionesDeDevolucion.isNotEmpty() })",
            ),
        )
    }

    /**
     * El aviso de efectivo sale de la regla probada, del permiso real y del switch del código del encargado — no de un
     * rol en línea, y sin prometer un teclado que con el switch apagado no existe.
     */
    @Test
    fun `el aviso de efectivo usa la regla probada, el permiso y el switch del codigo del encargado`() {
        val codigo = leerCodigoSinComentarios(hoja)
        assertTrue("La hoja ya no llama `textoDeAutorizacionParaEfectivo(...)`.", codigo.contains("textoDeAutorizacionParaEfectivo("))
        val quienAbre = leerCodigoSinComentarios("app/src/main/java/com/avoqado/pos/transactions/presentation/TransactionDetailSheet.kt")
        assertTrue("Quien abre la hoja ya no le pasa `canRefundToCash`.", quienAbre.contains("roleManager.canRefundToCash"))
        assertTrue(
            "Quien abre la hoja ya no le pasa el switch `managerPinOverrideEnabled`.",
            quienAbre.contains("codigoDeEncargadoActivo = "),
        )
    }

    /**
     * Las dos reglas de dinero viven fuera del Composable para que alguien pueda
     * probarlas. Si vuelven a escribirse en línea dentro del `onClick`, se pierde eso.
     */
    @Test
    fun `el envio usa las reglas de dinero probadas, no aritmetica en linea`() {
        val codigo = leerCodigoSinComentarios(hoja)

        assertFalse(
            "Volvió la conversión a centavos escrita en línea. `(pesos * 100).toInt()` TRUNCA: " +
                "1 145 de los 20 000 importes entre \$0.01 y \$200 se devolverían un centavo cortos. " +
                "Usa `centavosDelImporte(...)`.",
            codigo.contains("* 100).toInt()"),
        )
        assertTrue(
            "El envío dejó de usar `centavosDelImporte(...)`.",
            codigo.contains("centavosDelImporte("),
        )
        assertTrue(
            "El envío dejó de usar `tipRefundCentsParaEnvio(...)`.",
            codigo.contains("tipRefundCentsParaEnvio("),
        )
    }

    // MARK: - La pantalla completa (spec 2026-11-02). Son de fuente: lo que se ve lo comprueba el aparato.

    @Test
    fun `emitir reembolso abre en la hoja modal a pantalla completa`() {
        val ventana = ventanaDeLaPantalla()

        assertEquals("La pantalla abre en una sola hoja.", 1, Regex("""\bAvoqadoModalBottomSheet\(""").findAll(ventana).count())
        val llamada = ventana.substringAfter("AvoqadoModalBottomSheet(").substringBefore(") {")
        // D6: se cierra por `cerrar`, que sólo olvida la apertura en la terminal y llama a onDismiss (ver la prueba de abajo).
        listOf("onDismissRequest = cerrar", "aPantallaCompleta = true", "dragHandle = null", "conXPropia = true")
            .forEach { assertTrue("La hoja perdió `$it`.", llamada.contains(it)) }
        // `\\b`: la lista de terminales, más arriba, es un `AvoqadoDialog(`, con su propia ventana.
        assertFalse(
            "Volvió un `Dialog`: su ventana nace del alto de la configuración (1920×972 en la Sunmi) y por la franja se toca el tab bar.",
            Regex("""\bDialog\(""").containsMatchIn(ventana),
        )
        listOf("heightIn(max = 720.dp)", "CircleBackButton")
            .forEach { assertFalse("Volvió `$it`: era de la hoja de antes.", ventana.contains(it)) }
        listOf("AvoqadoFullscreenHeader(", "title = \"Emitir reembolso\"", "navStyle = FullscreenHeaderNav.CLOSE", "showDivider = true")
            .forEach { assertTrue("La pantalla perdió `$it`.", ventana.contains(it)) }
    }

    /** Lo de pantalla completa tiene que valer en las dos ramas de la hoja: Android y Windows. Con `false`, ni se nota. */
    @Test
    fun `la hoja a pantalla completa vale en sus dos ramas`() {
        val casa = leerCodigoSinComentarios("app/src/main/java/com/avoqado/pos/designsystem/components/AvoqadoModalBottomSheet.kt")

        assertEquals(2, Regex("""\bModalBottomSheet\(""").findAll(casa).count())
        listOf("sheetMaxWidth = anchoMaximo", "shape = forma").forEach {
            assertEquals("`$it` no está en las dos ramas.", 2, Regex(Regex.escape(it)).findAll(casa).count())
        }
        listOf("BarreraDeAtajos()", "ImmersiveWindow()", "aPantallaCompleta: Boolean = false", "Dp.Unspecified", "RectangleShape", "detectVerticalDragGestures", "onPostFling")
            .forEach { assertTrue("La hoja de la casa perdió `$it`.", casa.contains(it)) }
        // Con la barra de estado oculta, en un celular con cámara dentro de la pantalla el título o la ✕ quedarían debajo. Va después
        // del `pointerInput`: esa franja tampoco debe arrastrar la hoja.
        val sinArrastre = bloqueDe(casa, "private fun ContenidoSinArrastre(")
        assertTrue(
            "`ContenidoSinArrastre` perdió `.displayCutoutPadding()` o quedó antes del `pointerInput`.",
            sinArrastre.indexOf("detectVerticalDragGestures") in 0 until sinArrastre.indexOf(".displayCutoutPadding()"),
        )
    }

    /** Se abre en su propia ventana, arriba de la pantalla. */
    @Test
    fun `la hoja de escoger cliente sigue siendo hoja`() {
        val cliente = leerCodigoSinComentarios(hoja).substringAfter("if (escogiendoCliente)").substringBefore("private fun ItemsBody(")
        assertTrue("No se pudo recortar el bloque de escoger cliente.", cliente.contains("CustomersView("))
        assertTrue("La hoja de escoger cliente dejó de ser `AvoqadoModalBottomSheet`.", cliente.contains("AvoqadoModalBottomSheet("))
    }

    /**
     * La ✕ y el regreso cierran como hoy, también con un envío en vuelo: el de saldo a favor vive en el modelo del
     * detalle y sobrevive; la pantalla sólo lo espera.
     */
    @Test
    fun `cerrar no se bloquea ni cancela el envio a saldo a favor`() {
        val ventana = ventanaDeLaPantalla()

        assertTrue(ventana.contains("onDismissRequest = cerrar"))
        assertTrue(ventana.contains("onNav = cerrar"))
        // `cerrar` no bloquea nada: olvida lo de la terminal de este pago (D6) y cierra, siempre.
        val fuente = leerCodigoSinComentarios("app/src/main/java/com/avoqado/pos/transactions/presentation/IssueRefundSheet.kt")
        assertTrue(fuente.contains("val cerrar: () -> Unit = { AperturasEnTerminal.olvidar(transaction.id); onDismiss() }"))
        assertFalse("La ✕ quedó con un bloqueo que hoy no existe.", ventana.contains("navEnabled"))
        assertTrue("El envío a saldo a favor ya no se lanza con `enviarASaldo`.", ventana.contains("{ enviar(cuerpo, llave) }"))
        assertTrue("La pantalla ya no sólo espera el envío.", ventana.contains("envio?.await()"))
        val quienAbre = leerCodigoSinComentarios("app/src/main/java/com/avoqado/pos/transactions/presentation/TransactionDetailSheet.kt")
        assertTrue("El envío dejó de vivir en el modelo del detalle.", quienAbre.contains("viewModel.enviarReembolsoASaldo("))
    }

    /**
     * Lo que le queda a la lista sale de medir las pestañas y el pie, nunca la banda: al soltar su peso crecería y la pantalla regresaría al
     * modo anterior, en bucle. Y el pie de «Por importe» trae otros renglones: lo medido allá no decide en Artículos.
     */
    @Test
    fun `lo que le queda a la lista se mide sin la banda y sólo en Artículos`() {
        val ventana = ventanaDeLaPantalla()
        val piezas = PiezasDeLaHoja(leerCodigoSinComentarios(hoja))

        fun medidas(trozo: String) = Regex("""\bonSizeChanged\b""").findAll(trozo).count()
        assertEquals("Las pestañas se miden, y arriba de la banda.", 1, medidas(piezas.queSeDevuelve.substringBefore("Box(")))
        assertEquals("Se midió la banda: al soltar su peso crece, y esto oscilaría.", 0, medidas(piezas.queSeDevuelve.substringAfter("Box(")))
        assertEquals("El pie se mide, con el error del envío adentro.", 1, medidas(piezas.pie))
        assertTrue("Lo medido dejó de decidir sólo en Artículos.", ventana.contains("val loDemasEnArticulos = if (tab == RefundTab.ITEMS)"))
        assertTrue("Lo medido en «Por importe» ya decide en Artículos.", ventana.contains("if (tab == RefundTab.ITEMS) altoDelPieEnArticulos = it.height"))
        assertTrue(
            "`onSizeChanged` calla si el alto no cambió: al volver a Artículos hay que tomar lo último medido.",
            Regex("""LaunchedEffect\(tab\)\s*\{\s*if \(tab == RefundTab\.ITEMS\)\s*\{\s*withFrameNanos \{ \}\s*altoDelPieEnArticulos = altoDelPie""").containsMatchIn(ventana),
        )
    }

    /** `maxHeight` es el alto real bajo el encabezado y sin el teclado: de él dependen los dos cortes del pie. */
    @Test
    fun `el alto del cuerpo no cuenta el encabezado y baja con el teclado`() {
        val ventana = ventanaDeLaPantalla()
        val cuerpo = ventana.indexOf("BoxWithConstraints(")

        assertTrue("El encabezado tiene que ir arriba del cuerpo.", ventana.indexOf("AvoqadoFullscreenHeader(") in 1 until cuerpo)
        assertTrue("El cuerpo perdió `imePadding()`.", ventana.substring(cuerpo).substringBefore(") {").contains(".imePadding()"))
        assertTrue(
            "El corte de una columna dejó de salir del alto real.",
            ventana.contains("val todoSeDesliza = !dosColumnas && !pieFijoEnUnaColumna(maxHeight.value, loDemasEnArticulos)"),
        )
        assertTrue(
            "El aviso de la terminal vive en una columna: en dos, lo que cuenta es su mitad.",
            ventana.contains("val anchoDeColumna = if (dosColumnas) maxWidth / 2 else maxWidth") &&
                ventana.contains("val avisoEnRenglon = anchoDeColumna >= 480.dp"),
        )
    }

    /**
     * Sólo la tarjeta de regalo lleva tope y va centrada. `widthIn` tiene que ir ANTES de `fillMaxWidth`: al revés no
     * topa nada (`fillMaxWidth` ya fijó el ancho), y así estaba en la hoja vieja.
     */
    @Test
    fun `solo la tarjeta de regalo lleva tope de ancho y va centrada`() {
        val ventana = ventanaDeLaPantalla()

        assertTrue(
            Regex("""\.align\(Alignment\.TopCenter\)\s*\.then\(if \(soloCompleto\) Modifier\.widthIn\(max = 640\.dp\) else Modifier\)\s*\.fillMaxWidth\(\)""")
                .containsMatchIn(ventana),
        )
        assertEquals("Con una columna común va a todo el ancho.", 1, Regex("""widthIn\(""").findAll(ventana).count())
        assertTrue("El tope es de la rama de una columna.", PiezasDeLaHoja(leerCodigoSinComentarios(hoja)).unaColumna.contains("widthIn("))
    }

    // MARK: - Las dos columnas (spec 2026-11-02, tarea 3)

    @Test
    fun `el acomodo sale de la regla pura, con el alto real del cuerpo`() {
        val ventana = ventanaDeLaPantalla()
        val cuerpo = ventana.substring(ventana.indexOf("BoxWithConstraints("))

        assertTrue("El corte dejó de salir de `enDosColumnas`.", cuerpo.contains("enDosColumnas(maxWidth.value, soloCompleto)"))
        assertTrue("El pie dejó de salir de `pieFijoEnDosColumnas`.", cuerpo.contains("pieFijoEnDosColumnas(maxHeight.value)"))
    }

    @Test
    fun `las tres piezas se invocan en las dos ramas`() {
        val piezas = PiezasDeLaHoja(leerCodigoSinComentarios(hoja))

        assertEquals(1, invocaciones(piezas.dosColumnas, "queSeDevuelve"))
        assertEquals(1, invocaciones(piezas.unaColumna, "queSeDevuelve"))
        assertEquals(1, invocaciones(piezas.dosColumnas, "comoSeDevuelve"))
        assertEquals(1, invocaciones(piezas.unaColumna, "comoSeDevuelve"))
        assertEquals("En dos columnas, una con el pie fijo y otra con el pie deslizable.", 2, invocaciones(piezas.dosColumnas, "cierre"))
        assertEquals(1, invocaciones(piezas.unaColumna, "cierre"))
    }

    /** Pestañas fijas, banda desplazable y pie: el mismo orden de la hoja de antes. */
    @Test
    fun `una columna conserva el orden de hoy`() {
        val piezas = PiezasDeLaHoja(leerCodigoSinComentarios(hoja))

        assertTrue(
            "La banda tiene que ir arriba del pie.",
            piezas.unaColumna.indexOf("queSeDevuelve()") in 0 until piezas.unaColumna.indexOf("importeYSuPropina()"),
        )
        assertTrue(
            "El pie cambió de orden: importe y su casilla, cómo se devuelve y cierre.",
            Regex("""importeYSuPropina\(\)\s*Spacer\([^\n]*\)\s*Column\([^{]*\{[^}]*\}[^{]*\{\s*comoSeDevuelve\(\)\s*cierre\(\)\s*\}""").containsMatchIn(piezas.unaColumna),
        )
        // Arriba, con todo deslizado o con el teclado abierto, nadie lo veía y la cajera volvía a tocar (SAMSUNG, 3-nov).
        assertEquals("El error del envío va en el cierre, pegado al botón, en las dos ramas.", 1, invocaciones(piezas.cierre, "errorDelEnvio"))
        assertEquals("El error del envío ya no va arriba del pie.", 0, invocaciones(piezas.unaColumna, "errorDelEnvio"))
    }

    /**
     * El motivo va en la parte desplazable de la derecha A PROPÓSITO: a esa altura lo que no se puede perder es el botón, y
     * lo que falta se dice en lo fijo. `comoSeDevuelve()` no cambia de lugar cuando `pieFijo` cambia: abrir el teclado desde el
     * teléfono del saldo no debe recrear el campo, o perdería el foco y se cerraría el teclado.
     */
    @Test
    fun `en dos columnas el motivo se desliza a proposito y el pie solo se fija con alto`() {
        val piezas = PiezasDeLaHoja(leerCodigoSinComentarios(hoja))

        assertTrue("El motivo dejó `comoSeDevuelve`.", piezas.comoSeDevuelve.contains("Selecciona un motivo"))
        assertTrue("«Elige el motivo» dejó el cierre.", piezas.cierre.contains("Elige el motivo del reembolso."))
        assertTrue(
            "La derecha ya no es: lo de cómo en su `verticalScroll`, y el cierre dentro sólo sin pie fijo.",
            Regex("""Column\(Modifier\.fillMaxWidth\(\)\.verticalScroll\(pieScroll\)\)\s*\{\s*comoSeDevuelve\(\)\s*if \(!pieFijo\) cierre\(\)\s*\}[^}]*\}\s*if \(pieFijo\) cierre\(\)""")
                .containsMatchIn(piezas.dosColumnas),
        )
    }

    @Test
    fun `dos columnas parejas con su raya en medio`() {
        val dos = PiezasDeLaHoja(leerCodigoSinComentarios(hoja)).dosColumnas

        assertTrue(dos.contains("Row(Modifier.fillMaxSize())"))
        assertEquals("Cada columna con `weight(1f)` y todo el alto.", 2, Regex("""\.weight\(1f\)\s*\.fillMaxHeight\(\)""").findAll(dos).count())
        assertEquals(1, invocaciones(dos, "VerticalDivider"))
    }

    /** El envío se muda de lugar, no de reglas: sigue delimitado por lo mismo y pasa por las mismas guardas. */
    @Test
    fun `el envio sigue en el cierre, con sus comprobaciones de dinero`() {
        val cierre = PiezasDeLaHoja(leerCodigoSinComentarios(hoja)).cierre
        val envio = cierre.substringAfter("text = textoDelBotonDeReembolso(", "").substringBefore("enabled = canSubmit", "")

        assertTrue("No se encontró el envío entre `text = textoDelBotonDeReembolso(` y `enabled = canSubmit`.", envio.isNotBlank())
        listOf("antesDeReembolsar()", "puedeEnviar()", "conexion.iniciar(", "refundRepository.issueAssociatedRefund(", "centavosDelImporte(", "tipRefundCentsParaEnvio(")
            .forEach { assertTrue("El envío perdió `$it`.", envio.contains(it)) }
    }

    /**
     * 🔴 Abrir el teclado en un celular cruza `pieFijoEnUnaColumna`. Si ese cruce cambiara la estructura, el campo cambiaría de padre:
     * pierde el foco, el teclado se cierra, el alto sube y se repite en bucle. Por eso son los mismos nodos, y sólo cambian sus
     * modificadores. Un `verticalScroll` dentro de otro con alto infinito truena: la banda suelta el suyo y su `weight`.
     */
    @Test
    fun `con poco alto en una columna sólo cambian los modificadores de los mismos nodos`() {
        val ventana = ventanaDeLaPantalla()
        val piezas = PiezasDeLaHoja(leerCodigoSinComentarios(hoja))
        val cuerpo = ventana.substring(ventana.indexOf("BoxWithConstraints("))

        assertTrue(cuerpo.contains("val todoSeDesliza = !dosColumnas && !pieFijoEnUnaColumna(maxHeight.value, loDemasEnArticulos)"))
        assertFalse("`todoSeDesliza` decide estructura.", Regex("""if \(!?todoSeDesliza\)\s*\{""").containsMatchIn(cuerpo))
        // Se quitó el 3-nov: con todo deslizable ya no hacía falta, y sólo escondía el aviso de la terminal, con su botón.
        listOf("apretado", "584").forEach { assertFalse("Volvió `$it`: esconder la banda dejaba «Abrir en la terminal» sin alcance.", ventana.contains(it)) }
        assertFalse("Algo volvió a esconder la banda.", Regex("""\bif \([^)]*\)\s*\{\s*Box\(""").containsMatchIn(piezas.queSeDevuelve))
        assertTrue(
            "La columna de afuera dejó de llevar el deslizamiento o su mínimo. Con un tope, lo que no cabe se recorta sin alcance.",
            Regex("""\.verticalScroll\(pieScroll\)\s*\.then\(if \(todoSeDesliza\) Modifier else Modifier\.heightIn\(min = maxHeight\)\)""")
                .containsMatchIn(piezas.unaColumna),
        )
        assertTrue(
            "La banda dejó de soltar su `verticalScroll` donde no tiene un alto que repartir.",
            piezas.queSeDevuelve.contains(".then(if (bandaSeDesliza) Modifier.verticalScroll(bandaScroll) else Modifier)"),
        )
    }

    /** La derecha también avisa, con el pie fijo o sin él; y una columna, cuando se desliza. */
    @Test
    fun `lo que se desliza avisa si hay mas`() {
        val piezas = PiezasDeLaHoja(leerCodigoSinComentarios(hoja))

        assertTrue(
            "La derecha perdió su señal de «hay más».",
            piezas.dosColumnas.contains("if (pieScroll.canScrollForward) SenalDeHayMas(Modifier.align(Alignment.BottomCenter))"),
        )
        assertTrue(
            "Una columna perdió su señal de «hay más».",
            piezas.unaColumna.contains("if (pieScroll.canScrollForward) SenalDeHayMas(Modifier.align(Alignment.BottomCenter))"),
        )
        val senal = bloqueDe(leerCodigoSinComentarios(hoja), "private fun SenalDeHayMas(")
        assertTrue("La señal dejó de ser el degradado de la banda.", senal.contains("Brush.verticalGradient"))
    }

    /**
     * Cruzar el corte de 840 en WINDOWS recrea lo que hay en cada rama. Por eso nada capturado vive en ellas: un `remember` en
     * una pieza se perdería ahí, y `rememberSaveable` también, porque su llave sale del lugar en el árbol.
     */
    @Test
    fun `cruzar el corte no pierde lo capturado porque vive arriba de las ramas`() {
        val codigo = leerCodigoSinComentarios(hoja)
        val ventana = ventanaDeLaPantalla()
        val piezas = PiezasDeLaHoja(codigo)
        val cuerpo = ventana.indexOf("BoxWithConstraints(")

        listOf(piezas.errorDelEnvio, piezas.importeYSuPropina, piezas.queSeDevuelve, piezas.comoSeDevuelve, piezas.cierre, piezas.dosColumnas, piezas.unaColumna)
            .forEach { assertFalse("Una pieza o una rama trae su propio `remember`.", it.contains("remember")) }
        listOf("val bandaScroll = rememberScrollState()", "val pieScroll = rememberScrollState()")
            .forEach { assertTrue("`$it` tiene que ir arriba del cuerpo.", ventana.indexOf(it) in 1 until cuerpo) }
        assertTrue(
            "El teléfono a medio escribir ya no vive arriba de las ramas.",
            ventana.substring(0, cuerpo).contains("var telefonoDelSaldo by rememberSaveable(clienteDelSaldo?.id, pideTelefono)"),
        )
        val fila = bloqueDe(codigo, "private fun FilaClienteDelSaldo(")
        assertFalse("`FilaClienteDelSaldo` volvió a guardar su teléfono.", fila.contains("remember"))
        assertFalse("`FilaClienteDelSaldo` repite la condición de `pideTelefono`.", fila.contains("phoneChecked"))
    }

    /** El cuerpo de `IssueRefundSheet` hasta la hoja de escoger cliente, o sea la pantalla. */
    private fun ventanaDeLaPantalla(): String {
        val ventana = leerCodigoSinComentarios(hoja).substringAfter("fun IssueRefundSheet(").substringBefore("if (escogiendoCliente)")
        assertTrue("El recorte de la pantalla ya no llega al botón de reembolsar.", ventana.contains("text = textoDelBotonDeReembolso("))
        return ventana
    }

    /** Quita comentarios para que un guard no se dé por satisfecho con una mención en prosa. */
    private fun leerCodigoSinComentarios(rutaRelativa: String): String =
        leerFuente(rutaRelativa)
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("""//[^\n]*"""), " ")

    /**
     * El working dir de los tests es `app/` o la raíz del repo según cómo se invoque
     * Gradle. Un guard que no encuentra su archivo y pasa en silencio no vale nada:
     * se busca hacia arriba y se revienta si no aparece. (Misma forma que
     * [RefundSheetDesignSystemGuardTest]; la primera versión de este archivo usaba una
     * ruta relativa pelada y los 4 guards fallaron por no encontrar la hoja.)
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

/** Cuántas veces se INVOCA `nombre` en el trozo. */
internal fun invocaciones(trozo: String, nombre: String): Int = Regex("""\b$nombre\(""").findAll(trozo).count()

/**
 * Lo de adentro de las llaves que abren después de [inicio]. Revienta si no aparece o está vacío: una guarda sin bloque pasa en
 * verde sin medir nada. Recibe código ya sin comentarios.
 */
internal fun bloqueDe(codigo: String, inicio: String): String {
    val desde = codigo.indexOf(inicio)
    assertTrue("No se encontró `$inicio`.", desde >= 0)
    val abre = codigo.indexOf('{', desde + inicio.length)
    assertTrue("`$inicio` no abre ningún bloque.", abre >= 0)
    var nivel = 0
    for (i in abre until codigo.length) {
        if (codigo[i] == '{') nivel++
        if (codigo[i] == '}' && --nivel == 0) {
            val bloque = codigo.substring(abre + 1, i)
            assertTrue("El bloque de `$inicio` está vacío.", bloque.isNotBlank())
            return bloque
        }
    }
    throw AssertionError("El bloque de `$inicio` no cierra.")
}

/**
 * Las piezas de «Emitir reembolso» y sus dos ramas, recortadas por sus llaves. Antes se recortaba por marcas de texto (`=== PIE
 * FIJO`), y con piezas eso daba verde aunque una rama nunca las invocara.
 */
internal class PiezasDeLaHoja(codigoSinComentarios: String) {
    private val ventana = codigoSinComentarios.substringAfter("fun IssueRefundSheet(").substringBefore("if (escogiendoCliente)")
    val errorDelEnvio = bloqueDe(ventana, "val errorDelEnvio:")
    val importeYSuPropina = bloqueDe(ventana, "val importeYSuPropina:")
    val queSeDevuelve = bloqueDe(ventana, "val queSeDevuelve:")
    val comoSeDevuelve = bloqueDe(ventana, "val comoSeDevuelve:")
    val cierre = bloqueDe(ventana, "val cierre:")
    private val ramas = "if (dosColumnas) {" + ventana.substringAfterLast("if (dosColumnas) {", "")
    val dosColumnas = bloqueDe(ramas, "if (dosColumnas)")
    val unaColumna = bloqueDe(ramas.substringAfter(dosColumnas), "else")
    val pie = unaColumna.substringAfter("queSeDevuelve()", "").also { assertTrue("El pie de una columna quedó vacío.", it.isNotBlank()) }
}
