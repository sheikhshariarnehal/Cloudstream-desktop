package com.lagradost.cloudstream.desktop.ui.screens.player

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal fun vlcFrameBitmap(pixels: IntArray, width: Int, height: Int): ImageBitmap {
    require(width > 0 && height > 0 && width.toLong() * height <= Int.MAX_VALUE / 4)
    val count = width * height
    require(pixels.size >= count)
    val bytes = ByteArray(count * 4)
    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(pixels, 0, count)
    // RV32's unused alpha byte must not make opaque video transparent.
    for (i in 3 until bytes.size step 4) bytes[i] = -1
    val bitmap = Bitmap()
    if (!bitmap.installPixels(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE), bytes, width * 4)) {
        bitmap.close()
        error("Could not allocate video frame")
    }
    bitmap.setImmutable()
    return bitmap.asComposeImageBitmap()
}
