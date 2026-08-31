package com.avoqado.pos.customerdisplay

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayCapabilitySnapshotTest {

    private val hints = listOf("anydesk", "teamviewer", "scrcpy", "screencap")

    @Test
    fun `Sunmi D3 con secundaria fisica esta presente y permite invertir`() {
        val snapshot = resolveDisplayCapabilitySnapshot(
            defaultDisplayId = 0,
            candidates = listOf(CandidateDisplay(displayId = 2, ownerPackage = null)),
            remoteCaptureHints = hints,
        )

        assertEquals(DisplayCapabilitySnapshot(present = true, invertible = true), snapshot)
    }

    @Test
    fun `Sunmi T3 Pro con secundaria virtual esta presente pero no permite invertir`() {
        val snapshot = resolveDisplayCapabilitySnapshot(
            defaultDisplayId = 0,
            candidates = listOf(CandidateDisplay(displayId = 3, ownerPackage = "com.sunmi.usbscreen")),
            remoteCaptureHints = hints,
        )

        assertEquals(DisplayCapabilitySnapshot(present = true, invertible = false), snapshot)
    }

    @Test
    fun `telefono sin secundaria no reporta display ni inversion`() {
        val snapshot = resolveDisplayCapabilitySnapshot(
            defaultDisplayId = 0,
            candidates = emptyList(),
            remoteCaptureHints = hints,
        )

        assertEquals(DisplayCapabilitySnapshot(present = false, invertible = false), snapshot)
    }

    @Test
    fun `AnyDesk y capturas no se anuncian como display del cliente`() {
        listOf("com.anydesk.anydeskandroid", "com.vendor.screencap").forEach { owner ->
            val snapshot = resolveDisplayCapabilitySnapshot(
                defaultDisplayId = 0,
                candidates = listOf(CandidateDisplay(displayId = 5, ownerPackage = owner)),
                remoteCaptureHints = hints,
            )

            assertEquals(DisplayCapabilitySnapshot(present = false, invertible = false), snapshot)
        }
    }
}
