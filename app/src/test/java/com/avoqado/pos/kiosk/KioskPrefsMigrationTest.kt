package com.avoqado.pos.kiosk

import android.content.SharedPreferences
import com.avoqado.pos.kiosk.domain.KioskPrefs.Companion.KEY_CHECKIN
import com.avoqado.pos.kiosk.domain.KioskPrefs.Companion.KEY_SHARED_LEGACY
import com.avoqado.pos.kiosk.domain.KioskPrefs.Companion.Migration
import com.avoqado.pos.kiosk.domain.KioskPrefs.Companion.legacyMigration
import com.avoqado.pos.kiosk.domain.KioskPrefs.Companion.readOrMigrate
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El kiosco de check-in y «Esconder barras» (fijar pantalla) compartían la llave
 * `avoqado_kiosk/kiosk_enabled`: prender el fijado en una tableta de UNA pantalla prendía también el
 * kiosco al reiniciar. Ahora el kiosco tiene su llave y se migra UNA vez, sin apagar a quien ya lo tenía.
 */
class KioskPrefsMigrationTest {

    @Test fun `D3 con la llave vieja prendida conserva el kiosco y lo guarda`() =
        assertEquals(Migration(enabled = true, persist = true), legacyMigration(hasNewKey = false, legacyShared = true, keepLegacy = true))

    @Test fun `tableta de una pantalla con Esconder barras no prende el kiosco y lo vuelve a evaluar`() =
        assertEquals(Migration(enabled = false, persist = false), legacyMigration(false, true, false))

    @Test fun `llave vieja apagada deja el kiosco apagado para siempre`() =
        assertEquals(Migration(enabled = false, persist = true), legacyMigration(false, false, true))

    @Test fun `ya migrado no se toca aunque cambie el fijado o la pantalla`() =
        assertNull(legacyMigration(hasNewKey = true, legacyShared = true, keepLegacy = false))

    // -- readOrMigrate: lo que de verdad toca el disco --------------------------------------------------------------

    private fun prefs(nueva: Boolean?, vieja: Boolean?): Pair<SharedPreferences, SharedPreferences.Editor> {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.putBoolean(any(), any()) } returns editor
        val p = mockk<SharedPreferences>()
        every { p.contains(KEY_CHECKIN) } returns (nueva != null)
        every { p.getBoolean(KEY_CHECKIN, any()) } returns (nueva ?: false)
        every { p.getBoolean(KEY_SHARED_LEGACY, any()) } returns (vieja ?: false)
        every { p.edit() } returns editor
        return p to editor
    }

    @Test fun `con la llave propia ya guardada solo la lee y no pregunta por la pantalla ni escribe`() {
        val (p, _) = prefs(nueva = true, vieja = false)
        var pregunto = false
        assertTrue(readOrMigrate(p) { pregunto = true; false })
        assertFalse("no debe enumerar pantallas si ya migró", pregunto)
        verify(exactly = 0) { p.edit() }
    }

    @Test fun `D3 migrada guarda SOLO la llave del kiosco y nunca toca la del fijado`() {
        val (p, editor) = prefs(nueva = null, vieja = true)
        assertTrue(readOrMigrate(p) { true })
        verify(exactly = 1) { editor.putBoolean(KEY_CHECKIN, true) }
        verify(exactly = 0) { editor.putBoolean(KEY_SHARED_LEGACY, any()) }
        verify(exactly = 0) { editor.remove(any()) }
    }

    @Test fun `una pantalla con el fijado prendido regresa false y no guarda nada para reevaluar el siguiente arranque`() {
        val (p, editor) = prefs(nueva = null, vieja = true)
        assertFalse(readOrMigrate(p) { false })
        verify(exactly = 0) { editor.putBoolean(any(), any()) }
    }
}
