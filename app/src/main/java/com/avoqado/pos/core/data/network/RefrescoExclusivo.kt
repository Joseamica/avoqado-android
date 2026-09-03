package com.avoqado.pos.core.data.network

import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock

/**
 * UN solo refresco de sesión a la vez en toda la app.
 *
 * 🔴 Existe porque el refresco tiene DOS caminos y sólo uno tenía candado:
 * `TokenRefreshAuthenticator` (el 401 de cualquier petición) y `AuthRepository`
 * (`repairCurrentVenueBinding` / `refreshTokensForBiometric`, por Retrofit). El candado
 * privado del autenticador no podía verlos a los dos.
 *
 * Medido en la Sunmi D3 el 2026-09-02 16:07: el POS despertó del background, seis
 * peticiones vencidas salieron juntas y el servidor recibió DOS refrescos solapados 915 ms.
 * El servidor rota el refresh token en cada refresco, así que el segundo llegó con un grant
 * ya consumido, recibió «Tu sesión ya no es válida» y la app cerró sesión sola — el aparato
 * se quedó en la pantalla de bienvenida y nadie pudo cobrar hasta que alguien volvió a
 * entrar. Con red intermitente (el mostrador del ICP) es MÁS probable, porque reintentar
 * varias peticiones a la vez es justo lo que dispara el doble refresco.
 *
 * 🔑 Serializa, NO comparte resultado entre caminos distintos: quien entra segundo relee el
 * refresh token ya rotado y hace el suyo con datos frescos. Compartir el resultado sería
 * incorrecto para `repairCurrentVenueBinding`, cuyo refresco lleva un propósito propio
 * (re-ligar el token al venue actual) que el del autenticador no cumple. Dentro del
 * autenticador, el reuso del resultado entre hilos sigue viviendo donde ya estaba.
 *
 * `ReentrantLock` y no `Mutex` de coroutines: lo toman los dos mundos — el autenticador
 * desde un hilo bloqueante de OkHttp y el repositorio desde una corrutina. Un candado que
 * sólo uno de los dos puede tomar no es un candado.
 */
@Singleton
class RefrescoExclusivo @Inject constructor() {

    private val lock = ReentrantLock(true)

    fun <T> enExclusiva(bloque: () -> T): T = lock.withLock { bloque() }
}
