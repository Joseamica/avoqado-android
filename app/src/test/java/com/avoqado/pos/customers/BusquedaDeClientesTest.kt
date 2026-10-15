package com.avoqado.pos.customers

import com.avoqado.pos.customers.data.model.Customer
import com.avoqado.pos.customers.domain.esTextoDeTelefono
import com.avoqado.pos.customers.domain.filtrarClientesLocal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Dany no aparece» (Testarudo, 14-oct): la tablet comparaba el teléfono letra por letra, así que «81 2944 0042»
 * nunca encontraba «+528129440042». La regla es la del servidor: dígitos contra dígitos, sin la lada 52/521
 * sobrante; nombre por palabras, sin mayúsculas ni acentos. Esta tabla sirve igual para la prueba del servidor.
 */
class BusquedaDeClientesTest {

    private val dany = Customer(id = "c1", firstName = "Dany", lastName = "Muñoz", phone = "+528129440042", email = "dany@correo.mx")
    private val sinLada = Customer(id = "c2", phone = "+8129440042")
    private val pedro = Customer(id = "c3", firstName = "Pedro", lastName = "López Ávila", phone = "+525511112222")
    private val jose = Customer(id = "c4", firstName = "José", email = "jose@cafe.mx")
    private val todos = listOf(dany, sinLada, pedro, jose)

    private fun ids(texto: String) = filtrarClientesLocal(todos, texto).map { it.id }

    @Test
    fun `P1 telefono escrito con espacios encuentra al cliente con lada y al viejo sin lada`() {
        assertEquals(listOf("c1", "c2"), ids("81 2944 0042"))
    }

    @Test
    fun `P1 telefono con +52, con 521 o con parentesis y guiones se compara por digitos`() {
        assertEquals(listOf("c1", "c2"), ids("+52 81 2944 0042"))
        assertEquals(listOf("c1", "c2"), ids("521 81 2944 0042"))
        assertEquals(listOf("c1", "c2"), ids("(81) 2944-0042"))
    }

    @Test
    fun `cuatro digitos ya buscan por telefono y tres todavia no`() {
        assertEquals(listOf("c1", "c2"), ids("8129"))
        assertEquals(emptyList<String>(), ids("812"))
    }

    @Test
    fun `el nombre se busca sin mayusculas ni acentos`() {
        assertEquals(listOf("c1"), ids("munoz"))
        assertEquals(listOf("c1"), ids("MUÑOZ"))
        assertEquals(listOf("c4"), ids("jose"))
    }

    @Test
    fun `cada palabra debe aparecer en nombre, apellido o correo, en cualquier orden`() {
        assertEquals(listOf("c3"), ids("avila pedro"))
        assertEquals(listOf("c1"), ids("dany correo.mx"))
        assertEquals(emptyList<String>(), ids("dany lopez"))
    }

    @Test
    fun `texto vacio o solo espacios devuelve la lista tal cual`() {
        assertEquals(todos, filtrarClientesLocal(todos, ""))
        assertEquals(todos, filtrarClientesLocal(todos, "   "))
    }

    @Test
    fun `un telefono buscado nunca casa con un cliente sin telefono`() {
        assertEquals(listOf("c3"), ids("55 1111 2222"))
    }

    @Test
    fun `P1 el texto de un telefono siembra el telefono del alta y el de un nombre el nombre`() {
        assertTrue(esTextoDeTelefono("81 2944 0042"))
        assertTrue(esTextoDeTelefono("+52 (81) 2944-0042"))
        assertTrue(esTextoDeTelefono("81"))
        assertFalse(esTextoDeTelefono("Dany"))
        assertFalse(esTextoDeTelefono(""))
        assertFalse(esTextoDeTelefono("   "))
    }

    // Revisión final (Minor #5): «()» o «-» pasaban por teléfono y sembraban un TELÉFONO sin ningún número en el alta.
    @Test
    fun `un texto sin ningun digito no es telefono aunque solo traiga parentesis, guiones o mas`() {
        assertFalse(esTextoDeTelefono("()"))
        assertFalse(esTextoDeTelefono("-"))
        assertFalse(esTextoDeTelefono("+ ( ) -"))
        assertTrue("un solo dígito ya basta", esTextoDeTelefono("(8"))
    }
}
