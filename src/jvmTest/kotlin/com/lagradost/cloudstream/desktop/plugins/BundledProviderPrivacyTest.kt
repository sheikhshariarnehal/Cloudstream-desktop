package com.lagradost.cloudstream.desktop.plugins

import com.lagradost.cloudstream3.APIHolder
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class BundledProviderPrivacyTest {
    @Test
    fun registeredProviderTrackersCannotSendAfterDisablingTelemetry() = runBlocking {
        val profile = createTempDirectory("cloudstream-privacy-test-").toFile()
        val previousHome = System.getProperty("user.home")
        val previousProviders = APIHolder.allProviders.toList()
        val received = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                exchange.requestBody.use { it.readBytes() }
                received.incrementAndGet()
                exchange.sendResponseHeaders(204, -1)
                exchange.close()
            }
            start()
        }
        val savedEndpoints = mutableListOf<Pair<Any, String>>()
        try {
            System.setProperty("user.home", profile.absolutePath)
            profile.resolve(".cloudstream_desktop").mkdirs()
            DesktopPluginLoader.registerNehalProviders()
            val trackers = DesktopPluginLoader.registeredApis.value.mapNotNull { api ->
                runCatching { Class.forName("${api.javaClass.packageName}.AnalyticsTracker") }.getOrNull()
            }.distinct().map { it.getField("INSTANCE").get(null) }
            assertEquals(14, trackers.size)
            for (tracker in trackers) {
                val type = tracker.javaClass
                val endpoint = type.getMethod("getENDPOINT").invoke(tracker) as String
                assertEquals("", endpoint, "Provider registration must disable its tracker")
                savedEndpoints += tracker to endpoint
                type.getMethod("setENDPOINT", String::class.java).invoke(tracker, "http://127.0.0.1:${server.address.port}/")
            }
            suspend fun send(tracker: Any) {
                val method = tracker.javaClass.getMethod("track", String::class.java, String::class.java, Map::class.java)
                val job = method.invoke(tracker, "Fixture", "search", mapOf("query" to "synthetic fixture")) as Job
                withTimeout(5_000) { job.join() }
            }
            send(trackers.first())
            assertEquals(1, received.get(), "Control event must reach the loopback server")
            received.set(0)
            disableBundledProviderTelemetry()
            for (tracker in trackers) send(tracker)
            assertEquals(0, received.get(), "Bundled providers must not transmit usage events")
        } finally {
            for ((tracker, endpoint) in savedEndpoints) {
                tracker.javaClass.getMethod("setENDPOINT", String::class.java).invoke(tracker, endpoint)
            }
            server.stop(0)
            APIHolder.allProviders.clear()
            APIHolder.allProviders.addAll(previousProviders)
            DesktopPluginLoader.syncApisFromHolder()
            System.setProperty("user.home", previousHome)
            profile.deleteRecursively()
        }
    }
}
