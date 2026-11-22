package com.lagradost.cloudstream.desktop.ui.screens.player

import androidx.compose.ui.graphics.asSkiaBitmap
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VideoFrameTest {
    @Test
    fun preservesRgbChannelsRowsAndOpaqueVlcPixels() {
        val frame = vlcFrameBitmap(intArrayOf(0x00ff0000, 0x0000ff00, 0x000000ff, 0x12345678), 2, 2)
        try {
            val pixels = IntArray(4)
            frame.readPixels(pixels)
            assertContentEquals(intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt(), 0xff0000ff.toInt(), 0xff345678.toInt()), pixels)
        } finally {
            frame.asSkiaBitmap().close()
        }
    }

    @Test
    fun ignoresPooledTailAndKeepsPublishedFramesIndependent() {
        val pooled = intArrayOf(0x00112233, 0x00445566, 0x00778899, 0x00ffffff)
        val first = vlcFrameBitmap(pooled, 2, 1)
        pooled[0] = 0x00aabbcc
        val second = vlcFrameBitmap(pooled, 1, 1)
        try {
            assertEquals(2, first.width)
            assertEquals(1, first.height)
            assertEquals(0xff112233.toInt(), first.asSkiaBitmap().getColor(0, 0))
            assertEquals(0xffaabbcc.toInt(), second.asSkiaBitmap().getColor(0, 0))
        } finally {
            first.asSkiaBitmap().close()
            second.asSkiaBitmap().close()
        }
    }

    @Test
    fun rejectsInvalidDimensionsAndShortNativeFrames() {
        assertFailsWith<IllegalArgumentException> { vlcFrameBitmap(intArrayOf(0), 0, 1) }
        assertFailsWith<IllegalArgumentException> { vlcFrameBitmap(intArrayOf(0), -1, 1) }
        assertFailsWith<IllegalArgumentException> { vlcFrameBitmap(intArrayOf(0), Int.MAX_VALUE, 2) }
        assertFailsWith<IllegalArgumentException> { vlcFrameBitmap(intArrayOf(0), 2, 1) }
    }
}
