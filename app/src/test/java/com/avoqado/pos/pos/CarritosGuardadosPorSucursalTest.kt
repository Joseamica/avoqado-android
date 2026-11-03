package com.avoqado.pos.pos

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.auth.data.AuthRepository
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.StoredVenue
import com.avoqado.pos.core.data.network.RefrescoExclusivo
import com.avoqado.pos.payment.PrefsEnMemoria
import com.avoqado.pos.payment.data.PayLaterQueue
import com.avoqado.pos.payment.data.PayLaterRow
import com.avoqado.pos.pos.data.SavedCartsRepository
import com.avoqado.pos.pos.data.model.SavedCart
import com.avoqado.pos.pos.presentation.cart.CartPromotionTest
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * D8 (tablero hz:fa2343a0:D8): cambiar de sucursal BORRABA todos los carritos guardados del aparato, también las ventas que
 * «Pagar después» había pasado ahí. Ahora cada carrito se queda con su sucursal y se enseña sólo en ella, como en iOS.
 */
class CarritosGuardadosPorSucursalTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val disco = PrefsEnMemoria()
    private var venue: String? = "venue-A"
    private val storage = mockk<SecureStorage>(relaxed = true) {
        every { venueId } answers { venue }
        every { refreshSiSigue(any(), any()) } returns null
        every { switchVenue(any()) } answers { venue = firstArg<StoredVenue>().id }
    }

    private fun carrito(id: String, venueId: String? = null) = SavedCart(id = id, name = "Ana", items = emptyList(), venueId = venueId)

    private fun auth(carritos: SavedCartsRepository) = AuthRepository(
        apiService = mockk(relaxed = true),
        secureStorage = storage,
        productsRepository = mockk(relaxed = true),
        discountsRepository = mockk(relaxed = true),
        promotionsRepository = mockk(relaxed = true),
        tpvSettingsRepository = mockk(relaxed = true),
        savedCartsRepository = carritos,
        inventoryRepository = mockk(relaxed = true),
        transactionRepository = mockk(relaxed = true),
        notificationsRepository = mockk(relaxed = true),
        refrescoExclusivo = RefrescoExclusivo(),
    )

    @Test
    fun `P1 cambiar de sucursal y regresar conserva los carritos guardados de cada una`() = runTest {
        val carritos = SavedCartsRepository(disco.context, storage)
        assertTrue(carritos.saveCart(carrito("a1", "venue-A")))

        auth(carritos).switchVenue(StoredVenue(id = "venue-B", name = "Sucursal B"))

        assertEquals("el de A no se enseña en B", emptyList<String>(), carritos.deLaSucursal.value.map { it.id })
        assertEquals("pero no se borró", listOf("a1"), carritos.savedCarts.value.map { it.id })
        assertTrue(carritos.saveCart(carrito("b1", "venue-B")))
        assertEquals(listOf("b1"), carritos.deLaSucursal.value.map { it.id })

        auth(carritos).switchVenue(StoredVenue(id = "venue-A", name = "Sucursal A"))

        assertEquals(listOf("a1"), carritos.deLaSucursal.value.map { it.id })
        assertEquals("y la app que se vuelve a abrir en A los encuentra", listOf("a1"),
            SavedCartsRepository(disco.context, storage).deLaSucursal.value.map { it.id })
    }

    @Test
    fun `P1 un carrito guardado por la version anterior, sin sucursal, se queda con la sucursal que se deja`() = runTest {
        assertTrue(SavedCartsRepository(disco.context).saveCart(carrito("viejo")))
        val carritos = SavedCartsRepository(disco.context, storage)
        assertEquals("en A, donde se guardó, se ve", listOf("viejo"), carritos.deLaSucursal.value.map { it.id })

        auth(carritos).switchVenue(StoredVenue(id = "venue-B", name = "Sucursal B"))

        assertEquals(emptyList<String>(), carritos.deLaSucursal.value.map { it.id })
        assertEquals("se anotó en el disco", listOf("venue-A"),
            SavedCartsRepository(disco.context, storage).savedCarts.value.map { it.venueId })
        venue = "venue-A"
        carritos.alCambiarLaSesion()
        assertEquals(listOf("viejo"), carritos.deLaSucursal.value.map { it.id })
    }

    @Test
    fun `P1 un carrito viejo que la app encuentra sin sesion se queda con la primera sucursal que entra`() {
        assertTrue(SavedCartsRepository(disco.context).saveCart(carrito("viejo"))) // versión anterior
        venue = null
        val carritos = SavedCartsRepository(disco.context, storage) // la versión nueva abre sin sesión: no sabe de quién es

        venue = "venue-A"
        carritos.alCambiarLaSesion() // entra A: como antes, lo guardado antes de salir se ve aquí
        assertEquals(listOf("viejo"), carritos.deLaSucursal.value.map { it.id })
        venue = null
        carritos.alCambiarLaSesion()
        venue = "venue-B"
        carritos.alCambiarLaSesion()

        assertEquals("ya no se muda", emptyList<String>(), carritos.deLaSucursal.value.map { it.id })
        assertEquals(listOf("venue-A"), SavedCartsRepository(disco.context, storage).savedCarts.value.map { it.venueId })
    }

    @Test
    fun `P2 si el disco falla al anotar la sucursal, en memoria ya queda bien y el siguiente guardado la escribe`() {
        assertTrue(SavedCartsRepository(disco.context).saveCart(carrito("viejo"))) // versión anterior
        disco.discoSano = false
        val carritos = SavedCartsRepository(disco.context, storage) // abre en A y no lo puede anotar en el disco

        venue = "venue-B"
        carritos.alCambiarLaSesion()
        assertEquals(emptyList<String>(), carritos.deLaSucursal.value.map { it.id })

        disco.discoSano = true
        assertTrue(carritos.saveCart(carrito("b1", "venue-B")))
        assertEquals(listOf("b1" to "venue-B", "viejo" to "venue-A"),
            SavedCartsRepository(disco.context, storage).savedCarts.value.map { it.id to it.venueId })
    }

    @Test
    fun `P1 el carrito que se guarda nace con la sucursal de la sesion y se ve en Carritos guardados`() = runTest {
        venue = "venue-1"
        val carritos = SavedCartsRepository(disco.context, storage)
        assertTrue(carritos.saveCart(carrito("de-otra", "venue-X")))
        val cart = CartPromotionTest().apply { setup() }.createViewModel(carritos)
        cart.addCustomAmount(name = "A", amountCents = 10000)

        assertTrue(cart.saveCurrentCart("Ana"))

        assertEquals(listOf("venue-1", "venue-X"), carritos.savedCarts.value.map { it.venueId })
        assertEquals("la lista de la pantalla sólo trae los de la sucursal", listOf("venue-1"), cart.savedCarts.value.map { it.venueId })
    }

    @Test
    fun `P1 un carrito de la version anterior queda anotado al abrir la app, aunque el cambio de sucursal se corte a medias`() {
        assertTrue(SavedCartsRepository(disco.context).saveCart(carrito("viejo")))
        SavedCartsRepository(disco.context, storage) // la versión nueva abre en A

        venue = "venue-B" // cambia a B y la app muere antes de avisarles a los carritos (Codex r1 #1)
        val alVolverAAbrir = SavedCartsRepository(disco.context, storage)

        assertEquals(emptyList<String>(), alVolverAAbrir.deLaSucursal.value.map { it.id })
        assertEquals(listOf("venue-A"), alVolverAAbrir.savedCarts.value.map { it.venueId })
    }

    @Test
    fun `P1 una venta por recuperar de una version anterior pasa a guardados con la sucursal de su fila`() = runTest {
        val carritos = SavedCartsRepository(disco.context, storage)
        val cola = PayLaterQueue(PrefsEnMemoria().context, mockk(relaxed = true), storage, carritos, mockk(relaxed = true))
            .apply { io = Dispatchers.Unconfined }
        cola.guardar(PayLaterRow(
            id = "pay-later:1", venueId = "venue-A", staffId = "staff-1", customerId = "cli-1", customerName = "Ana",
            totalEsperadoCents = 10000, cuerpo = "{}", carritoGuardadoId = null, creadaEn = 1L,
            estado = PayLaterRow.Estado.RECUPERAR, venta = carrito("venta-1"),
        ))

        cola.mantenimientoLocal()

        assertEquals("Codex r1 #2", listOf("venta-1" to "venue-A"), carritos.savedCarts.value.map { it.id to it.venueId })
        assertTrue(cola.filas.value.isEmpty())
    }

    @Test
    fun `P1 si la sesion se borra mientras se guarda, el carrito se queda con la sucursal en que se armo`() {
        val carritos = SavedCartsRepository(disco.context, storage)
        assertTrue(carritos.saveCart(carrito("a1", "venue-A")))
        venue = null // una renovación rechazada borró la sesión en otro hilo; su aviso todavía no llega (Codex r2 #1)
        assertTrue(carritos.saveCart(carrito("nuevo")))
        assertTrue("re-guardar sin sucursal conserva la de su copia", carritos.saveCart(carrito("a1")))

        val alVolverAAbrir = SavedCartsRepository(disco.context, storage) // la app muere y abre sin sesión
        venue = "venue-B"
        alVolverAAbrir.alCambiarLaSesion() // entra B

        assertEquals(emptyList<String>(), alVolverAAbrir.deLaSucursal.value.map { it.id })
        assertEquals(listOf("a1" to "venue-A", "nuevo" to "venue-A"), alVolverAAbrir.savedCarts.value.map { it.id to it.venueId })
    }

    @Test
    fun `P2 sin ninguna sesion conocida no se guarda un carrito sin duena`() {
        venue = null
        val carritos = SavedCartsRepository(disco.context, storage)

        assertEquals(false, carritos.saveCart(carrito("nuevo")))
        assertTrue("con su sucursal sí", carritos.saveCart(carrito("con-duena", "venue-A")))
        assertEquals(listOf("con-duena"), carritos.savedCarts.value.map { it.id })
    }
}
