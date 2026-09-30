package com.avoqado.escritorio

import android.content.Context
import androidx.fragment.app.FragmentActivity
import java.nio.file.Path

/**
 * EL contexto de la app en escritorio. En Android `LocalContext` es la Activity y la app lo castea a
 * `Activity`/`FragmentActivity` (tema, biometría, findActivity): aquí el mismo objeto es las tres cosas
 * y también su propio applicationContext.
 */
class ActividadDeEscritorio(carpeta: Path) : FragmentActivity(ContextoDeEscritorio(carpeta)) {
    override fun getApplicationContext(): Context = this
}
