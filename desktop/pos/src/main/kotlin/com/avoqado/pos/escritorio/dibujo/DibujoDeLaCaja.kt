package com.avoqado.pos.escritorio.dibujo

import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.avoqado.pos.BuildConfig
import java.nio.file.Path

/**
 * El único dueño del modo de dibujo en este proceso (hz:a5fa2439:D2): fija el motor antes de la ventana, mide lo que tarda
 * en pintar y guarda lo que elige la persona. Nada de aquí lanza ni impide abrir la caja: ante la duda, automático.
 * `-Davoqado.dibujo.apagado=true` (también en producción, apagador de soporte): no lee, no escribe y no fija nada.
 */
object DibujoDeLaCaja {
    private const val TAG = "Dibujo"
    private val SIN_APERTURA = Apertura(aMano = false, fijo = false, pidioSoftware = false)
    private val INICIAL = ModoDeDibujo.estado(Ajuste(), SIN_APERTURA)

    // Estado de Compose: se escribe también desde hilos que no son el de la ventana (las escrituras de snapshot lo aguantan).
    private val _estado = mutableStateOf(INICIAL)
    val estado: State<Estado> = _estado

    /** Para la banda de confirmación; sólo en memoria. */
    private val _recienElegido = mutableStateOf(false)
    val recienElegido: State<Boolean> = _recienElegido

    /**
     * Se invoca DENTRO del candado (así dos cambios nunca publican al revés; Codex r6 #5) cada vez que cambia `estado` con la
     * ventana ya pintada. `revision` = 1, 2, 3… dentro del proceso. Quien se cuelgue aquí no vuelve a llamar a este objeto y
     * sólo arma algo corto: la primera llamada va en el hilo de la ventana, en el camino de la apertura. Por eso aquí NO se leen
     * los datos del equipo (atrasaban el primer cuadro ~0.19 s en el laboratorio): `SondeoDelDibujo` los lee después, donde el
     * diagnóstico siempre los leyó, y llama [republicar]. Tampoco se muevan antes de la ventana: en automático, antes de abrirla
     * sólo se mira dibujo.json (hz:a5fa2439:D1).
     */
    @Volatile var alCambiar: ((Estado, revision: Long) -> Unit)? = null

    /** El apagador de soporte está puesto: no hay modo que elegir ni avisos que dar en este proceso. */
    @Volatile var apagado = false
        private set

    private var arranco = false
    private var carpeta: Path? = null   // null = sin arrancar o apagado: nada lee, escribe ni fija
    private var ajuste = Ajuste()
    private var apertura = SIN_APERTURA
    private var reloj: () -> Long = System::nanoTime
    private var inicioNs = 0L
    private var fingido: Pair<String, Long>? = null
    private var revision = 0L

    /** Antes de `application {`. Nunca lanza. */
    @Synchronized
    fun alArrancar(
        carpeta: Path,
        entorno: (String) -> String? = System::getenv,
        propiedad: (String) -> String? = System::getProperty,
        fijar: (String) -> Unit = { System.setProperty("skiko.renderApi", it) },
        ahoraNs: () -> Long = System::nanoTime,
    ) {
        if (arranco) return
        arranco = true
        try {
            if (propiedad("avoqado.dibujo.apagado") == "true") {
                apagado = true
                Log.i(TAG, "apagado por avoqado.dibujo.apagado")
                return
            }
            reloj = ahoraNs
            inicioNs = ahoraNs()
            val desarrollo = !BuildConfig.PRODUCCION
            val noFijar = desarrollo && propiedad("avoqado.dibujo.noFijar") == "true"
            fingido = if (desarrollo) fingido(propiedad("avoqado.dibujo.fingir")) else null
            val valorAMano = entorno("SKIKO_RENDER_API")?.takeIf { it.isNotBlank() } ?: propiedad("skiko.renderApi")?.takeIf { it.isNotBlank() }

            val leido = ArchivoDeDibujo.leer(carpeta)
            val plan = ModoDeDibujo.alArrancar(leido, aMano = valorAMano != null)
            val guardado = plan.guardar != null && guardar(carpeta, plan.guardar)
            // Sólo avanza lo guardado, salvo la recuperación del modo seguro: esta apertura YA va en automático y el aviso
            // debe decirlo aunque el disco falle; el archivo conserva la marca y la siguiente apertura lo reintenta (Codex r8).
            ajuste = plan.guardar?.takeIf { guardado || leido.intentando } ?: leido
            if (leido.intentando) Log.i(TAG, "modo=${ajuste.modo} por=SEGURO")
            val fijo = plan.fijar && guardado
            // La pista de la apertura anterior (Skiko cayó solo a software) sólo adelanta el modo ligero: no fija ningún motor.
            val pidioSoftware = fijo || ModoDeDibujo.pideSoftware(valorAMano) || (valorAMano == null && ajuste.caeSolaASoftware)
            apertura = Apertura(aMano = valorAMano != null, fijo = fijo, pidioSoftware = pidioSoftware)
            this.carpeta = carpeta
            _estado.value = ModoDeDibujo.estado(ajuste, apertura)
            if (fijo) {
                // En la Mac SOFTWARE_FAST no existe y Skiko lanza: con noFijar se prueba el ciclo sin fijarlo.
                if (noFijar) Log.i(TAG, "habría fijado ${ModoDeDibujo.MOTOR_RAPIDO}") else fijar(ModoDeDibujo.MOTOR_RAPIDO)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Modo de dibujo omitido al abrir", t)
        }
    }

    /** Tras el segundo aviso de cuadro. Síncrono; una sola vez por proceso. Nunca lanza. */
    @Synchronized
    fun alPintar(motorReal: String) {
        try {
            val carpeta = carpeta ?: return
            if (apertura.motor != null) return
            val (motor, ms) = fingido ?: (motorReal to (reloj() - inicioNs) / 1_000_000)
            apertura = apertura.copy(motor = motor, pintarMs = ms.coerceAtLeast(0))
            ModoDeDibujo.alPintar(ajuste, apertura.fijo, apertura.aMano, motor)?.let { nuevo ->
                val guardado = guardar(carpeta, nuevo)
                // Regresar a automático se adopta aunque no se guarde (como al arrancar): la siguiente apertura llega al mismo
                // AUTO con fallo por la marca. Quitar la marca sólo avanza si guardó; si no, la siguiente va a automático.
                if (nuevo.modo != ajuste.modo) Log.i(TAG, "modo=${nuevo.modo} por=SEGURO")
                if (guardado || nuevo.modo != ajuste.modo) ajuste = nuevo
            }
            val e = publicar()
            Log.i(TAG, "motor=${e.motor} pintar=${e.pintarMs} ms modo=${e.modo} ligero=${e.modoLigero} rendimiento=${e.rendimiento} forzada=${e.forzada} fallo=${e.fallo} aMano=${e.aMano}")
        } catch (t: Throwable) {
            Log.w(TAG, "No se pudo anotar el primer cuadro", t)
        }
    }

    /** Lo que el diagnóstico cuenta de cómo dibuja. null con el apagador: para este proceso la función no existe. */
    fun paraElDiagnostico(): Estado? = if (apagado) null else _estado.value

    /** Avisa otra vez el mismo estado con una revisión mayor (la cabecera ya con los datos del equipo). Sin pintar o apagado, nada. */
    @Synchronized
    fun republicar() {
        if (carpeta == null || apertura.motor == null) return
        publicar()
    }

    /** El botón. `false` = no se pudo guardar y nada cambió. */
    @Synchronized
    fun elegir(modo: Modo): Boolean {
        val carpeta = carpeta ?: return false
        val nuevo = ModoDeDibujo.alElegir(ajuste, modo)
        if (!guardar(carpeta, nuevo)) return false
        ajuste = nuevo
        _recienElegido.value = true
        Log.i(TAG, "modo=$modo por=AQUI")
        publicar()
        return true
    }

    /** «Ahora no» / «Entendido» de un aviso: lo calla en memoria aunque no se guarde. */
    @Synchronized
    fun callarAviso(): Boolean {
        val carpeta = carpeta ?: return false
        ajuste = ModoDeDibujo.alCallarAviso(ajuste)
        val guardado = guardar(carpeta, ajuste)
        publicar()
        return guardado
    }

    /** «Entendido» de la confirmación. */
    @Synchronized
    fun callarConfirmacion() {
        _recienElegido.value = false
    }

    /** Para las pruebas: un proceso nuevo. */
    @Synchronized
    internal fun reiniciarParaPruebas() {
        arranco = false
        carpeta = null
        ajuste = Ajuste()
        apertura = SIN_APERTURA
        reloj = System::nanoTime
        inicioNs = 0L
        fingido = null
        revision = 0L
        alCambiar = null
        apagado = false
        _estado.value = INICIAL
        _recienElegido.value = false
    }

    private fun publicar(): Estado {
        val e = ModoDeDibujo.estado(ajuste, apertura)
        _estado.value = e
        val avisar = alCambiar
        if (apertura.motor != null && avisar != null) {
            runCatching { avisar(e, ++revision) }.onFailure { Log.w(TAG, "alCambiar falló", it) }
        }
        return e
    }

    private fun guardar(carpeta: Path, a: Ajuste): Boolean =
        ArchivoDeDibujo.guardar(carpeta, a).also { if (!it) Log.w(TAG, "No se pudo guardar dibujo.json") }

    /** `-Davoqado.dibujo.fingir=<MOTOR>:<ms>` (p. ej. `OPENGL:9000`); otra cosa ⇒ null. */
    private fun fingido(valor: String?): Pair<String, Long>? {
        val motor = valor?.substringBefore(':')?.trim()?.uppercase()
        val ms = valor?.substringAfter(':', "")?.trim()?.toLongOrNull()
        return if (motor.isNullOrEmpty() || ms == null || ms < 0) null else motor to ms
    }
}
