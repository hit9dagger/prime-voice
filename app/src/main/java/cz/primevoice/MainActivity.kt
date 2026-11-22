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
        viewModel.stop()
        super.onStop()
    }
}
