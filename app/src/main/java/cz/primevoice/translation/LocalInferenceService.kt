package cz.primevoice.translation

import android.app.Service
import android.content.Intent
import android.os.*
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.SamplerConfig
import cz.primevoice.core.*
import cz.primevoice.data.DeviceCapabilities
import java.io.File
import java.util.concurrent.Executors

internal object InferenceProtocol { const val PROBE = 1; const val LOAD = 2; const val TRANSLATE = 3; const val STOP = 4 }

/** Private separate process: native OOM, aborts and watchdog termination do not kill camera/TTS. */
class LocalInferenceService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private var engine: Engine? = null
    private val main = Handler(Looper.getMainLooper())
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.what == InferenceProtocol.STOP) { terminate(); return@Handler true }
        val reply = message.replyTo; val id = message.arg1; val command = message.what
        val data = Bundle(message.data)
        worker.execute {
            try {
                val text = when (command) {
                    InferenceProtocol.PROBE -> {
                        Engine.setNativeMinLogSeverity(LogSeverity.ERROR)
                        "LiteRT-LM 0.16.1: nativní knihovna načtena; model zatím neověřen."
                    }
                    InferenceProtocol.LOAD -> {
                        val mode = ExecutionMode.valueOf(data.getString("mode")!!)
                        val spec = requireNotNull(LocalModelCatalog.forMode(mode))
                        val resources = DeviceCapabilities(this).apply { engineLinked = true }.snapshot()
                        check(ExecutionPolicy.rejection(mode, resources, spec.bytes, true) == null) { "Nedostatek prostředků pro model." }
                        val file = File(File(noBackupFilesDir, "language_models"), spec.fileName)
                        check(file.length() == spec.bytes) { "Model není připraven." }
                        engine?.close(); engine = null
                        val created = Engine(EngineConfig(modelPath = file.path, backend = Backend.CPU(threadCount = 2),
                            maxNumTokens = 2048, cacheDir = cacheDir.path))
                        created.initialize(); engine = created
                        "Model načten"
                    }
                    InferenceProtocol.TRANSLATE -> {
                        val prompt = requireNotNull(data.getString("prompt"))
                        require(prompt.length <= 6000) { "Text je pro lokální model příliš dlouhý." }
                        val config = ConversationConfig(samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
                            maxOutputToken = 384, extraContext = mapOf("enable_thinking" to false))
                        checkNotNull(engine) { "Model není načten." }.createConversation(config).use { conversation ->
                            conversation.sendMessage(prompt).contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                        }
                    }
                    else -> error("Neznámý požadavek")
                }
                reply?.send(Message.obtain(null, command, id, 0).apply { this.data = Bundle().apply { putString("result", text) } })
            } catch (e: Throwable) {
                runCatching { reply?.send(Message.obtain(null, command, id, 0).apply {
                    this.data = Bundle().apply { putString("error", e.javaClass.simpleName + ": " + (e.message ?: "Selhání inferenčního enginu").take(180)) }
                }) }
                main.post { terminate() }
            }
        }
        true
    })
    override fun onBind(intent: Intent): IBinder = messenger.binder
    override fun onUnbind(intent: Intent): Boolean { terminate(); return false }
    private fun terminate() { worker.shutdownNow(); stopSelf(); android.os.Process.killProcess(android.os.Process.myPid()) }
}
