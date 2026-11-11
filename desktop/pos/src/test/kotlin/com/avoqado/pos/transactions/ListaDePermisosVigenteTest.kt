package com.avoqado.pos.transactions

import com.avoqado.escritorio.ContextoDeEscritorio
import com.avoqado.pos.cashdrawer.domain.AccesoAlRetiro
import com.avoqado.pos.cashdrawer.domain.accesoAlRetiro
import com.avoqado.pos.cashdrawer.domain.puedeRetirar
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.StoredVenue
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** El SecureStorage REAL sobre disco: la lista vigente de permisos (retiro con gerente, r3 #4) y su marca. */
class ListaDePermisosVigenteTest {
    private fun conStorage(bloque: (SecureStorage) -> Unit) {
        val folder = Files.createTempDirectory("lista-vigente")
        try { bloque(SecureStorage(ContextoDeEscritorio(folder))) } finally { folder.toFile().deleteRecursively() }
    }

    private fun SecureStorage.entrar(venue: String = "venue-a", permisos: List<String> = listOf("viejo"), vigentes: Boolean = false) = saveLogin(
        userId = "u1", email = "a@b.c", firstName = "A", lastName = "B", venueId = venue, venueName = "V", venueSlug = null,
        role = "MANAGER", accessToken = "t", refreshToken = "r", venuePermissions = permisos, permisosVigentes = vigentes,
    )

    /** Cómo llega al retiro quien está en la sesión, con lo que quedó guardado (lo mismo que lee la pantalla de Caja). */
    private fun SecureStorage.comoRetira(enLinea: Boolean) = accesoAlRetiro(
        puedeRetirar(listaDePermisosVigente, "cash-drawer:pay-out" in venuePermissions, userRole),
        pinDeGerenteActivo = true,
        enLinea = enLinea,
    )

    /** H3 (A): la lista que el SERVIDOR marcó vigente manda desde el login, también sin red después: el gerente sin el permiso no retira directo. */
    @Test fun `un login con la lista vigente sin el retiro no deja retirar directo al gerente`() = conStorage { s ->
        s.entrar(permisos = listOf("orders:read"), vigentes = true)
        assertTrue(s.listaDePermisosVigente)
        assertEquals(AccesoAlRetiro.CON_PIN, s.comoRetira(enLinea = true))
        assertEquals(AccesoAlRetiro.SIN_RED, s.comoRetira(enLinea = false))
    }

    @Test fun `un login de un servidor viejo (sin la marca) conserva el respaldo por rol`() = conStorage { s ->
        s.entrar(permisos = listOf("orders:read"))
        assertFalse(s.listaDePermisosVigente)
        assertEquals(AccesoAlRetiro.DIRECTO, s.comoRetira(enLinea = false))
    }

    @Test fun `cambiar de sucursal instala la marca del venue que entra`() = conStorage { s ->
        s.entrar()
        s.switchVenue(StoredVenue(id = "venue-b", name = "B", role = "MANAGER", permissions = listOf("orders:read"), permisosVigentes = true))
        assertTrue(s.listaDePermisosVigente)
        assertEquals(AccesoAlRetiro.SIN_RED, s.comoRetira(enLinea = false))
        s.switchVenue(StoredVenue(id = "venue-c", name = "C", role = "MANAGER", permissions = listOf("orders:read")))
        assertFalse(s.listaDePermisosVigente)
    }

    /** H5: una respuesta que salió en la sucursal A no instala sus permisos después de cambiar de sucursal, ni de vuelta en A (A → B → A). */
    @Test fun `cambiar de sucursal vence el sello de las respuestas en vuelo`() = conStorage { s ->
        s.entrar()
        val selloEnA = s.generacion
        s.switchVenue(StoredVenue(id = "venue-b", name = "B", permissions = listOf("de-b")))
        assertFalse(s.guardarPermisosDeLaSesionSi(selloEnA, "venue-b", listOf("de-a")), "con el sello de A, en B")
        s.switchVenue(StoredVenue(id = "venue-a", name = "A", permissions = listOf("de-a-nueva")))
        assertFalse(s.guardarPermisosDeLaSesionSi(selloEnA, "venue-a", listOf("de-a-vieja")), "de vuelta en A, con el sello viejo")
        assertEquals(listOf("de-a-nueva"), s.venuePermissions)
        assertFalse(s.listaDePermisosVigente)
        assertTrue(s.guardarPermisosDeLaSesionSi(s.generacion, "venue-a", listOf("x")), "con el sello vigente sí")
    }

    /** H5: el cambio de sucursal toma el mismo candado que el guardado de permisos: no se cuela entre su comprobación y su escritura. */
    @Test fun `cambiar de sucursal espera al guardado de permisos que ya empezo`() = conStorage { s ->
        s.entrar()
        val termino = CountDownLatch(1)
        lateinit var cambio: Thread
        synchronized(s) { // quien guarda permisos lo tiene tomado
            cambio = thread { s.switchVenue(StoredVenue(id = "venue-b", name = "B")); termino.countDown() }
            assertFalse(termino.await(300, TimeUnit.MILLISECONDS), "cambió de sucursal en medio de un guardado")
        }
        assertTrue(termino.await(5, TimeUnit.SECONDS))
        cambio.join()
        assertEquals("venue-b", s.venueId)
    }

    @Test fun `con el sello y la sucursal correctos guarda la lista y la marca`() = conStorage { s ->
        s.entrar()
        assertFalse(s.listaDePermisosVigente)
        assertTrue(s.guardarPermisosDeLaSesionSi(s.generacion, "venue-a", listOf("cash-drawer:pay-out")))
        assertEquals(listOf("cash-drawer:pay-out"), s.venuePermissions)
        assertTrue(s.listaDePermisosVigente)
        assertTrue(s.guardarPermisosDeLaSesionSi(s.generacion, "venue-a", emptyList()))
        assertEquals(emptyList<String>(), s.venuePermissions)
        assertTrue(s.listaDePermisosVigente)
    }

    @Test fun `con otro sello no cambia nada`() = conStorage { s ->
        s.entrar()
        assertFalse(s.guardarPermisosDeLaSesionSi(s.generacion - 1, "venue-a", listOf("x")))
        assertEquals(listOf("viejo"), s.venuePermissions)
        assertFalse(s.listaDePermisosVigente)
    }

    @Test fun `con otra sucursal no cambia nada`() = conStorage { s ->
        s.entrar()
        assertFalse(s.guardarPermisosDeLaSesionSi(s.generacion, "venue-b", listOf("x")))
        assertEquals(listOf("viejo"), s.venuePermissions)
        assertFalse(s.listaDePermisosVigente)
    }

    @Test fun `login, cambio de venue y cerrar sesion apagan la marca`() = conStorage { s ->
        fun vigente() { s.entrar(); assertTrue(s.guardarPermisosDeLaSesionSi(s.generacion, "venue-a", listOf("x"))); assertTrue(s.listaDePermisosVigente) }
        vigente(); s.entrar(); assertFalse(s.listaDePermisosVigente, "saveLogin (login y cambio de usuario)")
        vigente(); s.switchVenue(StoredVenue(id = "venue-b", name = "B", permissions = listOf("y")))
        assertFalse(s.listaDePermisosVigente, "switchVenue")
        assertEquals(listOf("y"), s.venuePermissions)
        vigente(); s.clearSession(); assertFalse(s.listaDePermisosVigente, "clearSession")
    }
}
