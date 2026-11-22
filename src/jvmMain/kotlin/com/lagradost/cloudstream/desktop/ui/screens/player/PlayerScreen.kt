package com.lagradost.cloudstream.desktop.ui.screens.player

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.lagradost.cloudstream.desktop.plugins.DesktopPluginLoader
import com.lagradost.cloudstream.desktop.storage.DesktopDataStore
import com.lagradost.cloudstream.desktop.storage.WatchProgress
import com.lagradost.cloudstream.desktop.utils.DesktopStreamProxy
import com.lagradost.cloudstream.desktop.ui.navigation.StreamSourceInfo
import com.lagradost.cloudstream.desktop.ui.navigation.SubtitleTrackInfo
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.media.MediaStatistics
import uk.co.caprica.vlcj.player.base.State
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.TrackDescription
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import com.sun.jna.Native
import com.sun.jna.WString
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.nio.ByteBuffer

// ─── VLC Discovery ───────────────────────────────────────────────────────────

private val VLC_WINDOWS_PATHS = listOf(
    "C:\\Program Files\\VideoLAN\\VLC",
    "C:\\Program Files (x86)\\VideoLAN\\VLC"
)
private fun findVlcPath(): String? = VLC_WINDOWS_PATHS.firstOrNull { File(it, "libvlc.dll").exists() }

private interface WinKernel32 : com.sun.jna.Library {
    fun SetEnvironmentVariableW(name: WString, value: WString): Boolean
}

private fun setVlcEnvironment(name: String, value: String) {
    // JNA uses system properties, while LibVLC reads its paths from the process environment.
    System.setProperty(name, value)
    try {
        Native.load("kernel32", WinKernel32::class.java)
            .SetEnvironmentVariableW(WString(name), WString(value))
    } catch (_: Throwable) {
        // NativeDiscovery remains available as a fallback outside Windows.
    }
}

private fun tryConfigureVlc(): String? {
    val path = findVlcPath()
    if (path != null) {
        setVlcEnvironment("jna.library.path", path)
        setVlcEnvironment("java.library.path", path)
        setVlcEnvironment("VLC_LIB_PATH", path)
        setVlcEnvironment("VLC_DATA_PATH", path)
        setVlcEnvironment("VLC_PLUGIN_PATH", File(path, "plugins").absolutePath)
        return path
    }
    return try { if (NativeDiscovery().discover()) "" else null } catch (_: Exception) { null }
}

private fun toVlcMrl(url: String): String = try {
    url.trim().replace(" ", "%20")
} catch (_: Exception) {
    url.replace(" ", "%20")
}

private fun logVlc(message: String) {
    try {
        File(System.getProperty("user.home"), "cloudstream_vlc_debug.log")
            .appendText("${java.time.LocalDateTime.now()}: $message\n")
    } catch (_: Exception) {}
}

private val VideoControlBackground = Color.Black.copy(0.55f)

enum class VideoAspectMode(val label: String, val scale: ContentScale) {
    CROP("Zoom / Fill Screen", ContentScale.Crop),
    STRETCH("Stretch to Fill", ContentScale.FillBounds),
    FIT("Original / Fit", ContentScale.Fit)
}

// ─── PlayerScreen ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun PlayerScreen(
    mediaUrl: String,
    title: String,
    episodeName: String? = null,
    posterUrl: String? = null,
    headers: Map<String, String> = emptyMap(),
    subtitles: List<SubtitleTrackInfo> = emptyList(),
    sources: List<StreamSourceInfo> = emptyList(),
    loadResponse: LoadResponse? = null,
    currentEpisode: Episode? = null,
    apiName: String = "",
    isFullscreen: Boolean = false,
    onToggleFullscreen: () -> Unit = {},
    onWindowShortcutsBlockedChange: (Boolean) -> Unit = {},
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    var isCopied by remember { mutableStateOf(false) }

    // ── TV Series Episodes State ──────────────────────────────────────────────
    val allEpisodes: List<Episode> = remember(loadResponse) {
        when (loadResponse) {
            is TvSeriesLoadResponse -> loadResponse.episodes
            is AnimeLoadResponse -> loadResponse.episodes.values.flatten()
            else -> emptyList()
        }
    }
    var currentEp by remember { mutableStateOf(currentEpisode) }
    fun isAudioOnlySource(src: StreamSourceInfo): Boolean {
        val uLower = src.url.lowercase()
        val nLower = src.name.lowercase()
        if (nLower.contains("audio track") || nLower.contains("audio-only")) return true
        if (uLower.endsWith(".m4a") || uLower.endsWith(".aac") || uLower.endsWith(".mp3") || uLower.endsWith(".mka") || uLower.endsWith(".wav") || uLower.endsWith(".flac")) return true
        if (uLower.contains(".audio.") || uLower.contains("/audio.")) return true
        return false
    }

    var activeEpisodeName by remember {
        mutableStateOf(episodeName ?: currentEpisode?.let { "S${it.season}E${it.episode}" })
    }
    val filteredSources = remember(sources) {
        sources.filterNot { isAudioOnlySource(it) }.ifEmpty { sources }
    }
    var activeSourcesList by remember { mutableStateOf(filteredSources) }

    LaunchedEffect(filteredSources) {
        if (filteredSources.isNotEmpty()) {
            activeSourcesList = filteredSources
        }
    }
    var activeSubtitlesList by remember { mutableStateOf(subtitles) }
    var showEpisodesPicker by remember { mutableStateOf(false) }

    val currentEpIndex = remember(currentEp, activeEpisodeName, allEpisodes) {
        if (allEpisodes.isEmpty()) -1
        else {
            val byData = allEpisodes.indexOfFirst { ep -> currentEp != null && (ep == currentEp || ep.data == currentEp?.data) }
            if (byData != -1) byData
            else if (currentEp?.season != null && currentEp?.episode != null) {
                val bySeasonEp = allEpisodes.indexOfFirst { ep -> ep.season == currentEp?.season && ep.episode == currentEp?.episode }
                if (bySeasonEp != -1) bySeasonEp
                else allEpisodes.indexOfFirst { ep -> ep.episode == currentEp?.episode }
            } else if (!activeEpisodeName.isNullOrBlank()) {
                val match = Regex("""S(\d+)E(\d+)""", RegexOption.IGNORE_CASE).find(activeEpisodeName!!)
                val sNum = match?.groupValues?.get(1)?.toIntOrNull()
                val eNum = match?.groupValues?.get(2)?.toIntOrNull()
                if (sNum != null && eNum != null) {
                    val byParsed = allEpisodes.indexOfFirst { ep -> ep.season == sNum && ep.episode == eNum }
                    if (byParsed != -1) byParsed
                    else allEpisodes.indexOfFirst { ep -> ep.episode == eNum }
                } else {
                    allEpisodes.indexOfFirst { ep -> ep.name == activeEpisodeName }
                }
            } else -1
        }
    }
    val hasPrevEpisode = allEpisodes.isNotEmpty() && currentEpIndex > 0
    val hasNextEpisode = allEpisodes.isNotEmpty() && currentEpIndex != -1 && currentEpIndex < (allEpisodes.size - 1)

    // ── Active Stream Source ──────────────────────────────────────────────────
    val initialSource = remember {
        activeSourcesList.firstOrNull { it.url == mediaUrl && it.headers == headers }
            ?: StreamSourceInfo(name = "Primary Stream", url = mediaUrl, quality = 0, headers = headers)
    }

    var videoAspectMode by remember { mutableStateOf(VideoAspectMode.CROP) }
    var isPlaying by remember { mutableStateOf(true) }
    var isMuted by remember { mutableStateOf(false) }
    var volume by remember { mutableStateOf(100) }          // 0..200
    var playbackSpeed by remember { mutableStateOf(1.0f) }
    val initialPosition = remember {
        val targetUrl = loadResponse?.url ?: mediaUrl
        val cleanTitle = title.trim().lowercase()
        val epObj = currentEpisode ?: (if (currentEpIndex != -1) allEpisodes.getOrNull(currentEpIndex) else null)
        val targetSeason = epObj?.season
        val targetEpisode = epObj?.episode

        val historyMatch = DesktopDataStore.currentSettings.watchHistory.firstOrNull { h ->
            val hUrl = h.url.trim().lowercase()
            val hTitle = h.title.trim().lowercase()
            val tUrl = targetUrl.trim().lowercase()
            val mUrl = mediaUrl.trim().lowercase()
            val sUrl = initialSource.url.trim().lowercase()

            val urlOrTitleMatch = (hUrl == tUrl || hUrl == mUrl || hUrl == sUrl ||
                    hTitle == cleanTitle ||
                    (cleanTitle.length > 5 && hTitle.startsWith(cleanTitle)))

            if (targetSeason != null && targetEpisode != null) {
                urlOrTitleMatch && h.seasonIndex == targetSeason && h.episodeIndex == targetEpisode
            } else if (!episodeName.isNullOrBlank()) {
                urlOrTitleMatch && (h.title.contains(episodeName, ignoreCase = true) || hTitle.contains(episodeName.lowercase()))
            } else {
                urlOrTitleMatch
            }
        }
        if (historyMatch != null && historyMatch.positionMillis > 3000L) {
            if (historyMatch.durationMillis <= 0 || (historyMatch.durationMillis - historyMatch.positionMillis) > 10000L) {
                historyMatch.positionMillis
            } else 0L
        } else 0L
    }
    val session = remember { PlaybackSession(initialSource, initialPosition) }
    val playbackRequest = session.request
    val activeSource = playbackRequest.source
    val isExtractingNextEp = session.loadingEpisode
    var desiredPaused by remember { mutableStateOf(false) }
    var resumeSettled by remember { mutableStateOf(false) }
    var pendingResumeGeneration by remember { mutableStateOf<Int?>(null) }
    var completedGeneration by remember { mutableStateOf<Int?>(null) }
    var lastConfirmedProgress by remember { mutableStateOf<WatchProgress?>(null) }
    val disposed = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val frameGeneration = remember { java.util.concurrent.atomic.AtomicInteger(-1) }
    val presentedFrames = remember { java.util.concurrent.atomic.AtomicLong(0) }
    val frameErrorGeneration = remember { java.util.concurrent.atomic.AtomicInteger(-1) }
    val activeProxyUrl = remember { java.util.concurrent.atomic.AtomicReference<String?>(null) }
    val stallDetector = remember(playbackRequest.generation) { PlaybackStallDetector() }
    var lastPositionFraction by remember { mutableFloatStateOf(Float.NaN) }
    var lastNativePosition by remember { mutableStateOf(-1L) }
    var initialProgressObserved by remember { mutableStateOf(false) }
    var interruptedGeneration by remember { mutableStateOf<Int?>(null) }
    var mediaWasSeekable by remember { mutableStateOf(false) }
    var bufferPercent by remember { mutableFloatStateOf(0f) }
    var currentPosition by remember { mutableStateOf(initialPosition) }
    var totalDuration by remember { mutableStateOf(0L) }
    var videoFrame by remember { mutableStateOf<ImageBitmap?>(null) }
    var isVideoReady by remember { mutableStateOf(false) }
    var vlcError by remember { mutableStateOf<String?>(null) }
    var playbackNotice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(playbackNotice) {
        if (playbackNotice != null) { delay(6000); playbackNotice = null }
    }
    var isLocked by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }
    var controlsInteraction by remember { mutableIntStateOf(0) }
    var isBuffering by remember { mutableStateOf(false) }

    // ── Dialog states ─────────────────────────────────────────────────────────
    var showSourcesPicker by remember { mutableStateOf(false) }
    var showAudioTrackPicker by remember { mutableStateOf(false) }
    var showSubtitlePicker by remember { mutableStateOf(false) }
    var showSpeedPicker by remember { mutableStateOf(false) }
    var showVolumePicker by remember { mutableStateOf(false) }
    var desiredSubtitle by remember { mutableStateOf<SubtitleTrackInfo?>(activeSubtitlesList.firstOrNull()) }
    var selectedSubtitle by remember { mutableStateOf<SubtitleTrackInfo?>(null) }
    var subtitleRequestNumber by remember { mutableIntStateOf(0) }
    var subtitleStatus by remember { mutableStateOf<String?>(null) }
    var subtitleReloadRequired by remember { mutableStateOf(false) }
    val externalSubtitleIds = remember { mutableMapOf<String, Int>() }
    val subtitleDirectory = remember { java.nio.file.Files.createTempDirectory("cloudstream-subtitles-").toFile() }
    val externalSubtitleFiles = remember { mutableMapOf<String, File>() }
    var isSeekable by remember { mutableStateOf(false) }
    var seekIndicator by remember { mutableStateOf<String?>(null) }

    // ── Native Decoded Tracks ──────────────────────────────────────────────────
    var audioTracks by remember { mutableStateOf<List<TrackDescription>>(emptyList()) }
    var nativeSubTracks by remember { mutableStateOf<List<TrackDescription>>(emptyList()) }
    var selectedAudioTrackId by remember { mutableStateOf<Int?>(null) }
    var selectedSubTrackId by remember { mutableStateOf<Int?>(null) }

    // ── Pooled Raw Frames and Latest-Frame Handoff ─────────────────
    class RawFrameData(val pixels: IntArray, val width: Int, val height: Int, val generation: Int)
    val arrayPool = remember { java.util.concurrent.ConcurrentLinkedQueue<IntArray>() }

    fun obtainPixelArray(size: Int): IntArray {
        val existing = arrayPool.poll()
        return if (existing != null && existing.size >= size) existing else IntArray(size)
    }

    fun recyclePixelArray(arr: IntArray) {
        if (arrayPool.size < 8) arrayPool.offer(arr)
    }

    val pendingRawFrame = remember { java.util.concurrent.atomic.AtomicReference<RawFrameData?>(null) }
    // Convert only the latest frame, outside the UI thread.
    LaunchedEffect(Unit) {
        while (isActive) {
            val raw = pendingRawFrame.getAndSet(null)
            if (raw != null) {
                try {
                    if (raw.generation != session.request.generation || session.loadingEpisode) continue
                    val converted = withContext(Dispatchers.Default) {
                        vlcFrameBitmap(raw.pixels, raw.width, raw.height)
                    }
                    if (!disposed.get() && raw.generation == session.request.generation && !session.loadingEpisode) {
                        videoFrame = converted
                        presentedFrames.incrementAndGet()
                    } else converted.asSkiaBitmap().close()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (frameErrorGeneration.getAndSet(raw.generation) != raw.generation) {
                        logVlc("Video frame conversion failed generation=${raw.generation} type=${e.javaClass.simpleName}")
                    }
                } finally {
                    recyclePixelArray(raw.pixels)
                }
            }
            delay(16)
        }
    }

    // ── VLC Setup (Created ONCE for lifetime of PlayerScreen) ───────────────────
    val factoryAndPlayer = remember {
        val vlcPath = tryConfigureVlc() ?: run {
            vlcError = "VLC not found. Install from videolan.org"
            return@remember null
        }
        try {
            val args = buildList {
                add("--quiet")
                add("--no-video-title-show")
                add("--stats")
                add("--avcodec-threads=0")       // Auto multi-threaded CPU decoding
                add("--network-caching=2500")    // Optimized 2.5s network buffer for fast stream start
                add("--file-caching=2500")       // 2.5s file buffer
                add("--live-caching=2500")
                add("--http-reconnect")
            }
            val factory = MediaPlayerFactory(*args.toTypedArray())
            val player = factory.mediaPlayers().newEmbeddedMediaPlayer()

            val bufferFmt = object : BufferFormatCallback {
                override fun getBufferFormat(width: Int, height: Int): BufferFormat {
                    // Cap max pixel buffer to 1920x1080 for 4K streams (prevents memory copy bottleneck)
                    val targetW = if (width > 1920) 1920 else width
                    val targetH = if (width > 1920) (height * 1920) / width else height
                    return RV32BufferFormat(targetW, targetH)
                }
                override fun allocatedBuffers(buffers: Array<ByteBuffer>) {}
            }

            val render = object : RenderCallback {
                override fun display(
                    mp: MediaPlayer,
                    nativeBuffers: Array<out ByteBuffer>,
                    fmt: uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
                ) {
                    try {
                        val generation = frameGeneration.get()
                        if (generation < 0 || disposed.get()) return
                        val fw = fmt.width; val fh = fmt.height
                        if (fw <= 0 || fh <= 0) return
                        val buffer = nativeBuffers.firstOrNull() ?: return
                        buffer.rewind()
                        val ints = buffer.asIntBuffer()
                        val count = (fw * fh).coerceAtMost(ints.remaining())
                        if (count == fw * fh && count > 0) {
                            val pixels = obtainPixelArray(count)
                            ints.get(pixels, 0, count)
                            val oldFrame = pendingRawFrame.getAndSet(RawFrameData(pixels, fw, fh, generation))
                            if (oldFrame != null) {
                                recyclePixelArray(oldFrame.pixels)
                            }
                        }
                    } catch (_: Exception) {}
                }
            }

            val surface = factory.videoSurfaces().newVideoSurface(bufferFmt, render, true)
            player.videoSurface().set(surface)

            Pair(factory, player)
        } catch (e: Exception) {
            vlcError = e.message ?: "Failed to initialize VLC"
            null
        }
    }

    val player = factoryAndPlayer?.second

    fun recoverPlayback(reason: String) {
        if (disposed.get() || session.loadingEpisode || desiredPaused || vlcError != null) return
        val resume = when {
            !resumeSettled && session.request.resumePositionMillis > 0L -> session.request.resumePositionMillis
            mediaWasSeekable -> lastNativePosition.coerceAtLeast(0L)
            else -> 0L
        }
        logVlc("Playback recovery generation=${session.request.generation} reason=$reason position=$resume")
        when (session.recoverPlayback(activeSourcesList, resume, desiredPaused)) {
            PlaybackRecovery.RELOADED -> playbackNotice = "Playback stalled. Reloading this source..."
            PlaybackRecovery.SWITCHED_SOURCE -> playbackNotice = "Playback stalled again. Trying the next source..."
            PlaybackRecovery.EXHAUSTED -> {
                vlcError = "Playback stopped responding. Select a source to retry."
                isBuffering = false
                isPlaying = false
                showControls = true
                activeProxyUrl.getAndSet(null)?.let(DesktopStreamProxy::releaseProxiedUrl)
                try { player?.controls()?.stop() } catch (_: Exception) {}
            }
            PlaybackRecovery.IGNORED -> Unit
        }
    }

    // ── TV Series Episode Switching Function ─────────────────────────────────
    fun playEpisode(ep: Episode) {
        val targetApiName = apiName.ifBlank { loadResponse?.apiName ?: "" }
        val api = DesktopPluginLoader.getApiByName(targetApiName) ?: return

        if (!session.beginEpisode()) return
        val previousError = vlcError
        lastConfirmedProgress?.let(DesktopDataStore::saveWatchProgress)
        try { player?.controls()?.setPause(true) } catch (_: Exception) {}
        coroutineScope.launch {
            vlcError = null
            isBuffering = true
            var switched = false
            try {
                val links = mutableListOf<ExtractorLink>()
                val subs = mutableListOf<SubtitleTrackInfo>()
                withContext(Dispatchers.IO) {
                    api.loadLinks(
                        data = ep.data,
                        isCasting = false,
                        subtitleCallback = { sub: com.lagradost.cloudstream3.SubtitleFile ->
                            subs.add(SubtitleTrackInfo(url = sub.url, language = sub.lang))
                        },
                        callback = { link: ExtractorLink -> links.add(link) }
                    )
                }
                fun rankLink(link: ExtractorLink): Int {
                    var score = link.quality * 100
                    val urlLower = link.url.lowercase().trimEnd('/')
                    val nameLower = link.name.lowercase()
                    if (urlLower.endsWith(".mkv") || urlLower.endsWith(".mp4") || urlLower.endsWith(".m3u8")) score += 10000
                    if (nameLower.contains("gb") || nameLower.contains("mb")) score += 5000
                    return score
                }
                val sortedLinks = links.sortedByDescending { rankLink(it) }
                if (sortedLinks.isNotEmpty()) {
                    currentEp = ep
                    val epCode = "S${ep.season ?: 1}E${ep.episode ?: 1}"
                    val epNameStr = ep.name
                    activeEpisodeName = if (!epNameStr.isNullOrBlank() && !epNameStr.contains(epCode, ignoreCase = true)) {
                        "$epCode — $epNameStr"
                    } else if (!epNameStr.isNullOrBlank()) {
                        epNameStr
                    } else {
                        epCode
                    }
                    val newSources = sortedLinks.map { StreamSourceInfo(name = it.name, url = it.url, quality = it.quality, headers = it.getAllHeaders()) }
                    activeSourcesList = newSources
                    activeSubtitlesList = subs
                    desiredSubtitle = subs.firstOrNull()
                    session.startEpisode(newSources.first())
                    switched = true
                } else {
                    playbackNotice = "No playable links found for ${ep.name ?: "Episode ${ep.episode}"}"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                playbackNotice = "Episode load failed: ${e.localizedMessage}"
            } finally {
                session.finishEpisodeLoad()
                isBuffering = false
                if (!switched) {
                    vlcError = previousError
                    if (!disposed.get() && previousError == null) {
                        try { player?.controls()?.setPause(desiredPaused) } catch (_: Exception) {}
                    }
                }
            }
        }
    }


    // ── Smooth Server / Source Switching with Seamless Position Resume ──────────
    LaunchedEffect(player, playbackRequest.generation) {
        if (player == null) return@LaunchedEffect
        val request = playbackRequest
        fun ownsRequest() = !disposed.get() && session.request.generation == request.generation
        fun isCurrent() = ownsRequest() && !session.loadingEpisode
        fun post(action: () -> Unit) {
            javax.swing.SwingUtilities.invokeLater { if (isCurrent() && vlcError == null) action() }
        }
        var failed = false
        fun failSource(message: String) {
            if (!isCurrent() || failed) return
            failed = true
            if (!session.failover(activeSourcesList, if (resumeSettled) currentPosition else request.resumePositionMillis, desiredPaused)) {
                vlcError = message
                isBuffering = false
                isPlaying = false
                activeProxyUrl.getAndSet(null)?.let(DesktopStreamProxy::releaseProxiedUrl)
                try { player.controls().stop() } catch (_: Exception) {}
            }
        }

        if (!ownsRequest()) return@LaunchedEffect
        activeProxyUrl.getAndSet(null)?.let(DesktopStreamProxy::releaseProxiedUrl)
        frameGeneration.set(-1)
        player.controls().stop()
        pendingRawFrame.getAndSet(null)?.let { recyclePixelArray(it.pixels) }
        vlcError = null
        videoFrame = null
        isVideoReady = false
        isSeekable = false
        isBuffering = true
        isPlaying = false
        desiredPaused = request.paused
        currentPosition = request.resumePositionMillis
        totalDuration = 0L
        lastPositionFraction = Float.NaN
        lastNativePosition = -1L
        initialProgressObserved = false
        interruptedGeneration = null
        mediaWasSeekable = false
        bufferPercent = 0f
        presentedFrames.set(0)
        stallDetector.reset()
        resumeSettled = false
        pendingResumeGeneration = request.generation
        audioTracks = emptyList()
        nativeSubTracks = emptyList()
        selectedAudioTrackId = null
        selectedSubTrackId = null
        selectedSubtitle = null
        subtitleStatus = null
        subtitleReloadRequired = false
        externalSubtitleIds.clear()

        val listener = object : uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter() {
            override fun error(mp: MediaPlayer) = post { failSource("VLC playback error - all servers failed") }
            override fun buffering(mp: MediaPlayer, newCache: Float) = post {
                bufferPercent = newCache
                isBuffering = newCache < 100f && !desiredPaused
            }
            override fun playing(mp: MediaPlayer) = post {
                frameGeneration.set(request.generation)
                isVideoReady = true
                isBuffering = false
                isPlaying = true
                if (desiredPaused) mp.controls().setPause(true)
            }
            override fun paused(mp: MediaPlayer) = post { isPlaying = false; isBuffering = false }
            override fun stopped(mp: MediaPlayer) = post { isPlaying = false }
            override fun finished(mp: MediaPlayer) = post {
                if (!initialProgressObserved && presentedFrames.get() == 0L) {
                    val finalStatistics = MediaStatistics()
                    val hadOutput = runCatching {
                        mp.media().isValid && mp.media().info().statistics(finalStatistics) &&
                            (finalStatistics.picturesDisplayed() > 0 || finalStatistics.audioBuffersPlayed() > 0)
                    }.getOrDefault(false)
                    if (!hadOutput) {
                        interruptedGeneration = request.generation
                        isPlaying = false
                        isBuffering = false
                        if (desiredPaused) playbackNotice = "Stream interrupted. Press Play to reconnect."
                        else failSource("Stream ended before playback started")
                        return@post
                    }
                }
                if (isPrematureCompletion(lastNativePosition, totalDuration, lastPositionFraction, mediaWasSeekable)) {
                    interruptedGeneration = request.generation
                    isPlaying = false
                    isBuffering = false
                    if (desiredPaused) playbackNotice = "Stream interrupted. Press Play to reconnect."
                    else recoverPlayback("premature completion")
                    return@post
                }
                desiredPaused = true
                isPlaying = false
                isBuffering = false
                completedGeneration = request.generation
            }
        }
        player.events().addMediaPlayerEventListener(listener)
        var proxiedUrl: String? = null
        try {
            val rawUrl = request.source.url
            val headers = request.source.headers

            // Streams with headers (like Referer/User-Agent/Cookies), DASH (.mpd), or special streams
            // MUST route through DesktopStreamProxy so every sub-segment (.ts/.m4s) carries the required headers.
            val needsProxy = headers.isNotEmpty() || rawUrl.contains(".mpd", ignoreCase = true) || rawUrl.contains(".m3u8", ignoreCase = true) || rawUrl.contains("hakunaymatata", ignoreCase = true)
            val playUrl = if (needsProxy) {
                DesktopStreamProxy.getProxiedUrl(rawUrl, headers).also {
                    proxiedUrl = it
                    activeProxyUrl.set(it)
                }
            } else {
                toVlcMrl(rawUrl)
            }

            val opts = buildList {
                headers.forEach { (k, v) ->
                    val keyLower = k.lowercase()
                    when {
                        keyLower == "referer" -> {
                            add(":http-referrer=$v")
                            add(":http-header=Referer: $v")
                        }
                        keyLower == "user-agent" -> {
                            add(":http-user-agent=$v")
                            add(":http-header=User-Agent: $v")
                        }
                        keyLower == "cookie" -> {
                            add(":http-cookie=$v")
                            add(":http-header=Cookie: $v")
                        }
                        // Skip hop-by-hop headers VLC doesn't understand
                        keyLower == "connection" || keyLower == "pragma" ||
                        keyLower == "cache-control" || keyLower == "accept-encoding" -> { /* skip */ }
                        else -> add(":http-header=$k: $v")
                    }
                }
                add(":network-caching=3000")
                add(":file-caching=1000")
                add(":live-caching=3000")
                add(":http-reconnect=1")
                if (rawUrl.contains(".mpd", ignoreCase = true) || playUrl.contains(".mpd", ignoreCase = true)) {
                    add(":demux=adaptive")
                }
            }.toTypedArray()

            // Keep callbacks and delayed work owned by this media request until it is replaced.
            logVlc("Starting playback generation ${request.generation}, resume ${request.resumePositionMillis}ms")
            if (!player.media().play(playUrl, *opts)) {
                failSource("VLC could not open this stream - all servers failed")
            }
            launch {
                try {
                    while (isActive && ownsRequest() && vlcError == null && (!isVideoReady || session.loadingEpisode)) delay(100)
                    if (!ownsRequest() || vlcError != null) return@launch
                    player.audio().setVolume(volume)
                    player.audio().isMute = isMuted
                    player.controls().setRate(playbackSpeed)
                    var issuedResumeTarget: Long? = null
                    repeat(100) {
                        while (isActive && ownsRequest() && session.loadingEpisode) delay(100)
                        if (!ownsRequest() || vlcError != null || pendingResumeGeneration != request.generation) return@launch
                        if (request.resumePositionMillis == 0L ||
                            (issuedResumeTarget?.let { resumePositionConfirmed(it, player.status().time()) } == true)) {
                            pendingResumeGeneration = null
                            resumeSettled = true
                            player.controls().setPause(desiredPaused)
                            return@launch
                        }
                        if (issuedResumeTarget == null) {
                            val target = resumeTarget(request.resumePositionMillis, player.status().length(), player.status().isSeekable)
                            if (target != null) {
                                player.controls().setTime(target)
                                currentPosition = target
                                issuedResumeTarget = target
                            }
                        }
                        delay(100)
                    }
                    if (isCurrent() && pendingResumeGeneration == request.generation) {
                        pendingResumeGeneration = null
                        playbackNotice = "Saved position could not be restored. Previous progress was kept."
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    if (isCurrent()) {
                        pendingResumeGeneration = null
                        playbackNotice = "Could not restore playback settings. Previous progress was kept."
                    }
                }
            }
            launch {
                // Native playing can precede the first output; intentional pauses suspend this startup deadline.
                var activeSince = System.nanoTime()
                while (isActive && ownsRequest() && vlcError == null && !initialProgressObserved && presentedFrames.get() == 0L) {
                    delay(500)
                    if (!ownsRequest() || vlcError != null || initialProgressObserved || presentedFrames.get() > 0L) return@launch
                    val now = System.nanoTime()
                    if (desiredPaused || session.loadingEpisode) activeSince = now
                    else if (now - activeSince >= 30_000_000_000L) {
                        failSource("Stream timed out - all servers failed")
                        return@launch
                    }
                }
            }
            awaitCancellation()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failSource("Failed to switch source: ${e.message}")
        } finally {
            proxiedUrl?.let {
                DesktopStreamProxy.releaseProxiedUrl(it)
                activeProxyUrl.compareAndSet(it, null)
            }
            if (!disposed.get()) player.events().removeMediaPlayerEventListener(listener)
        }
    }

    // Polling & Track Sync. Never label outgoing media with a new episode or save an unsettled resume.
    LaunchedEffect(player, playbackRequest.generation) {
        if (player == null) return@LaunchedEffect
        val generation = playbackRequest.generation
        val statistics = MediaStatistics()
        var lastSavedAt = 0L
        var lastDiagnosticAt = 0L
        var startupNativeTime: Long? = null
        while (isActive) {
            delay(500)
            if (disposed.get() || session.request.generation != generation || session.loadingEpisode || !isVideoReady || vlcError != null) {
                stallDetector.reset()
                continue
            }
            try {
                val nativeState = player.status().state()
                val readable = nativeState == State.PLAYING || nativeState == State.BUFFERING || nativeState == State.PAUSED
                val duration = player.status().length().coerceAtLeast(0L)
                val position = player.status().time()
                val nativeSeekable = player.status().isSeekable
                isSeekable = readable && nativeSeekable && totalDuration > 0L
                if (readable) {
                    if (pendingResumeGeneration == null && position >= 0L) {
                        if (startupNativeTime?.let { position > it } == true) initialProgressObserved = true
                        startupNativeTime = position
                    } else startupNativeTime = null
                    if (position >= 0L) lastNativePosition = position
                    if (duration > 0L) totalDuration = duration
                    if (nativeSeekable) mediaWasSeekable = true
                    val fraction = player.status().position()
                    if (fraction.isFinite() && fraction in 0f..1f) lastPositionFraction = fraction
                    if (position >= 0L && (isPlaying || desiredPaused)) {
                        currentPosition = position
                        if (resumeSettled && duration > 0L) {
                            val epObj = currentEp ?: allEpisodes.getOrNull(currentEpIndex) ?: currentEpisode
                            val progress = WatchProgress(
                                title = title + (activeEpisodeName?.takeIf { it.isNotBlank() }?.let { " \u2014 $it" } ?: ""),
                                url = loadResponse?.url ?: session.request.source.url,
                                posterUrl = posterUrl ?: loadResponse?.posterUrl,
                                positionMillis = position,
                                durationMillis = duration,
                                episodeIndex = epObj?.episode,
                                seasonIndex = epObj?.season,
                                apiName = loadResponse?.apiName ?: apiName
                            )
                            lastConfirmedProgress = progress
                            val now = System.nanoTime() / 1_000_000L
                            if (position > 3000L && now - lastSavedAt >= 5000L) {
                                DesktopDataStore.saveWatchProgress(progress)
                                lastSavedAt = now
                            }
                        }
                    }
                }
                audioTracks = player.audio().trackDescriptions()
                selectedAudioTrackId = player.audio().track()
                nativeSubTracks = player.subpictures().trackDescriptions()
                selectedSubTrackId = player.subpictures().track()
                if (selectedSubtitle != null && externalSubtitleIds[selectedSubtitle?.url] != selectedSubTrackId) selectedSubtitle = null

                val videoEnabled = player.video().track() >= 0
                val audioEnabled = selectedAudioTrackId?.let { it >= 0 } == true
                val statsAvailable = player.media().isValid && player.media().info().statistics(statistics)
                if (presentedFrames.get() > 0L || (statsAvailable &&
                    (statistics.picturesDisplayed() > 0 || statistics.audioBuffersPlayed() > 0))) initialProgressObserved = true
                val now = System.nanoTime() / 1_000_000L
                val stalled = stallDetector.sample(
                    nowMillis = now,
                    positionMillis = lastNativePosition,
                    videoPictures = if (statsAvailable && videoEnabled) statistics.picturesDisplayed() else null,
                    audioBuffers = if (statsAvailable && audioEnabled) statistics.audioBuffersPlayed() else null,
                    presentedVideoFrames = if (videoEnabled) presentedFrames.get() else null,
                    active = pendingResumeGeneration == null && !desiredPaused && completedGeneration != generation
                )
                if (stalled != null) {
                    recoverPlayback("${stalled.name.lowercase()} stopped progressing for 45s")
                    continue
                }
                if (!desiredPaused && now - lastDiagnosticAt >= 10_000L) {
                    lastDiagnosticAt = now
                    val diagnostic = "Playback progress generation=$generation time=$currentPosition duration=$totalDuration " +
                        "state=$nativeState cache=$bufferPercent stats=$statsAvailable " +
                        "video=${statistics.picturesDisplayed()} audio=${statistics.audioBuffersPlayed()} published=${presentedFrames.get()}"
                    withContext(Dispatchers.IO) { logVlc(diagnostic) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}
        }
    }

    // Auto-Play Next Episode only after VLC reports the current media finished, once per request.
    LaunchedEffect(completedGeneration) {
        val generation = completedGeneration ?: return@LaunchedEffect
        if (session.consumeCompletion(generation) && hasNextEpisode) {
            playEpisode(allEpisodes[currentEpIndex + 1])
        }
    }

    LaunchedEffect(player, playbackRequest.generation, isVideoReady) {
        if (player == null || !isVideoReady) return@LaunchedEffect
        val generation = playbackRequest.generation
        fun isCurrent() = !disposed.get() && session.request.generation == generation
        var handledRequest = -1
        while (isActive && isCurrent()) {
            if (session.loadingEpisode || handledRequest == subtitleRequestNumber || desiredSubtitle == null) {
                delay(100)
                continue
            }
            if (subtitleReloadRequired) {
                session.restartSource(if (resumeSettled) currentPosition else playbackRequest.resumePositionMillis, desiredPaused)
                return@LaunchedEffect
            }
            val operation = subtitleRequestNumber
            val sub = desiredSubtitle ?: continue
            handledRequest = operation
            subtitleStatus = "Loading ${sub.language}..."
            try {
                var id = externalSubtitleIds[sub.url]
                if (id == null) {
                    val file = externalSubtitleFiles[sub.url] ?: withContext(Dispatchers.IO) {
                        cacheSubtitle(sub.url, subtitleDirectory)
                    }.also { externalSubtitleFiles[sub.url] = it }
                    if (!isCurrent() || operation != subtitleRequestNumber || desiredSubtitle != sub) continue
                    val before = player.subpictures().trackDescriptions().map { it.id() }.toSet()
                    if (!player.media().addSlave(uk.co.caprica.vlcj.media.MediaSlaveType.SUBTITLE, file.toPath().toUri().toASCIIString(), false)) {
                        subtitleStatus = "Could not load subtitle. Try another track."
                        continue
                    }
                    var attempts = 0
                    while (id == null && attempts++ < 40) {
                        delay(200)
                        if (!isCurrent()) return@LaunchedEffect
                        val added = player.subpictures().trackDescriptions().filter { it.id() >= 0 && it.id() !in before }
                        if (added.size == 1) id = added.single().id()
                        else if (added.size > 1) break
                    }
                    if (!isCurrent()) return@LaunchedEffect
                    if (id != null) externalSubtitleIds[sub.url] = id!!
                    else {
                        // VLC exposes no request ID; reset this media before another addition can claim a late track.
                        subtitleReloadRequired = true
                        if (operation == subtitleRequestNumber) subtitleStatus = "Subtitle did not become available. Try another track."
                    }
                }
                if (id != null && operation == subtitleRequestNumber && desiredSubtitle == sub && !session.loadingEpisode) {
                    player.subpictures().setTrack(id)
                    selectedSubTrackId = player.subpictures().track()
                    if (selectedSubTrackId == id) {
                        selectedSubtitle = sub
                        subtitleStatus = null
                    } else subtitleStatus = "Subtitle selection was not accepted. Try again."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (isCurrent() && operation == subtitleRequestNumber) subtitleStatus = "Could not load subtitle. Try another track."
            }
        }
    }

    // Auto-hide controls after 4s
    LaunchedEffect(showControls, isLocked, isVideoReady, isPlaying, controlsInteraction) {
        if (showControls && isVideoReady && isPlaying && !isLocked) {
            delay(4000)
            showControls = false
        }
    }

    // Seek indicator auto-clear
    LaunchedEffect(seekIndicator) {
        if (seekIndicator != null) {
            delay(1200)
            seekIndicator = null
        }
    }

    // ── Cleanup / Save progress on screen exit ────────────────────────────────
    DisposableEffect(Unit) {
        onDispose {
            disposed.set(true)
            frameGeneration.set(-1)
            activeProxyUrl.getAndSet(null)?.let(DesktopStreamProxy::releaseProxiedUrl)
            try {
                player?.controls()?.stop()
                player?.release()
                factoryAndPlayer?.first?.release()
            } catch (_: Exception) {}
            lastConfirmedProgress?.let(DesktopDataStore::saveWatchProgress)
            subtitleDirectory.listFiles()?.forEach { it.delete() }
            subtitleDirectory.delete()
        }
    }

    // ── Helper lambdas ────────────────────────────────────────────────────────
    fun seekBy(ms: Long) {
        if (isLocked || session.loadingEpisode) return
        try {
            val target = seekTarget(currentPosition, totalDuration, player?.status()?.isSeekable == true, ms) ?: return
            val delta = target - currentPosition
            if (delta == 0L) return
            pendingResumeGeneration = null
            resumeSettled = true
            stallDetector.reset()
            player?.controls()?.setTime(target)
            currentPosition = target
            seekIndicator = seekIndicator(delta).takeIf { it.isNotEmpty() }
        } catch (_: Exception) {}
    }

    fun togglePlayPause() {
        if (player == null || isLocked || session.loadingEpisode) return
        if (interruptedGeneration == session.request.generation) {
            desiredPaused = false
            recoverPlayback("interrupted source resumed")
            return
        }
        if (completedGeneration == session.request.generation) {
            session.resetRecovery()
            session.restartSource(0L, false)
            return
        }
        desiredPaused = !desiredPaused
        stallDetector.reset()
        try { player.controls().setPause(desiredPaused) } catch (_: Exception) {}
    }

    fun setVolume(v: Int) {
        volume = v.coerceIn(0, 200)
        try { player?.audio()?.setVolume(volume) } catch (_: Exception) {}
    }

    fun setSpeed(s: Float) {
        stallDetector.reset()
        playbackSpeed = s
        try { player?.controls()?.setRate(s) } catch (_: Exception) {}
    }

    fun selectAudioTrack(trackId: Int) {
        stallDetector.reset()
        try {
            player?.audio()?.setTrack(trackId)
            selectedAudioTrackId = player?.audio()?.track()
        } catch (_: Exception) {}
    }

    fun selectNativeSubTrack(trackId: Int) {
        desiredSubtitle = null
        subtitleRequestNumber++
        subtitleStatus = null
        try {
            player?.subpictures()?.setTrack(trackId)
            selectedSubTrackId = player?.subpictures()?.track()
            if (selectedSubTrackId == trackId) selectedSubtitle = null
            else subtitleStatus = "Subtitle selection was not accepted. Try again."
        } catch (_: Exception) { subtitleStatus = "Could not select subtitle track." }
    }

    fun launchExternalVlc() {
        try {
            val vlcPath = findVlcPath()
            val exe = if (vlcPath != null) File(vlcPath, "vlc.exe") else null
            if (exe?.exists() == true) {
                val cmd = mutableListOf(exe.absolutePath, activeSource.url)
                activeSource.headers.forEach { (k, v) ->
                    when (k.lowercase()) {
                        "referer" -> cmd.addAll(listOf("--http-referrer", v))
                        "user-agent" -> cmd.addAll(listOf("--http-user-agent", v))
                        "cookie" -> cmd.addAll(listOf("--http-cookie", v))
                        else -> {} // skip other headers for external VLC
                    }
                }
                Runtime.getRuntime().exec(cmd.toTypedArray())
            } else {
                Desktop.getDesktop().browse(URI(activeSource.url))
            }
        } catch (_: Exception) { try { Desktop.getDesktop().browse(URI(activeSource.url)) } catch (_: Exception) {} }
    }

    val focusRequester = remember { FocusRequester() }
    var escapePressed by remember { mutableStateOf(false) }
    val toggleKeys = remember { ToggleKeyLatch<Key>() }
    val windowInfo = LocalWindowInfo.current
    LaunchedEffect(windowInfo.isWindowFocused) {
        if (!windowInfo.isWindowFocused) { toggleKeys.clear(); escapePressed = false }
    }
    val drawerOpen = showEpisodesPicker || showSourcesPicker || showAudioTrackPicker || showSubtitlePicker || showSpeedPicker || showVolumePicker
    LaunchedEffect(drawerOpen, isLocked) { onWindowShortcutsBlockedChange(drawerOpen || isLocked) }
    LaunchedEffect(drawerOpen, isLocked, isFullscreen) {
        toggleKeys.clear()
        if (!drawerOpen) focusRequester.requestFocus()
    }

    // ── Render ────────────────────────────────────────────────────────────────
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .onFocusChanged {
                if (!it.hasFocus) { toggleKeys.clear(); escapePressed = false }
            }
            .onPreviewKeyEvent { event ->
                if (event.key != Key.Escape) false
                else {
                    if (event.type == KeyEventType.KeyDown) escapePressed = true
                    if (event.type == KeyEventType.KeyUp && escapePressed) {
                        escapePressed = false
                        if (drawerOpen) {
                            showEpisodesPicker = false
                            showSourcesPicker = false
                            showAudioTrackPicker = false
                            showSubtitlePicker = false
                            showSpeedPicker = false
                            showVolumePicker = false
                        } else if (isFullscreen) onToggleFullscreen()
                        else onBack()
                    }
                    true
                }
            }
            .onKeyEvent { event ->
                if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed || drawerOpen) {
                    toggleKeys.clear()
                    false
                } else if (event.key == Key.Backspace) {
                    if (event.type == KeyEventType.KeyDown) toggleKeys.press(event.key)
                    if (event.type == KeyEventType.KeyUp && toggleKeys.release(event.key)) onBack()
                    true
                } else if (isLocked) {
                    toggleKeys.clear()
                    false
                } else when (event.key) {
                    Key.Spacebar, Key.K, Key.M, Key.F, Key.F11 -> {
                        if (event.type == KeyEventType.KeyDown) toggleKeys.press(event.key)
                        if (event.type == KeyEventType.KeyUp && toggleKeys.release(event.key)) {
                            controlsInteraction++
                            showControls = true
                            when (event.key) {
                                Key.Spacebar, Key.K -> togglePlayPause()
                                Key.M -> {
                                    isMuted = !isMuted
                                    try { player?.audio()?.isMute = isMuted } catch (_: Exception) {}
                                }
                                else -> onToggleFullscreen()
                            }
                        }
                        true
                    }
                    Key.DirectionRight, Key.L, Key.DirectionLeft, Key.J, Key.DirectionUp, Key.DirectionDown -> {
                        if (event.type == KeyEventType.KeyDown) {
                            controlsInteraction++
                            showControls = true
                            when (event.key) {
                                Key.DirectionRight, Key.L -> seekBy(10_000L)
                                Key.DirectionLeft, Key.J -> seekBy(-10_000L)
                                Key.DirectionUp -> setVolume(volume + 10)
                                else -> setVolume(volume - 10)
                            }
                        }
                        true
                    }
                    else -> false
                }
            }
            .focusable()
            .onPointerEvent(PointerEventType.Move) {
                if (!isLocked) {
                    showControls = true
                    controlsInteraction++
                }
            }
            .pointerInput(isLocked, isFullscreen) {
                detectTapGestures(
                    onTap = {
                        focusRequester.requestFocus()
                        controlsInteraction++
                        if (!isLocked) showControls = !showControls
                        else showControls = true
                    },
                    onDoubleTap = {
                        if (!isLocked) {
                            focusRequester.requestFocus()
                            controlsInteraction++
                            showControls = true
                            onToggleFullscreen()
                        }
                    }
                )
            }
    ) {
        // Video bitmap or fallback/connecting states
        when {
            vlcError != null -> FallbackDashboard(
                mediaUrl = activeSource.url, title = title, episodeName = episodeName,
                errorMsg = vlcError!!, isCopied = isCopied,
                onCopy = { clipboardManager.setText(AnnotatedString(activeSource.url)); isCopied = true },
                onLaunchVlc = { launchExternalVlc() },
                onOpenBrowser = { try { Desktop.getDesktop().browse(URI(activeSource.url)) } catch (_: Exception) {} }
            )
            isExtractingNextEp -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF121212)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        color = Color(0xFFE50914),
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(44.dp)
                    )
                    Spacer(Modifier.height(18.dp))
                    Text("Fetching stream sources…", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("Connecting to provider link", color = Color(0xFFB0B0B0), fontSize = 12.sp)
                }
            }
            videoFrame == null || !isVideoReady -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF121212)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        color = Color(0xFFE50914),
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(44.dp)
                    )
                    Spacer(Modifier.height(18.dp))
                    Text("Connecting to ${activeSource.name}…", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("Buffering initial stream segments", color = Color(0xFFB0B0B0), fontSize = 12.sp)
                }
            }
            else -> Image(
                bitmap = videoFrame!!,
                contentDescription = "Video",
                modifier = Modifier.fillMaxSize(),
                contentScale = videoAspectMode.scale
            )
        }

        playbackNotice?.let { message ->
            Surface(
                color = VideoControlBackground,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 70.dp, start = 24.dp, end = 24.dp)
            ) {
                Text(message, color = Color.White, modifier = Modifier.padding(12.dp))
            }
        }

        // Seek indicator (centre flash)
        seekIndicator?.let { label ->
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Surface(
                    color = Color.Black.copy(alpha = 0.75f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        label,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Buffering spinner overlay
        if (isBuffering && videoFrame != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.size(48.dp).background(VideoControlBackground, CircleShape),
                    strokeWidth = 3.dp
                )
            }
        }

        // Controls overlay
        AnimatedVisibility(
            visible = showControls || videoFrame == null,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.fillMaxSize()
        ) {
            if (isLocked) {
                LockedOverlay(onUnlock = { isLocked = false })
            } else {
                PlayerControlsOverlay(
                    title = title,
                    episodeName = activeEpisodeName,
                    activeSourceName = activeSource.name,
                    isPlaying = isPlaying,
                    isMuted = isMuted,
                    volume = volume,
                    playbackSpeed = playbackSpeed,
                    currentPosition = currentPosition,
                    totalDuration = totalDuration,
                    hasEpisodes = allEpisodes.isNotEmpty(),
                    hasPrevEpisode = hasPrevEpisode,
                    hasNextEpisode = hasNextEpisode,
                    hasSources = activeSourcesList.isNotEmpty(),
                    hasAudioTracks = audioTracks.isNotEmpty(),
                    hasSubtitles = activeSubtitlesList.isNotEmpty() || nativeSubTracks.isNotEmpty(),
                    hasVideo = videoFrame != null && isVideoReady && !isExtractingNextEp,
                    canSeek = isSeekable,
                    isFullscreen = isFullscreen,
                    onToggleFullscreen = onToggleFullscreen,
                    videoAspectMode = videoAspectMode,
                    onToggleAspectMode = {
                        videoAspectMode = when (videoAspectMode) {
                            VideoAspectMode.CROP -> VideoAspectMode.STRETCH
                            VideoAspectMode.STRETCH -> VideoAspectMode.FIT
                            VideoAspectMode.FIT -> VideoAspectMode.CROP
                        }
                    },
                    onBack = onBack,
                    onPlayPause = { togglePlayPause() },
                    onSeekBack30 = { seekBy(-30_000) },
                    onSeekForward30 = { seekBy(30_000) },
                    onSeekBack10 = { seekBy(-10_000) },
                    onSeekForward10 = { seekBy(10_000) },
                    onPrevEpisode = { if (hasPrevEpisode) playEpisode(allEpisodes[currentEpIndex - 1]) },
                    onNextEpisode = { if (hasNextEpisode) playEpisode(allEpisodes[currentEpIndex + 1]) },
                    onSeek = { pct ->
                        seekBy((pct * totalDuration).toLong() - currentPosition)
                    },
                    onMuteToggle = {
                        isMuted = !isMuted
                        try { player?.audio()?.isMute = isMuted } catch (_: Exception) {}
                    },
                    onVolumeClick = { showVolumePicker = true },
                    onSpeedClick = { showSpeedPicker = true },
                    onEpisodesClick = { showEpisodesPicker = true },
                    onSourcesClick = { showSourcesPicker = true },
                    onAudioTracksClick = { showAudioTrackPicker = true },
                    onSubtitleClick = { showSubtitlePicker = true },
                    onLock = { isLocked = true; showControls = false },
                    onExternalVlc = { launchExternalVlc() }
                )
            }
        }

        // ── Sidebar Drawers (smooth fade-in background scrim + slide-in sidebar) ──
        if (allEpisodes.isNotEmpty()) {
            EpisodesPickerDialog(
                visible = showEpisodesPicker,
                episodes = allEpisodes,
                currentEpisodeIndex = currentEpIndex,
                onSelect = { ep ->
                    playEpisode(ep)
                    showEpisodesPicker = false
                },
                onDismiss = { showEpisodesPicker = false }
            )
        }

        if (activeSourcesList.isNotEmpty()) {
            SourcesPickerDialog(
                visible = showSourcesPicker,
                sources = activeSourcesList,
                currentSource = activeSource,
                onSelect = { src ->
                    if (!session.loadingEpisode) {
                        val position = if (resumeSettled) currentPosition else playbackRequest.resumePositionMillis
                        if (vlcError != null && samePlaybackSource(src, activeSource)) {
                            session.resetRecovery()
                            session.restartSource(position, desiredPaused)
                        } else if (session.selectSource(src, position, desiredPaused)) session.resetRecovery()
                    }
                    showSourcesPicker = false
                },
                onDismiss = { showSourcesPicker = false }
            )
        }

        AudioTrackPickerDialog(
            visible = showAudioTrackPicker,
            tracks = audioTracks,
            selectedTrackId = selectedAudioTrackId,
            onSelect = { id -> selectAudioTrack(id); showAudioTrackPicker = false },
            onDismiss = { showAudioTrackPicker = false }
        )

        CombinedSubtitlePickerDialog(
            visible = showSubtitlePicker,
            externalSubtitles = activeSubtitlesList,
            selectedExternal = selectedSubtitle,
            nativeTracks = nativeSubTracks.filterNot { it.id() in externalSubtitleIds.values },
            selectedNativeId = selectedSubTrackId,
            status = subtitleStatus,
            onSelectExternal = { sub -> desiredSubtitle = sub; subtitleRequestNumber++ },
            onDisable = { selectNativeSubTrack(-1) },
            onSelectNative = { id -> selectNativeSubTrack(id); showSubtitlePicker = false },
            onDismiss = { showSubtitlePicker = false }
        )

        SpeedPickerDialog(
            visible = showSpeedPicker,
            current = playbackSpeed,
            onSelect = { setSpeed(it); showSpeedPicker = false },
            onDismiss = { showSpeedPicker = false }
        )

        VolumeDialog(
            visible = showVolumePicker,
            volume = volume,
            isMuted = isMuted,
            onVolumeChange = { setVolume(it) },
            onMuteToggle = {
                isMuted = !isMuted
                try { player?.audio()?.isMute = isMuted } catch (_: Exception) {}
            },
            onDismiss = { showVolumePicker = false }
        )
    }
}

// ─── Full Controls Overlay ────────────────────────────────────────────────────

@Composable
private fun PlayerControlsOverlay(
    title: String,
    episodeName: String?,
    activeSourceName: String,
    isPlaying: Boolean,
    isMuted: Boolean,
    volume: Int,
    playbackSpeed: Float,
    currentPosition: Long,
    totalDuration: Long,
    hasEpisodes: Boolean,
    hasPrevEpisode: Boolean,
    hasNextEpisode: Boolean,
    hasSources: Boolean,
    hasAudioTracks: Boolean,
    hasSubtitles: Boolean,
    hasVideo: Boolean,
    canSeek: Boolean,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    videoAspectMode: VideoAspectMode,
    onToggleAspectMode: () -> Unit,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSeekBack30: () -> Unit,
    onSeekForward30: () -> Unit,
    onSeekBack10: () -> Unit,
    onSeekForward10: () -> Unit,
    onPrevEpisode: () -> Unit,
    onNextEpisode: () -> Unit,
    onSeek: (Float) -> Unit,
    onMuteToggle: () -> Unit,
    onVolumeClick: () -> Unit,
    onSpeedClick: () -> Unit,
    onEpisodesClick: () -> Unit,
    onSourcesClick: () -> Unit,
    onAudioTracksClick: () -> Unit,
    onSubtitleClick: () -> Unit,
    onLock: () -> Unit,
    onExternalVlc: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Top gradient fade
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(0.85f),
                        0.55f to Color.Black.copy(0.8f),
                        1f to Color.Transparent
                    )
                )
        )
        // Bottom gradient fade
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.4f to Color.Black.copy(0.65f),
                        1f to Color.Black.copy(0.90f)
                    )
                )
        )

        // ── TOP BAR ──────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ControlIconButton(Icons.Default.ArrowBack, "Back", onClick = onBack)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        title, color = Color.White, fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (episodeName != null) {
                            Text(episodeName, color = Color.White.copy(0.7f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("  •  ", color = Color.White.copy(0.4f), fontSize = 12.sp)
                        }
                        Text(cleanServerTitle(activeSourceName), color = Color(0xFFE50914), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Episodes Selector Button
                if (hasEpisodes) {
                    Surface(
                        onClick = onEpisodesClick,
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFF252525),
                        border = BorderStroke(1.dp, Color(0x1AFFFFFF)),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Default.FormatListBulleted, null, modifier = Modifier.size(14.dp), tint = Color.White)
                            Text("Episodes", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Medium)
                        }
                    }
                }

                // Sources / Mirrors Button
                if (hasSources) {
                    Surface(
                        onClick = onSourcesClick,
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFF252525),
                        border = BorderStroke(1.dp, Color(0x1AFFFFFF)),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Default.Dns, null, modifier = Modifier.size(14.dp), tint = Color.White)
                            Text("Sources", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Medium)
                        }
                    }
                }

                // Speed badge
                if (playbackSpeed != 1.0f) {
                    Surface(color = Color(0xFFE50914), shape = RoundedCornerShape(4.dp)) {
                        Text("${playbackSpeed}x", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // ── CENTRE CONTROLS ──────────────────────────────────────────────────
        if (hasVideo) {
            Row(
                modifier = Modifier.align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (hasEpisodes) {
                    IconButton(
                        onClick = onPrevEpisode,
                        enabled = hasPrevEpisode,
                        modifier = Modifier.size(44.dp).background(VideoControlBackground, CircleShape)
                    ) {
                        Icon(
                            Icons.Default.SkipPrevious, null,
                            tint = if (hasPrevEpisode) Color.White else Color.White.copy(0.3f),
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                IconButton(
                    onClick = onSeekBack30,
                    modifier = Modifier.size(48.dp).background(VideoControlBackground, CircleShape)
                ) {
                    Icon(Icons.Default.Replay30, null, tint = Color.White, modifier = Modifier.size(30.dp))
                }

                // Play / Pause central button
                Surface(
                    onClick = onPlayPause,
                    shape = CircleShape,
                    color = VideoControlBackground,
                    border = BorderStroke(1.dp, Color.White.copy(0.3f)),
                    modifier = Modifier.size(64.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            null, tint = Color.White, modifier = Modifier.size(34.dp)
                        )
                    }
                }

                IconButton(
                    onClick = onSeekForward30,
                    modifier = Modifier.size(48.dp).background(VideoControlBackground, CircleShape)
                ) {
                    Icon(Icons.Default.Forward30, null, tint = Color.White, modifier = Modifier.size(30.dp))
                }

                if (hasEpisodes) {
                    IconButton(
                        onClick = onNextEpisode,
                        enabled = hasNextEpisode,
                        modifier = Modifier.size(44.dp).background(VideoControlBackground, CircleShape)
                    ) {
                        Icon(
                            Icons.Default.SkipNext, null,
                            tint = if (hasNextEpisode) Color.White else Color.White.copy(0.3f),
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }
        }

        // ── BOTTOM BAR ───────────────────────────────────────────────────────
        if (hasVideo) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            ) {
                // Seekbar with time
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(formatTime(currentPosition), color = Color.White.copy(0.8f), fontSize = 12.sp)
                    Slider(
                        value = if (totalDuration > 0) (currentPosition.toFloat() / totalDuration).coerceIn(0f, 1f) else 0f,
                        onValueChange = onSeek,
                        enabled = canSeek,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color(0xFFE50914),
                            inactiveTrackColor = Color.White.copy(0.25f)
                        )
                    )
                    Text(formatTime(totalDuration), color = Color.White.copy(0.8f), fontSize = 12.sp)
                }

                // Controls row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        ControlIconButton(Icons.Default.Replay10, "Replay 10s", onClick = onSeekBack10)
                        ControlIconButton(
                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            "Play/Pause", onClick = onPlayPause
                        )
                        ControlIconButton(Icons.Default.Forward10, "Forward 10s", onClick = onSeekForward10)
                        Spacer(Modifier.width(6.dp))
                        ControlIconButton(
                            if (isMuted || volume == 0) Icons.Default.VolumeOff
                            else if (volume < 80) Icons.Default.VolumeDown
                            else Icons.Default.VolumeUp,
                            "Volume",
                            onClick = onVolumeClick
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (hasSources) {
                            ControlIconButton(Icons.Default.Dns, "Mirrors & Sources", onClick = onSourcesClick)
                        }
                        if (hasAudioTracks) {
                            ControlIconButton(Icons.Default.Audiotrack, "Audio Tracks", onClick = onAudioTracksClick)
                        }
                        Surface(
                            onClick = onSpeedClick,
                            shape = RoundedCornerShape(4.dp),
                            color = Color.Transparent,
                            modifier = Modifier.height(30.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 8.dp)) {
                                Text("${playbackSpeed}x", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        if (hasSubtitles) {
                            ControlIconButton(Icons.Default.ClosedCaption, "Subtitles", onClick = onSubtitleClick)
                        }
                        ControlIconButton(Icons.Default.Lock, "Lock controls", onClick = onLock)
                        ControlIconButton(
                            Icons.Default.AspectRatio,
                            "Aspect Mode: ${videoAspectMode.label}",
                            onClick = onToggleAspectMode
                        )
                        ControlIconButton(
                            if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                            if (isFullscreen) "Exit Fullscreen" else "Fullscreen",
                            onClick = onToggleFullscreen
                        )
                    }
                }
            }
        }
    }
}

// ─── Locked Overlay ───────────────────────────────────────────────────────────

@Composable
private fun LockedOverlay(onUnlock: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        IconButton(
            onClick = onUnlock,
            modifier = Modifier
                .padding(20.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(0.5f))
                .size(48.dp)
        ) {
            Icon(Icons.Default.LockOpen, "Unlock", tint = Color.White, modifier = Modifier.size(24.dp))
        }
    }
}

// ─── Sources & Mirrors Picker Dialog ──────────────────────────────────────────

private fun cleanServerTitle(rawName: String): String {
    val tokens = rawName.split("|", "•").map { it.trim() }.filter { it.isNotBlank() }
    val seen = mutableSetOf<String>()
    val result = mutableListOf<String>()
    for (t in tokens) {
        val norm = t.lowercase().replace(" ", "")
        if (seen.add(norm)) {
            result.add(t)
        }
    }
    val cleaned = result.joinToString(" • ")
    return if (cleaned.isBlank()) rawName else cleaned
}

private fun detectQualityLabel(source: StreamSourceInfo): String {
    val text = (source.name + " " + source.url).lowercase()
    return when {
        source.quality >= 2160 || text.contains("4k") || text.contains("2160") -> "4K"
        source.quality >= 1080 || text.contains("1080") -> "1080p"
        source.quality >= 720 || text.contains("720") -> "720p"
        source.quality >= 480 || text.contains("480") -> "480p"
        else -> "HD"
    }
}

// ─── Reusable Full Height Right Sidebar Drawer Container ───────────────────────

@Composable
private fun RightSidebarDrawer(
    visible: Boolean,
    onDismiss: () -> Unit,
    width: Dp = 440.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200, easing = FastOutSlowInEasing)),
        exit = fadeOut(tween(180, easing = FastOutSlowInEasing)),
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(0.65f))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onDismiss
                )
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = slideInHorizontally(
                    initialOffsetX = { fullWidth -> fullWidth },
                    animationSpec = tween(280, easing = FastOutSlowInEasing)
                ),
                exit = slideOutHorizontally(
                    targetOffsetX = { fullWidth -> fullWidth },
                    animationSpec = tween(220, easing = FastOutSlowInEasing)
                ),
                modifier = Modifier
                    .fillMaxHeight()
                    .width(width)
                    .align(Alignment.CenterEnd)
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = {}
                        ),
                    color = Color(0xFF1C1C1C).copy(0.98f),
                    border = BorderStroke(1.dp, Color(0x1AFFFFFF))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 20.dp)
                    ) {
                        content()
                    }
                }
            }
        }
    }
}

// ─── Sources Picker Sidebar ───────────────────────────────────────────────────

// ─── Sources Picker Sidebar ───────────────────────────────────────────────────

@Composable
private fun SourcesPickerDialog(
    visible: Boolean,
    sources: List<StreamSourceInfo>,
    currentSource: StreamSourceInfo,
    onSelect: (StreamSourceInfo) -> Unit,
    onDismiss: () -> Unit
) {
    RightSidebarDrawer(visible = visible, onDismiss = onDismiss, width = 460.dp) {
        // Aligned Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Dns, null,
                    tint = Color(0xFFE50914),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        "Stream Sources",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = Color.White
                    )
                    Text(
                        "${sources.size} mirror server${if (sources.size != 1) "s" else ""} available",
                        color = Color(0xFFB0B0B0),
                        fontSize = 12.sp
                    )
                }
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Close, "Close", tint = Color(0xFFB0B0B0), modifier = Modifier.size(18.dp))
            }
        }

        HorizontalDivider(color = Color(0x1AFFFFFF))
        Spacer(Modifier.height(14.dp))

        // Full Height Scrollable Item List
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            sources.forEach { source ->
                val isSelected = samePlaybackSource(source, currentSource)
                val qualityLabel = detectQualityLabel(source)
                val cleanedTitle = cleanServerTitle(source.name)

                Win11SidebarOptionCard(
                    isSelected = isSelected,
                    onClick = { onSelect(source) }
                ) {
                    // Quality Badge Pill
                    val (badgeBg, badgeTextColor) = when (qualityLabel) {
                        "4K" -> Pair(Color(0xFFD97706), Color.Black)
                        "1080p" -> Pair(Color(0xFF0078D4), Color.White)
                        "720p" -> Pair(Color(0xFF107C41), Color.White)
                        else -> Pair(Color(0xFF333333), Color.White)
                    }
                    Surface(
                        color = badgeBg,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            qualityLabel,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                            color = badgeTextColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Title & Domain URL
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            cleanedTitle,
                            color = Color.White,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            source.url,
                            color = Color(0xFFB0B0B0),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Selection Checkmark
                    if (isSelected) {
                        Icon(
                            Icons.Default.Check,
                            null,
                            tint = Color(0xFFE50914),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

// ─── Audio Track Picker Sidebar ───────────────────────────────────────────────

private fun cleanAudioTrackName(rawName: String): String {
    val name = rawName.trim()
    val bracketMatch = Regex("""\[(.*?)\]""").find(name)
    if (bracketMatch != null) {
        val lang = bracketMatch.groupValues[1].trim()
        if (lang.isNotBlank()) return "$lang Audio"
    }
    return name.ifBlank { "Audio Track" }
}

@Composable
private fun AudioTrackPickerDialog(
    visible: Boolean,
    tracks: List<TrackDescription>,
    selectedTrackId: Int?,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    RightSidebarDrawer(visible = visible, onDismiss = onDismiss, width = 420.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Audiotrack, null,
                    tint = Color(0xFFE50914),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        "Audio Track",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = Color.White
                    )
                    Text(
                        "Select active audio stream language",
                        color = Color(0xFFB0B0B0),
                        fontSize = 12.sp
                    )
                }
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Close, "Close", tint = Color(0xFFB0B0B0), modifier = Modifier.size(18.dp))
            }
        }

        HorizontalDivider(color = Color(0x1AFFFFFF))
        Spacer(Modifier.height(14.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            tracks.forEach { track ->
                val isSelected = track.id() == selectedTrackId
                val cleanName = cleanAudioTrackName(track.description())

                Win11SidebarOptionCard(
                    isSelected = isSelected,
                    onClick = { onSelect(track.id()) }
                ) {
                    Text(
                        cleanName,
                        color = Color.White,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f)
                    )
                    if (isSelected) {
                        Icon(
                            Icons.Default.Check,
                            null,
                            tint = Color(0xFFE50914),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

// ─── Combined Subtitle Picker Sidebar ──────────────────────────────────────────

@Composable
private fun CombinedSubtitlePickerDialog(
    visible: Boolean,
    externalSubtitles: List<SubtitleTrackInfo>,
    selectedExternal: SubtitleTrackInfo?,
    nativeTracks: List<TrackDescription>,
    selectedNativeId: Int?,
    status: String?,
    onSelectExternal: (SubtitleTrackInfo) -> Unit,
    onSelectNative: (Int) -> Unit,
    onDisable: () -> Unit,
    onDismiss: () -> Unit
) {
    RightSidebarDrawer(visible = visible, onDismiss = onDismiss, width = 440.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.ClosedCaption, null,
                    tint = Color(0xFFE50914),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        "Subtitles",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = Color.White
                    )
                    Text(
                        "Select subtitles track or embedded stream",
                        color = Color(0xFFB0B0B0),
                        fontSize = 12.sp
                    )
                }
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Close, "Close", tint = Color(0xFFB0B0B0), modifier = Modifier.size(18.dp))
            }
        }

        HorizontalDivider(color = Color(0x1AFFFFFF))
        Spacer(Modifier.height(14.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            status?.let { Text(it, color = Color(0xFFB0B0B0), fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp)) }
            SubtitleOption("Off", selectedExternal == null && selectedNativeId == -1, onClick = onDisable)

            if (nativeTracks.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("Embedded Subtitles", color = Color(0xFFE50914), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                nativeTracks.filter { it.id() != -1 }.forEach { track ->
                    val isSelected = selectedExternal == null && track.id() == selectedNativeId
                    SubtitleOption(cleanAudioTrackName(track.description()), isSelected, onClick = { onSelectNative(track.id()) })
                }
            }

            if (externalSubtitles.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("External Subtitles", color = Color(0xFFE50914), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                externalSubtitles.forEach { track ->
                    val isSelected = track == selectedExternal
                    SubtitleOption(
                        track.language.ifBlank { track.url.substringAfterLast('/') },
                        isSelected,
                        onClick = { onSelectExternal(track) }
                    )
                }
            }
        }
    }
}

@Composable
private fun EpisodesPickerDialog(
    visible: Boolean,
    episodes: List<Episode>,
    currentEpisodeIndex: Int,
    onSelect: (Episode) -> Unit,
    onDismiss: () -> Unit
) {
    val seasons = remember(episodes) { episodes.mapNotNull { it.season }.distinct().sorted() }
    var selectedSeason by remember { mutableStateOf(episodes.getOrNull(currentEpisodeIndex)?.season ?: seasons.firstOrNull() ?: 1) }

    val seasonEpisodes = remember(episodes, selectedSeason) {
        if (seasons.size > 1) {
            episodes.filter { it.season == selectedSeason || (it.season == null && selectedSeason == 1) }
        } else episodes
    }

    RightSidebarDrawer(visible = visible, onDismiss = onDismiss, width = 480.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.VideoLibrary, null,
                    tint = Color(0xFFE50914),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        "Episodes",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = Color.White
                    )
                    Text(
                        "${episodes.size} episode${if (episodes.size != 1) "s" else ""} available",
                        color = Color(0xFFB0B0B0),
                        fontSize = 12.sp
                    )
                }
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Close, "Close", tint = Color(0xFFB0B0B0), modifier = Modifier.size(18.dp))
            }
        }

        HorizontalDivider(color = Color(0x1AFFFFFF))

        if (seasons.size > 1) {
            Spacer(Modifier.height(12.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(seasons) { season ->
                    val isSelected = selectedSeason == season
                    val textColor by animateColorAsState(if (isSelected) Color(0xFFE50914) else Color(0xFFB0B0B0), tween(150))

                    Surface(
                        onClick = { selectedSeason = season },
                        shape = RoundedCornerShape(5.dp),
                        color = if (isSelected) Color(0xFFE50914).copy(0.15f) else Color.Transparent,
                        border = BorderStroke(1.dp, if (isSelected) Color(0xFFE50914) else Color(0x1AFFFFFF))
                    ) {
                        Text(
                            "Season $season",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                            color = textColor,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            seasonEpisodes.forEach { ep ->
                val isSelected = episodes.getOrNull(currentEpisodeIndex)?.data == ep.data
                val epLabel = "S${ep.season ?: 1}:E${ep.episode ?: (seasonEpisodes.indexOf(ep) + 1)}"

                Win11SidebarOptionCard(
                    isSelected = isSelected,
                    onClick = { onSelect(ep) }
                ) {
                    Surface(
                        color = if (isSelected) Color(0xFFE50914) else Color(0xFF333333),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            epLabel,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            color = if (isSelected) Color.White else Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            ep.name ?: epLabel,
                            color = Color.White,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        ep.description?.let { desc ->
                            if (desc.isNotBlank()) {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    desc,
                                    color = Color(0xFFB0B0B0),
                                    fontSize = 11.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    if (isSelected) {
                        Icon(
                            Icons.Default.Check, null,
                            tint = Color(0xFFE50914),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SubtitleOption(label: String, isSelected: Boolean, onClick: () -> Unit) {
    Win11SidebarOptionCard(
        isSelected = isSelected,
        onClick = onClick
    ) {
        Text(
            label,
            color = Color.White,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f)
        )
        if (isSelected) {
            Icon(
                Icons.Default.Check,
                null,
                tint = Color(0xFFE50914),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

// ─── Windows 11 Option Card Component ─────────────────────────────────────────

@Composable
private fun Win11SidebarOptionCard(
    isSelected: Boolean,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(6.dp),
        color = if (isSelected) Color(0xFF252525) else if (hovered) Color(0xFF333333) else Color(0xFF1C1C1C),
        border = BorderStroke(
            1.dp,
            if (isSelected) Color(0xFFE50914) else if (hovered) Color(0x33FFFFFF) else Color(0x1AFFFFFF)
        ),
        modifier = Modifier.fillMaxWidth().hoverable(interactionSource)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Windows 11 Accent Left Bar
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(
                        if (isSelected) Color(0xFFE50914) else Color.Transparent,
                        shape = RoundedCornerShape(2.dp)
                    )
            )

            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                content = content
            )
        }
    }
}

// ─── Speed Picker Sidebar ─────────────────────────────────────────────────────

@Composable
private fun SpeedPickerDialog(visible: Boolean, current: Float, onSelect: (Float) -> Unit, onDismiss: () -> Unit) {
    val speeds = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 3.0f)
    RightSidebarDrawer(visible = visible, onDismiss = onDismiss, width = 360.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Playback Speed", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = Color.White)
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Close, "Close", tint = Color(0xFFB0B0B0), modifier = Modifier.size(18.dp))
            }
        }
        HorizontalDivider(color = Color(0x1AFFFFFF))
        Spacer(Modifier.height(14.dp))
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            speeds.forEach { speed ->
                val isSelected = speed == current
                SubtitleOption("${speed}x${if (speed == 1.0f) " (Normal)" else ""}", isSelected, onClick = { onSelect(speed) })
            }
        }
    }
}

// ─── Volume Sidebar ───────────────────────────────────────────────────────────

@Composable
private fun VolumeDialog(
    visible: Boolean,
    volume: Int, isMuted: Boolean,
    onVolumeChange: (Int) -> Unit,
    onMuteToggle: () -> Unit,
    onDismiss: () -> Unit
) {
    RightSidebarDrawer(visible = visible, onDismiss = onDismiss, width = 360.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Volume", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = Color.White)
                Spacer(Modifier.width(8.dp))
                Text("${volume}%", color = Color(0xFFE50914), fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Close, "Close", tint = Color(0xFFB0B0B0), modifier = Modifier.size(18.dp))
            }
        }
        HorizontalDivider(color = Color(0x1AFFFFFF))
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onMuteToggle) {
                Icon(if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp, null, tint = if (isMuted) Color(0xFFEF5350) else Color.White)
            }
            Slider(
                value = volume.toFloat(),
                onValueChange = { onVolumeChange(it.toInt()) },
                valueRange = 0f..200f,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFFE50914),
                    activeTrackColor = Color(0xFFE50914),
                    inactiveTrackColor = Color(0x33FFFFFF)
                )
            )
        }
        if (volume > 100) {
            Text("⚠ Volume above 100% may distort audio", color = Color(0xFFFFB300), fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
        }
    }
}

// ─── Fallback Dashboard ───────────────────────────────────────────────────────

@Composable
private fun FallbackDashboard(
    mediaUrl: String, title: String, episodeName: String?,
    errorMsg: String, isCopied: Boolean,
    onCopy: () -> Unit, onLaunchVlc: () -> Unit, onOpenBrowser: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF121212), Color(0xFF1C1C1C)))),
        contentAlignment = Alignment.Center
    ) {
        Card(modifier = Modifier.fillMaxWidth(0.7f), colors = CardDefaults.cardColors(containerColor = Color(0xFF252525)), shape = RoundedCornerShape(24.dp)) {
            Column(modifier = Modifier.padding(36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.PlayCircle, null, tint = Color(0xFFE50914), modifier = Modifier.size(80.dp))
                Spacer(Modifier.height(16.dp))
                Text(title, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), color = Color.White, textAlign = TextAlign.Center)
                if (episodeName != null) Text(episodeName, color = Color(0xFFE50914), fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Text("VLC: $errorMsg", color = Color(0xFFEF5350), fontSize = 12.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(24.dp))
                Surface(modifier = Modifier.fillMaxWidth(), color = Color(0xFF1C1C1C), shape = RoundedCornerShape(12.dp)) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Link, null, tint = Color(0xFFE50914), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(mediaUrl, color = Color(0xFFB0B0B0), fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        IconButton(onClick = onCopy) {
                            Icon(if (isCopied) Icons.Default.Check else Icons.Default.ContentCopy, null, tint = Color(0xFFE50914))
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onLaunchVlc, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE50914), contentColor = Color.White), shape = RoundedCornerShape(10.dp)) {
                        Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Launch VLC")
                    }
                    OutlinedButton(onClick = onOpenBrowser, shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) {
                        Icon(Icons.Default.OpenInNew, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Browser")
                    }
                }
            }
        }
    }
}

// ─── Helper ───────────────────────────────────────────────────────────────────

@Composable
private fun ControlIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    onClick: () -> Unit,
    size: androidx.compose.ui.unit.Dp = 20.dp
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(6.dp),
        color = if (hovered) Color.White.copy(alpha = 0.12f) else Color.Transparent,
        modifier = Modifier.size(32.dp).hoverable(interactionSource)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, desc, tint = Color.White, modifier = Modifier.size(size))
        }
    }
}

private fun formatTime(ms: Long): String {
    val s = (ms / 1000) % 60; val m = (ms / 60000) % 60; val h = ms / 3600000
    return if (h > 0) "%02d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
