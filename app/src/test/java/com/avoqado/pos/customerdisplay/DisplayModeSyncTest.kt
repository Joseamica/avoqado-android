package com.avoqado.pos.customerdisplay

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayModeSyncTest {
    @Test
    fun `local dirty pushes and clean local may adopt legacy confirmation`() {
        assertEquals(DisplayModeAction.Push(true), reconcileDisplayMode(true, true, false))
        assertEquals(DisplayModeAction.Adopt(true), reconcileDisplayMode(false, false, true))
        assertEquals(DisplayModeAction.Keep, reconcileDisplayMode(true, false, null))
        assertEquals(DisplayModeAction.Keep, reconcileDisplayMode(true, false, true))
    }

    @Test
    fun `local mutation commits value dirty and incremented generation before publishing`() {
        lateinit var subject: DisplayModePrefs
        var publishedBeforeCommit = true
        val prefs = preferenceHarness(
            onCommit = {
                publishedBeforeCommit = subject.inverted.value || subject.dirty.value ||
                    subject.generation.value != 0L
            },
        )
        subject = DisplayModePrefs(prefs)

        subject.setInverted(true)

        assertFalse(publishedBeforeCommit)
        assertTrue(subject.inverted.value)
        assertTrue(subject.dirty.value)
        assertEquals(1L, subject.generation.value)
        assertEquals(true, prefs.getBoolean("customer_display_inverted", false))
        assertEquals(true, prefs.getBoolean("customer_display_inverted_dirty", false))
        assertEquals(1L, prefs.getLong("customer_display_local_generation", -1L))
    }

    @Test
    fun `remote exact value supersedes older dirty without incrementing generation`() {
        val subject = DisplayModePrefs(preferenceHarness())
        subject.setInverted(true)
        val journalGeneration = subject.generation.value

        val result = subject.applyRemoteIntent(false, journalGeneration)

        assertEquals(RemoteDisplayModeApplyResult.Applied(false), result)
        assertFalse(subject.inverted.value)
        assertFalse(subject.dirty.value)
        assertEquals(journalGeneration, subject.generation.value)
    }

    @Test
    fun `local change after journal rejects remote and preserves local state`() {
        val subject = DisplayModePrefs(preferenceHarness())
        val journalGeneration = subject.generation.value
        subject.setInverted(true)

        val result = subject.applyRemoteIntent(false, journalGeneration)

        assertEquals(RemoteDisplayModeApplyResult.LocalOverride(true, 1L), result)
        assertTrue(subject.inverted.value)
        assertTrue(subject.dirty.value)
        assertEquals(1L, subject.generation.value)
    }

    @Test
    fun `A B A local sequence cannot clear dirty from an old matching response`() {
        val subject = DisplayModePrefs(preferenceHarness())
        subject.setInverted(true)
        val generationSent = subject.generation.value
        subject.setInverted(false)
        subject.setInverted(true)

        assertFalse(subject.markSynced(generationSent, expectedValue = true))
        assertTrue(subject.dirty.value)
        assertEquals(3L, subject.generation.value)
    }

    @Test
    fun `persistence failure never publishes an in memory success`() {
        val subject = DisplayModePrefs(preferenceHarness(commitResults = listOf(false, false)))

        val error = runCatching { subject.setInverted(true) }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertFalse(subject.inverted.value)
        assertFalse(subject.dirty.value)
        assertEquals(0L, subject.generation.value)
    }

    private fun preferenceHarness(
        commitResults: List<Boolean> = emptyList(),
        onCommit: (() -> Unit)? = null,
    ): SharedPreferences {
        val values = mutableMapOf<String, Any>()
        val remaining = commitResults.toMutableList()
        val preferences = mockk<SharedPreferences>()
        every { preferences.contains(any()) } answers { values.containsKey(firstArg()) }
        every { preferences.getBoolean(any(), any()) } answers { values[firstArg()] as? Boolean ?: secondArg() }
        every { preferences.getLong(any(), any()) } answers { values[firstArg()] as? Long ?: secondArg() }
        every { preferences.edit() } answers {
            val editor = mockk<SharedPreferences.Editor>()
            val writes = mutableMapOf<String, Any>()
            val removals = mutableSetOf<String>()
            every { editor.putBoolean(any(), any()) } answers {
                writes[firstArg()] = secondArg<Boolean>()
                removals.remove(firstArg())
                editor
            }
            every { editor.putLong(any(), any()) } answers {
                writes[firstArg()] = secondArg<Long>()
                removals.remove(firstArg())
                editor
            }
            every { editor.remove(any()) } answers {
                removals += firstArg<String>()
                writes.remove(firstArg())
                editor
            }
            every { editor.commit() } answers {
                onCommit?.invoke()
                removals.forEach(values::remove)
                values.putAll(writes)
                if (remaining.isEmpty()) true else remaining.removeAt(0)
            }
            editor
        }
        return preferences
    }
}
