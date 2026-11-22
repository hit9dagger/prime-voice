package cz.primevoice.core

import java.util.concurrent.atomic.AtomicBoolean

/** Only an admitted frame owns the shared inference permit. Dropped frames own nothing. */
class FrameAdmission(private val gate: InferenceGate) {
    @Volatile var inFlight = false
        private set
    @Volatile var skippedBecause = ""
        private set
    private var lastSample: Long? = null

    @Synchronized fun resetCadence() { lastSample = null }

    @Synchronized fun acquire(now: Long, interval: Long, enabled: Boolean): Lease? {
        val reason = when {
            !enabled -> "pozastaveno"
            interval == Long.MAX_VALUE -> "teplota telefonu"
            inFlight -> "probíhá OCR"
            lastSample?.let { now - it < interval } == true -> "interval snímání"
            !gate.tryCamera() -> "probíhá překlad / AI"
            else -> null
        }
        if (reason != null) { skippedBecause = reason; return null }
        inFlight = true
        lastSample = now
        skippedBecause = ""
        return Lease {
            synchronized(this) {
                gate.releaseCamera()
                inFlight = false
            }
        }
    }

    class Lease internal constructor(private val release: () -> Unit) : AutoCloseable {
        private val closed = AtomicBoolean(false)
        override fun close() { if (closed.compareAndSet(false, true)) release() }
    }
}
