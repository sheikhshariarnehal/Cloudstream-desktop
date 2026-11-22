package com.lagradost.cloudstream.desktop.utils

import com.lagradost.cloudstream3.app
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object DesktopStreamProxy {
    private const val DASH_PATH_PREFIX = "_dash/"
    private var server: HttpServer? = null
    private var port: Int = 18999
    private val DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    private val client: OkHttpClient by lazy {
        app.baseClient.newBuilder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private class ProxySession(
        val targetUrl: String,
        val headers: Map<String, String>
    ) {
        val rootUrl = targetUrl.toHttpUrlOrNull()
        @Volatile var baseUrl = rootUrl
        val calls = ConcurrentHashMap.newKeySet<Call>()
    }

    private val sessions = ConcurrentHashMap<String, ProxySession>()

    fun startIfNeeded(): Int {
        if (server != null) return port
        synchronized(this) {
            if (server != null) return port
            try {
                val socket = InetSocketAddress("127.0.0.1", 0)
                val srv = HttpServer.create(socket, 0)
                port = srv.address.port
                srv.executor = Executors.newCachedThreadPool()
                srv.createContext("/", ProxyHandler())
                srv.start()
                server = srv
                println("DesktopStreamProxy running on http://127.0.0.1:$port")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return port
    }

    fun getProxiedUrl(targetUrl: String, headers: Map<String, String>): String {
        val currentPort = startIfNeeded()

        val sessionId = java.util.UUID.randomUUID().toString().take(8)
        sessions[sessionId] = ProxySession(targetUrl, headers)

        val fileName = targetUrl.substringBefore("?").substringAfterLast("/").ifEmpty { "stream.m3u8" }
        val encodedUrl = URLEncoder.encode(targetUrl, "UTF-8")

        return "http://127.0.0.1:$currentPort/proxy/$sessionId/$fileName?url=$encodedUrl"
    }

    fun releaseProxiedUrl(proxiedUrl: String) {
        val url = proxiedUrl.toHttpUrlOrNull() ?: return
        if (url.host != "127.0.0.1" || url.port != port || url.pathSegments.firstOrNull() != "proxy") return
        val sessionId = url.pathSegments.getOrNull(1) ?: return
        sessions.remove(sessionId)?.calls?.forEach(Call::cancel)
    }

    private class ProxyHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            try {
                val path = exchange.requestURI.rawPath.removePrefix("/")
                val pathParts = path.split("/")

                // Expecting path like: /proxy/{sessionId}/{fileName} or relative paths
                if (pathParts.size < 2 || pathParts[0] != "proxy") {
                    exchange.sendResponseHeaders(404, -1)
                    return
                }

                val sessionId = pathParts[1]
                val session = sessions[sessionId]
                if (session == null) {
                    exchange.sendResponseHeaders(404, -1)
                    return
                }

                val relativePath = pathParts.drop(2).joinToString("/")
                val targetUrlStr = resolveTarget(relativePath, exchange.requestURI.rawQuery, session)
                if (targetUrlStr.isNullOrBlank()) {
                    exchange.sendResponseHeaders(400, -1)
                    return
                }

                val reqBuilder = Request.Builder().url(targetUrlStr)

                // Inject session headers into target OkHttp request
                session.headers.forEach { (k, v) ->
                    reqBuilder.header(k, v)
                }

                // Inject default User-Agent if none provided (prevents Cloudflare/DDoS-Guard blocks)
                val hasUA = session.headers.keys.any { it.equals("user-agent", ignoreCase = true) }
                if (!hasUA) {
                    reqBuilder.header("User-Agent", DEFAULT_USER_AGENT)
                }

                // Pass Range header from VLC if requested
                val rangeHeader = exchange.requestHeaders.getFirst("Range")
                if (!rangeHeader.isNullOrBlank()) {
                    reqBuilder.header("Range", rangeHeader)
                }

                val call = client.newCall(reqBuilder.build())
                session.calls.add(call)
                try {
                    // A release between lookup and registration must not start another upstream request.
                    if (sessions[sessionId] !== session) {
                        exchange.sendResponseHeaders(404, -1)
                        return
                    }
                    forwardResponse(exchange, call, sessionId, session)
                } finally {
                    session.calls.remove(call)
                }
            } catch (_: Exception) {
                if (exchange.responseCode == -1) {
                    runCatching { exchange.sendResponseHeaders(502, -1) }
                }
            } finally {
                // Closing the exchange first aborts the socket if a fixed-length body was truncated.
                exchange.close()
            }
        }

        private fun resolveTarget(relativePath: String, rawQuery: String?, session: ProxySession): String? {
            if (relativePath.startsWith(DASH_PATH_PREFIX)) {
                return DashManifest.resolve(relativePath.removePrefix(DASH_PATH_PREFIX), rawQuery)?.toString()
            }
            val encodedTarget = rawQuery?.split('&')?.firstOrNull { it.startsWith("url=") }?.removePrefix("url=")
            val target = encodedTarget?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
            if (!target.isNullOrBlank()) return target
            if (relativePath.isBlank()) return session.targetUrl
            val reference = if (rawQuery == null) relativePath else "$relativePath?$rawQuery"
            return session.baseUrl?.resolve(reference)?.toString()
        }

        private fun forwardResponse(exchange: HttpExchange, call: Call, sessionId: String, session: ProxySession) {
            val requestUrl = call.request().url
            call.execute().use { response ->
                val responseUrl = response.request.url
                val responseBody = response.body
                val contentType = response.header("Content-Type")
                val isMpd = response.isSuccessful && (
                    requestUrl.encodedPath.endsWith(".mpd", ignoreCase = true) ||
                        responseUrl.encodedPath.endsWith(".mpd", ignoreCase = true) ||
                        contentType?.contains("dash+xml", ignoreCase = true) == true
                    )
                if (isMpd && requestUrl == session.rootUrl) session.baseUrl = responseUrl

                val isM3u8 = !isMpd && response.isSuccessful && (
                    requestUrl.encodedPath.endsWith(".m3u8", ignoreCase = true) ||
                        responseUrl.encodedPath.endsWith(".m3u8", ignoreCase = true) ||
                        contentType?.contains("mpegurl", ignoreCase = true) == true
                    )
                val playlistBytes = if (isM3u8) responseBody.bytes() else null
                val playlist = playlistBytes?.toString(Charsets.UTF_8)?.removePrefix("\uFEFF")
                val rewrittenHls = playlist?.takeIf { it.contains("#EXTM3U") }
                    ?.let { rewriteHls(it, responseUrl, sessionId) }
                val bufferedBody = if (isMpd && response.code == 200) {
                    val proxyBase = "http://127.0.0.1:$port/proxy/$sessionId/$DASH_PATH_PREFIX"
                    DashManifest.rewrite(responseBody.bytes(), responseUrl, proxyBase)
                } else {
                    rewrittenHls?.toByteArray(Charsets.UTF_8) ?: playlistBytes
                }
                if (rewrittenHls != null) {
                    exchange.responseHeaders.set("Content-Type", "application/vnd.apple.mpegurl")
                } else {
                    response.headers.forEach { (key, value) ->
                        if (!key.equals("Transfer-Encoding", ignoreCase = true) &&
                            !key.equals("Content-Length", ignoreCase = true)) {
                            exchange.responseHeaders.add(key, value)
                        }
                    }
                    if (isMpd) exchange.responseHeaders.set("Content-Type", "application/dash+xml")
                }
                exchange.responseHeaders.set("Access-Control-Allow-Origin", "*")
                val contentLength = bufferedBody?.size?.toLong() ?: responseBody.contentLength()
                exchange.sendResponseHeaders(response.code, contentLength.coerceAtLeast(0L))
                if (bufferedBody == null) {
                    responseBody.byteStream().copyTo(exchange.responseBody, 32768)
                } else {
                    exchange.responseBody.write(bufferedBody)
                }
            }
        }

        private fun rewriteHls(playlist: String, responseUrl: HttpUrl, sessionId: String): String {
            fun proxyUrl(rawUrl: String, fallbackName: String): String {
                val resolved = responseUrl.resolve(rawUrl) ?: return rawUrl
                val fileName = resolved.encodedPath.substringAfterLast('/').ifEmpty { fallbackName }
                val encodedUrl = URLEncoder.encode(resolved.toString(), "UTF-8")
                return "http://127.0.0.1:$port/proxy/$sessionId/$fileName?url=$encodedUrl"
            }

            val uriAttrRegex = Regex("""URI="([^"]+)"""")
            return playlist.lineSequence().joinToString("\n") { line ->
                val trimmed = line.trim()
                when {
                    trimmed.startsWith("#") -> uriAttrRegex.replace(line) { match ->
                        """URI="${proxyUrl(match.groupValues[1], "key")}""""
                    }
                    trimmed.isNotEmpty() -> proxyUrl(trimmed, "segment.ts")
                    else -> line
                }
            }
        }
    }
}
