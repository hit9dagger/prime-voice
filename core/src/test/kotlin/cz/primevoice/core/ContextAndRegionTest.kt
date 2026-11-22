package cz.primevoice.core

import org.junit.Test
import kotlin.test.*

class ContextAndRegionTest {
    @Test fun `context contains only eight recent dialogues`() {
        val context = DialogueContextManager()
        repeat(100) { context.remember("English $it", "Czech $it") }
        assertEquals(8, context.snapshot().size); assertEquals("English 92", context.snapshot().first().english)
    }
    @Test fun `protected names are restored and words are not individually translated`() {
        val c = DialogueContextManager()
        val p = c.prepare("Lucia, we're gonna find Jason.", listOf("Lucia", "Jason"))
        assertFalse(p.text.contains("Lucia")); assertTrue(p.text.contains("going to"))
        val output = p.restore(p.text)
        assertTrue(output.contains("Lucia")); assertTrue(output.contains("Jason"))
    }
    @Test fun `corrupted name placeholders cause visible failure instead of speech garbage`() {
        val protected = DialogueContextManager().prepare("Go with Jason.", listOf("Jason"))
        assertFailsWith<IllegalStateException> { protected.restore("Jdi s XYZ.") }
    }
    @Test fun `crop is clamped with nonzero size`() {
        val region = Region(-2f, 2f, -.5f, 1.9f).valid()
        assertEquals(0f, region.left); assertTrue(region.top >= 0)
        assertTrue(region.bottom <= 1); assertTrue(region.right > region.left)
        assertTrue(region.bottom > region.top)
    }
}
