package cz.primevoice.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class RecoveringTranslatorTest {
    @Test fun failedAiRetriesExactCompleteBlockOnceWithLight() = runTest {
        val source = "I told you we shouldn't come here. Now find another way out."
        val received = mutableListOf<String>(); var failures = 0
        val t = RecoveringTranslator(Translator { error("Native process died") },
            Translator { received += it; "Celý překlad." }) { failures++ }
        assertEquals("Celý překlad.", t.translate(source))
        assertEquals(listOf(source), received); assertEquals(1, failures)
    }
    @Test fun inferenceWatchdogUsesFallback() = runTest {
        val t = RecoveringTranslator(Translator { withTimeout(100) { delay(1000); "late" } }, Translator { "LIGHT" }) { }
        assertEquals("LIGHT", t.translate("block"))
    }
    @Test fun userCancellationDoesNotResurrectSkippedDialogue() = runTest {
        var fallbackCalls = 0
        val t = RecoveringTranslator(Translator { awaitCancellation() }, Translator { fallbackCalls++; it }) { }
        val job = launch { t.translate("skipped") }; runCurrent(); job.cancelAndJoin()
        assertEquals(0, fallbackCalls)
    }
    @Test fun exhaustedLightStillReportsFailureInsteadOfSilentlyLosingText() = runTest {
        val t = RecoveringTranslator(Translator { error("AI") }, Translator { error("ML Kit") }) { }
        assertEquals("ML Kit", assertFailsWith<IllegalStateException> { t.translate("retained") }.message)
    }
    @Test fun fallbackWhileFirstSentenceSpeaksPreservesNextThreeAndNoOverlap() = runTest {
        val starts = mutableListOf<String>(); val ends = mutableListOf<String>(); var active = 0; var maximum = 0
        val t = RecoveringTranslator(Translator { if (it == "one") "cs:$it" else error("AI memory pressure") }, Translator { "cs:$it" }) { }
        val q = TranslationQueueManager(backgroundScope, t, Speaker {
            starts += it; active++; maximum = maxOf(maximum, active); delay(2000); active--; ends += it
        }, { testScheduler.currentTime }, translationParallelism = 1)
        q.start(0); q.offer(Subtitle("one",0,0,.9f,"test")); runCurrent()
        listOf("two","three","four").forEach { q.offer(Subtitle(it,0,0,.9f,"test")) }
        runCurrent(); assertEquals(listOf("cs:one"), starts); assertEquals(3,q.state.value.waiting)
        advanceTimeBy(8001); runCurrent()
        assertEquals(listOf("cs:one","cs:two","cs:three","cs:four"), starts)
        assertEquals(starts,ends); assertEquals(1,maximum); assertTrue(q.state.value.items.isEmpty())
    }
}
