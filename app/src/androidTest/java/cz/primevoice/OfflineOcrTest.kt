package cz.primevoice

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.primevoice.camera.Crop
import cz.primevoice.camera.OCRProcessor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Runs real bundled ML Kit inference on Android. No mocked recognizer and no network required. */
@RunWith(AndroidJUnit4::class)
class OfflineOcrTest {
    @Test fun bundledRecognizerReadsCompleteTwoLineBlock() = runBlocking {
        val bitmap = Bitmap.createBitmap(1200, 280, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 38f }
        canvas.drawText("I told you we should not have come here.", 35f, 100f, paint)
        canvas.drawText("Now we have to find another way out.", 35f, 165f, paint)
        val processor = OCRProcessor()
        try {
            val result = processor.recognize(Crop(bitmap, IntArray(512), 70f, 300f), 0)
            assertTrue(result.reading.text.contains("I told you we should not have come here."))
            assertTrue(result.reading.text.contains("Now we have to find another way out."))
            assertTrue(result.reading.text.indexOf("I told") < result.reading.text.indexOf("Now we"))
            assertTrue(result.elapsedMs >= 0)
        } finally { processor.close(); bitmap.recycle() }
    }

    @Test fun mainActivityLaunchesAndClosesWithoutCameraOrMicrophonePermission() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { assertFalse(it.isFinishing) }
        }
    }
}
