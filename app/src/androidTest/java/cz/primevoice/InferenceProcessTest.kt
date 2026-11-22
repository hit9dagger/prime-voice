package cz.primevoice

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.primevoice.translation.LocalInferenceClient
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InferenceProcessTest {
    @Test fun nativeEngineLoadsInPrivateProcessWithoutAnyModel() = runBlocking {
        assumeTrue(Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "x86_64" })
        val client = LocalInferenceClient(ApplicationProvider.getApplicationContext())
        try {
            val status = client.request(1)
            assertTrue(status.contains("LiteRT-LM 0.16.1"))
            assertTrue(status.contains("model zatím neověřen"))
        } finally { withContext(Dispatchers.Main) { client.shutdown() } }
    }
    @Test fun failedPrivateProcessDoesNotKillAppAndCanBeReconnected() = runBlocking {
        assumeTrue(Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "x86_64" })
        val client = LocalInferenceClient(ApplicationProvider.getApplicationContext())
        try {
            var failed = false
            try { client.request(999) } catch (_: Exception) { failed = true }
            assertTrue(failed)
            delay(700) // Android must retire the deliberately failed remote process.
            assertTrue(client.request(1).contains("LiteRT-LM"))
        } finally { withContext(Dispatchers.Main) { client.shutdown() } }
    }
}
