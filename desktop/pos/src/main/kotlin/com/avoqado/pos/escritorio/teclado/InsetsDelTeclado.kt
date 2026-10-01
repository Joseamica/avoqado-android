package com.avoqado.pos.escritorio.teclado

import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.platform.ConfiguracionDeInsetsDeEscritorio

/** La altura (dp) del teclado en pantalla, para que los diálogos de Compose lo traten como el teclado de un celular. */
object InsetsDelTeclado {
    /** 0 = sin teclado. Lo escribe ConTecladoEnPantalla al medirlo. */
    val alto = mutableFloatStateOf(0f)

    fun instalar() = ConfiguracionDeInsetsDeEscritorio.instalar(alto)
    fun desinstalar() = ConfiguracionDeInsetsDeEscritorio.desinstalar()
}
