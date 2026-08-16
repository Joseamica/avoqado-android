package com.avoqado.pos.core.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionLabelsTest {

    @Test
    fun `traduce los permisos que el piso puede encontrarse`() {
        assertEquals("fusionar cuentas", PermissionLabels.of("orders:merge"))
        assertEquals("hacer un reembolso", PermissionLabels.of("payments:refund"))
        assertEquals("cancelar la cuenta", PermissionLabels.of("orders:cancel"))
        assertEquals("dar una cortesía", PermissionLabels.of("orders:comp"))
        assertEquals("anular artículos", PermissionLabels.of("orders:void"))
        assertEquals("modificar la cuenta", PermissionLabels.of("orders:update"))
        assertEquals("aplicar un descuento", PermissionLabels.of("discounts:apply"))
    }

    @Test
    fun `un permiso desconocido cae a un texto neutro, nunca a la string tecnica`() {
        assertEquals("esta acción", PermissionLabels.of("cosas:raras"))
        assertEquals("esta acción", PermissionLabels.of(""))
    }
}
