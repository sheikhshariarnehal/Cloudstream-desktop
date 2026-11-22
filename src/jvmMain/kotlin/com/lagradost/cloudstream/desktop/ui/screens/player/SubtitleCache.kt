package com.lagradost.cloudstream.desktop.ui.screens.player

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.util.UUID

private const val MAX_SUBTITLE_BYTES = 10 * 1024 * 1024

internal fun cacheSubtitle(url: String, directory: File): File {
    val uri = URI(url)
    require(uri.scheme?.lowercase() in setOf("http", "https", "file")) { "Unsupported subtitle URL scheme" }
    val connection = uri.toURL().openConnection().apply {
        connectTimeout = 8_000
        readTimeout = 8_000
    }
    val bytes = try {
        connection.getInputStream().use { it.readNBytes(MAX_SUBTITLE_BYTES + 1) }
    } finally {
        (connection as? HttpURLConnection)?.disconnect()
    }
    if (bytes.isEmpty() || bytes.size > MAX_SUBTITLE_BYTES) throw IOException("Invalid subtitle size")

    val suffix = uri.path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
    val extension = when (suffix) {
        "srt", "vtt", "ass", "ssa", "sub", "ttml" -> suffix
        else -> {
            val header = bytes.decodeToString(0, minOf(256, bytes.size)).trimStart('\uFEFF', ' ', '\t', '\r', '\n')
            when {
                header.startsWith("WEBVTT") -> "vtt"
                header.startsWith("[Script Info]", ignoreCase = true) -> "ass"
                else -> "srt"
            }
        }
    }
    Files.createDirectories(directory.toPath())
    val file = directory.resolve("cloudstream-subtitle-${UUID.randomUUID()}.$extension")
    Files.createFile(file.toPath())
    try {
        file.writeBytes(bytes)
    } catch (error: IOException) {
        Files.deleteIfExists(file.toPath())
        throw error
    }
    return file
}
