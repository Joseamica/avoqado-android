package com.avoqado.pos.kds.data

import android.content.Context
import android.content.SharedPreferences
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Lo que ESTA tablet recuerda. Sin Robolectric: `SharedPreferences` es interfaz (patrón `BorradorDeConteoPrefsTest`). */
class KdsPrefsTest {

    private val guardado = mutableMapOf<String, Any?>()
    private val editor = mockk<SharedPreferences.Editor>()
    private val prefs = mockk<SharedPreferences>()
    private val context = mockk<Context>()

    @Before
    fun armar() {
        every { context.getSharedPreferences("avoqado_kds", Context.MODE_PRIVATE) } returns prefs
        every { prefs.getString(any(), any()) } answers { guardado[firstArg<String>()] as String? ?: secondArg<String?>() }
        every { prefs.getBoolean(any(), any()) } answers { guardado[firstArg<String>()] as Boolean? ?: secondArg<Boolean>() }
        every { prefs.edit() } returns editor
        every { editor.putString(any(), any()) } answers { guardado[firstArg<String>()] = secondArg<String?>(); editor }
        every { editor.putBoolean(any(), any()) } answers { guardado[firstArg<String>()] = secondArg<Boolean>(); editor }
        every { editor.apply() } just Runs
    }

    @Test
    fun `la estacion se recuerda POR NEGOCIO - otra sucursal no hereda la de la primera`() {
        val kds = KdsPrefs(context)
        kds.guardarEstacion("venue-centro", "st-barra")
        assertEquals("st-barra", kds.estacion("venue-centro"))
        assertNull(kds.estacion("venue-norte"))
    }

    @Test
    fun `la foto del tablero conserva impresionReparto y una foto vieja sin el campo se lee con null`() {
        val kds = KdsPrefs(context)
        val ir = com.avoqado.pos.kds.domain.ImpresionReparto("RAPPI", "cA", false, "cB")
        val o = com.avoqado.pos.kds.domain.KDSOrder(
            id = "k1", orderNumber = "1", orderType = "Delivery", items = emptyList(), createdAt = 1L,
            status = com.avoqado.pos.kds.domain.KDSOrderStatus.NEW, impresionReparto = ir,
        )
        kds.guardarFoto("v", "s", listOf(o), tomadaEnMillis = 1_000L)
        assertEquals(ir, kds.foto("v", "s", ahora = 2_000L)!!.single().impresionReparto)

        guardado["board_v_old"] = """{"tomadaEnMillis":1000,"comandas":[{"id":"k1","orderNumber":"1","orderType":"Delivery","items":[],"createdAt":1,"status":"NEW"}]}"""
        assertNull(kds.foto("v", "old", ahora = 2_000L)!!.single().impresionReparto)
    }

    @Test
    fun `sin nada guardado suena y la letra es normal`() {
        val kds = KdsPrefs(context)
        assertTrue(kds.sonido)
        assertFalse(kds.letraGrande)
    }

    @Test
    fun `sonido y letra grande sobreviven a que la pantalla se cierre`() {
        KdsPrefs(context).apply {
            sonido = false
            letraGrande = true
        }
        val otra = KdsPrefs(context)
        assertFalse(otra.sonido)
        assertTrue(otra.letraGrande)
    }
}
