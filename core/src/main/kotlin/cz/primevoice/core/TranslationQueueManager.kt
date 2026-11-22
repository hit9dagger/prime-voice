package cz.primevoice.core

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

enum class Stage { TRANSLATING, READY, SPEAKING, FAILED }
data class Dialogue(val id: Long, val subtitle: Subtitle, val czech: String = "",
                    val stage: Stage = Stage.TRANSLATING, val translationMs: Long = 0,
                    val error: String? = null)
data class QueueSnapshot(val items: List<Dialogue> = emptyList(), val enabled: Boolean = false,
                         val initialBufferRemainingMs: Long = 0, val spoken: Int = 0,
                         val skipped: Int = 0, val duplicates: Int = 0,
                         val lastSpoken: Dialogue? = null, val lastReady: Dialogue? = null,
                         val lastStartLatencyMs: Long = 0) {
    val waiting get() = items.count { it.stage != Stage.SPEAKING }
}
fun interface Translator { suspend fun translate(text: String): String }
fun interface Speaker { suspend fun speak(text: String) }

/** Bounded, ordered queue. Translation workers may finish in any order; only the head may speak. */
class TranslationQueueManager(
    private val scope: CoroutineScope,
    private val translator: Translator,
    private val speaker: Speaker,
    private val clock: () -> Long,
    val capacity: Int = 40,
    private val translationTimeoutMs: Long = 15_000,
    private val onSpoken: (Dialogue) -> Unit = {}
) {
    private val mutex = Mutex()
    private val permits = Semaphore(2)
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val mutable = MutableStateFlow(QueueSnapshot())
    val state = mutable.asStateFlow()
    private val jobs = mutableMapOf<Long, Job>()
    private var nextId = 0L
    private var delayMs = 0L
    private var firstReadyAt: Long? = null
    private var buffered = false
    private var outputAllowed = true
    private val recent = ArrayDeque<Pair<String, Long>>()

    init { scope.launch { for (ignored in signal) { while (playOne()) { /* one speaker */ } } } }

    suspend fun start(initialDelaySeconds: Int) = mutex.withLock {
        if (!mutable.value.enabled) {
            delayMs = initialDelaySeconds.coerceIn(0, 10) * 1000L
            firstReadyAt = null; buffered = false
        }
        mutable.value = mutable.value.copy(enabled = true)
        signal.trySend(Unit)
    }
    suspend fun pause() = mutex.withLock {
        mutable.value = mutable.value.copy(enabled = false, initialBufferRemainingMs = 0)
        signal.trySend(Unit)
    }
    suspend fun allowOutput(allowed: Boolean) = mutex.withLock {
        outputAllowed = allowed; signal.trySend(Unit)
    }

    /** False means backpressure: caller retains the subtitle and stops OCR until space is free. */
    suspend fun offer(subtitle: Subtitle): Boolean = mutex.withLock {
        val now = clock(); val key = TextRules.key(subtitle.text)
        while (recent.isNotEmpty() && now - recent.first().second > 18_000) recent.removeFirst()
        if (recent.any { it.first == key }) {
            mutable.value = mutable.value.copy(duplicates = mutable.value.duplicates + 1)
            return@withLock true
        }
        if (mutable.value.items.size >= capacity) return@withLock false
        val item = Dialogue(++nextId, subtitle)
        recent.addLast(key to now)
        mutable.value = mutable.value.copy(items = mutable.value.items + item)
        launchTranslation(item)
        true
    }

    suspend fun replayLast(): Boolean = mutex.withLock {
        val previous = mutable.value.lastSpoken ?: return@withLock false
        if (mutable.value.items.size >= capacity) return@withLock false
        val replay = previous.copy(id = ++nextId, stage = Stage.READY,
            subtitle = previous.subtitle.copy(capturedAt = clock(), completedAt = clock()))
        mutable.value = mutable.value.copy(items = mutable.value.items + replay)
        signal.trySend(Unit); true
    }

    suspend fun retryFailed() = mutex.withLock {
        mutable.value.items.filter { it.stage == Stage.FAILED }.forEach { old ->
            val retry = old.copy(stage = if (old.czech.isBlank()) Stage.TRANSLATING else Stage.READY, error = null)
            replace(retry)
            if (retry.stage == Stage.TRANSLATING) launchTranslation(retry)
        }
        signal.trySend(Unit)
    }

    /** Explicit user action. Never removes, cancels or stops the currently speaking item. */
    suspend fun skipWaiting() = mutex.withLock {
        val waiting = mutable.value.items.filter { it.stage != Stage.SPEAKING }
        waiting.forEach { jobs.remove(it.id)?.cancel() }
        mutable.value = mutable.value.copy(items = mutable.value.items.filter { it.stage == Stage.SPEAKING },
            skipped = mutable.value.skipped + waiting.size)
        signal.trySend(Unit)
    }

    private fun launchTranslation(item: Dialogue) {
        jobs[item.id] = scope.launch {
            var started = clock()
            try {
                val result = permits.withPermit {
                    started = clock()
                    var translated: String? = null
                    var failure: Exception? = null
                    repeat(2) { attempt ->
                        if (translated == null) {
                            try { translated = withTimeout(translationTimeoutMs) { translator.translate(item.subtitle.text) } }
                            catch (e: TimeoutCancellationException) { failure = IllegalStateException("Překlad překročil časový limit", e) }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { failure = e }
                            if (translated == null && attempt == 0) delay(450)
                        }
                    }
                    translated?.takeIf { it.isNotBlank() } ?: throw (failure ?: IllegalStateException("Prázdný překlad"))
                }
                mutex.withLock {
                    if (mutable.value.items.any { it.id == item.id }) {
                        val ready = item.copy(czech = result, stage = Stage.READY, translationMs = clock() - started)
                        replace(ready)
                        mutable.value = mutable.value.copy(lastReady = ready)
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                mutex.withLock { replace(item.copy(stage = Stage.FAILED, error = e.message ?: "Překlad selhal")) }
            } finally {
                withContext(NonCancellable) { mutex.withLock { jobs.remove(item.id) } }
                signal.trySend(Unit)
            }
        }
    }

    private suspend fun playOne(): Boolean {
        val selected = mutex.withLock {
            val s = mutable.value
            val head = s.items.firstOrNull() ?: return@withLock null
            if (!s.enabled || !outputAllowed || head.stage != Stage.READY) return@withLock null
            if (!buffered) {
                if (firstReadyAt == null) firstReadyAt = clock()
                val remaining = (delayMs - (clock() - firstReadyAt!!)).coerceAtLeast(0)
                mutable.value = s.copy(initialBufferRemainingMs = remaining)
                if (remaining > 0) return@withLock head to false
                buffered = true
            }
            replace(head.copy(stage = Stage.SPEAKING))
            mutable.value = mutable.value.copy(lastStartLatencyMs = clock() - head.subtitle.capturedAt)
            head to true
        } ?: return false
        if (!selected.second) { delay(50); return true }
        val head = selected.first
        try {
            speaker.speak(head.czech)
            mutex.withLock {
                mutable.value = mutable.value.copy(items = mutable.value.items.filterNot { it.id == head.id },
                    spoken = mutable.value.spoken + 1, lastSpoken = head)
            }
            onSpoken(head)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            mutex.withLock { replace(head.copy(stage = Stage.FAILED, error = e.message ?: "Přehrávání selhalo")) }
        }
        return true
    }

    private fun replace(item: Dialogue) {
        mutable.value = mutable.value.copy(items = mutable.value.items.map { if (it.id == item.id) item else it })
    }
}
