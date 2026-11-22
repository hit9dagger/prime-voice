package cz.primevoice.core

import org.junit.Test
import kotlin.test.*

class MultimodalFusionEngineTest {
    @Test fun `contradicting microphone cannot rewrite readable camera`() {
        val f = MultimodalFusionEngine()
        f.add(SpeechEvidence("I do not want to go home", 0, 1000, .99f, true))
        assertEquals("I do want to go home", f.fuse(OcrReading("I do want to go home", 1100, .9f)).text)
    }
    @Test fun `noisy room with unrelated speech is ignored`() {
        val f = MultimodalFusionEngine()
        f.add(SpeechEvidence("Can somebody open the window please", 0, 1000, .98f, true))
        assertEquals("We have to get out of here!", f.fuse(OcrReading("We have to get out of here!", 1100)).text)
    }
    @Test fun `aligned final speech repairs a damaged token`() {
        val f = MultimodalFusionEngine()
        f.add(SpeechEvidence("We should leave the house right now", 0, 1000, .95f, true))
        val result = f.fuse(OcrReading("We should leave the h0use right now", 1100, .55f))
        assertEquals("We should leave the house right now", result.text)
        assertTrue(result.alignedEnd)
    }
    @Test fun `late or partial ASR cannot repair camera`() {
        val f = MultimodalFusionEngine()
        f.add(SpeechEvidence("We should leave the house right now", 0, 1000, .95f, false))
        val ocr = OcrReading("We should leave the h0use right now", 5000, .55f)
        assertEquals(ocr.text, f.fuse(ocr).text)
        f.add(SpeechEvidence("We should leave the house right now", 0, 1000, .95f, true))
        assertEquals(ocr.text, f.fuse(ocr).text)
    }
    @Test fun `unknown speech confidence cannot repair camera`() {
        val f = MultimodalFusionEngine()
        f.add(SpeechEvidence("We should leave the house right now", 0, 1000, -1f, true))
        val text = "We should leave the h0use right now"
        assertEquals(text, f.fuse(OcrReading(text, 1100, .55f)).text)
    }
    @Test fun `clearing mic evidence preserves camera functionality`() {
        val f = MultimodalFusionEngine()
        f.add(SpeechEvidence("We should leave the house right now", 0, 1000, .95f, true))
        f.clear()
        val reading = OcrReading("We should leave the h0use right now", 1100, .55f)
        assertEquals(reading.text, f.fuse(reading).text)
    }
    @Test fun `strong alignment can restore one missing interior word`() {
        val f = MultimodalFusionEngine()
        f.add(SpeechEvidence("We should leave this house right now", 0, 1000, .95f, true))
        assertEquals("We should leave this house right now", f.fuse(OcrReading("We should leave house right now", 1100, .55f)).text)
    }
    @Test fun `ASR cannot insert negation`() {
        val f = MultimodalFusionEngine()
        f.add(SpeechEvidence("We should not leave this house right now", 0, 1000, .95f, true))
        val text = "We should leave this house right now"
        assertEquals(text, f.fuse(OcrReading(text, 1100, .55f)).text)
    }
}
