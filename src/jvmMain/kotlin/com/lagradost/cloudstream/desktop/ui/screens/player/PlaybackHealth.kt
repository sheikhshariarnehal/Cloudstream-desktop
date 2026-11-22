package com.lagradost.cloudstream.desktop.ui.screens.player

internal enum class PlaybackStall { VIDEO, AUDIO, STREAM }

internal class PlaybackStallDetector(private val timeoutMillis: Long = 45_000L) {
    private val timeline = Progress()
    private val nativeVideo = Progress()
    private val presentedVideo = Progress()
    private val audio = Progress()
    private var lastSampleMillis: Long? = null

    fun sample(
        nowMillis: Long,
        positionMillis: Long,
        videoPictures: Int?,
        audioBuffers: Int?,
        presentedVideoFrames: Long?,
        active: Boolean,
    ): PlaybackStall? {
        if (!active) {
            reset()
            return null
        }
        val position = positionMillis.takeIf { it >= 0 }
        val previousPosition = timeline.value
        val previousSample = lastSampleMillis
        if ((previousSample != null && nowMillis < previousSample) ||
            (previousPosition != null && position != null && position < previousPosition)
        ) {
            reset()
        }

        lastSampleMillis = nowMillis
        timeline.observe(position, nowMillis)
        nativeVideo.observe(videoPictures?.toLong(), nowMillis)
        presentedVideo.observe(presentedVideoFrames, nowMillis)
        audio.observe(audioBuffers?.toLong(), nowMillis)

        val videoStalled = nativeVideo.isStalled(nowMillis, timeoutMillis) ||
            presentedVideo.isStalled(nowMillis, timeoutMillis)
        val audioStalled = audio.isStalled(nowMillis, timeoutMillis)
        return when {
            videoStalled && audioStalled -> PlaybackStall.STREAM
            !nativeVideo.hasAdvanced && !audio.hasAdvanced && timeline.isStalled(nowMillis, timeoutMillis) ->
                PlaybackStall.STREAM
            videoStalled -> PlaybackStall.VIDEO
            audioStalled -> PlaybackStall.AUDIO
            else -> null
        }
    }

    fun reset() {
        lastSampleMillis = null
        timeline.reset()
        nativeVideo.reset()
        presentedVideo.reset()
        audio.reset()
    }

    private class Progress {
        var value: Long? = null
            private set
        private var lastAdvanceMillis: Long? = null
        val hasAdvanced get() = lastAdvanceMillis != null

        fun observe(value: Long?, nowMillis: Long) {
            val current = value?.takeIf { it >= 0 }
            val previous = this.value
            this.value = current
            if (previous == null || current == null || current < previous) {
                lastAdvanceMillis = null
            } else if (current > previous) {
                lastAdvanceMillis = nowMillis
            }
        }

        fun isStalled(nowMillis: Long, timeoutMillis: Long): Boolean =
            lastAdvanceMillis?.let { nowMillis - it >= timeoutMillis } == true

        fun reset() {
            value = null
            lastAdvanceMillis = null
        }
    }
}

internal fun isPrematureCompletion(
    positionMillis: Long,
    durationMillis: Long,
    positionFraction: Float,
    seekable: Boolean,
): Boolean {
    if (positionMillis < 0) return false
    if (durationMillis > 0) {
        val endTolerance = minOf(5_000L, durationMillis / 2)
        return positionMillis < durationMillis - endTolerance
    }
    return seekable && positionMillis > 0 && positionFraction.isFinite() &&
        positionFraction >= 0f && positionFraction < 0.98f
}

internal fun resumeTarget(positionMillis: Long, durationMillis: Long, seekable: Boolean): Long? {
    if (!seekable) return null
    return if (durationMillis > 0) positionMillis.coerceIn(0, durationMillis)
    else positionMillis.coerceAtLeast(0)
}
