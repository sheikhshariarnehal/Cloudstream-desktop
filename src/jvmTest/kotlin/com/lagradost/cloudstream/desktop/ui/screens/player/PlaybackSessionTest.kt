package com.lagradost.cloudstream.desktop.ui.screens.player

import com.lagradost.cloudstream.desktop.ui.navigation.StreamSourceInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackSessionTest {
    private val primary = StreamSourceInfo("Primary", "https://example.test/stream", 1080)
    private val mirror = primary.copy(name = "Mirror", url = "https://example.test/mirror")

    @Test
    fun newEpisodeStartsAtZeroEvenWhenItReusesAPausedSource() {
        val session = PlaybackSession(primary, 18_000)
        assertEquals(18_000L, session.request.resumePositionMillis)
        session.selectSource(mirror, 24_000, true)
        val old = session.request

        assertTrue(session.beginEpisode())
        assertTrue(session.loadingEpisode)
        assertFalse(session.beginEpisode())
        assertEquals(old, session.request)
        session.startEpisode(mirror)
        session.finishEpisodeLoad()

        assertEquals(PlaybackRequest(mirror, 0, false, old.generation + 1), session.request)
        assertFalse(session.loadingEpisode)
    }

    @Test
    fun failedExtractionKeepsCurrentRequestAndAllowsAnotherAttempt() {
        val session = PlaybackSession(primary)
        val playing = session.request
        session.beginEpisode()
        session.finishEpisodeLoad()
        assertEquals(playing, session.request)
        assertTrue(session.beginEpisode())
    }

    @Test
    fun cosmeticSourceChangesAreIgnoredButNewHeadersRestartAtThePausedPosition() {
        val session = PlaybackSession(primary)
        val playing = session.request
        assertFalse(session.selectSource(primary.copy(name = "Renamed", quality = 720), 15_000, true))
        assertEquals(playing, session.request)

        val authenticated = primary.copy(headers = mapOf("Referer" to "https://example.test/"))
        assertTrue(session.selectSource(authenticated, 15_000, true))
        assertEquals(PlaybackRequest(authenticated, 15_000, true, playing.generation + 1), session.request)
        assertTrue(session.selectSource(mirror, -1, false))
        assertEquals(0L, session.request.resumePositionMillis)
    }

    @Test
    fun restartingAfterAnUnconfirmedSubtitleKeepsSourcePositionAndPauseIntent() {
        val session = PlaybackSession(primary)
        val previous = session.request.generation
        session.restartSource(25_000, true)
        assertEquals(PlaybackRequest(primary, 25_000, true, previous + 1), session.request)
        assertFalse(session.consumeCompletion(previous))
        assertTrue(session.consumeCompletion(session.request.generation))
        session.restartSource(0, false)
        assertEquals(PlaybackRequest(primary, 0, false, previous + 2), session.request)
        assertTrue(session.consumeCompletion(session.request.generation))
    }

    @Test
    fun failoverOnlyTriesRemainingMirrorsFromTheCurrentEpisode() {
        val session = PlaybackSession(primary)
        val nextEpisode = primary.copy(url = "https://example.test/episode-2")
        session.startEpisode(nextEpisode)
        val playing = session.request
        assertFalse(session.failover(listOf(primary, mirror), 30_000, true))
        assertEquals(playing, session.request)

        val sources = listOf(nextEpisode.copy(name = "Alias"), nextEpisode, mirror, nextEpisode)
        assertTrue(session.failover(sources, 30_000, true))
        val switched = PlaybackRequest(mirror, 30_000, true, playing.generation + 1)
        assertEquals(switched, session.request)
        assertFalse(session.failover(sources, 32_000, false))
        assertEquals(switched, session.request)
    }


    @Test
    fun recoveryReloadsTheExactSourceOnceThenMovesForwardWithoutWrapping() {
        val authenticated = primary.copy(headers = mapOf("Referer" to "https://example.test/authenticated"))
        val session = PlaybackSession(authenticated)
        val last = mirror.copy(name = "Last", url = "https://example.test/last")
        val sources = listOf(primary, authenticated.copy(name = "Alias"), authenticated, mirror, last)

        assertEquals(PlaybackRecovery.RELOADED, session.recoverPlayback(sources, 10_655, false))
        assertEquals(PlaybackRequest(authenticated, 10_655, false, 1), session.request)
        assertFalse(session.consumeCompletion(0))
        assertEquals(PlaybackRecovery.SWITCHED_SOURCE, session.recoverPlayback(sources, 11_902, false))
        assertEquals(PlaybackRequest(mirror, 11_902, false, 2), session.request)
        assertEquals(PlaybackRecovery.SWITCHED_SOURCE, session.recoverPlayback(sources, 14_189, false))
        val exhausted = PlaybackRequest(last, 14_189, false, 3)
        assertEquals(exhausted, session.request)
        assertEquals(PlaybackRecovery.EXHAUSTED, session.recoverPlayback(sources, 20_000, false))
        assertEquals(exhausted, session.request)
    }

    @Test
    fun pausedPlaybackAndEpisodeLoadingDoNotSpendRecoveryBudget() {
        val session = PlaybackSession(primary, 10_655)
        val original = session.request
        val sources = listOf(primary, mirror)

        assertEquals(PlaybackRecovery.IGNORED, session.recoverPlayback(sources, 45_000, true))
        assertTrue(session.beginEpisode())
        assertEquals(PlaybackRecovery.IGNORED, session.recoverPlayback(sources, 45_000, false))
        assertEquals(original, session.request)

        session.finishEpisodeLoad()
        assertEquals(PlaybackRecovery.RELOADED, session.recoverPlayback(sources, 10_655, false))
        assertEquals(PlaybackRequest(primary, 10_655, false, 1), session.request)
    }

    @Test
    fun startingAnEpisodeOrResettingRecoveryAllowsOneNewReload() {
        val session = PlaybackSession(primary)
        val sources = listOf(primary)
        assertEquals(PlaybackRecovery.RELOADED, session.recoverPlayback(sources, 1_000, false))
        assertEquals(PlaybackRecovery.EXHAUSTED, session.recoverPlayback(sources, 2_000, false))

        session.startEpisode(primary)
        assertEquals(PlaybackRecovery.RELOADED, session.recoverPlayback(sources, 3_000, false))
        assertEquals(PlaybackRequest(primary, 3_000, false, 3), session.request)
        assertEquals(PlaybackRecovery.EXHAUSTED, session.recoverPlayback(sources, 4_000, false))

        session.resetRecovery()
        assertEquals(PlaybackRecovery.RELOADED, session.recoverPlayback(sources, -1, false))
        assertEquals(PlaybackRequest(primary, 0, false, 4), session.request)
    }

    @Test
    fun onlyTheFirstCompletionFromTheActiveGenerationCanAdvance() {
        val session = PlaybackSession(primary)
        val previous = session.request.generation
        session.beginEpisode()
        assertFalse(session.consumeCompletion(previous))
        session.startEpisode(mirror)
        session.finishEpisodeLoad()
        assertFalse(session.consumeCompletion(previous))
        val current = session.request.generation
        assertTrue(session.consumeCompletion(current))
        assertFalse(session.consumeCompletion(current))
    }

    @Test
    fun delayedNativeStartupDoesNotConfirmALongResumeUntilItReachesTheTarget() {
        val target = 40 * 60_000L
        assertFalse(resumePositionConfirmed(target, 0))
        assertFalse(resumePositionConfirmed(target, target - 2_001))
        assertTrue(resumePositionConfirmed(target, target - 2_000))
        assertTrue(resumePositionConfirmed(target, target + 2_000))
        assertFalse(resumePositionConfirmed(target, target + 2_001))
        assertFalse(resumePositionConfirmed(0, -1))
        assertTrue(resumePositionConfirmed(0, 12_000))
        assertTrue(resumePositionConfirmed(Long.MAX_VALUE, Long.MAX_VALUE - 1))
        assertFalse(resumePositionConfirmed(Long.MAX_VALUE, Long.MIN_VALUE))
    }

    @Test
    fun seekRequiresASeekableStreamWithAKnownDuration() {
        assertNull(seekTarget(3_000, 20_000, false, 10_000))
        assertNull(seekTarget(3_000, 0, true, 10_000))
        assertNull(seekTarget(3_000, -1, true, -10_000))
    }

    @Test
    fun feedbackShowsTheActualDistanceToTheBoundary() {
        val forward = seekTarget(18_500, 20_000, true, 10_000)
        assertEquals(20_000L, forward)
        assertEquals("+1.5s", seekIndicator(forward!! - 18_500))
        val back = seekTarget(3_000, 20_000, true, -10_000)
        assertEquals(0L, back)
        assertEquals("-3s", seekIndicator(back!! - 3_000))
        assertEquals(0L, seekTarget(0, 20_000, true, -10_000))
    }

    @Test
    fun toggleNeedsOneMatchingReleaseAndForgetsPressesAfterFocusLoss() {
        val latch = ToggleKeyLatch<String>()
        assertFalse(latch.release("space"))
        latch.press("space")
        latch.press("space")
        assertFalse(latch.release("mute"))
        assertTrue(latch.release("space"))
        assertFalse(latch.release("space"))
        latch.press("mute")
        latch.clear()
        assertFalse(latch.release("mute"))
        latch.press("mute")
        assertTrue(latch.release("mute"))
    }
}
