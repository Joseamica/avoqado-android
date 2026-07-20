package com.avoqado.pos.designsystem.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AvoqadoBrandLoaderTest {

    @Test
    fun `seed appears before green growth starts`() {
        val frame = avoqadoLoaderFrameAt(0.08f)

        assertEquals(1f, frame.seedAlpha, 0.001f)
        assertEquals(0f, frame.growthProgress, 0.001f)
        assertEquals(0f, frame.completeGreenAlpha, 0.001f)
    }

    @Test
    fun `growth resolves into complete mark`() {
        val growthFrame = avoqadoLoaderFrameAt(0.40f)
        val completeFrame = avoqadoLoaderFrameAt(0.72f)

        assertTrue(growthFrame.growthProgress in 0f..1f)
        assertEquals(1f, completeFrame.growthProgress, 0.001f)
        assertEquals(1f, completeFrame.completeGreenAlpha, 0.001f)
        assertEquals(1f, completeFrame.seedAlpha, 0.001f)
    }
}
