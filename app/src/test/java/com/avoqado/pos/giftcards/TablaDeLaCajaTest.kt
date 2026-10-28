package com.avoqado.pos.giftcards

import com.avoqado.pos.giftcards.data.*
import com.avoqado.pos.giftcards.domain.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

// Prerequisito puro; la matriz VM→diario→HTTP se amplía en 6C sin sustituirla.
@RunWith(Parameterized::class)
class TablaDeLaCajaTest(private val caso: String, private val fila: FilaDeLaCaja, private val flags: Set<String>) {
    @Test fun precedenciaDePrerequisitos() {
        val ajustes = if ("noDisponible" in flags) EstadoDeAjustes.NoDisponible else EstadoDeAjustes.Disponible(
            AjustesDeTarjetas("apagado" !in flags, emptyList(),100.0,10000.0, sale=VentaPermitida(false), tieneTarjetas="tarjetas" in flags))
        val r = estadoDeLaVenta(ajustes,"offline" !in flags,"sinRedeem" !in flags,"regalo" in flags,
            "vales" in flags,"membresia" in flags,"rapido" !in flags,"provisional" in flags,
            "incierta" in flags,"guardada" in flags,"trabada" in flags,"mesa" in flags,
            "lista" in flags,"ligada" in flags,"local" in flags,"sinRead" !in flags,"sinCrear" !in flags)
        assertEquals(caso,fila,r.fila)
        assertEquals(fila !in setOf(FilaDeLaCaja.T9,FilaDeLaCaja.T11,FilaDeLaCaja.T12) || fila==FilaDeLaCaja.T11 && "sinRead" !in flags,r.visible)
        assertEquals(fila in setOf(FilaDeLaCaja.T1,FilaDeLaCaja.T2,FilaDeLaCaja.T7,FilaDeLaCaja.T8),r.habilitada)
    }
    companion object {
        @JvmStatic @Parameterized.Parameters(name="{0}") fun casos(): List<Array<Any>> = listOf(
            Triple("T1",FilaDeLaCaja.T1,""), Triple("T2",FilaDeLaCaja.T2,"ligada"),
            Triple("T3",FilaDeLaCaja.T3,"provisional"), Triple("T4",FilaDeLaCaja.T4,"incierta"),
            Triple("T6",FilaDeLaCaja.T6,"guardada"),Triple("T6 con pago",FilaDeLaCaja.T6,"guardada ligada"),
            Triple("T7",FilaDeLaCaja.T7,"lista ligada"), Triple("T8",FilaDeLaCaja.T8,"mesa ligada"),
            Triple("T9 primero",FilaDeLaCaja.T9,"regalo offline sinRead noDisponible"),
            Triple("T11 read",FilaDeLaCaja.T11,"sinRead offline"),
            Triple("T12 no endpoint",FilaDeLaCaja.T12,"noDisponible offline"),
            Triple("T12 apagado",FilaDeLaCaja.T12,"apagado offline"),
            Triple("T0",FilaDeLaCaja.T0,"offline"),Triple("T0 cuenta",FilaDeLaCaja.T0,"offline ligada"),
            Triple("T0 mesa",FilaDeLaCaja.T0,"offline mesa ligada"),
            Triple("T11 redeem antes vales",FilaDeLaCaja.T11,"sinRedeem vales"),
            Triple("T1 sin crear",FilaDeLaCaja.T11,"sinCrear"),
            Triple("cuenta sin crear",FilaDeLaCaja.T2,"ligada sinCrear"),
            Triple("T10 vales",FilaDeLaCaja.T10,"vales incierta"),
            Triple("T10 membresia",FilaDeLaCaja.T10,"membresia"),Triple("T10 rapido",FilaDeLaCaja.T10,"rapido"),
            Triple("T3 antes T4",FilaDeLaCaja.T3,"provisional incierta"),
            Triple("T4 antes T6",FilaDeLaCaja.T4,"incierta guardada"),
            Triple("T6 antes T13",FilaDeLaCaja.T6,"guardada local ligada"),
            Triple("T13",FilaDeLaCaja.T13,"trabada ligada"),Triple("T13 otro cobro",FilaDeLaCaja.T13,"local ligada"),
            Triple("T8 antes T7",FilaDeLaCaja.T8,"mesa lista ligada"),
            Triple("apagado con tarjetas",FilaDeLaCaja.T1,"apagado tarjetas")
        ).map { arrayOf(it.first,it.second,it.third.split(' ').filter(String::isNotBlank).toSet()) }
    }
}
