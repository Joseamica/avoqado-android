package com.avoqado.pos.escritorio.e2e

import android.util.Log
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.avoqado.escritorio.Bitacora
import com.avoqado.escritorio.CandadoDeInstancia
import com.avoqado.escritorio.CarpetaDeDatos
import com.avoqado.pos.BuildConfig
import com.avoqado.pos.escritorio.AppEscritorio
import com.avoqado.pos.escritorio.Arranque
import java.io.File
import javax.imageio.ImageIO
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Una fase del recorrido, en su propio proceso (así «matar y reabrir» es de verdad).
 * Uso: FasesE2EKt en-linea | sin-red <centavos> | reabrir — con -Davoqado.datos, -Davoqado.api (el proxy) y -Davoqado.evidencia.
 * «en-linea» imprime FASE_OK y sale; «sin-red» imprime ENCOLADO y se queda viva; «reabrir» imprime ABIERTA y se queda viva.
 * Al que se queda vivo lo termina el orquestador. Si algo falla: vuelca la pantalla, la captura, imprime FASE_FALLO y sale 1.
 */
@OptIn(ExperimentalTestApi::class)
fun main(args: Array<String>) {
    // El mismo arranque que Main.kt, menos la ventana.
    Thread.setDefaultUncaughtExceptionHandler { _, e -> Log.e("SinCapturar", "Excepción no atrapada", e) }
    val carpeta = CarpetaDeDatos.resolver(produccion = false).also(Bitacora::iniciar)
    // Tras un kill -9 el sistema tiene que soltar el candado: si no, «reabrir» no abriría.
    if (!CandadoDeInstancia.tomar(carpeta)) { avisar("FASE_FALLO candado ocupado"); exitProcess(3) }
    if (System.getProperty("avoqado.version") == null) System.setProperty("avoqado.version", BuildConfig.VERSION_NAME)
    Arranque.abrir(carpeta)
    val fase = args.joinToString("-")
    vigilar(fase)
    runDesktopComposeUiTest(1280, 800) {
        try {
            setContent { AppEscritorio() }
            mainClock.advanceTimeBy(1_600)   // el splash (1.35 s + 220 ms) tapa los clics
            mainClock.autoAdvance = false     // el reloj lo empuja `esperar`; ver ahí por qué
            when (args[0]) {
                "en-linea" -> {
                    iniciarSesion(); captura("e2e-1-dentro")
                    cobrarEnEfectivo("8731")
                    check(onAllNodesWithText(EN_COLA, substring = true).fetchSemanticsNodes().isEmpty()) {
                        "con red el cobro no debía quedar en la cola"
                    }
                    captura("e2e-2-cobro-en-linea")
                    listo = true; avisar("FASE_OK")
                }
                "sin-red" -> {
                    cobrarEnEfectivo(args[1])
                    esperarTexto(EN_COLA)   // lo que ve el cajero: «guardado», no un error
                    captura("e2e-3-cobro-sin-red-${args[1]}")
                    clic("Venta nueva")
                    esperarTexto("Teclado", 30_000)
                    volcar("Cobrar sin red, después de la venta")
                    captura("e2e-3b-cobrar-sin-red-${args[1]}")
                    listo = true; avisar("ENCOLADO"); dormirMientrasVivaElPadre()
                }
                "reabrir" -> {
                    esperarTexto("Teclado", 60_000); captura("e2e-4-reabierta")
                    listo = true; avisar("ABIERTA"); dormirMientrasVivaElPadre()
                }
                else -> error("fase desconocida: ${args.toList()}")
            }
        } catch (t: Throwable) {
            Log.e("E2E", "La fase $fase falló", t)
            runCatching { volcar("FALLO en $fase") }
            runCatching { captura("e2e-fallo-$fase") }
            avisar("FASE_FALLO ${t.message?.lines()?.firstOrNull()}")
            exitProcess(1)
        }
    }
    exitProcess(0)
}

@Volatile private var listo = false

/**
 * Si la fase se cuelga (un clic que no vuelve, el hilo de Swing trabado) no hay excepción que atrapar: cada 30 s se
 * vuelcan las pilas de «main» y del hilo de Swing, y a los 240 s (el tope más largo del orquestador) se declara
 * FASE_FALLO y se sale sin esperar a nadie.
 */
private fun vigilar(fase: String) = thread(isDaemon = true, name = "vigia-e2e") {
    val inicio = System.nanoTime()
    while (true) {
        Thread.sleep(30_000)
        if (listo) return@thread
        val seg = (System.nanoTime() - inicio) / 1_000_000_000
        val pilas = Thread.getAllStackTraces().filterKeys { it.name == "main" || it.name.startsWith("AWT-EventQueue") }
            .entries.joinToString("\n") { (h, p) -> "${h.name} ${h.state}\n" + p.take(40).joinToString("\n") { "    at $it" } }
        Log.w("E2E", "La fase $fase lleva $seg s. Pilas:\n$pilas")
        if (seg >= 240) { avisar("FASE_FALLO la fase se colgó $seg s (pilas arriba)"); Runtime.getRuntime().halt(1) }
    }
}

/**
 * La fase que se queda viva espera a que el orquestador la termine. Pero si la JVM de Gradle muere sin llegar a su
 * `finally`, nadie lo haría: la fase quedaría huérfana con el candado de la carpeta y cientos de MB. Por eso duerme
 * sólo mientras viva su proceso padre, y nunca más de 15 min.
 */
private fun dormirMientrasVivaElPadre(): Nothing {
    val padre = ProcessHandle.current().parent().orElse(null)
    val hasta = System.nanoTime() + 15 * 60 * 1_000_000_000L
    while (padre?.isAlive == true && System.nanoTime() < hasta) Thread.sleep(1_000)
    avisar("FASE_SALE ${if (padre?.isAlive == true) "tope de 15 min" else "el proceso padre ya no existe"}")
    exitProcess(0)
}

/** El aviso del resultado de un cobro encolado sin red (PaymentResultScreen). */
private const val EN_COLA = "Se sincronizará cuando haya conexión"

private fun avisar(linea: String) { println(linea); System.out.flush() }

/**
 * Espera a que [cond] se cumpla, empujando el reloj de la prueba a mano (100 ms de pantalla por vuelta). Con el reloj en
 * automático, una pantalla que se redibuja sin parar deja a Compose esperando «reposo» para siempre (medido: el primer
 * intento se colgó 150 s en `waitForIdle` sin pasar nunca por el tope de `waitUntil`).
 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.esperar(que: String, ms: Long = 20_000, cond: () -> Boolean) {
    val hasta = System.nanoTime() + ms * 1_000_000
    while (!cond()) {
        check(System.nanoTime() < hasta) { "no apareció $que en ${ms / 1000} s" }
        mainClock.advanceTimeBy(100)
        Thread.sleep(50)
    }
}

@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.hay(texto: String, substring: Boolean = true) =
    onAllNodesWithText(texto, substring = substring).fetchSemanticsNodes().isNotEmpty()

@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.esperarTexto(texto: String, ms: Long = 20_000) = esperar("«$texto»", ms) { hay(texto) }

/** Vuelca el árbol de la pantalla a la bitácora: de aquí salen los textos exactos de cada botón. */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.volcar(motivo: String) = Log.i("E2E", "$motivo\n" + onAllNodes(isRoot()).printToString(maxDepth = 60))

/** El último nodo tocable con ese texto (el de más arriba en pantalla: diálogos y hojas se dibujan después). */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.tocable(texto: String, substring: Boolean = false): SemanticsNodeInteraction {
    val todos = onAllNodes(hasText(texto, substring = substring) and hasClickAction())
    val n = todos.fetchSemanticsNodes().size
    check(n > 0) { "no hay nada tocable con el texto «$texto»" }
    return todos[n - 1]
}

@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.clic(texto: String, substring: Boolean = false) {
    esperar("«$texto» tocable") {
        onAllNodes(hasText(texto, substring = substring) and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
    }
    asentar()
    tocable(texto, substring).performClick()
    asentar()
}

/**
 * Con el reloj a mano, una transición (el paso correo → contraseña, una hoja que sube) se queda a medias hasta que
 * alguien lo empuje, y un toque a media transición cae en la pantalla de ANTES (medido: el login no salió). Antes y
 * después de cada toque se dejan pasar 800 ms de pantalla, como la pausa de una persona.
 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.asentar() = mainClock.advanceTimeBy(800)

/** Una tecla del teclado: tocable y con ESE texto solo (el «1» de un contador o de un badge no cuenta). */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.tecla(t: String) {
    val esTecla = SemanticsMatcher("tecla «$t»") { n -> n.config.getOrNull(SemanticsProperties.Text)?.map { it.text } == listOf(t) }
    val teclas = onAllNodes(esTecla and hasClickAction())
    check(teclas.fetchSemanticsNodes().size == 1) { "la tecla «$t» no es única: ${teclas.fetchSemanticsNodes().size}" }
    teclas[0].performClick()
    asentar()
}

@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.iniciarSesion() {
    esperarTexto("Iniciar sesión")
    // LandingScreen → SignInFlowScreen: correo → Siguiente → contraseña → Iniciar sesión (textos del 29-sep).
    clic("Iniciar sesión")
    esperarTexto("Correo electrónico")
    asentar()
    onAllNodesWithText("Correo electrónico").let { it[it.fetchSemanticsNodes().size - 1] }
        .performTextInput(checkNotNull(System.getProperty("avoqado.e2e.correo")) { "falta avoqado.e2e.correo" })
    clic("Siguiente")
    esperarTexto("Contraseña")
    asentar()
    onAllNodesWithText("Contraseña").let { it[it.fetchSemanticsNodes().size - 1] }
        .performTextInput(checkNotNull(System.getProperty("avoqado.e2e.clave")) { "falta avoqado.e2e.clave" })
    clic("Iniciar sesión")
    esperarTexto("Teclado", 60_000)
    volcar("después del login")
}

/**
 * Receta (NumericKeypadView → CartPanelView → PaymentFlow, textos leídos del volcado del 30-sep): pestaña «Teclado» →
 * dígitos → «+» (sin esto los dígitos no llegan al carrito) → «Cobrar $87.32» → la TPV pide calificación («Omitir») y,
 * si la pide, propina («Sin propina») → fila «Efectivo» de los métodos: se toca el billete sugerido ($90, el redondeo a
 * $5 que calcula la app) → pantalla del resultado («Venta nueva» y el cambio). Termina EN el resultado.
 * (No hay «Exacto» en esta fila: las sugerencias de un total de $87.3x son $90 y $100; «Personalizado» abre un teclado.)
 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.cobrarEnEfectivo(centavos: String) {
    val importe = "$" + "%.2f".format(java.util.Locale.US, centavos.toInt() / 100.0)
    val billete = "$" + (kotlin.math.ceil(centavos.toInt() / 500.0) * 5).toInt()   // < $100: al múltiplo de $5 de arriba
    esperarTexto("Teclado", 60_000)
    clic("Teclado")
    esperarTexto("+ Nota")
    centavos.forEach { tecla(it.toString()) }
    esperarTexto(importe)   // el visor del teclado ya dice el importe
    tecla("+")
    esperarTexto("Cobrar $importe")   // el carrito tiene la línea y el total
    volcar("carrito con $importe")
    clic("Cobrar $importe", substring = true)
    // La TPV puede pedir calificación y propina antes de los métodos; se saltan.
    esperar("la calificación, la propina o los métodos", 30_000) {
        listOf("Personalizado", "Sin propina", "¿Cómo fue tu experiencia?").any { hay(it) }
    }
    if (hay("¿Cómo fue tu experiencia?")) {
        volcar("calificación")
        clic("Omitir")   // «Continuar» está deshabilitado hasta elegir estrellas
        esperar("la propina o los métodos") { listOf("Personalizado", "Sin propina").any { hay(it) } }
    }
    if (hay("Sin propina")) { volcar("propina"); clic("Sin propina") }
    esperarTexto("Personalizado")
    volcar("métodos de pago para $importe")
    clic(billete)
    esperarTexto("Venta nueva", 90_000)
    volcar("resultado del cobro de $importe")
}

/** La escena entera (con diálogos y hojas, que son raíces aparte), no sólo la raíz principal. */
@OptIn(ExperimentalTestApi::class)
private fun SkikoComposeUiTest.captura(nombre: String) {
    val dir = File(checkNotNull(System.getProperty("avoqado.evidencia")) { "falta -Davoqado.evidencia" }).apply { mkdirs() }
    ImageIO.write(captureToImage().toAwtImage(), "png", File(dir, "$nombre.png"))
}
