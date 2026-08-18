package com.avoqado.pos.tables

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.PermisosRealesDelServer
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.tables.data.TableServiceRepository.TableOwnership
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 🔴 DINERO. Con la propiedad de mesa encendida, la caja tiene que poder liquidar
 * el cheque que abrió un mesero — es su trabajo literal.
 *
 * El defecto: "Pagar" colgaba del MISMO candado que editar (`tables:manage-all`),
 * así que al CAJERO le salía "Mesa de {mesero} — solo lectura" y la venta no se
 * cobraba. El server ya decía que sí desde que su ruta de cobro exime la propiedad
 * con `PAYMENT_OWNERSHIP_OVERRIDES = ['tables:manage-all', 'tables:pay-any']` —
 * pero el 403 nunca llegaba, porque la llamada nunca salía. **El gate del cliente
 * era lo ÚNICO que bloqueaba**, que es la forma más cara de este bug: no deja
 * rastro en el log del server.
 *
 * Los permisos NO están escritos a mano aquí: salen de `PermisosRealesDelServer`,
 * que es la salida literal de `getEffectiveRolePermissions()`.
 */
class CobrarMesaAjenaTest {

    private val secureStorage = mockk<SecureStorage>()
    private val roleManager = RoleManager(secureStorage)

    private fun conRol(rol: String, permisos: List<String>): RoleManager {
        every { secureStorage.userRole } returns rol
        every { secureStorage.venuePermissions } returns permisos
        return roleManager
    }

    /** El estado real del salón: la regla encendida y el cheque es de OTRO. */
    private fun mesaDeOtro(rm: RoleManager, canManageAll: Boolean) = TableOwnership(
        enforced = true,
        staffId = "yo",
        canManageAll = canManageAll,
        canPayAny = rm.canSettleAnyTable,
    )

    private val elDuenio = "el-mesero-que-la-abrio"

    // MARK: - El caso que estaba roto

    @Test
    fun `el CAJERO cobra la mesa del mesero, y sigue sin poder editarla`() {
        val rm = conRol("CASHIER", PermisosRealesDelServer.CASHIER)
        // Premisas medidas contra el server, no supuestas.
        assertTrue("premisa: el server SÍ le da tables:pay-any", "tables:pay-any" in PermisosRealesDelServer.CASHIER)
        assertFalse("premisa: y NO le da tables:manage-all", "tables:manage-all" in PermisosRealesDelServer.CASHIER)

        val ownership = mesaDeOtro(rm, canManageAll = false)
        assertFalse("el cajero tiene que poder COBRARLA", ownership.isLockedForPayment(elDuenio))
        assertTrue("y seguir sin poder EDITARLA", ownership.isLockedForMe(elDuenio))
    }

    @Test
    fun `el permiso se lee de la lista efectiva, no del rol`() {
        // Un venue con Permission Sets puede quitárselo a su cajero: ahí la app
        // tiene que obedecer la lista, no el nombre del rol. Es la razón por la
        // que un gate por lista de roles es una SEGUNDA fuente de verdad.
        val sinPayAny = PermisosRealesDelServer.CASHIER - "tables:pay-any"
        val rm = conRol("CASHIER", sinPayAny)
        assertTrue(mesaDeOtro(rm, canManageAll = false).isLockedForPayment(elDuenio))
    }

    // MARK: - Contención: nadie más gana nada

    @Test
    fun `el MESERO no cobra la mesa de otro mesero`() {
        val rm = conRol("WAITER", PermisosRealesDelServer.WAITER)
        assertFalse("premisa: el server NO le da tables:pay-any", "tables:pay-any" in PermisosRealesDelServer.WAITER)
        val ownership = mesaDeOtro(rm, canManageAll = false)
        assertTrue("bloqueado para cobrar", ownership.isLockedForPayment(elDuenio))
        assertTrue("y para editar", ownership.isLockedForMe(elDuenio))
    }

    @Test
    fun `el GERENTE no pierde nada — pasa por manage-all como siempre`() {
        val rm = conRol("MANAGER", PermisosRealesDelServer.MANAGER)
        val ownership = mesaDeOtro(rm, canManageAll = true)
        assertFalse(ownership.isLockedForPayment(elDuenio))
        assertFalse(ownership.isLockedForMe(elDuenio))
    }

    // MARK: - Las ramas que NO deben cambiar

    @Test
    fun `con la regla apagada nadie se bloquea, tenga o no el permiso`() {
        val rm = conRol("WAITER", PermisosRealesDelServer.WAITER)
        val apagada = TableOwnership(enforced = false, staffId = "yo", canManageAll = false, canPayAny = rm.canSettleAnyTable)
        assertFalse(apagada.isLockedForPayment(elDuenio))
        assertFalse(apagada.isLockedForMe(elDuenio))
    }

    @Test
    fun `mi propia mesa nunca se bloquea`() {
        val rm = conRol("WAITER", PermisosRealesDelServer.WAITER)
        val mia = TableOwnership(enforced = true, staffId = "yo", canManageAll = false, canPayAny = rm.canSettleAnyTable)
        assertFalse(mia.isLockedForPayment("yo"))
        assertFalse(mia.isLockedForMe("yo"))
    }

    @Test
    fun `una cuenta sin dueno conocido no se bloquea`() {
        // Offline / mesa provisional: el cheque todavía no sabe de quién es.
        val rm = conRol("WAITER", PermisosRealesDelServer.WAITER)
        val ownership = mesaDeOtro(rm, canManageAll = false)
        assertFalse(ownership.isLockedForPayment(null))
        assertFalse(ownership.isLockedForMe(null))
    }

    // MARK: - El respaldo por rol (lista efectiva vacía)

    @Test
    fun `sin lista efectiva el respaldo es MANAGER+ — lo mismo que hacia antes`() {
        every { secureStorage.venuePermissions } returns emptyList()
        every { secureStorage.userRole } returns "CASHIER"
        assertFalse("un cajero sin lista cae al comportamiento viejo", roleManager.canSettleAnyTable)
        every { secureStorage.userRole } returns "MANAGER"
        assertTrue(roleManager.canSettleAnyTable)
    }
}
