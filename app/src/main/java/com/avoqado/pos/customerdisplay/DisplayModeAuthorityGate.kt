package com.avoqado.pos.customerdisplay

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializa la autoridad legacy con la creación/reemplazo del journal tipado. */
@Singleton
class DisplayModeAuthorityGate @Inject constructor() {
    private val mutex = Mutex()

    suspend fun <T> withAuthority(block: suspend () -> T): T = mutex.withLock { block() }
}
