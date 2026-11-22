package cz.primevoice.core

import org.junit.Test
import kotlin.test.*

class SubtitleStabilityDetectorTest {
    private fun stable(detector: SubtitleStabilityDetector, text: String, from: Long = 0,
                       until: Long = from + 2000): List<Subtitle> =
        (from..until step 250).flatMap { detector.observe(OcrReading(text, it)) }

    @Test fun `two lines form one complete block`() {
        val text = "I told you we shouldn't have come here.\nNow we have to find another way out."
        val output = stable(SubtitleStabilityDetector(), text)
        assertEquals(listOf(text.replace('\n', ' ')), output.map { it.text })
    }
    @Test fun `typewriter text does not speak every word`() {
        val detector = SubtitleStabilityDetector(); val out = mutableListOf<Subtitle>()
        listOf("I told", "I told you", "I told you we shouldn't", "I told you we shouldn't have come here.")
            .forEachIndexed { i, text -> out += stable(detector, text, i * 400L, i * 400L + 200) }
        assertTrue(out.isEmpty())
        out += stable(detector, "I told you we shouldn't have come here.", 1600, 3000)
        assertEquals(1, out.size); assertEquals("I told you we shouldn't have come here.", out.single().text)
    }
    @Test fun `long visible caption stays deduplicated beyond recent ttl`() {
        assertEquals(1, stable(SubtitleStabilityDetector(), "We have been waiting for you all this time.", 0, 60_000).size)
    }
    @Test fun `two quickly replaced blocks retain their order`() {
        val d = SubtitleStabilityDetector()
        val out = mutableListOf<Subtitle>()
        out += d.observe(OcrReading("Get down!", 0))
        out += d.observe(OcrReading("Get down!", 150))
        out += d.observe(OcrReading("Run away!", 300))
        out += stable(d, "Run away!", 450, 2000)
        assertEquals(listOf("Get down!", "Run away!"), out.map { it.text })
    }
    @Test fun `later genuine repetition after disappearance is allowed`() {
        val d = SubtitleStabilityDetector(); val out = stable(d, "Let's go!").toMutableList()
        d.observe(OcrReading("", 3000)); d.observe(OcrReading("", 5500))
        out += stable(d, "Let's go!", 23_000, 25_000)
        assertEquals(2, out.size)
    }
    @Test fun `brief disappearance does not repeat same subtitle`() {
        val d = SubtitleStabilityDetector(); val out = stable(d, "Let's go!").toMutableList()
        d.observe(OcrReading("", 2200)); d.observe(OcrReading("", 4500))
        out += stable(d, "Let's go!", 4700, 7000)
        assertEquals(1, out.size)
        assertEquals(1, d.duplicates)
    }
    @Test fun `incomplete caption continues on next screen`() {
        val d = SubtitleStabilityDetector(); val out = mutableListOf<Subtitle>()
        out += d.observe(OcrReading("We need to find", 0))
        out += d.observe(OcrReading("We need to find", 250))
        out += d.observe(OcrReading("another way out.", 500))
        out += stable(d, "another way out.", 750, 2500)
        assertEquals(listOf("We need to find another way out."), out.map { it.text })
    }
    @Test fun `incomplete sentence has bounded waiting`() {
        val out = stable(SubtitleStabilityDetector(), "There is something", 0, 6000)
        assertEquals(1, out.size)
        assertTrue(out.single().completedAt <= 5000)
    }
    @Test fun `new unrelated sentence does not join incomplete one`() {
        val d = SubtitleStabilityDetector()
        d.observe(OcrReading("I thought", 0)); d.observe(OcrReading("I thought", 250))
        val out = stable(d, "Stop the car!", 500, 2500)
        assertEquals(listOf("I thought", "Stop the car!"), out.map { it.text })
    }
    @Test fun `single noisy OCR frame is not spoken`() {
        val d = SubtitleStabilityDetector()
        assertTrue(d.observe(OcrReading("Garbage 1234!", 0)).isEmpty())
        val out = stable(d, "We are safe now.", 250, 2500)
        assertEquals(listOf("We are safe now."), out.map { it.text })
    }
    @Test fun `low confidence text is not accepted`() {
        val d = SubtitleStabilityDetector()
        assertTrue((0L..4000L step 250).flatMap { d.observe(OcrReading("??? xyz", it, .1f)) }.isEmpty())
    }
    @Test fun `punctuation does not split a whole block`() {
        assertEquals(1, stable(SubtitleStabilityDetector(), "No! Wait. Are you sure?").size)
    }
    @Test fun `abbreviation is not end of sentence`() {
        assertFalse(TextRules.terminal("Please ask Dr."))
        assertFalse(TextRules.terminal("I thought..."))
        assertTrue(TextRules.terminal("Really?"))
    }
    @Test fun `correction settles before completion`() {
        val d = SubtitleStabilityDetector()
        d.observe(OcrReading("We need to leave the h0use now.", 0))
        d.observe(OcrReading("We need to leave the house now.", 300))
        val out = stable(d, "We need to leave the house now.", 600, 2200)
        assertEquals(listOf("We need to leave the house now."), out.map { it.text })
    }
    @Test fun `a later appended sentence never replays the already spoken prefix`() {
        val d = SubtitleStabilityDetector()
        val out = stable(d, "We have to go.", 0, 2000).toMutableList()
        out += stable(d, "We have to go. Find another way out.", 2300, 5000)
        assertEquals(listOf("We have to go.", "Find another way out."), out.map { it.text })
    }
}
