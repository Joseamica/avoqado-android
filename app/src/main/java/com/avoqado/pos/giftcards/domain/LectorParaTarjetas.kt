package com.avoqado.pos.giftcards.domain
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
object LectorParaTarjetas {
    private val estado = MutableStateFlow(false)
    val abierta = estado.asStateFlow()
    fun abrir() { estado.value = true }
    fun cerrar() { estado.value = false }
}
