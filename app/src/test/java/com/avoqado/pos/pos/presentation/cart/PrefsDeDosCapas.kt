package com.avoqado.pos.pos.presentation.cart

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk

/** El disco y la caché del proceso, separados. `reiniciarProceso()` es lo que ve la app al volver a abrir. Plan 7b (r3 R3-4). */
internal class PrefsDeDosCapas(private val disco: MutableMap<String, String?> = mutableMapOf()) {
    var commitFalla = false
    private val memoria = disco.toMutableMap()
    private val pendientes = mutableListOf<Map<String, String?>>()

    fun escribirPendientes() { pendientes.forEach { disco.clear(); disco.putAll(it) }; pendientes.clear() }
    fun reiniciarProceso() = PrefsDeDosCapas(disco.toMutableMap()) // lo de `apply()` sin escribir se pierde
    fun enDisco(clave: String) = disco[clave]

    val context: Context = mockk<Context>().also { ctx ->
        val prefs = mockk<SharedPreferences>()
        every { prefs.getString(any(), any()) } answers { memoria[firstArg()] ?: secondArg() }
        every { prefs.edit() } answers {
            val cambios = mutableMapOf<String, String?>()
            var limpiar = false
            mockk<SharedPreferences.Editor>().also { e ->
                every { e.putString(any(), any()) } answers { cambios[firstArg()] = secondArg(); e }
                every { e.clear() } answers { limpiar = true; e }
                every { e.commit() } answers {
                    if (limpiar) memoria.clear(); memoria.putAll(cambios) // como AOSP: la memoria cambia ANTES de escribir
                    if (commitFalla) false else { if (limpiar) disco.clear(); disco.putAll(cambios); true }
                }
                every { e.apply() } answers { if (limpiar) memoria.clear(); memoria.putAll(cambios); pendientes += memoria.toMap() }
            }
        }
        every { ctx.getSharedPreferences("saved_carts", Context.MODE_PRIVATE) } returns prefs
    }
}
