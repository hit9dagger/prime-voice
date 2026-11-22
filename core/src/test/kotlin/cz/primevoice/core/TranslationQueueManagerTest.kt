package cz.primevoice.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Test
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class TranslationQueueManagerTest {
    private fun subtitle(text: String, at: Long = 0) = Subtitle(text, at, at, .9f, "test")

    @Test fun `first voice continues while next three subtitles arrive`() = runTest {
        val starts = mutableListOf<String>(); val ends = mutableListOf<String>()
        var concurrent = 0; var maximum = 0
        val q = TranslationQueueManager(backgroundScope, Translator { "cs:$it" }, Speaker {
            concurrent++; maximum = maxOf(maximum, concurrent); starts += it
            delay(2000); ends += it; concurrent--
        }, { testScheduler.currentTime })
        q.start(0); q.offer(subtitle("one")); runCurrent()
        assertEquals(listOf("cs:one"), starts)
        listOf("two", "three", "four").forEach { q.offer(subtitle(it)) }
        runCurrent(); advanceTimeBy(1999); runCurrent()
        assertEquals(listOf("cs:one"), starts); assertTrue(ends.isEmpty())
        advanceTimeBy(6500); runCurrent()
        assertEquals(listOf("cs:one", "cs:two", "cs:three", "cs:four"), starts)
        assertEquals(starts, ends); assertEquals(1, maximum); assertEquals(0, q.state.value.waiting)
    }
    @Test fun `slow first translation cannot be overtaken by fast second`() = runTest {
        val played = mutableListOf<String>()
        val q = TranslationQueueManager(backgroundScope, Translator { if (it == "one") delay(4000) else delay(10); it },
            Speaker { played += it }, { testScheduler.currentTime })
        q.start(0); q.offer(subtitle("one")); q.offer(subtitle("two")); runCurrent()
        advanceTimeBy(100); runCurrent(); assertTrue(played.isEmpty())
        assertEquals(Stage.READY, q.state.value.items[1].stage)
        advanceTimeBy(4000); runCurrent(); assertEquals(listOf("one", "two"), played)
    }
    @Test fun `initial delay applies once and not after queue becomes empty`() = runTest {
        val at = mutableListOf<Long>()
        val q = TranslationQueueManager(backgroundScope, Translator { it }, Speaker { at += testScheduler.currentTime; delay(200) },
            { testScheduler.currentTime })
        q.start(3); q.offer(subtitle("one")); q.offer(subtitle("two")); runCurrent()
        advanceTimeBy(2999); runCurrent(); assertTrue(at.isEmpty())
        advanceTimeBy(1000); runCurrent(); assertEquals(listOf(3000L, 3200L), at)
        q.offer(subtitle("three")); runCurrent()
        assertEquals(3999L, at.last())
    }
    @Test fun `zero delay starts as soon as translation is ready`() = runTest {
        var started: Long? = null
        val q = TranslationQueueManager(backgroundScope, Translator { delay(900); it }, Speaker { started = testScheduler.currentTime },
            { testScheduler.currentTime })
        q.start(0); q.offer(subtitle("hello")); advanceTimeBy(901); runCurrent(); assertEquals(900L, started)
    }
    @Test fun `ten second buffer begins after first translation is ready`() = runTest {
        var started: Long? = null
        val q = TranslationQueueManager(backgroundScope, Translator { delay(2000); it }, Speaker { started = testScheduler.currentTime },
            { testScheduler.currentTime })
        q.start(10); q.offer(subtitle("hello")); advanceTimeBy(11999); runCurrent(); assertNull(started)
        advanceTimeBy(2); runCurrent(); assertEquals(12000L, started)
    }
    @Test fun `skip waiting never interrupts active voice`() = runTest {
        val started = mutableListOf<String>(); var ended = false
        val q = TranslationQueueManager(backgroundScope, Translator { it }, Speaker { started += it; delay(5000); ended = true },
            { testScheduler.currentTime })
        q.start(0); q.offer(subtitle("one")); q.offer(subtitle("two")); runCurrent()
        q.skipWaiting(); assertEquals(1, q.state.value.items.size)
        advanceTimeBy(5001); runCurrent(); assertTrue(ended); assertEquals(listOf("one"), started)
        assertEquals(1, q.state.value.skipped)
    }
    @Test fun `translation timeout is visible and keeps order until explicit retry`() = runTest {
        var fail = true; val played = mutableListOf<String>()
        val q = TranslationQueueManager(backgroundScope, Translator { if (it == "one" && fail) delay(50_000); it },
            Speaker { played += it }, { testScheduler.currentTime }, translationTimeoutMs = 1000)
        q.start(0); q.offer(subtitle("one")); q.offer(subtitle("two")); advanceTimeBy(3000); runCurrent()
        assertEquals(Stage.FAILED, q.state.value.items.first().stage); assertTrue(played.isEmpty())
        fail = false; q.retryFailed(); runCurrent(); assertEquals(listOf("one", "two"), played)
    }
    @Test fun `transient translation failure retries automatically`() = runTest {
        var attempts = 0; val played = mutableListOf<String>()
        val q = TranslationQueueManager(backgroundScope, Translator { if (++attempts == 1) error("temporary"); it },
            Speaker { played += it }, { testScheduler.currentTime })
        q.start(0); q.offer(subtitle("one")); advanceTimeBy(500); runCurrent()
        assertEquals(listOf("one"), played); assertEquals(2, attempts)
    }
    @Test fun `output loss defers following speech and preserves active utterance`() = runTest {
        val played = mutableListOf<String>(); val ended = mutableListOf<String>()
        val q = TranslationQueueManager(backgroundScope, Translator { it }, Speaker { played += it; delay(2000); ended += it },
            { testScheduler.currentTime })
        q.start(0); q.offer(subtitle("one")); q.offer(subtitle("two")); runCurrent()
        q.allowOutput(false); advanceTimeBy(3000); runCurrent()
        assertEquals(listOf("one"), played); assertEquals(listOf("one"), ended)
        q.allowOutput(true); runCurrent(); assertEquals(listOf("one", "two"), played)
    }
    @Test fun `pause completes current sentence and retains waiting sentences`() = runTest {
        val played = mutableListOf<String>()
        val q = TranslationQueueManager(backgroundScope, Translator { it }, Speaker { played += it; delay(2000) },
            { testScheduler.currentTime })
        q.start(0); q.offer(subtitle("one")); q.offer(subtitle("two")); runCurrent(); q.pause()
        advanceTimeBy(2500); runCurrent(); assertEquals(listOf("one"), played)
        assertEquals(1, q.state.value.waiting); q.start(0); runCurrent(); assertEquals(listOf("one", "two"), played)
    }
    @Test fun `bounded queue applies backpressure without deleting accepted text`() = runTest {
        val q = TranslationQueueManager(backgroundScope, Translator { delay(60_000); it }, Speaker {},
            { testScheduler.currentTime }, capacity = 8)
        repeat(1000) { index -> assertEquals(index < 8, q.offer(subtitle("dialogue $index"))) }
        assertEquals((0..7).map { "dialogue $it" }, q.state.value.items.map { it.subtitle.text })
    }
    @Test fun `duplicate suppression expires for real repetition`() = runTest {
        val played = mutableListOf<String>()
        val q = TranslationQueueManager(backgroundScope, Translator { it }, Speaker { played += it }, { testScheduler.currentTime })
        q.start(0); q.offer(subtitle("yes")); runCurrent(); q.offer(subtitle("Yes!")); runCurrent()
        assertEquals(1, played.size); assertEquals(1, q.state.value.duplicates)
        advanceTimeBy(19_000); q.offer(subtitle("yes", 19_000)); runCurrent(); assertEquals(2, played.size)
    }
    @Test fun `speaker failure retains text and blocks following dialogue`() = runTest {
        var fail = true; val played = mutableListOf<String>()
        val q = TranslationQueueManager(backgroundScope, Translator { it }, Speaker { if (fail) error("device lost"); played += it },
            { testScheduler.currentTime })
        q.start(0); q.offer(subtitle("one")); q.offer(subtitle("two")); runCurrent()
        assertEquals(Stage.FAILED, q.state.value.items.first().stage); assertEquals("one", q.state.value.items.first().czech)
        fail = false; q.retryFailed(); runCurrent(); assertEquals(listOf("one", "two"), played)
    }
    @Test fun `replay is queued instead of interrupting active speech`() = runTest {
        val played = mutableListOf<String>()
        val q = TranslationQueueManager(backgroundScope, Translator { it }, Speaker { played += it; delay(1000) }, { testScheduler.currentTime })
        q.start(0); q.offer(subtitle("one")); advanceTimeBy(1001); runCurrent()
        q.offer(subtitle("two")); runCurrent(); assertTrue(q.replayLast()); runCurrent()
        assertEquals(listOf("one", "two"), played)
        advanceTimeBy(2001); runCurrent(); assertEquals(listOf("one", "two", "one"), played)
    }
}
