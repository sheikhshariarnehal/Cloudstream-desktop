package com.lagradost.cloudstream.desktop.utils

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopStreamProxyTest {
    private val client = OkHttpClient.Builder()
        .readTimeout(2, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    @Test
    fun redirectedManifestLoadsBothRenditionsWithSessionHeaders() {
        withOrigin { exchange ->
            when (exchange.requestURI.path) {
                "/entry/master.m3u8" -> {
                    exchange.responseHeaders.set("Location", "/cdn/master.m3u8")
                    exchange.sendResponseHeaders(302, -1)
                }
                "/cdn/master.m3u8" -> exchange.reply(
                    200,
                    """#EXTM3U
                        |#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="English",URI="audio/en.m3u8"
                        |#EXT-X-STREAM-INF:BANDWIDTH=100000,AUDIO="audio"
                        |video/main.m3u8
                    """.trimMargin()
                )
                "/cdn/audio/en.m3u8", "/cdn/video/main.m3u8" -> {
                    val rendition = exchange.requestURI.path.split('/')[2]
                    if (exchange.requestHeaders.getFirst("X-Test-Session") == "test-rendition") {
                        exchange.reply(200, "#EXTM3U\n#EXTINF:4,$rendition\nsegment.ts")
                    } else {
                        exchange.reply(403, "missing session header")
                    }
                }
                else -> exchange.reply(404, "wrong rendition path")
            }
        }.use { origin ->
            val url = origin.proxy("/entry/master.m3u8", mapOf("X-Test-Session" to "test-rendition"))
            val manifest = get(url)
            val audioUrl = manifest.lineSequence().first { it.startsWith("#EXT-X-MEDIA:") }
                .substringAfter("URI=\"").substringBefore('"')
            val videoUrl = manifest.lineSequence().first { it.startsWith("http://") }
            assertTrue(get(audioUrl).contains("#EXTINF:4,audio"))
            assertTrue(get(videoUrl).contains("#EXTINF:4,video"))
        }
    }

    @Test
    fun queryOnlyRenditionReferenceKeepsTheManifestPath() {
        withOrigin { exchange ->
            when (exchange.requestURI.toString()) {
                "/cdn/master.m3u8?old=/embedded/path" ->
                    exchange.reply(200, "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100000\n?track=video")
                "/cdn/master.m3u8?track=video" -> exchange.reply(200, "#EXTM3U\n#EXTINF:4,video\nsegment.ts")
                else -> exchange.reply(404, "wrong rendition path")
            }
        }.use { origin ->
            val manifest = get(origin.proxy("/cdn/master.m3u8?old=/embedded/path"))
            val videoUrl = manifest.lineSequence().first { it.startsWith("http://") }
            assertTrue(get(videoUrl).contains("#EXTINF:4,video"))
        }
    }

    @Test
    fun playlistErrorReturnsTheUpstreamStatusAndBody() {
        withOrigin { it.reply(403, "stream expired") }.use { origin ->
            val url = origin.proxy("/expired.m3u8")
            client.newCall(Request.Builder().url(url).build()).execute().use {
                assertEquals(403, it.code)
                assertEquals("stream expired", it.body.string())
            }
        }
    }

    @Test
    fun dashSegmentKeepsItsMediaTypeAndByteRange() {
        withOrigin { exchange ->
            if (exchange.requestHeaders.getFirst("Range") == "bytes=0-6") {
                exchange.responseHeaders.set("Content-Type", "video/iso.segment")
                exchange.responseHeaders.set("Content-Range", "bytes 0-6/16")
                exchange.reply(206, "segment")
            } else {
                exchange.reply(400, "missing byte range")
            }
        }.use { origin ->
            val request = Request.Builder().url(origin.proxy("/dash/video/chunk.m4s"))
                .header("Range", "bytes=0-6").build()
            client.newCall(request).execute().use {
                assertEquals(206, it.code)
                assertEquals("video/iso.segment", it.header("Content-Type"))
                assertEquals("bytes 0-6/16", it.header("Content-Range"))
                assertEquals("segment", it.body.string())
            }
        }
    }

    @Test
    fun relativeDashSegmentFollowsTheManifestRedirect() {
        withOrigin { exchange ->
            when (exchange.requestURI.toString()) {
                "/entry/manifest.mpd" -> {
                    exchange.responseHeaders.set("Location", "/cdn/dash/manifest.mpd?old=/embedded/path")
                    exchange.sendResponseHeaders(302, -1)
                }
                "/cdn/dash/manifest.mpd?old=/embedded/path" -> exchange.reply(
                    200, """<MPD xmlns="urn:mpeg:dash:schema:mpd:2011"><Period><AdaptationSet><SegmentTemplate media="video/${'$'}Number${'$'}.m4s?track=video"/></AdaptationSet></Period></MPD>"""
                )
                "/cdn/dash/video/1.m4s?track=video" -> {
                    if (exchange.requestHeaders.getFirst("X-Test-Session") == "test-dash") {
                        exchange.reply(200, "video")
                    } else {
                        exchange.reply(403, "missing session header")
                    }
                }
                else -> exchange.reply(404, "wrong segment path")
            }
        }.use { origin ->
            val manifestUrl = origin.proxy("/entry/manifest.mpd", mapOf("X-Test-Session" to "test-dash"))
            val manifest = get(manifestUrl)
            val manifestBase = manifest.substringAfter("<BaseURL>").substringBefore("</BaseURL>")
            val mediaTemplate = manifest.substringAfter("media=\"").substringBefore('"')
            val mediaUrl = URI.create(manifestBase).resolve(mediaTemplate.replace("\$Number\$", "1"))
            assertEquals("video", get(mediaUrl.toString()))
        }
    }

    @Test
    fun absoluteDashAudioBaseKeepsSessionHeadersAndTemplateQuery() {
        withOrigin { exchange ->
            when (exchange.requestURI.toString()) {
                "/master.mpd" -> exchange.reply(
                    200,
                    """<MPD xmlns="urn:mpeg:dash:schema:mpd:2011">
                        |<Period><AdaptationSet>
                        |<BaseURL>http://127.0.0.1:${exchange.localAddress.port}/cdn/audio/</BaseURL>
                        |<SegmentTemplate media="${'$'}RepresentationID${'$'}/${'$'}Number${'$'}.m4s?url=audio"/>
                        |<Representation id="audio" bandwidth="32000"/>
                        |</AdaptationSet></Period>
                        |</MPD>""".trimMargin()
                )
                "/cdn/audio/audio/1.m4s?url=audio" -> {
                    if (exchange.requestHeaders.getFirst("X-Test-Session") == "test-dash") {
                        exchange.reply(200, "audio")
                    } else {
                        exchange.reply(403, "missing session header")
                    }
                }
                else -> exchange.reply(404, "wrong segment path")
            }
        }.use { origin ->
            val manifestUrl = origin.proxy("/master.mpd", mapOf("X-Test-Session" to "test-dash"))
            val manifest = get(manifestUrl)
            val manifestBase = manifest.substringBefore("<Period").substringAfter("<BaseURL>", "")
                .substringBefore("</BaseURL>")
            val audio = manifest.substringAfter("<AdaptationSet")
            val audioBase = audio.substringAfter("<BaseURL>").substringBefore("</BaseURL>")
            val media = audio.substringAfter("media=\"").substringBefore('"')
                .replace("\$RepresentationID\$", "audio").replace("\$Number\$", "1")
            val mediaUrl = URI.create(manifestUrl).resolve(manifestBase).resolve(audioBase).resolve(media)
            assertEquals("audio", get(mediaUrl.toString()))
        }
    }

    @Test
    fun releasingTheSessionCancelsAnUpstreamRequestWaitingForHeaders() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { origin ->
            val upstreamReady = CountDownLatch(1)
            val upstreamDisconnected = CompletableFuture.supplyAsync {
                origin.accept().use { upstream ->
                    upstream.soTimeout = 5000
                    val input = upstream.getInputStream().bufferedReader()
                    while (input.readLine()?.isNotEmpty() == true) {
                        // Wait for the complete upstream request before releasing the session.
                    }
                    upstreamReady.countDown()
                    input.read() == -1
                }
            }
            val url = DesktopStreamProxy.getProxiedUrl("http://127.0.0.1:${origin.localPort}/track.ts", emptyMap())
            val pending = client.newBuilder().readTimeout(10, TimeUnit.SECONDS).build()
                .newCall(Request.Builder().url(url).build())
            val response = CompletableFuture.supplyAsync { pending.execute().use { it.code } }
            try {
                assertTrue(upstreamReady.await(10, TimeUnit.SECONDS), "The upstream request reached the origin")
                DesktopStreamProxy.releaseProxiedUrl(url)
                assertTrue(upstreamDisconnected.get(2, TimeUnit.SECONDS), "The origin saw the cancelled connection close")
                assertEquals(502, response.get(2, TimeUnit.SECONDS))
            } finally {
                pending.cancel()
                DesktopStreamProxy.releaseProxiedUrl(url)
            }
        }
    }

    @Test
    fun releasedSessionStopsForwardingRequests() {
        val requests = AtomicInteger()
        withOrigin {
            requests.incrementAndGet()
            it.reply(200, "video")
        }.use { origin ->
            val url = origin.proxy("/video.mp4")
            assertEquals("video", get(url))
            DesktopStreamProxy.releaseProxiedUrl(url)
            DesktopStreamProxy.releaseProxiedUrl(url)
            client.newCall(Request.Builder().url(url).build()).execute().use { assertEquals(404, it.code) }
            assertEquals(1, requests.get())
        }
    }

    @Test
    fun truncatedFixedLengthResponseClosesThePlayerConnection() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { origin ->
            thread(isDaemon = true) {
                origin.accept().use { upstream ->
                    val headers = upstream.getInputStream().bufferedReader()
                    while (headers.readLine()?.isNotEmpty() == true) {
                        // Drain the request headers before sending the truncated response.
                    }
                    upstream.getOutputStream().write(
                        "HTTP/1.1 200 OK\r\nContent-Length: 16\r\nConnection: close\r\n\r\npartial".toByteArray()
                    )
                }
            }
            val url = DesktopStreamProxy.getProxiedUrl("http://127.0.0.1:${origin.localPort}/track.ts", emptyMap())
            val failure = assertFailsWith<IOException> { get(url) }
            assertFalse(failure is SocketTimeoutException, "Playback must see the upstream failure without waiting for a timeout")
        }
    }

    private fun get(url: String): String = client.newCall(Request.Builder().url(url).build()).execute().use {
        val body = it.body.string()
        assertEquals(200, it.code, body)
        body
    }

    private fun withOrigin(handler: (HttpExchange) -> Unit): Origin {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> exchange.use(handler) }
        server.start()
        return Origin(server)
    }

    private class Origin(private val server: HttpServer) : AutoCloseable {
        fun proxy(path: String, headers: Map<String, String> = emptyMap()): String =
            DesktopStreamProxy.getProxiedUrl("http://127.0.0.1:${server.address.port}$path", headers)

        override fun close() = server.stop(0)
    }

    private fun HttpExchange.reply(status: Int, body: String) {
        val bytes = body.toByteArray()
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.write(bytes)
    }
}
