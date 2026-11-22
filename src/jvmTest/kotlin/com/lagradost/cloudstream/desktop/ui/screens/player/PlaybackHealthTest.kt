package com.lagradost.cloudstream.desktop.ui.screens.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackHealthTest {
    @Test
    fun nativeVideoStallRequiresFortyFiveSecondsWithoutProgress() {
        val detector = PlaybackStallDetector()
        assertNull(detector.sample(0, 0, 0, 0, 0, true))
        assertNull(detector.sample(1_000, 1_000, 1, 1, 1, true))
        assertNull(detector.sample(31_000, 31_000, 1, 31, 31, true))
        assertNull(detector.sample(45_999, 45_999, 1, 45, 45, true))
        assertEquals(PlaybackStall.VIDEO, detector.sample(46_000, 46_000, 1, 46, 46, true))
    }

    @Test
    fun stalledVideoPresentationCanBeDetectedWhileNativeVideoKeepsAdvancing() {
        val detector = PlaybackStallDetector()
        detector.sample(0, 0, 1, 1, 1, true)
        detector.sample(1_000, 1_000, 2, 2, 2, true)

        assertEquals(PlaybackStall.VIDEO, detector.sample(46_000, 46_000, 46, 46, 2, true))
    }

    @Test
    fun audioStallIsDetectedWhileVideoAndTimelineAdvance() {
        val detector = PlaybackStallDetector()
        detector.sample(0, 0, 0, 0, 0, true)
        detector.sample(1_000, 1_000, 1, 1, 1, true)

        assertEquals(PlaybackStall.AUDIO, detector.sample(46_000, 46_000, 46, 1, 46, true))
    }

    @Test
    fun bothNativeOutputsStallingMarksTheStreamEvenWhenVideoCallbacksRepeat() {
        val detector = PlaybackStallDetector()
        detector.sample(0, 0, 1, 1, 1, true)
        detector.sample(1_000, 1_000, 2, 2, 2, true)

        assertEquals(PlaybackStall.STREAM, detector.sample(46_000, 46_000, 2, 2, 46, true))
    }

    @Test
    fun onlyObservedProgressCanArmAStallTimer() {
        val detector = PlaybackStallDetector()
        detector.sample(0, 0, 0, 12, 6, true)
        assertNull(detector.sample(46_000, 0, 0, 12, 6, true))
    }

    @Test
    fun timelineCanDetectAStallWhenNativeStatisticsNeverAdvance() {
        val detector = PlaybackStallDetector()
        detector.sample(0, 0, 0, null, 0, true)
        assertNull(detector.sample(1_000, 1_000, 0, null, 1, true))
        assertNull(detector.sample(31_000, 1_000, 0, null, 31, true))

        assertEquals(PlaybackStall.STREAM, detector.sample(46_000, 1_000, 0, null, 46, true))
    }

    @Test
    fun missingAndDecreasingCountersClearFormerProgressForThoseChannels() {
        val detector = PlaybackStallDetector()
        detector.sample(0, 0, 5, 5, 5, true)
        detector.sample(1_000, 1_000, 6, 6, 6, true)
        assertNull(detector.sample(46_000, 46_000, null, 1, null, true))
        assertNull(detector.sample(47_000, 47_000, 6, 1, 6, true))
        assertNull(detector.sample(93_000, 93_000, 6, 1, 6, true))
    }

    @Test
    fun inactivePlaybackAndExplicitResetDiscardPreviousSamples() {
        val detector = PlaybackStallDetector()
        detector.sample(0, 0, 0, 0, 0, true)
        detector.sample(1_000, 1_000, 1, 1, 1, true)
        assertNull(detector.sample(50_000, 1_000, 1, 1, 1, false))
        assertNull(detector.sample(50_500, 1_000, 1, 1, 1, true))
        assertNull(detector.sample(51_000, 1_500, 2, 2, 2, true))
        detector.reset()
        assertNull(detector.sample(97_000, 1_500, 2, 2, 2, true))
    }

    @Test
    fun seekingBackwardsOrAClockRewindStartsNewGrace() {
        val detector = PlaybackStallDetector()
        detector.sample(100_000, 30_000, 8, 8, 8, true)
        detector.sample(101_000, 31_000, 9, 9, 9, true)
        assertNull(detector.sample(146_000, 1_000, 9, 9, 9, true))
        assertNull(detector.sample(147_000, 2_000, 10, 10, 10, true))
        assertNull(detector.sample(2_000, 2_000, 10, 10, 10, true))
        assertNull(detector.sample(48_000, 2_000, 10, 10, 10, true))
    }

    @Test
    fun knownDurationDistinguishesEarlyCompletionFromNormalFinishingTolerance() {
        assertTrue(isPrematureCompletion(10_655, 39_700, 0.2684f, true))
        assertTrue(isPrematureCompletion(34_699, 39_700, 0.99f, false))
        assertFalse(isPrematureCompletion(34_700, 39_700, 0.9f, true))
        assertTrue(isPrematureCompletion(1_999, 4_000, 0.5f, true))
        assertFalse(isPrematureCompletion(2_000, 4_000, 0.5f, true))
        assertFalse(isPrematureCompletion(-1, 39_700, 0.1f, true))
    }

    @Test
    fun unknownDurationRequiresASeekableSourceWithUsablePositionEvidence() {
        assertTrue(isPrematureCompletion(14_189, 0, 0.36f, true))
        assertTrue(isPrematureCompletion(14_189, -1, 0f, true))
        assertFalse(isPrematureCompletion(14_189, 0, 0.98f, true))
        assertFalse(isPrematureCompletion(14_189, 0, Float.NaN, true))
        assertFalse(isPrematureCompletion(14_189, 0, Float.POSITIVE_INFINITY, true))
        assertFalse(isPrematureCompletion(14_189, 0, -0.01f, true))
        assertFalse(isPrematureCompletion(0, 0, 0.36f, true))
        assertFalse(isPrematureCompletion(14_189, 0, 0.36f, false))
    }

    @Test
    fun recoveryResumeSupportsSeekableMediaWithoutAKnownDuration() {
        assertEquals(12_822L, resumeTarget(12_822, 0, true))
        assertEquals(0L, resumeTarget(-1, -1, true))
        assertEquals(39_700L, resumeTarget(50_000, 39_700, true))
        assertEquals(0L, resumeTarget(-1, 39_700, true))
        assertNull(resumeTarget(12_822, 0, false))
        assertNull(resumeTarget(12_822, 39_700, false))
    }
}
