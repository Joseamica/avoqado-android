package com.avoqado.pos.core.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * El fixture de permisos NO puede divergir del server. Este test es el que truena.
 *
 * 🔴 QUÉ FALLA SI ESTO NO EXISTE — y ya falló: el fixture llegó a ser una lista de
 * 9 nombres escrita a mano, titulada "los permisos REALES", que omitía las
 * dependencias IMPLÍCITAS que el server expande antes de mandar la lista. Con ese
 * fixture, dos tests afirmaban en verde que el cajero y el mesero no veían
 * inventario mientras el aparato se los abría. Un fixture que miente no falla: te
 * confirma la premisa equivocada.
 *
 * Los dos artefactos que compara —el Kotlin y el JSON— salen de la MISMA corrida de
 * `scripts/regenerar-permisos-reales.mjs`, así que este test detecta la edición a
 * mano y la regeneración a medias. Para detectar que el SERVER se movió y nadie
 * regeneró, el detector es otro y vive fuera de la JVM:
 * `node scripts/regenerar-permisos-reales.mjs --check` (exit 1 si quedó viejo).
 */
class PermisosRealesDelServerTest {

    private val snapshot = Json.parseToJsonElement(
        File("src/test/resources/permisos-efectivos-del-server.json").readText(),
    ).jsonObject

    private val rolesDelServer: Map<String, List<String>> =
        snapshot["roles"]!!.jsonObject.mapValues { (_, v) ->
            v.jsonObject["effective"]!!.jsonArray.map { it.jsonPrimitive.content }.sorted()
        }

    @Test
    fun `el fixture cuadra con el server, rol por rol`() {
        // Sin esto, un JSON vacío haría pasar el test entero sin comparar nada.
        assertEquals("El snapshot debe traer los 9 roles del enum StaffRole", 9, rolesDelServer.size)
        assertEquals(
            "El fixture y el snapshot deben cubrir EXACTAMENTE los mismos roles",
            rolesDelServer.keys.sorted(),
            PermisosRealesDelServer.PorRol.keys.sorted(),
        )

        val divergen = mutableListOf<String>()
        rolesDelServer.forEach { (rol, delServer) ->
            val enElFixture = PermisosRealesDelServer.PorRol.getValue(rol).sorted()
            val faltan = delServer - enElFixture.toSet()
            val sobran = enElFixture - delServer.toSet()
            if (faltan.isNotEmpty() || sobran.isNotEmpty()) {
                divergen += "$rol: fixture=${enElFixture.size} server=${delServer.size}" +
                    (if (faltan.isNotEmpty()) " · FALTAN $faltan" else "") +
                    (if (sobran.isNotEmpty()) " · SOBRAN $sobran" else "")
            }
        }
        assertTrue(
            "El fixture divergió del server. Corre `node scripts/regenerar-permisos-reales.mjs`.\n" +
                divergen.joinToString("\n"),
            divergen.isEmpty(),
        )
    }

    @Test
    fun `los implicitos del fixture son los que el server calcula`() {
        // La mitad que la versión escrita a mano se comió: lo que NADIE concedió.
        val delServer = snapshot["roles"]!!.jsonObject.mapValues { (_, v) ->
            v.jsonObject["implicit"]!!.jsonArray.map { it.jsonPrimitive.content }.sorted()
        }
        delServer.forEach { (rol, esperados) ->
            assertEquals("implícitos de $rol", esperados, PermisosRealesDelServer.ImplicitosPorRol.getValue(rol).sorted())
        }
        // Premisa del gate de inventario: al cajero le llega por dependencia, no
        // porque alguien se lo diera. Si esto deja de ser cierto, el comentario de
        // `RoleManager.canAccessInventory` se vuelve falso.
        assertTrue(
            "inventory:read del CASHIER tiene que seguir siendo IMPLÍCITO",
            "inventory:read" in PermisosRealesDelServer.ImplicitosPorRol.getValue("CASHIER"),
        )
    }

    // MARK: - Los 5 nombres que el server estrenó y ningún cliente conocía
    //
    // Fijados uno por uno, no por conteo: un conteo correcto con el nombre
    // equivocado pasa igual, y "a name mismatch fails silently" es justo la regla
    // que esto protege.

    @Test
    fun `los 5 nombres nuevos del server estan en los roles correctos`() {
        fun tiene(rol: String, permiso: String) = permiso in PermisosRealesDelServer.PorRol.getValue(rol)

        // `estimates:create` — cotizar dejó de ser `orders:create`. El HOST es el
        // que lo estrena: recepción hace presupuestos y ahora también los cierra.
        assertTrue("HOST debe poder cotizar", tiene("HOST", "estimates:create"))
        assertTrue(tiene("WAITER", "estimates:create"))
        assertTrue(tiene("CASHIER", "estimates:create"))

        // `tpv-shifts:*` — el caso semilla: el cajero abre y cierra SU turno sin
        // que un gerente camine a la terminal.
        listOf("WAITER", "CASHIER", "MANAGER").forEach { rol ->
            assertTrue("$rol abre su turno", tiene(rol, "tpv-shifts:create"))
            assertTrue("$rol cierra su turno", tiene(rol, "tpv-shifts:close"))
        }

        // `orders:cancel-unpaid` — deshacer la venta que el POS creó ANTES de
        // cobrar. Acotado a propósito: NO es `orders:cancel`.
        assertTrue(tiene("CASHIER", "orders:cancel-unpaid"))
        assertTrue(tiene("WAITER", "orders:cancel-unpaid"))
        assertTrue(
            "`orders:cancel` (anular cheques ajenos en servicio) sigue siendo de MANAGER+",
            !tiene("CASHIER", "orders:cancel") && !tiene("WAITER", "orders:cancel"),
        )

        // `tables:pay-any` — liquidar el cheque de otro sin poder editarlo.
        assertTrue("el CAJERO estrena cobrar mesa ajena", tiene("CASHIER", "tables:pay-any"))
        assertTrue("y NO gana editarla", !tiene("CASHIER", "tables:manage-all"))
        assertTrue("el mesero NO cobra mesas ajenas", !tiene("WAITER", "tables:pay-any"))
    }

    @Test
    fun `los roles de solo mirar no ganaron nada de los 5`() {
        // Contención: si un ensanchamiento se les cuela, este test lo canta.
        val nuevos = listOf("estimates:create", "tpv-shifts:create", "tpv-shifts:close", "orders:cancel-unpaid", "tables:pay-any")
        listOf("VIEWER", "KITCHEN").forEach { rol ->
            val ganados = nuevos.filter { it in PermisosRealesDelServer.PorRol.getValue(rol) }
            assertTrue("$rol no debería tener $ganados", ganados.isEmpty())
        }
        // El HOST sólo estrena cotizar: ni turnos, ni cancelar, ni cobrar.
        val hostGano = nuevos.filter { it in PermisosRealesDelServer.PorRol.getValue("HOST") }
        assertEquals(listOf("estimates:create"), hostGano)
    }

    @Test
    fun `el fixture no trae duplicados ni entradas vacias`() {
        PermisosRealesDelServer.PorRol.forEach { (rol, permisos) ->
            assertEquals("$rol trae permisos repetidos", permisos.distinct().size, permisos.size)
            assertTrue("$rol trae una entrada vacía", permisos.none { it.isBlank() })
            assertTrue("$rol no puede llegar vacío", permisos.isNotEmpty())
        }
    }
}
