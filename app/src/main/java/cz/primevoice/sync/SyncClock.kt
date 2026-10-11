package cz.primevoice.sync
import kotlin.math.ceil
import kotlin.math.max

data class SyncSlot(val startMs: Long, val speed: Float)

/**
 * Shared monotonic timeline, separate speaker lanes. Never owns or drops queue items.
 * schedule immediately before a lane is ready; markFinished only after actual drain.
 * Retry of the latest unfinished reservation is idempotent.
 * Bounded target delay is not a guarantee of bounded latency under overload.
 */
class SyncClock(
    private val initialDelayMs: Long = 800L, private val maxDelayMs: Long = 4_000L,
    private val maxSpeakers: Int = 64
) {
    companion object { const val MAX_SPEED = 1.35f; private const val CATCH_UP_WINDOW_MS = 8_000.0 }
    private data class Lane(var sourceStartMs: Long, var sourceEndMs: Long, var endMs: Long, var slot: SyncSlot, var finished: Boolean = false)
    private val lanes = mutableMapOf<String, Lane>()
    private var delayMs = initialDelayMs
    val targetDelayMs: Long get() = synchronized(this) { delayMs }
    init {
        require(initialDelayMs >= 0L)
        require(maxDelayMs >= initialDelayMs)
        require(maxSpeakers in 1..64) { "Bounded current and restored speaker lanes." }
    }
    @Synchronized fun schedule(speaker: String, sourceStartMs: Long, sourceEndMs: Long, readyMs: Long, durationMs: Long): SyncSlot {
        require(speaker.isNotBlank() && speaker.length <= 128)
        require(sourceStartMs >= 0L && sourceEndMs >= sourceStartMs)
        require(readyMs >= 0L && durationMs > 0L)
        val previous = lanes[speaker]
        if (previous != null) {
            require(sourceStartMs >= previous.sourceStartMs) { "Source order must be preserved within a speaker lane." }
            if (sourceStartMs == previous.sourceStartMs && sourceEndMs == previous.sourceEndMs && !previous.finished) return previous.slot
            require(sourceStartMs > previous.sourceStartMs) { "Source interval has already been scheduled." }
        } else require(lanes.size < maxSpeakers) { "Speaker lane capacity exceeded; apply backpressure." }
        val observedDelay = if (readyMs > sourceStartMs) readyMs - sourceStartMs else 0L
        val nextDelay = max(delayMs, observedDelay.coerceAtMost(maxDelayMs))
        val idealStart = saturatedAdd(sourceStartMs, nextDelay)
        val start = max(max(readyMs, idealStart), previous?.endMs ?: 0L)
        val lateMs = start - idealStart
        val speed = (1.0 + lateMs.toDouble() / CATCH_UP_WINDOW_MS).coerceIn(1.0, MAX_SPEED.toDouble()).toFloat()
        val playMs = ceil(durationMs.toDouble() / speed.toDouble()).coerceAtLeast(1.0).toLong()
        val end = saturatedAdd(start, playMs)
        val slot = SyncSlot(start, speed)
        delayMs = nextDelay
        lanes[speaker] = Lane(sourceStartMs, sourceEndMs, end, slot)
        return slot
    }
    @Synchronized fun markFinished(speaker: String, finishedMs: Long) {
        require(finishedMs >= 0L)
        val lane = requireNotNull(lanes[speaker]) { "No reservation for this speaker." }
        require(finishedMs >= lane.slot.startMs)
        lane.endMs = if (lane.finished) max(lane.endMs, finishedMs) else finishedMs
        lane.finished = true
    }
    @Synchronized fun reset() { lanes.clear(); delayMs = initialDelayMs }
    private fun saturatedAdd(left: Long, right: Long): Long = if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right
}
