package cz.primevoice.sync
import org.junit.Assert.*
import org.junit.Test

class SpeakerRegistryTest {
    private fun axis(index: Int, dimensions: Int = 8) = FloatArray(dimensions) { if (it == index) 1f else 0f }
    @Test fun noModelOutputMeansUnknown() {
        val registry = SpeakerRegistry()
        assertEquals(SpeakerRegistry.UNKNOWN, registry.identify(null, 1_000))
        assertEquals(SpeakerRegistry.UNKNOWN, registry.identify(floatArrayOf(1f, 0f), 399))
        assertEquals("speaker-1", registry.identify(floatArrayOf(1f, 0f), 400))
    }
    @Test fun scaleDoesNotChangeIdentity() {
        val registry = SpeakerRegistry(); val first = registry.identify(floatArrayOf(1f, 0f), 400)
        assertEquals(first, registry.identify(floatArrayOf(100f, 0f), 400))
        assertEquals(first, registry.identify(floatArrayOf(0.001f, 0f), 400))
    }
    @Test fun twoAndThreeDistinctVoicesRemainSeparate() {
        val registry = SpeakerRegistry()
        repeat(3) { assertEquals("speaker-${it + 1}", registry.identify(axis(it), 400)) }
        repeat(3) { assertEquals("speaker-${it + 1}", registry.identify(axis(it), 800)) }
    }
    @Test fun invalidEmbeddingDoesNotCreateOrAlterIdentity() {
        val registry = SpeakerRegistry()
        listOf(floatArrayOf(), floatArrayOf(0f, 0f), floatArrayOf(Float.NaN, 1f),
            floatArrayOf(Float.POSITIVE_INFINITY, 0f), FloatArray(4_097) { 1f })
            .forEach { assertEquals(SpeakerRegistry.UNKNOWN, registry.identify(it, 400)) }
        assertEquals("speaker-1", registry.identify(axis(0), 400))
        assertEquals(SpeakerRegistry.UNKNOWN, registry.identify(floatArrayOf(1f), 400))
        assertEquals("speaker-1", registry.identify(axis(0), 400))
    }
    @Test fun greyZoneIsUnknownInsteadOfInventingSpeaker() {
        val registry = SpeakerRegistry()
        assertEquals("speaker-1", registry.identify(floatArrayOf(1f, 0f), 400))
        assertEquals(SpeakerRegistry.UNKNOWN, registry.identify(floatArrayOf(0.6f, 0.8f), 400))
        assertEquals("speaker-2", registry.identify(floatArrayOf(0f, 1f), 400))
    }
    @Test fun equallyPlausibleCentroidsAreUnknownAndNotUpdated() {
        val registry = SpeakerRegistry(matchThreshold = 0.70)
        registry.identify(floatArrayOf(1f, 0f), 400); registry.identify(floatArrayOf(0f, 1f), 400)
        repeat(10) { assertEquals(SpeakerRegistry.UNKNOWN, registry.identify(floatArrayOf(1f, 1f), 400)) }
        assertEquals("speaker-1", registry.identify(floatArrayOf(1f, 0f), 400))
        assertEquals("speaker-2", registry.identify(floatArrayOf(0f, 1f), 400))
    }
    @Test fun ninthSpeakerStaysUnknownAndExistingMatchesStillWork() {
        val registry = SpeakerRegistry()
        repeat(8) { assertEquals("speaker-${it + 1}", registry.identify(axis(it, 9), 400)) }
        assertEquals(SpeakerRegistry.UNKNOWN, registry.identify(axis(8, 9), 400))
        assertEquals("speaker-1", registry.identify(axis(0, 9), 400))
    }
    @Test fun registryDoesNotRetainMutableCallerArrays() {
        val registry = SpeakerRegistry(); val vector = floatArrayOf(1f, 0f)
        registry.identify(vector, 400); vector[0] = 0f; vector[1] = 1f
        assertEquals("speaker-1", registry.identify(floatArrayOf(1f, 0f), 400))
        assertEquals("speaker-2", registry.identify(floatArrayOf(0f, 1f), 400))
    }
    @Test fun extremeFiniteMagnitudeStillNormalizes() {
        val registry = SpeakerRegistry()
        assertEquals("speaker-1", registry.identify(floatArrayOf(Float.MAX_VALUE, 0f), 400))
        assertEquals("speaker-1", registry.identify(floatArrayOf(1f, 0f), 400))
    }
    @Test fun resetForgetsPreviousModelDimensionsAndSpeakerIds() {
        val registry = SpeakerRegistry()
        registry.identify(axis(0), 400); registry.identify(axis(1), 400); registry.reset()
        assertEquals("speaker-1", registry.identify(floatArrayOf(0f, 1f), 400))
    }
    @Test fun invalidConfigurationFailsFast() {
        assertThrows(IllegalArgumentException::class.java) { SpeakerRegistry(maxSpeakers = 9) }
        assertThrows(IllegalArgumentException::class.java) { SpeakerRegistry(minFrames = 399) }
        assertThrows(IllegalArgumentException::class.java) { SpeakerRegistry(matchThreshold = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { SpeakerRegistry(ambiguityMargin = 0.0) }
    }
}
