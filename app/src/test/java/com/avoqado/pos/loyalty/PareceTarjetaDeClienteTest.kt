package com.avoqado.pos.loyalty

import com.avoqado.pos.loyalty.data.pareceTarjetaDeCliente
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El filtro que decide si vale la pena preguntarle al servidor por un código escaneado.
 *
 * 🔴 Existe para no hacer un viaje de red por cada código que no está en el catálogo:
 * en una caja con fila esa latencia se nota, y casi todos los códigos desconocidos son
 * productos mal dados de alta, no tarjetas.
 *
 * Lo que se prueba es que NO se confunda con mercancía real, que es lo que rompería el
 * cobro de todos los días.
 */
class PareceTarjetaDeClienteTest {

    @Test
    fun `un token de tarjeta si parece tarjeta`() {
        // 24 bytes en hexadecimal = 48 caracteres, que es lo que emite el servidor.
        val token = "a".repeat(48)
        assertTrue(pareceTarjetaDeCliente(token))
        assertTrue(pareceTarjetaDeCliente("0123456789abcdef0123456789abcdef0123456789abcdef"))
    }

    @Test
    fun `acepta hexadecimal en mayusculas`() {
        // Algunos lectores devuelven el contenido en mayúsculas.
        assertTrue(pareceTarjetaDeCliente("ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789"))
    }

    @Test
    fun `ignora espacios alrededor`() {
        assertTrue(pareceTarjetaDeCliente("  " + "f".repeat(48) + "  "))
    }

    @Test
    fun `un codigo de barras de producto NO parece tarjeta`() {
        // 🔴 Lo que de verdad importa: mercancía real no puede caer en este camino, o
        // cada producto sin dar de alta se convertiría en un viaje al servidor.
        assertFalse(pareceTarjetaDeCliente("7501055310838"))  // EAN-13 real
        assertFalse(pareceTarjetaDeCliente("036000291452"))   // UPC-A
        assertFalse(pareceTarjetaDeCliente("4011"))           // PLU de fruta
        assertFalse(pareceTarjetaDeCliente(""))
    }

    @Test
    fun `un texto de 48 caracteres que NO es hexadecimal tampoco pasa`() {
        // Un código alfanumérico interno del negocio puede medir 48 y no ser una
        // tarjeta. La forma tiene que cumplirse entera, no sólo el largo.
        assertFalse(pareceTarjetaDeCliente("z".repeat(48)))
        assertFalse(pareceTarjetaDeCliente("TICKET-" + "a".repeat(41)))
    }

    @Test
    fun `casi-48 no pasa`() {
        assertFalse(pareceTarjetaDeCliente("a".repeat(47)))
        assertFalse(pareceTarjetaDeCliente("a".repeat(49)))
    }
}
