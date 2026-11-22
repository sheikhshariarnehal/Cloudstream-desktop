package com.lagradost.cloudstream.desktop.ui.screens.player

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.lagradost.cloudstream.desktop.ui.navigation.StreamSourceInfo
import com.lagradost.cloudstream.desktop.ui.theme.CloudStreamDesktopTheme
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

fun main(args: Array<String>) {
    require(args.size == 1 && System.getProperty("user.home").contains("buffering")) {
        "Pass the generated MP4 and use an isolated buffering test profile."
    }
    val media = File(args.single()).readBytes()
    val firstRequest = AtomicBoolean(true)
    val reopened = AtomicBoolean(false)
    val origin = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = Executors.newCachedThreadPool { runnable -> Thread(runnable).apply { isDaemon = true } }
        createContext("/") { exchange ->
            try {
                if (exchange.requestHeaders.getFirst("X-Playback-Test") != "expected") {
                    exchange.sendResponseHeaders(403, -1)
                    return@createContext
                }
                val primary = exchange.requestURI.path == "/primary.mp4"
                val range = exchange.requestHeaders.getFirst("Range")
                val start = range?.substringAfter("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
                if (start !in media.indices) {
                    exchange.sendResponseHeaders(416, -1)
                    return@createContext
                }
                exchange.responseHeaders.set("Content-Type", "video/mp4")
                exchange.responseHeaders.set("Accept-Ranges", "bytes")
                when {
                    primary && firstRequest.compareAndSet(true, false) -> {
                        println("FIXTURE truncated primary response")
                        exchange.sendResponseHeaders(200, media.size.toLong())
                        exchange.responseBody.write(media, 0, media.size * 36 / 100)
                    }
                    primary && !reopened.get() && start > 0 -> {
                        println("FIXTURE rejected native range retry")
                        exchange.sendResponseHeaders(503, -1)
                    }
                    else -> {
                        if (primary && reopened.compareAndSet(false, true)) println("FIXTURE healthy primary reopened")
                        val length = media.size - start
                        if (range != null) {
                            exchange.responseHeaders.set("Content-Range", "bytes $start-${media.lastIndex}/${media.size}")
                        }
                        exchange.sendResponseHeaders(if (range == null) 200 else 206, length.toLong())
                        exchange.responseBody.write(media, start, length)
                    }
                }
            } finally {
                exchange.close()
            }
        }
        start()
    }
    val base = "http://127.0.0.1:${origin.address.port}"
    val headers = mapOf("X-Playback-Test" to "expected")
    val sources = listOf(
        StreamSourceInfo("Controlled primary", "$base/primary.mp4", 360, headers),
        StreamSourceInfo("Controlled backup", "$base/backup.mp4", 360, headers)
    )
    application {
        val state = rememberWindowState(width = 1040.dp, height = 680.dp)
        val close = { origin.stop(0); exitApplication() }
        Window(onCloseRequest = close, state = state, title = "CloudStream Buffering Smoke Test") {
            CloudStreamDesktopTheme {
                PlayerScreen(
                    mediaUrl = sources.first().url,
                    title = "Controlled A/V recovery fixture",
                    headers = headers,
                    sources = sources,
                    onBack = close
                )
            }
        }
    }
}
