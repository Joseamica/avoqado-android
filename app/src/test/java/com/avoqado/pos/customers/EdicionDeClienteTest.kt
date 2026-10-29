package com.avoqado.pos.customers

import com.avoqado.pos.customers.data.model.Customer
import com.avoqado.pos.customers.data.model.UpdateCustomerRequest
import com.avoqado.pos.customers.domain.AccesoAEditarCliente
import com.avoqado.pos.customers.domain.CampoDeContacto
import com.avoqado.pos.customers.domain.ContactoEditado
import com.avoqado.pos.customers.domain.accesoAEditarCliente
import com.avoqado.pos.customers.domain.cambiosDeContacto
import com.avoqado.pos.customers.domain.erroresDeContacto
import com.avoqado.pos.customers.domain.normalizarTelefono
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Editar cliente desde el POS (26-oct): sólo se manda lo que cambió, un dato que ya existía no se puede vaciar, y quien
 * no tiene `customers:update` necesita el PIN de un gerente (si el negocio lo tiene prendido).
 */
class EdicionDeClienteTest {

    private val soloTelefono = Customer(id = "c1", phone = "+527351748538")

    private fun editado(nombre: String = "", apellido: String = "", telefono: String = "+527351748538", correo: String = "") =
        ContactoEditado(nombre, apellido, telefono, correo)

    @Test
    fun `P1 ponerle nombre a quien solo tenia telefono manda solo el nombre`() {
        assertEquals(
            UpdateCustomerRequest(firstName = "Ana"),
            cambiosDeContacto(soloTelefono, editado(nombre = "  Ana ")),
        )
    }

    @Test
    fun `sin cambios no hay nada que mandar`() {
        assertNull(cambiosDeContacto(soloTelefono, editado()))
        // Sólo espacios alrededor tampoco es un cambio.
        assertNull(cambiosDeContacto(soloTelefono, editado(telefono = " +527351748538 ")))
    }

    @Test
    fun `el telefono se compara y se manda sin espacios ni guiones`() {
        assertNull(cambiosDeContacto(soloTelefono, editado(telefono = "+52 735-174 8538")))
        assertEquals(
            UpdateCustomerRequest(phone = "+525511112222"),
            cambiosDeContacto(soloTelefono, editado(telefono = "+52 (55) 1111-2222")),
        )
        assertEquals("5511112222", normalizarTelefono(" 55 1111 2222 "))
        assertEquals("+525511112222", normalizarTelefono("+52 55+1111 2222"))
    }

    @Test
    fun `cambiar varios datos manda todos los que cambiaron`() {
        val ana = Customer(id = "c1", firstName = "Ana", lastName = "Lopez", phone = "+527351748538", email = "ana@x.mx")
        assertEquals(
            UpdateCustomerRequest(lastName = "López", email = "ana@nuevo.mx"),
            cambiosDeContacto(ana, ContactoEditado("Ana", "López", "+527351748538", "ana@nuevo.mx")),
        )
    }

    @Test
    fun `P1 un dato que ya estaba no se puede dejar vacio`() {
        val ana = Customer(id = "c1", firstName = "Ana", phone = "+527351748538", email = "ana@x.mx")
        val errores = erroresDeContacto(ana, ContactoEditado("", "", "", "ana@x.mx"))
        assertEquals("No se puede dejar vacío", errores[CampoDeContacto.NOMBRE])
        assertEquals("No se puede dejar vacío", errores[CampoDeContacto.TELEFONO])
        // El apellido nunca existió: dejarlo vacío está bien.
        assertNull(errores[CampoDeContacto.APELLIDO])
    }

    @Test
    fun `formatos que el servidor rechazaria se dicen antes de guardar`() {
        val errores = erroresDeContacto(soloTelefono, editado(telefono = "735", correo = "ana@", nombre = "x".repeat(51)))
        assertEquals("El teléfono debe tener de 10 a 15 dígitos", errores[CampoDeContacto.TELEFONO])
        assertEquals("El correo no es válido", errores[CampoDeContacto.CORREO])
        assertEquals("Máximo 50 letras", errores[CampoDeContacto.NOMBRE])
        assertTrue(erroresDeContacto(soloTelefono, editado(nombre = "Ana", correo = "ana@ejemplo.mx")).isEmpty())
    }

    @Test
    fun `P1 quien puede editar entra directo, sin permiso pide PIN y con el PIN apagado se le explica`() {
        assertEquals(AccesoAEditarCliente.DIRECTO, accesoAEditarCliente(puedeEditar = true, pinDeGerenteActivo = false))
        assertEquals(AccesoAEditarCliente.DIRECTO, accesoAEditarCliente(puedeEditar = true, pinDeGerenteActivo = true))
        assertEquals(AccesoAEditarCliente.CON_PIN, accesoAEditarCliente(puedeEditar = false, pinDeGerenteActivo = true))
        assertEquals(AccesoAEditarCliente.SIN_PERMISO, accesoAEditarCliente(puedeEditar = false, pinDeGerenteActivo = false))
    }

    @Test
    fun `el correo se valida con la MISMA regla que el servidor`() {
        for (malo in listOf("ana..lopez@ejemplo.mx", "ana@x.c", ".ana@x.mx", "ñ@x.mx")) {
            assertEquals(malo, "El correo no es válido", erroresDeContacto(soloTelefono, editado(correo = malo))[CampoDeContacto.CORREO])
        }
        for (bueno in listOf("ana@ejemplo.mx", "a.b+c@sub.dominio.com.mx", "ANA@EJEMPLO.MX")) {
            assertNull(bueno, erroresDeContacto(soloTelefono, editado(correo = bueno))[CampoDeContacto.CORREO])
        }
    }
}
