package cz.primevoice

import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.primevoice.core.DialogueContextManager
import cz.primevoice.translation.MlKitTranslationEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** One online setup step; the actual OCR -> translation -> TTS tests run with networking disabled. */
@RunWith(AndroidJUnit4::class)
class ModelPreparationTest {
    @Test fun downloadOfflineCzechTranslationData() = runBlocking {
        val engine = MlKitTranslationEngine(DialogueContextManager()) { emptyList() }
        try {
            engine.download(false)
            assertTrue(engine.state.value.message, engine.state.value.ready)
        } finally { engine.close() }
    }
}
