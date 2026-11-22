package cz.primevoice.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ExecutionPolicyTest {
    private fun phone(total: Long = 12 * GIB, available: Long = 7 * GIB, free: Long = 20 * GIB,
        linked: Boolean = true, lowRam: Boolean = false, lowMemory: Boolean = false) =
        DeviceResources(total, available, free, lowRam, lowMemory, linked)

    @Test fun fourGbAlwaysStaysLightEvenAfterManualUltraSelection() {
        val d = phone(total = 4 * GIB, available = 3 * GIB)
        assertTrue(ExecutionPolicy.economical(d))
        assertNotNull(ExecutionPolicy.rejection(ExecutionMode.ULTRA, d, 3 * GIB, true))
        assertNotNull(ExecutionPolicy.rejection(ExecutionMode.STANDARD, d, 600 * MIB, true))
        assertEquals(ExecutionMode.LIGHT, ExecutionPolicy.recommend(d, ExecutionMode.entries.toSet()))
    }
    @Test fun actualRamBelowAdvertisedFourGbStillUsesLight() {
        assertTrue(ExecutionPolicy.economical(phone(total = 3500 * MIB)))
    }
    @Test fun androidLowRamFlagOverridesPhysicalCapacity() {
        assertNotNull(ExecutionPolicy.rejection(ExecutionMode.STANDARD, phone(lowRam = true), 0, true))
    }
    @Test fun strongPhoneNeverRecommendsUntestedModel() {
        assertEquals(ExecutionMode.LIGHT, ExecutionPolicy.recommend(phone(), emptySet()))
    }
    @Test fun testedStandardIsRecommendedOnSixGb() {
        assertEquals(ExecutionMode.STANDARD, ExecutionPolicy.recommend(phone(total = 6 * GIB, available = 3 * GIB), setOf(ExecutionMode.STANDARD)))
    }
    @Test fun testedUltraNeedsBothTotalAndFreeRam() {
        assertEquals(ExecutionMode.ULTRA, ExecutionPolicy.recommend(phone(), setOf(ExecutionMode.ULTRA)))
        assertEquals(ExecutionMode.LIGHT, ExecutionPolicy.recommend(phone(available = GIB), setOf(ExecutionMode.ULTRA)))
    }
    @Test fun unsupportedNativeAbiFallsBack() {
        assertEquals(ExecutionMode.LIGHT, ExecutionPolicy.recommend(phone(linked = false), ExecutionMode.entries.toSet()))
    }
    @Test fun storageMustIncludeModelAndWorkingReserve() {
        assertNotNull(ExecutionPolicy.rejection(ExecutionMode.ULTRA, phone(free = 4 * GIB), 3655827456L, false))
        assertNull(ExecutionPolicy.rejection(ExecutionMode.ULTRA, phone(free = 4 * GIB), 3655827456L, true))
    }
    @Test fun missingStorageCannotBreakLight() {
        assertNull(ExecutionPolicy.rejection(ExecutionMode.LIGHT, phone(free = 0), 0, false))
    }
    @Test fun systemLowMemoryAndActualFreeMemoryTriggerPressure() {
        assertTrue(ExecutionPolicy.underPressure(phone(lowMemory = true)))
        assertTrue(ExecutionPolicy.underPressure(phone(available = 500 * MIB)))
        assertFalse(ExecutionPolicy.underPressure(phone()))
    }
    @Test fun lightBudgetCapsResolutionAndSampling() {
        val b = ExecutionPolicy.capture(ExecutionMode.LIGHT, phone())
        assertEquals(1280, b.width); assertEquals(720, b.height)
        assertTrue(b.cropWidth <= 960); assertTrue(b.intervalMs >= 400)
    }
    @Test fun pressureAlsoRestrictsUltraCapture() {
        assertEquals(720, ExecutionPolicy.capture(ExecutionMode.ULTRA, phone(lowMemory = true)).height)
    }
    @Test fun translationAndOcrNeverOwnPermitTogether() = runTest {
        val gate = InferenceGate(); var finished = false
        assertTrue(gate.tryCamera())
        val job = launch { gate.run(heavy = true) { assertFalse(gate.tryCamera()); finished = true } }
        runCurrent(); assertFalse(finished); assertFalse(gate.heavy.value)
        gate.releaseCamera(); job.join()
        assertTrue(finished); assertFalse(gate.heavy.value)
        assertTrue(gate.tryCamera()); gate.releaseCamera()
    }
    @Test fun canceledInferenceReturnsPermitAndResetsHeavyFlag() = runTest {
        val gate = InferenceGate()
        val job = launch { gate.run(heavy = true) { awaitCancellation() } }
        runCurrent(); assertTrue(gate.heavy.value)
        job.cancelAndJoin()
        assertFalse(gate.heavy.value); assertTrue(gate.tryCamera()); gate.releaseCamera()
    }
    @Test fun inferenceFailureReleasesCamera() = runTest {
        val gate = InferenceGate()
        assertFailsWith<IllegalStateException> { gate.run(heavy = true) { error("Native engine failed") } }
        assertTrue(gate.tryCamera()); gate.releaseCamera()
    }
    @Test fun probeRejectsEnglishAndWrongNegation() {
        assertFalse(LocalTranslationChecks.passesProbe(0, "Open the door."))
        assertFalse(LocalTranslationChecks.passesProbe(0, "Otevři dveře."))
        assertTrue(LocalTranslationChecks.passesProbe(0, "Neotevírej dveře."))
        assertTrue(LocalTranslationChecks.passesProbe(1, "Jasone, kde jsi?"))
    }
    @Test fun outputGuardRejectsReasoningAndUntranslatedBlocks() {
        assertFalse(LocalTranslationChecks.acceptable("Do not open the door.", "Do not open the door."))
        assertFalse(LocalTranslationChecks.acceptable("Hello", "<think>Reasoning</think>Ahoj"))
        assertTrue(LocalTranslationChecks.acceptable("Do not open the door.", "Neotvírej dveře."))
    }
    @Test fun longDialogueCannotFillMemoryWithUnlimitedGeneration() {
        assertFalse(LocalTranslationChecks.acceptable("Hi", "a".repeat(2000)))
    }
}
