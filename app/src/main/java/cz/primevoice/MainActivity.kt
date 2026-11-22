package cz.primevoice

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import cz.primevoice.ui.PrimeVoiceApp

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<PrimeVoiceViewModel>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { PrimeVoiceApp(viewModel) }
    }
    override fun onStop() {
        // Foreground-only by design: no hidden camera/mic or foreground-service restrictions.
        viewModel.background()
        super.onStop()
    }
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW &&
            level != android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) viewModel.memoryPressure()
    }
    override fun onLowMemory() { super.onLowMemory(); viewModel.memoryPressure() }
}
