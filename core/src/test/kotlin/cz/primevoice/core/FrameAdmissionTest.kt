package cz.primevoice.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class FrameAdmissionTest {
    @Test fun skippedFastFrameDoesNotLockOutOcrOrTranslation() = runTest {
        val gate = InferenceGate(); val admission = FrameAdmission(gate)
        admission.acquire(0, 400, true)!!.close()
        // 2.0 acquired the semaphore here, then returned without releasing it.
        repeat(10) { assertNull(admission.acquire(30L + it * 30, 400, true)) }
        withTimeout(1000) { assertEquals("překlad", gate.run { "překlad" }) }
        admission.acquire(400, 400, true)!!.close()
        assertFalse(admission.inFlight)
    }

    @Test fun pausedAndThermalFramesDoNotAcquirePermit() = runTest {
        val gate = InferenceGate(); val a = FrameAdmission(gate)
        repeat(1000) { assertNull(a.acquire(it.toLong(), 400, false)) }
        assertNull(a.acquire(2000, Long.MAX_VALUE, true))
        withTimeout(1000) { gate.run { } }
        a.acquire(2001, 400, true)!!.close()
    }

    @Test fun rejectedFrameCannotClearActiveOcrOwnership() = runTest {
        val gate = InferenceGate(); val a = FrameAdmission(gate)
        val first = a.acquire(0, 400, true)!!
        assertNull(a.acquire(500, 400, true))
        assertTrue(a.inFlight)
        assertFalse(gate.tryCamera())
        first.close(); first.close() // idempotent even with cancellation + completion callbacks
        a.acquire(501, 400, true)!!.close()
    }

    @Test fun translationContentionAndSessionRestartDoNotResetActiveOwner() = runTest {
        val gate = InferenceGate(); val a = FrameAdmission(gate)
        gate.run { assertNull(a.acquire(0, 400, true)); assertFalse(a.inFlight) }
        val first = a.acquire(0, 400, true)!!
        a.resetCadence()
        assertNull(a.acquire(10, 400, true))
        first.close()
        a.acquire(11, 400, true)!!.close()
    }

    @Test fun failureReleasesLeaseForFollowingTranslation() = runTest {
        val gate = InferenceGate(); val a = FrameAdmission(gate)
        runCatching { a.acquire(0, 400, true)!!.use { error("OCR failed") } }
        withTimeout(1000) { gate.run { } }
        assertFalse(a.inFlight)
    }
}
