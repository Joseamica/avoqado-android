package com.avoqado.pos.customerdisplay

import java.awt.GraphicsEnvironment
import java.awt.Rectangle

/*
 * Escritorio: los monitores de la PC vistos como las pantallas de Android. El monitor principal de Windows hace de
 * `Display.DEFAULT_DISPLAY` (id 0) y los demás de pantallas de presentación (1..n, de izquierda a derecha), así la decisión
 * es la MISMA función pura de Android ([resolveDisplayRoles]). Ningún monitor tiene «dueño»: todos son físicos.
 *
 * 🔴 El principal es el de Windows, no «donde esté la caja»: con el modo invertido la caja se muda al otro monitor, y si el
 * principal la siguiera, el siguiente vistazo volvería a invertir (ping-pong).
 */

/** Un monitor: [clave] es la de Windows (`\\.\DISPLAY1`), [limites] en coordenadas de AWT (las mismas de una ventana). */
internal data class Monitor(val clave: String, val limites: Rectangle, val principal: Boolean)

/** Principal ⇒ 0; los demás 1..n por posición (x, luego y). Sin principal declarado, el primero de la lista hace de él. */
internal fun numerar(monitores: List<Monitor>): Map<Int, Monitor> {
    if (monitores.isEmpty()) return emptyMap()
    val principal = monitores.firstOrNull { it.principal } ?: monitores.first()
    val otros = (monitores - principal).sortedWith(compareBy({ it.limites.x }, { it.limites.y }))
    return buildMap {
        put(0, principal)
        otros.forEachIndexed { i, m -> put(i + 1, m) }
    }
}

/** Qué monitor le toca a la caja y cuál al cliente: la decisión de Android, con todos los monitores físicos. */
internal fun rolesDeEscritorio(numerados: Map<Int, Monitor>, invertido: Boolean): DisplayRoles = resolveDisplayRoles(
    defaultDisplayId = 0,
    candidates = numerados.keys.filter { it != 0 }.map { CandidateDisplay(it, ownerPackage = null) },
    remoteCaptureHints = emptyList(),
    inverted = invertido,
)

/** El monitor donde está [ventana]: el que contiene su centro; si ninguno, el de mayor intersección; si nada se toca, null. */
internal fun monitorDe(ventana: Rectangle, numerados: Map<Int, Monitor>): Int? {
    val cx = ventana.centerX.toInt()
    val cy = ventana.centerY.toInt()
    numerados.entries.firstOrNull { it.value.limites.contains(cx, cy) }?.let { return it.key }
    return numerados.entries
        .map { it.key to it.value.limites.intersection(ventana).let { r -> if (r.isEmpty) 0L else r.width.toLong() * r.height } }
        .filter { it.second > 0 }
        .maxByOrNull { it.second }?.first
}

/** Los monitores que ve AWT ahora mismo. Sin pantalla (servidor, pruebas sin ventana), ninguno. */
internal fun monitoresDeAwt(): List<Monitor> {
    if (GraphicsEnvironment.isHeadless()) return emptyList()
    val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
    val delSistema = ge.defaultScreenDevice
    val reales = ge.screenDevices.map { d -> Monitor(d.iDstring, d.defaultConfiguration.bounds, principal = d == delSistema) }
    return if (monitoresDivididos()) dividirEnDos(reales, areaUtil = ge.maximumWindowBounds) else reales
}

/**
 * Sólo para probar en una PC de UN monitor (la Alienware): `-Davoqado.monitores=dividir` parte el principal en dos mitades,
 * caja a la izquierda y cliente a la derecha. Producción lo ignora.
 */
internal fun monitoresDivididos(): Boolean =
    !com.avoqado.pos.BuildConfig.PRODUCCION && System.getProperty("avoqado.monitores") == "dividir"

/**
 * `areaUtil`: el monitor principal SIN la barra de tareas. Las dos mitades salen de ahí: con el
 * alto completo, la barra de abajo de la app quedaba tapada (full-testing 3-oct).
 */
internal fun dividirEnDos(reales: List<Monitor>, areaUtil: Rectangle? = null): List<Monitor> {
    val p = reales.firstOrNull { it.principal } ?: return reales
    val r = areaUtil?.let { p.limites.intersection(it) }?.takeUnless { it.isEmpty } ?: p.limites
    val mitad = r.width / 2
    return listOf(
        Monitor(p.clave + "#izquierda", Rectangle(r.x, r.y, mitad, r.height), principal = true),
        Monitor(p.clave + "#derecha", Rectangle(r.x + mitad, r.y, r.width - mitad, r.height), principal = false),
    ) + (reales - p)
}
