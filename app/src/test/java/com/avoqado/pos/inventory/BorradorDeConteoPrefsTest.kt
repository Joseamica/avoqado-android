package com.avoqado.pos.inventory

import android.content.Context
import android.content.SharedPreferences
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.inventory.data.BorradorDeConteo
import com.avoqado.pos.inventory.data.BorradorDeConteoPrefs
import com.avoqado.pos.inventory.data.ConteoEnCurso
import com.avoqado.pos.inventory.data.model.StockCountItem
import com.avoqado.pos.inventory.data.model.StockCountType
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * El almacén durable del conteo en curso. Sin Robolectric (no está en las dependencias):
 * `SharedPreferences` es una interfaz y `Context` una clase abstracta, así que mockk basta
 * y las pruebas corren en la JVM en milisegundos.
 *
 * Lo que guardan estas pruebas es el punto EXACTO donde el conteo se pierde o no se pierde:
 * que la escritura sea síncrona (`commit()`), que el venue lo ponga el almacén, y que sin
 * venue no se escriba un borrador que después nadie podría leer.
 */
class BorradorDeConteoPrefsTest {

    private val prefs: SharedPreferences = mockk(relaxed = true)
    private val editor: SharedPreferences.Editor = mockk(relaxed = true)
    private val context: Context = mockk(relaxed = true)
    private val secureStorage: SecureStorage = mockk(relaxed = true)

    @Before
    fun setUp() {
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { prefs.edit() } returns editor
        every { prefs.getString(any(), any()) } returns null
        every { prefs.all } returns emptyMap()
        // El editor se encadena consigo MISMO: si `putString` devolviera otro mock (lo que hace
        // un relaxed sin stub), el `commit()` caería en un objeto distinto y la verificación de
        // abajo pasaría por el motivo equivocado.
        every { editor.putString(any(), any()) } returns editor
        every { editor.remove(any()) } returns editor
        every { editor.commit() } returns true
        every { secureStorage.venueId } returns VENUE
    }

    private fun store() = BorradorDeConteoPrefs(context, secureStorage)

    private fun borrador(venueId: String = VENUE) = BorradorDeConteo(
        venueId = venueId,
        countId = "count-1",
        type = StockCountType.FULL,
        lineas = listOf(
            StockCountItem(id = "uuid-1", productId = "p1", productName = "Yogurt", expected = 10.0, counted = 8.0, difference = -2.0, countedAt = "2026-09-07T11:00:00Z"),
        ),
        nota = "estante 3",
        pendientesDeEnviar = setOf("uuid-1"),
        revision = 4,
        actualizadoEn = 1_700_000_000_000,
    )

    @Test
    fun `P1 guardar escribe con commit y nunca con apply`() {
        store().guardar(borrador())

        // `apply()` es asíncrono: si el proceso muere entre teclear y el flush, la línea se pierde.
        verify(exactly = 1) { editor.commit() }
        verify(exactly = 0) { editor.apply() }
    }

    @Test
    fun `P1 guardar sustituye el venueId por el real de SecureStorage`() {
        val llave = slot<String>()
        val json = slot<String>()
        every { editor.putString(capture(llave), capture(json)) } returns editor

        // El ViewModel manda "" mientras no conoce el venue. Guardar ese "" dentro del JSON
        // dejaría un borrador que `leer()` descarta PARA SIEMPRE: trabajo perdido en disco.
        store().guardar(borrador(venueId = ""))

        assertEquals("borrador.$VENUE", llave.captured)
        val guardado = ConteoEnCurso.decodificar(json.captured)
        assertNotNull(guardado)
        assertEquals(VENUE, guardado!!.venueId)
        assertEquals(listOf("uuid-1"), guardado.lineas.map { it.id })
        assertEquals(setOf("uuid-1"), guardado.pendientesDeEnviar)
    }

    @Test
    fun `sin venue no escribe nada`() {
        every { secureStorage.venueId } returns null
        val store = store()

        store.guardar(borrador())

        // La garantía es ÉSTA: sin venue no se toca el disco.
        verify(exactly = 0) { prefs.edit() }
        // El `leer()` de abajo NO añade una segunda garantía: el stub del `setUp` devuelve null
        // pase lo que pase, así que esta línea documenta el estado, no lo guarda.
        assertNull(store.leer())
    }

    @Test
    fun `P1 guardar no adopta un borrador de OTRA sucursal`() {
        // Cambiar de sucursal a media captura no puede convertir lo contado en A en un conteo
        // de B: estampar el venue actual encima sería adoptar trabajo ajeno.
        store().guardar(borrador(venueId = "otro"))

        verify(exactly = 0) { prefs.edit() }
    }

    @Test
    fun `borrar quita el borrador con commit`() {
        // Corre al confirmar un conteo y al descartarlo: si fallara, el borrador sobreviviría a
        // un conteo YA cerrado y la tarjeta "conteo sin terminar" llevaría al cajero a un
        // CONFLICTO contra el servidor.
        store().borrar()

        verify(exactly = 1) { editor.remove("borrador.$VENUE") }
        verify(exactly = 1) { editor.commit() }
        verify(exactly = 0) { editor.apply() }
    }

    @Test
    fun `P1 leer devuelve el borrador recien guardado sin volver a disco`() {
        val store = store()

        store.guardar(borrador())

        // Sin caché, cada cantidad tecleada costaría —además del commit con fsync que la
        // durabilidad sí exige— un getString y un decode del conteo ENTERO, en el hilo del que
        // llama.
        assertEquals("count-1", store.leer()?.countId)
        // `guardar` relee una vez para proteger revisión/stage; la lectura posterior sale de cache.
        verify(exactly = 1) { prefs.getString("borrador.$VENUE", null) }

        // Y borrar la invalida: si no, el borrador seguiría "existiendo" después de confirmar.
        store.borrar()
        assertNull(store.leer())
        verify(exactly = 2) { prefs.getString("borrador.$VENUE", null) }
    }

    @Test
    fun `leer descarta un borrador de otro venue`() {
        // Fija una DEFENSA, no un estado que el código pueda producir hoy: `guardar()` estampa
        // el venue actual y escribe bajo la llave de ESE mismo venue, así que un borrador ajeno
        // bajo esta llave sólo podría venir de fuera de esta clase.
        every { prefs.getString("borrador.$VENUE", null) } returns ConteoEnCurso.codificar(borrador(venueId = "otro"))
        assertNull(store().leer())

        // Control positivo: si `leer()` devolviera null siempre, la aserción de arriba
        // pasaría sin guardar nada.
        every { prefs.getString("borrador.$VENUE", null) } returns ConteoEnCurso.codificar(borrador())
        assertEquals(VENUE, store().leer()?.venueId)
    }

    @Test
    fun `P1 dos cancelaciones pendientes caben las dos y quitar una deja la otra`() {
        // Con UNA ranura, descartar un segundo conteo sin red borraba la cancelación del primero
        // y ese conteo se quedaba IN_PROGRESS para siempre.
        val store = store()
        val escrito = slot<String>()
        every { editor.putString("cancelar.$VENUE", capture(escrito)) } returns editor

        store.agregarCancelacionPendiente("c1")
        every { prefs.getString("cancelar.$VENUE", null) } answers { escrito.captured }
        store.agregarCancelacionPendiente("c2")
        assertEquals(listOf("c1", "c2"), store.cancelacionesPendientes())

        store.quitarCancelacionPendiente("c1")
        assertEquals(listOf("c2"), store.cancelacionesPendientes())

        // La última se va con la llave entera, no con una lista vacía en disco.
        store.quitarCancelacionPendiente("c2")
        verify(exactly = 1) { editor.remove("cancelar.$VENUE") }
        // Síncrono siempre: una cancelación perdida deja un conteo abierto para siempre.
        verify(exactly = 0) { editor.apply() }
    }

    @Test
    fun `un id suelto de la version anterior se sigue leyendo`() {
        // El formato viejo era el id a pelo, no una lista JSON. Descartarlo al leer perdería una
        // cancelación ya encolada en un aparato que se actualiza.
        every { prefs.getString("cancelar.$VENUE", null) } returns "c-viejo"
        assertEquals(listOf("c-viejo"), store().cancelacionesPendientes())
    }

    @Test
    fun `P1 descartar quita el borrador y encola la cancelacion en UN SOLO commit`() {
        // 🔴 Eran dos `commit()` seguidos y entre ellos cabe la muerte del proceso: el borrador ya
        // borrado y la cancelación sin encolar dejaban el conteo IN_PROGRESS en el servidor con
        // NADA en el aparato que volviera a intentarlo. Un `Editor` es atómico: o los dos cambios
        // o ninguno.
        val store = store()

        assertTrue(store.descartarYEncolarCancelacion(VENUE, "count-1", expectedRevision = 4))

        // Un solo `edit()` y un solo `commit()` = una sola escritura atómica.
        verify(exactly = 1) { prefs.edit() }
        verify(exactly = 1) { editor.commit() }
        verify(exactly = 0) { editor.apply() }
        // …con los DOS cambios dentro del mismo editor.
        verify(exactly = 1) { editor.remove("borrador.$VENUE") }
        val cola = slot<String>()
        verify(exactly = 1) { editor.putString("cancelar.$VENUE", capture(cola)) }
        val cancelacion = ConteoEnCurso.decodificarCancelacionesConRevision(cola.captured)!!.single()
        assertEquals("count-1", cancelacion.countId)
        assertEquals(4, cancelacion.expectedRevision)
    }

    @Test
    fun `descartar un ciclico sin crear sólo quita el borrador`() {
        // Sin id del servidor no hay nada que cancelar allá: encolar un "" dejaría una cancelación
        // que ninguna petición puede satisfacer.
        assertTrue(store().descartarYEncolarCancelacion(""))

        verify(exactly = 1) { editor.remove("borrador.$VENUE") }
        verify(exactly = 0) { editor.putString("cancelar.$VENUE", any()) }
        verify(exactly = 1) { editor.commit() }
    }

    @Test
    fun `P1 un commit fallido NO actualiza la cache y lo dice`() {
        // 🔴 El `commit()` se llamaba y su resultado se tiraba: con el disco lleno o en modo
        // lectura, la caché se quedaba con un borrador que NUNCA llegó a disco, `leer()` lo
        // devolvía tan campante y el ViewModel seguía al PUT como si la línea estuviera a salvo.
        // Al morir el proceso, esa línea no existía en ningún lado.
        every { editor.commit() } returns false
        val store = store()

        assertFalse(store.guardar(borrador()))

        // La caché describe el DISCO: si no se escribió, no hay borrador que enseñar…
        every { prefs.getString("borrador.$VENUE", null) } returns null
        assertNull(store.leer())
        // …y `leer()` vuelve a preguntarle al disco en vez de servir la copia fantasma.
        verify(atLeast = 1) { prefs.getString("borrador.$VENUE", null) }
    }

    @Test
    fun `P1 borrar y las cancelaciones tambien reportan el commit fallido`() {
        every { editor.commit() } returns false
        val store = store()

        assertFalse("borrar", store.borrar())
        assertFalse("agregar cancelación", store.agregarCancelacionPendiente("c1"))
        assertFalse("descartar", store.descartarYEncolarCancelacion("c1"))

        // Y quitar una que sí está encolada: perderla en silencio dejaría el conteo abierto.
        every { prefs.getString("cancelar.$VENUE", null) } returns ConteoEnCurso.codificarCancelaciones(listOf("c1"))
        assertFalse("quitar cancelación", store.quitarCancelacionPendiente("c1"))
    }

    @Test
    fun `una escritura que NO hace falta cuenta como exito`() {
        // Encolar una cancelación que ya está encolada no toca el disco: devolver `false` ahí haría
        // que el ViewModel se plantara con el estado ya correcto delante.
        every { prefs.getString("cancelar.$VENUE", null) } returns ConteoEnCurso.codificarCancelaciones(listOf("c1"))
        val store = store()

        assertTrue(store.agregarCancelacionPendiente("c1"))
        assertTrue(store.quitarCancelacionPendiente("no-esta"))
        verify(exactly = 0) { editor.commit() }
    }

    @Test
    fun `P1 edicion UI sobre ACK concurrente conserva revision nueva y no revive linea reconocida`() {
        val store = store()
        val original = borrador()
        assertTrue(store.guardar(original))
        assertTrue(
            store.reconocerPut(
                VENUE,
                "count-1",
                expectedRevision = 4,
                nuevaRevision = 5,
                sellos = mapOf("uuid-1" to (8.0 to "2026-09-07T11:00:00Z")),
            ),
        )
        val nueva = original.lineas.single().copy(
            id = "uuid-2",
            productId = "p2",
            counted = 3.0,
            countedAt = "2026-09-08T12:00:00Z",
        )

        assertTrue(
            store.guardarEdicion(
                VENUE,
                original.copy(
                    lineas = original.lineas + nueva,
                    pendientesDeEnviar = setOf("uuid-1", "uuid-2"),
                ),
            ),
        )

        val vigente = store.leer(VENUE)!!
        assertEquals(5, vigente.revision)
        assertEquals(setOf("uuid-2"), vigente.pendientesDeEnviar)
    }

    @Test
    fun `P1 cualquier edicion invalida el stage de cierre`() {
        val store = store()
        val original = borrador().copy(
            pendientesDeEnviar = emptySet(),
            notaPendienteDeEnviar = false,
            revisionConPutFinalConfirmado = 4,
        )
        store.guardar(original)
        val editada = original.lineas.single().copy(counted = 9.0, countedAt = "nuevo")

        store.guardarEdicion(VENUE, original.copy(lineas = listOf(editada)))

        assertNull(store.leer(VENUE)?.revisionConPutFinalConfirmado)
        assertEquals(setOf("uuid-1"), store.leer(VENUE)?.pendientesDeEnviar)
    }

    @Test
    fun `P1 indice durable enumera borradores y cancelaciones de todos los venues`() {
        every { prefs.all } returns mapOf(
            "borrador.venue-z" to "{}",
            "cancelar.venue-a" to "[]",
            "otra.llave" to "x",
        )

        assertEquals(listOf("venue-a", "venue-z"), store().venuesConTrabajo())
    }

    private companion object {
        const val VENUE = "venue-real"
    }
}
