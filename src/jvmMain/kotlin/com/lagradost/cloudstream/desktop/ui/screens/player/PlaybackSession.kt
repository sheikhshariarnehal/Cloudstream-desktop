package com.lagradost.cloudstream.desktop.ui.screens.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lagradost.cloudstream.desktop.ui.navigation.StreamSourceInfo
import java.math.BigDecimal

internal data class PlaybackRequest(
    val source: StreamSourceInfo,
    val resumePositionMillis: Long,
    val paused: Boolean,
    val generation: Int,
)

internal enum class PlaybackRecovery { RELOADED, SWITCHED_SOURCE, EXHAUSTED, IGNORED }

internal class PlaybackSession(initialSource: StreamSourceInfo, initialPositionMillis: Long = 0) {
    var request by mutableStateOf(PlaybackRequest(initialSource, initialPositionMillis.coerceAtLeast(0), false, 0))
        private set
    var loadingEpisode by mutableStateOf(false)
        private set
    private var completedGeneration: Int? = null
    private var hasReloadedForRecovery = false

    fun beginEpisode(): Boolean {
        if (loadingEpisode) return false
        loadingEpisode = true
        return true
    }

    fun finishEpisodeLoad() {
        loadingEpisode = false
    }

    fun startEpisode(source: StreamSourceInfo) {
        resetRecovery()
        request = PlaybackRequest(source, 0, false, request.generation + 1)
    }

    fun selectSource(source: StreamSourceInfo, positionMillis: Long, paused: Boolean): Boolean {
        if (samePlaybackSource(request.source, source)) return false
        request = PlaybackRequest(source, positionMillis.coerceAtLeast(0), paused, request.generation + 1)
        return true
    }

    fun restartSource(positionMillis: Long, paused: Boolean) {
        request = PlaybackRequest(request.source, positionMillis.coerceAtLeast(0), paused, request.generation + 1)
    }

    fun failover(sources: List<StreamSourceInfo>, positionMillis: Long, paused: Boolean): Boolean {
        val mirrors = sources.distinctBy { it.playbackIdentity }
        val current = mirrors.indexOfFirst { samePlaybackSource(it, request.source) }
        if (current < 0 || current == mirrors.lastIndex) return false
        return selectSource(mirrors[current + 1], positionMillis, paused)
    }

    fun recoverPlayback(sources: List<StreamSourceInfo>, positionMillis: Long, paused: Boolean): PlaybackRecovery {
        if (paused || loadingEpisode) return PlaybackRecovery.IGNORED
        // Each episode or manual restart gets one reload, then recovery advances through mirrors.
        if (!hasReloadedForRecovery) {
            hasReloadedForRecovery = true
            restartSource(positionMillis, paused)
            return PlaybackRecovery.RELOADED
        }
        return if (failover(sources, positionMillis, paused)) PlaybackRecovery.SWITCHED_SOURCE
        else PlaybackRecovery.EXHAUSTED
    }

    fun resetRecovery() {
        hasReloadedForRecovery = false
    }

    fun consumeCompletion(generation: Int): Boolean {
        if (generation != request.generation || loadingEpisode || completedGeneration == generation) return false
        completedGeneration = generation
        return true
    }
}

private val StreamSourceInfo.playbackIdentity get() = url to headers

internal fun samePlaybackSource(a: StreamSourceInfo, b: StreamSourceInfo): Boolean =
    a.playbackIdentity == b.playbackIdentity

internal fun resumePositionConfirmed(expected: Long, actual: Long): Boolean {
    if (actual < 0 || expected < 0) return false
    return expected == 0L || kotlin.math.abs(expected - actual) <= 2_000
}

internal fun seekTarget(position: Long, duration: Long, seekable: Boolean, delta: Long): Long? {
    if (!seekable || duration <= 0) return null
    val start = position.coerceIn(0, duration)
    return start + delta.coerceIn(-start, duration - start)
}

internal fun seekIndicator(delta: Long): String {
    if (delta == 0L) return ""
    val seconds = BigDecimal.valueOf(delta, 3).stripTrailingZeros().toPlainString()
    return if (delta > 0) "+${seconds}s" else "${seconds}s"
}

internal class ToggleKeyLatch<T> {
    private val pressed = mutableSetOf<T>()

    fun press(key: T) {
        pressed.add(key)
    }

    fun release(key: T): Boolean = pressed.remove(key)

    fun clear() = pressed.clear()
}
