package com.avoqado.pos.giftcards

import com.avoqado.pos.giftcards.domain.*
import com.avoqado.pos.giftcards.data.SolicitudesDeTarjetaStore
import com.avoqado.pos.core.data.local.SecureStorage
import android.content.SharedPreferences
import io.mockk.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SolicitudesDeTarjetaTest {
    private val body = "{ \"amount\":500.00, \"nombre\":\"Ana López ñ\" }"
    private fun s(k: String = "k") = SolicitudDeTarjeta(tipo = "GIFT_CARD_SALE", llave = k, venueId = "a", creadaEn = 1792345678901, cuerpo = body)
    @Test fun `foto para pantallas publica SALE y por terminar solo tras escritura confirmada`() {
        val fake = Preferencias(); val storage = fake.almacen(); val store = SolicitudesDeTarjetaStore(storage)
        assertTrue(store.solicitudes.value.isEmpty())
        assertTrue(store.guardar(s()))
        assertEquals(listOf(s()), store.solicitudes.value)
        fake.ok = false
        assertFalse(store.guardar(s("otra").copy(tipo = "GIFT_CARD_REDEEM", porTerminar = true, orderId = "o")))
        assertEquals(listOf(s()), store.solicitudes.value)
        fake.ok = true
        assertTrue(store.guardar(s("otra").copy(tipo = "GIFT_CARD_REDEEM", porTerminar = true, orderId = "o")))
        assertEquals(2, store.solicitudes.value.size)
        assertTrue(store.solicitudes.value.last().porTerminar)
        assertEquals(store.solicitudes.value, SolicitudesDeTarjetaStore(storage).solicitudes.value)
    }
    @Test fun `P1 formato comun conserva bytes campos futuros y desconocidos`() {
        val raw = """[{"v":1,"tipo":"GIFT_CARD_REDEEM","llave":"r","venueId":"a","creadaEn":1792345678901,"orderId":"o","orderNumber":null,"impresos":[],"cuerpo":"opaque","nuevaCuenta":false,"mandado":true,"foto":"{}","porRecuperar":false,"porTerminar":true,"restanteCents":50000,"iniciadoPor":"a","iniciadoPorNombre":"Ana López","futuro":{"noBorrar":[1,null,"ñ"]}},{"v":2,"tipo":"GIFT_CARD_FUTURE","llave":"f","futuro":3},{"v":1,"tipo":"GIFT_CARD_FUTURE","llave":"f1"}]"""
        assertEquals("Sólo se resuelve v1 de tipo conocido", 1, SolicitudesDeTarjeta.leer(raw).size)
        val parsed = SolicitudesDeTarjeta.leer(raw).single()
        assertEquals(50000, parsed.restanteCents); assertTrue(parsed.mandado); assertTrue(parsed.porTerminar)
        val added = SolicitudesDeTarjeta.agregar(raw, s())!!
        assertArrayEquals(body.toByteArray(), SolicitudesDeTarjeta.leer(added).last().cuerpo.toByteArray())
        val changed = SolicitudesDeTarjeta.conImpreso(SolicitudesDeTarjeta.conOrden(added, "k", "order", "n"), "k", "g")
        val before = Json.parseToJsonElement(raw).jsonArray
        val after = Json.parseToJsonElement(changed).jsonArray
        assertEquals(before, JsonArray(after.take(3)))
        assertEquals(setOf("g"), SolicitudesDeTarjeta.leer(changed).last().impresos)
        assertEquals("order", SolicitudesDeTarjeta.leer(changed).last().orderId)
        assertEquals(raw, SolicitudesDeTarjeta.quitar(raw, "ausente"))
        assertFalse(s().toString().contains(body))
    }
    @Test fun `P1 tope incluye desconocidos e ilegible no se pisa`() {
        val raw = JsonArray(List(50) { buildJsonObject { put("v", 2); put("llave", "f$it") } }).toString()
        assertNull(SolicitudesDeTarjeta.agregar(raw, s()))
        assertNull(SolicitudesDeTarjeta.agregar("{x", s()))
        assertEquals("{x", SolicitudesDeTarjeta.ilegible("{x"))
        assertTrue(SolicitudesDeTarjeta.leer("{x").isEmpty())
    }
    @Test fun `Q95 ausente es inerte y marcar conserva el cuerpo`() {
        val added = SolicitudesDeTarjeta.agregar(null, s().copy(tipo = "GIFT_CARD_REDEEM", orderId = "o"))
        assertNotNull("Guardar el primer registro", added)
        val raw = added!!
        assertFalse(SolicitudesDeTarjeta.leer(raw).single().mandado)
        val marked = SolicitudesDeTarjeta.leer(SolicitudesDeTarjeta.conMandado(raw, "k")).single()
        assertTrue(marked.mandado); assertEquals(body, marked.cuerpo)
        assertEquals("[]", SolicitudesDeTarjeta.quitar(raw, "k"))
    }

    @Test fun `P1 false que cambia lectura no se adopta al quitar ni en store nuevo ni mutacion posterior`() {
        val fake = Preferencias()
        val storage = fake.almacen()
        val store = SolicitudesDeTarjetaStore(storage)
        assertTrue(store.guardar(s("k1")))
        assertTrue(store.guardar(s("k2")))
        fake.ok = false
        assertFalse(store.quitar("k1"))
        assertEquals(listOf("k1", "k2"), store.delNegocio("a").map { it.llave })
        val nuevo = SolicitudesDeTarjetaStore(storage)
        assertEquals(listOf("k1", "k2"), nuevo.delNegocio("a").map { it.llave })
        fake.ok = true
        assertTrue(nuevo.anotarImpreso("k2", "g"))
        assertEquals(listOf("k1", "k2"), nuevo.delNegocio("a").map { it.llave })
        assertEquals(setOf("g"), nuevo.delNegocio("a").last().impresos)
    }
    @Test fun `P1 false al marcar no activa ni se adopta despues y clearAll conserva diario y respaldo`() {
        val fake = Preferencias()
        fake.values["giftCardRequestsIlegible"] = "opaque"
        val storage = fake.almacen()
        val store = SolicitudesDeTarjetaStore(storage)
        assertTrue(store.guardar(s("k1").copy(tipo = "GIFT_CARD_REDEEM", orderId = "o")))
        assertTrue(store.guardar(s("k2")))
        fake.ok = false
        assertFalse(store.marcarMandado("k1"))
        assertFalse(store.delNegocio("a").first().mandado)
        assertTrue(store.cuentasTrabadas.value.isEmpty())
        fake.ok = true
        val nuevo = SolicitudesDeTarjetaStore(storage)
        assertTrue(nuevo.anotarImpreso("k2", "g"))
        assertFalse(nuevo.delNegocio("a").first().mandado)
        assertTrue(nuevo.marcarMandado("k1"))
        assertEquals(setOf("o"), nuevo.cuentasTrabadas.value)
        val raw = storage.giftCardRequestsJson
        storage.clearAll()
        assertEquals(raw, storage.giftCardRequestsJson)
        assertEquals(raw, fake.values["giftCardRequests"])
        assertEquals("opaque", fake.values["giftCardRequestsIlegible"])
        assertNull(fake.values["userId"])
    }
    @Test fun `P1 null transformacion rechaza nunca borra e ilegible se conserva`() {
        val fake = Preferencias(); val storage = fake.almacen()
        assertTrue(storage.escribirSolicitudesDeTarjeta { "[]" })
        assertFalse(storage.escribirSolicitudesDeTarjeta { null })
        assertEquals("[]", storage.giftCardRequestsJson)
        assertEquals("[]", fake.values["giftCardRequests"])
        val bad = Preferencias().apply { values["giftCardRequests"] = "{x" }
        val badStorage = bad.almacen()
        assertFalse(SolicitudesDeTarjetaStore(badStorage).guardar(s()))
        assertEquals("{x", bad.values["giftCardRequests"])
    }
    @Test fun `P1 anotar orden rechaza si el registro desaparecio de la lectura confirmada`() {
        val storage = Preferencias().almacen()
        val store = SolicitudesDeTarjetaStore(storage)
        assertTrue(store.guardar(s()))
        val foto = store.delNegocio("a").single()
        assertTrue(storage.escribirSolicitudesDeTarjeta { "[]" })
        assertFalse("No debe confirmar una orden sobre una fila que ya no existe", store.anotarOrden(foto.llave, "o", "n"))
        assertEquals("[]", storage.giftCardRequestsJson)
        assertTrue(store.delNegocio("a").isEmpty())
    }
    @Test fun `P1 anotar impreso no confirma una fila desconocida que reemplazo a la anterior`() {
        val storage = Preferencias().almacen()
        val store = SolicitudesDeTarjetaStore(storage)
        assertTrue(store.guardar(s()))
        val foto = store.delNegocio("a").single()
        val futuro = """[ {"v":2,"tipo":"GIFT_CARD_SALE","llave":"k","datosFuturos":"intactos"} ]"""
        assertTrue(storage.escribirSolicitudesDeTarjeta { futuro })
        assertFalse("La fila futura no es el registro conocido que se imprimió", store.anotarImpreso(foto.llave, "g"))
        assertEquals(futuro, storage.giftCardRequestsJson)
        assertTrue(store.delNegocio("a").isEmpty())
    }
    internal class Preferencias {
        var ok = true
        val values = mutableMapOf<String, String?>("userId" to "actor")
        fun almacen(): SecureStorage {
            val prefs = mockk<SharedPreferences>()
            every { prefs.getString(any(), any()) } answers { values[firstArg()] ?: secondArg() }
            every { prefs.edit() } answers {
                val changes = mutableMapOf<String, String?>(); var clear = false
                val editor = mockk<SharedPreferences.Editor>(relaxed = true)
                every { editor.putString(any(), any()) } answers { changes[firstArg()] = secondArg(); editor }
                every { editor.remove(any()) } answers { changes[firstArg()] = null; editor }
                every { editor.clear() } answers { clear = true; editor }
                every { editor.commit() } answers { if (clear) values.clear(); values.putAll(changes); ok }
                every { editor.apply() } answers { if (clear) values.clear(); values.putAll(changes) }
                editor
            }
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
            val s = unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, SecureStorage::class.java) as SecureStorage
            fun field(name: String, value: Any) { SecureStorage::class.java.getDeclaredField(name).apply { isAccessible = true }.set(s, value) }
            field("prefs", prefs); field("plainPrefs", mockk<SharedPreferences>(relaxed = true))
            field("_sessionInvalidated", kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1))
            return s
        }
    }
}
