package com.avoqado.pos.core.domain.refresh

import com.avoqado.pos.core.data.network.ClaseDeFallo
import com.avoqado.pos.core.data.network.laAutomaticaReintenta
import javax.inject.Inject
import javax.inject.Singleton

/** D22: lo que había cuando EMPEZÓ una carga. Su retorno sólo atiende esto: lo que llegue después queda pendiente. */
data class Instantanea(val sucursal: String, val pedida: Long, val avisos: Long)

/** D22 ronda 3: cuántos avisos tenía la sucursal cuando falló la última carga reintentable. */
data class AvisosAlFallar(val sucursal: String, val avisos: Long)

/**
 * D22 (diseño r2 §B): si a Ventas o Informes les hace falta recargar. Es un ESTADO del ViewModel, no un flanco: se compara
 * cada vez que algo cambia. Lo sube un fallo reintentable (`pedida`) o un aviso de [com.avoqado.pos.core.data.sync.AvisosDeVentasSubidas]; lo
 * apaga sólo una carga buena, y sólo hasta lo que había cuando ESA carga empezó.
 */
data class Necesidad(
    /** Sube con cada fallo reintentable de una carga de esta pantalla. */
    val pedida: Long = 0,
    /** La mayor `pedida` de una instantánea cuya carga terminó bien. */
    val atendida: Long = 0,
    /** Por sucursal, el mayor contador de avisos de una instantánea cuya carga terminó bien. */
    val avisosAtendidos: Map<String, Long> = emptyMap(),
    /** Una denegación propia (o un 4xx definitivo) detuvo la automática en ESTA instantánea, hasta que llegue algo nuevo. */
    val detenidaEn: Instantanea? = null,
    val fallosSeguidos: Int = 0,
    /** Antes de esto (reloj de [RelojDeRecarga]) la automática no reintenta: 5, 10, 20 y luego 30 s tras cada fallo. */
    val proximoIntentoEn: Long = 0,
    /** Los avisos de la sucursal al fallar la última carga reintentable: uno que llegue después salta la escalera. */
    val avisosAlUltimoFallo: AvisosAlFallar? = null,
) {
    fun instantanea(sucursal: String, avisos: Long) = Instantanea(sucursal, pedida, avisos)

    /** Un fallo reintentable sigue sin una carga buena que empezara después. */
    fun porFallo(): Boolean = pedida > atendida

    /** Llegó un aviso de [sucursal] después de que empezó su última carga buena. Una sucursal nunca cargada no pide nada. */
    fun porAvisos(sucursal: String, avisos: Long): Boolean = avisosAtendidos[sucursal]?.let { avisos > it } ?: false

    /**
     * D22 ronda 3 (D3, 4-nov): llegó un aviso DESPUÉS del último fallo de la escalera. El ACK de una subida prueba que el
     * servidor contesta: no se espera el escalón, se agrupa como cualquier aviso. Se cuenta, no se mide con la hora: la
     * primera vuelta de la recarga no puede pasar por «aviso nuevo».
     */
    fun avisoDespuesDelUltimoFallo(sucursal: String, avisos: Long): Boolean =
        avisosAlUltimoFallo?.let { it.sucursal == sucursal && avisos > it.avisos } ?: false

    fun haceFalta(sucursal: String, avisos: Long): Boolean =
        (porFallo() || porAvisos(sucursal, avisos)) && detenidaEn != instantanea(sucursal, avisos)

    fun alTerminarBien(inst: Instantanea): Necesidad = copy(
        atendida = maxOf(atendida, inst.pedida),
        avisosAtendidos = avisosAtendidos + (inst.sucursal to maxOf(avisosAtendidos[inst.sucursal] ?: Long.MIN_VALUE, inst.avisos)),
        detenidaEn = null,
        fallosSeguidos = 0,
        proximoIntentoEn = 0,
        avisosAlUltimoFallo = null,
    )

    /** [avisosAhora]: los avisos de la sucursal al fallar (no al empezar): uno que llegue después salta la escalera. */
    fun alFallar(inst: Instantanea, clases: List<ClaseDeFallo>, ahora: Long, avisosAhora: Long = inst.avisos): Necesidad =
        if (clases.laAutomaticaReintenta()) {
            val fallos = fallosSeguidos + 1
            copy(
                pedida = pedida + 1,
                fallosSeguidos = fallos,
                proximoIntentoEn = ahora + esperaTras(fallos),
                avisosAlUltimoFallo = AvisosAlFallar(inst.sucursal, avisosAhora),
            )
        } else {
            copy(detenidaEn = Instantanea(inst.sucursal, pedida, inst.avisos))
        }

    /**
     * D22 (D3, 4-nov): al volver a verse la pantalla, la escalera de reintentos vuelve al primer escalón (5 s), medido desde
     * el último fallo. Sin esto, tras varios fallos el primer intento al regresar era hasta 30 s después. La necesidad no
     * cambia: sigue haciendo falta lo mismo.
     */
    fun alVolverAVerse(): Necesidad {
        if (fallosSeguidos <= 1) return this
        val ultimoFallo = proximoIntentoEn - esperaTras(fallosSeguidos)
        return copy(fallosSeguidos = 1, proximoIntentoEn = ultimoFallo + esperaTras(1))
    }

    companion object {
        /** 5 s, 10 s, 20 s y después cada 30 s. */
        fun esperaTras(fallosSeguidos: Int): Long = when {
            fallosSeguidos <= 0 -> 0L
            fallosSeguidos == 1 -> 5_000L
            fallosSeguidos == 2 -> 10_000L
            fallosSeguidos == 3 -> 20_000L
            else -> 30_000L
        }
    }
}

/** El reloj monotónico de la recarga automática (las pruebas usan el del planificador). */
@Singleton
open class RelojDeRecarga @Inject constructor() {
    open fun ahora(): Long = System.nanoTime() / 1_000_000
}
