package com.avoqado.pos.escritorio.dibujo

import kotlinx.serialization.Serializable

/** Cómo dibuja el POS de Windows (hz:a5fa2439:D2). RAPIDO = motor por software, para PCs con video viejo. */
enum class Modo { AUTO, RAPIDO }

enum class Rendimiento { FLUIDA, LIGERA, NO_ALCANZA }

enum class Aviso { FALLO_RAPIDO, NO_ALCANZA, OFRECER_RAPIDO }

/** Lo único que se guarda entre aperturas (dibujo.json). */
@Serializable
data class Ajuste(
    val modo: Modo = Modo.AUTO,
    val intentando: Boolean = false,
    val fallo: Boolean = false,
    val sinAviso: Boolean = false,
    val caeSolaASoftware: Boolean = false,
)

/** Lo que pasó en ESTA apertura. `fijo`: la app fijó el motor rápido. `pidioSoftware`: `fijo`, o ajuste a mano con valor de software. */
data class Apertura(val aMano: Boolean, val fijo: Boolean, val pidioSoftware: Boolean, val motor: String? = null, val pintarMs: Long? = null)

data class Estado(
    val motor: String?,
    val pintarMs: Long?,
    val modoLigero: Boolean,
    val rendimiento: Rendimiento?,
    val modo: Modo,
    val forzada: Boolean,
    val fallo: Boolean,
    val aMano: Boolean,
    val aplicaAlReabrir: Boolean,
    val aviso: Aviso?,
)

/** Las transiciones del modo, sin archivo ni reloj: quien las llama guarda y publica. */
object ModoDeDibujo {
    const val MOTOR_RAPIDO = "SOFTWARE_FAST"

    /** Los valores de software que acepta Skiko 0.8.18 en `SKIKO_RENDER_API` / `-Dskiko.renderApi`. */
    private val VALORES_DE_SOFTWARE = setOf("SOFTWARE", "SOFTWARE_FAST", "SOFTWARE_COMPAT", "DIRECT_SOFTWARE")

    fun esDeSoftware(motor: String): Boolean = motor.startsWith("SOFTWARE")

    fun pideSoftware(valor: String?): Boolean = valor?.trim()?.uppercase() in VALORES_DE_SOFTWARE

    /** `guardar`: lo que hay que escribir ANTES de la ventana (null = nada). `fijar` sólo vale si ese guardado salió bien. */
    data class AlArrancar(val guardar: Ajuste?, val fijar: Boolean)

    fun alArrancar(a: Ajuste, aMano: Boolean): AlArrancar = when {
        a.intentando -> AlArrancar(regresaAAutomatico(a), fijar = false)   // la apertura rápida anterior no pintó: modo seguro
        aMano || a.modo != Modo.RAPIDO -> AlArrancar(null, fijar = false)
        else -> AlArrancar(a.copy(intentando = true), fijar = true)
    }

    /**
     * Tras el primer ciclo de dibujo. null = el archivo no cambia. Sin fijar ni ajuste a mano, recuerda si Skiko cayó solo a
     * software: la siguiente apertura arranca ya en modo ligero (sin fijar nada) y el NavHost se monta sin transiciones.
     */
    fun alPintar(a: Ajuste, fijo: Boolean, aMano: Boolean, motorReal: String): Ajuste? = when {
        fijo && esDeSoftware(motorReal) -> a.copy(intentando = false)
        fijo -> regresaAAutomatico(a)   // se fijó el rápido y Skiko abrió con otro motor
        aMano -> null
        esDeSoftware(motorReal) != a.caeSolaASoftware -> a.copy(caeSolaASoftware = esDeSoftware(motorReal))
        else -> null
    }

    // Elegir sólo pasa con la ventana ya pintada: quita la marca de intento, así el archivo nunca queda con `intentando` y
    // AUTO aunque haya fallado el guardado que la quitaba al pintar. Elegir automático a mano es «no me ofrezcas el modo
    // rápido»: calla los avisos. El rápido es un modo nuevo, con avisos nuevos. (El modo seguro no pasa por aquí: su aviso sí sale.)
    fun alElegir(a: Ajuste, modo: Modo): Ajuste = a.copy(modo = modo, intentando = false, fallo = false, sinAviso = modo == Modo.AUTO)

    // Un aviso sólo sale con la ventana ya pintada: callarlo también quita la marca, por si falló el guardado que la quitaba.
    fun alCallarAviso(a: Ajuste): Ajuste = a.copy(sinAviso = true, intentando = false)

    fun estado(a: Ajuste, ap: Apertura): Estado {
        val motor = ap.motor
        val pintarMs = ap.pintarMs?.coerceAtMost(PINTAR_TOPE_MS)
        val software = motor != null && esDeSoftware(motor)
        val rendimiento = when {
            motor == null || pintarMs == null -> null   // todavía no pinta
            pintarMs > PINTAR_LENTO_MS -> Rendimiento.NO_ALCANZA
            software -> Rendimiento.LIGERA
            else -> Rendimiento.FLUIDA
        }
        val pinto = rendimiento != null
        val forzada = ap.fijo && (!pinto || software)
        val aviso = when {
            a.sinAviso || !pinto -> null
            a.fallo -> Aviso.FALLO_RAPIDO
            rendimiento != Rendimiento.NO_ALCANZA -> null
            software -> Aviso.NO_ALCANZA
            motor != "DIRECT3D" && !ap.aMano && a.modo == Modo.AUTO -> Aviso.OFRECER_RAPIDO
            else -> null
        }
        return Estado(
            motor = motor,
            pintarMs = pintarMs,
            modoLigero = if (pinto) software else ap.pidioSoftware,
            rendimiento = rendimiento,
            modo = a.modo,
            forzada = forzada,
            fallo = a.fallo,
            aMano = ap.aMano,
            aplicaAlReabrir = !ap.aMano && ((a.modo == Modo.RAPIDO) != forzada),
            aviso = aviso,
        )
    }

    private fun regresaAAutomatico(a: Ajuste) = a.copy(modo = Modo.AUTO, intentando = false, fallo = true, sinAviso = false)
}
