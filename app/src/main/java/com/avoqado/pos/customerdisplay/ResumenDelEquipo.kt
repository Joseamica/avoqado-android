package com.avoqado.pos.customerdisplay

/**
 * Compartido; ANDROID nunca lo llena (hz:a5fa2439:D1). Lo que el POS de Windows reporta de cómo dibuja: si no es null, el
 * sondeo (`DisplayModeRemoteRepository.fetchDisplayModeRequest`) lo manda tal cual en la cabecera `x-device-diagnostics`.
 */
object ResumenDelEquipo {
    @Volatile var cabecera: String? = null
}
