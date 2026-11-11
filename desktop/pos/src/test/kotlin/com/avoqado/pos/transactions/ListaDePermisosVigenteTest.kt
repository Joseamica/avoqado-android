package com.avoqado.pos.transactions

import com.avoqado.escritorio.ContextoDeEscritorio
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.StoredVenue
import java.nio.file.Files
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

    private fun SecureStorage.entrar(venue: String = "venue-a", permisos: List<String> = listOf("viejo")) = saveLogin(
        userId = "u1", email = "a@b.c", firstName = "A", lastName = "B", venueId = venue, venueName = "V", venueSlug = null,
        role = "MANAGER", accessToken = "t", refreshToken = "r", venuePermissions = permisos,
    )

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
