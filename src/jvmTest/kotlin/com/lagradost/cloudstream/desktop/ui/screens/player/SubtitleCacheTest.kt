package com.lagradost.cloudstream.desktop.ui.screens.player

import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SubtitleCacheTest {
    private val temp = createTempDirectory("subtitle-test-").toFile()
    private val cache = temp.resolve("cache").apply { mkdir() }

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    @Test
    fun repeatedCopiesGetDistinctTrackNamesAndPreserveKnownExtensions() {
        val input = temp.resolve("captions.SSA").apply { writeText("[Script Info]\nTitle: test") }
        val first = cacheSubtitle(input.toURI().toString(), cache)
        val second = cacheSubtitle(input.toURI().toString(), cache)
        for (output in listOf(first, second)) {
            assertTrue(output.name.startsWith("cloudstream-subtitle-"))
            UUID.fromString(output.nameWithoutExtension.removePrefix("cloudstream-subtitle-"))
            assertEquals("ssa", output.extension)
            assertContentEquals(input.readBytes(), output.readBytes())
        }
        assertNotEquals(first, second)
    }

    @Test
    fun missingExtensionsUseTheSubtitleSignature() {
        val input = temp.resolve("captions")
        for ((content, extension) in listOf("\uFEFFWEBVTT\n" to "vtt", "[Script Info]\n" to "ass", "1\n" to "srt")) {
            input.writeText(content)
            assertEquals(extension, cacheSubtitle(input.toURI().toString(), cache).extension)
        }
    }

    @Test
    fun emptyOversizedAndUnsupportedInputsLeaveTheCacheEmpty() {
        val input = temp.resolve("empty.srt").apply { createNewFile() }
        assertFailsWith<IOException> { cacheSubtitle(input.toURI().toString(), cache) }
        RandomAccessFile(input, "rw").use { it.setLength(10L * 1024 * 1024 + 1) }
        assertFailsWith<IOException> { cacheSubtitle(input.toURI().toString(), cache) }
        assertFailsWith<IllegalArgumentException> { cacheSubtitle("ftp://example.test/unsupported.srt", cache) }
        assertTrue(cache.listFiles()!!.isEmpty())
    }
}
