package com.avoqado.escritorio

import java.time.LocalTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Lo que NO se pudo guardar en el disco de esta computadora, para que la ventana lo DIGA (`AvisoDeFallaDeGuardado` en
 * :pos). En Windows lo más probable es un antivirus o un disco lleno, y la app de Android no lo avisa: sin esto el cajero
 * seguiría vendiendo creyendo que su retiro o su cierre quedó guardado.
 *
 * 🔴 Sólo el nombre LÓGICO del archivo (`avoqado_secure_prefs`, no la ruta) y la hora: nunca un valor.
 */
object FallasDeGuardado {
    /** [despuesSiGuardo]: el siguiente guardado del MISMO archivo salió bien (se dice, pero el aviso no se va solo). */
    data class Falla(val archivo: String, val hora: LocalTime, val despuesSiGuardo: Boolean = false)

    private val _fallas = MutableStateFlow<List<Falla>>(emptyList())
    val fallas: StateFlow<List<Falla>> = _fallas.asStateFlow()

    /** Un archivo que no se pudo guardar. Si ya estaba en la lista, se actualiza su hora y vuelve a «sin guardar». */
    fun reportar(archivo: String, hora: LocalTime = LocalTime.now()) {
        _fallas.update { lista -> lista.filter { it.archivo != archivo } + Falla(archivo, hora.withNano(0)) }
    }

    /** El archivo se guardó bien: si tenía un aviso, se marca; el aviso sigue hasta que el cajero lo lea. */
    fun guardoBien(archivo: String) {
        if (_fallas.value.none { it.archivo == archivo && !it.despuesSiGuardo }) return   // lo normal: nada que hacer
        _fallas.update { lista -> lista.map { if (it.archivo == archivo) it.copy(despuesSiGuardo = true) else it } }
    }

    /** El cajero leyó el aviso («Entendido»). */
    fun entendido() { _fallas.value = emptyList() }
}
