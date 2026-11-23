package cz.primevoice

import android.content.Context
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import cz.primevoice.core.DialogueContextManager
import cz.primevoice.translation.MlKitTranslationEngine
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class TranslationCorpusTest {
    @Test fun actualOfflineMlKitTranslationsFor32GameDialogues() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val translator = MlKitTranslationEngine(DialogueContextManager()) { emptyList() }
        try {
            translator.checkModels(); assertTrue("Prepare offline models first", translator.state.value.ready)
            val corpus = InstrumentationRegistry.getInstrumentation().context.assets.open("dialogues.tsv").bufferedReader().use { it.readLines().drop(1) }
            val output = File(context.getExternalFilesDir(null), "translation-mlkit.tsv")
            output.printWriter().use { writer ->
                writer.println("id\tgroup\tenglish\treference_cs\tlatency_ms\tpss_kib\tactual_cs")
                for (row in corpus) {
                    val fields = row.split('\t'); val source = fields[2]
                    val start = SystemClock.elapsedRealtime()
                    val translated = withTimeout(30_000) { translator.translate(source) }
                    assertTrue(translated.isNotBlank())
                    val line = "$row\t${SystemClock.elapsedRealtime() - start}\t${Debug.getPss()}\t${translated.replace('\n',' ').replace('\t',' ')}"
                    writer.println(line); Log.i("REAL_TRANSLATION_CORPUS", line)
                }
            }
            assertEquals(32,corpus.size)
        } finally { translator.close() }
    }
}
