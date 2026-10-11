package cz.primevoice.sync
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.ceil

class SyncClockTest {
    @Test fun twoSimultaneousSpeakersShareTimelineWithoutGlobalSerialization() {
        val clock = SyncClock()
        val first = clock.schedule("speaker-1", 0, 500, 1_000, 2_000)
        val second = clock.schedule("speaker-2", 0, 500, 1_000, 2_000)
        assertEquals(first.startMs, second.startMs)
        assertEquals(1f, first.speed, 0f); assertEquals(1f, second.speed, 0f)
    }
    @Test fun threeVoicesMayOverlapAndEachLaneRetainsItsOwnFifo() {
        val clock = SyncClock()
        val slots = (1..3).map { clock.schedule("speaker-$it", 0, 500, 1_000, 3_000) }
        assertEquals(listOf(1_000L, 1_000L, 1_000L), slots.map { it.startMs })
        assertEquals(4_000L, clock.schedule("speaker-1", 600, 1_000, 1_600, 1_000).startMs)
        clock.markFinished("speaker-2", 4_200)
        assertEquals(4_200L, clock.schedule("speaker-2", 600, 1_000, 1_600, 1_000).startMs)
    }
    @Test fun rapidSameSpeakerSentencesNeverOverlap() {
        val clock = SyncClock(); var end = 0L
        for (index in 0 until 30) {
            val source = index * 100L
            val slot = clock.schedule("speaker-1", source, source + 100, source + 1_000, 2_000)
            assertTrue(slot.startMs >= end); assertTrue(slot.speed in 1f..1.35f)
            end = slot.startMs + ceil(2_000.0 / slot.speed.toDouble()).toLong()
        }
    }
    @Test fun longSentencesRespectSpeedCapWithoutDropping() {
        val clock = SyncClock()
        clock.schedule("speaker-1", 0, 500, 1_000, 60_000)
        val second = clock.schedule("speaker-1", 600, 1_000, 2_000, 60_000)
        assertEquals(61_000L, second.startMs); assertEquals(1.35f, second.speed, 0f)
        val third = clock.schedule("speaker-1", 1_100, 1_500, 2_500, 60_000)
        assertTrue(third.startMs > second.startMs); assertEquals(1.35f, third.speed, 0f)
    }
    @Test fun actualCompletionMovesTheNextItemPastAudioDeviceStalls() {
        val clock = SyncClock()
        clock.schedule("speaker-1", 0, 500, 1_000, 1_000); clock.markFinished("speaker-1", 8_000)
        assertEquals(8_000L, clock.schedule("speaker-1", 600, 1_000, 2_000, 1_000).startMs)
    }
    @Test fun actualEarlyCompletionAvoidsKeepingAStalePrediction() {
        val clock = SyncClock()
        clock.schedule("speaker-1", 0, 500, 1_000, 10_000); clock.markFinished("speaker-1", 2_000)
        assertEquals(2_100L, clock.schedule("speaker-1", 600, 1_000, 2_100, 1_000).startMs)
    }
    @Test fun routeFailureRetryReturnsOriginalReservation() {
        val clock = SyncClock()
        val first = clock.schedule("speaker-1", 0, 500, 1_000, 10_000)
        repeat(10) { assertEquals(first, clock.schedule("speaker-1", 0, 500, 20_000L + it, 10_000)) }
        assertEquals(1_000L, clock.targetDelayMs)
        clock.markFinished("speaker-1", 32_000)
        assertEquals(32_000L, clock.schedule("speaker-1", 600, 1_000, 32_000, 1_000).startMs)
    }
    @Test fun targetDelayIsBoundedButActualLatencyIsNotFalselyBounded() {
        val clock = SyncClock()
        val first = clock.schedule("speaker-1", 0, 500, 20_000, 1_000)
        assertEquals(4_000L, clock.targetDelayMs); assertEquals(20_000L, first.startMs); assertEquals(1.35f, first.speed, 0f)
        assertEquals(24_000L, clock.schedule("speaker-2", 20_000, 20_500, 21_000, 1_000).startMs)
        assertEquals(4_000L, clock.targetDelayMs)
    }
    @Test fun sourceSpacingIsPreservedForIndependentLanes() {
        val clock = SyncClock(initialDelayMs = 2_000, maxDelayMs = 4_000)
        val first = clock.schedule("speaker-1", 0, 500, 1_000, 500)
        val second = clock.schedule("speaker-2", 400, 900, 1_400, 500)
        assertEquals(400L, second.startMs - first.startMs)
    }
    @Test fun sourceOrderIsCheckedPerSpeakerNotGlobally() {
        val clock = SyncClock()
        clock.schedule("speaker-1", 10_000, 10_500, 11_000, 1_000)
        assertEquals(1_100L, clock.schedule("speaker-2", 100, 600, 1_100, 1_000).startMs)
        assertThrows(IllegalArgumentException::class.java) { clock.schedule("speaker-1", 9_000, 9_500, 11_000, 1_000) }
    }
    @Test fun invalidTimesAndKeysDoNotChangeClockState() {
        val clock = SyncClock()
        val invalid = listOf<() -> Unit>(
            { clock.schedule("", 0, 1, 1, 1) }, { clock.schedule("a", -1, 1, 1, 1) },
            { clock.schedule("a", 2, 1, 1, 1) }, { clock.schedule("a", 0, 1, -1, 1) },
            { clock.schedule("a", 0, 1, 1, 0) })
        invalid.forEach { call -> assertThrows(IllegalArgumentException::class.java) { call() }; assertEquals(800L, clock.targetDelayMs) }
        assertThrows(IllegalArgumentException::class.java) { clock.markFinished("missing", 1_000) }
    }
    @Test fun badCompletionDoesNotReleaseTheReservation() {
        val clock = SyncClock(); val first = clock.schedule("speaker-1", 0, 500, 1_000, 1_000)
        assertThrows(IllegalArgumentException::class.java) { clock.markFinished("speaker-1", 999) }
        assertEquals(first, clock.schedule("speaker-1", 0, 500, 3_000, 1_000))
    }
    @Test fun capacityIsBoundedAndUnknownHasItsOwnLane() {
        val clock = SyncClock(maxSpeakers = 9)
        repeat(8) { clock.schedule("speaker-${it + 1}", 0, 1, 1, 1) }
        clock.schedule(SpeakerRegistry.UNKNOWN, 0, 1, 1, 1)
        assertThrows(IllegalArgumentException::class.java) { clock.schedule("speaker-9", 0, 1, 1, 1) }
    }
    @Test fun arithmeticDoesNotWrapForExtremeTimestamps() {
        val clock = SyncClock()
        val slot = clock.schedule("speaker-1", Long.MAX_VALUE - 10, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE, slot.startMs); assertTrue(slot.speed in 1f..1.35f)
    }
    @Test fun resetBeginsANewTimeline() {
        val clock = SyncClock()
        clock.schedule("speaker-1", 10_000, 10_500, 20_000, 10_000); clock.reset()
        assertEquals(800L, clock.targetDelayMs)
        assertEquals(1_000L, clock.schedule("speaker-1", 0, 500, 1_000, 1_000).startMs)
    }
}
