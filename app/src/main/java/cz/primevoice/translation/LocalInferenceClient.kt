package cz.primevoice.translation

import android.content.*
import android.os.*
import kotlinx.coroutines.*

/** All connection bookkeeping is confined to Main; native work runs in the private service. */
class LocalInferenceClient(private val context: Context) {
    private var remote: Messenger? = null
    private var connection: ServiceConnection? = null
    private var nextId = 0
    private val pending = mutableMapOf<Int, CompletableDeferred<String>>()
    private val receiver = Messenger(Handler(Looper.getMainLooper()) { message ->
        val deferred = pending.remove(message.arg1)
        val error = message.data.getString("error")
        if (error != null) deferred?.completeExceptionally(IllegalStateException(error))
        else deferred?.complete(message.data.getString("result").orEmpty())
        true
    })
    private suspend fun connect() = withContext(Dispatchers.Main.immediate) {
        if (remote != null) return@withContext
        val ready = CompletableDeferred<Unit>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (connection !== this) return
                remote = Messenger(binder); ready.complete(Unit)
            }
            override fun onServiceDisconnected(name: ComponentName) { disconnected() }
            override fun onBindingDied(name: ComponentName) { disconnected() }
            override fun onNullBinding(name: ComponentName) { disconnected() }
            private fun disconnected() {
                if (connection !== this) return
                remote = null
                val error = IllegalStateException("Proces AI skončil; pokračuji v LIGHT.")
                ready.completeExceptionally(error); pending.values.forEach { it.completeExceptionally(error) }; pending.clear()
            }
        }
        connection = conn
        try {
            check(context.bindService(Intent(context, LocalInferenceService::class.java), conn, Context.BIND_AUTO_CREATE)) { "Engine nelze spustit." }
            withTimeout(10_000) { ready.await() }
        } catch (e: Exception) { shutdown(); throw e }
    }
    suspend fun request(command: Int, data: Bundle = Bundle(), timeoutMs: Long = 12_000): String = withContext(Dispatchers.Main.immediate) {
        connect()
        val id = ++nextId; val response = CompletableDeferred<String>(); pending[id] = response
        try {
            remote!!.send(Message.obtain(null, command, id, 0).apply { this.data = data; replyTo = receiver })
            withTimeout(timeoutMs) { response.await() }
        } catch (e: Exception) { shutdown(); throw e }
        finally { pending.remove(id) }
    }
    fun shutdown() {
        check(Looper.myLooper() == Looper.getMainLooper())
        runCatching { remote?.send(Message.obtain(null, InferenceProtocol.STOP)) }
        remote = null
        val conn = connection; connection = null
        if (conn != null) runCatching { context.unbindService(conn) }
        pending.values.forEach { it.completeExceptionally(IllegalStateException("AI byla uvolněna; pokračuji v LIGHT.")) }; pending.clear()
    }
}
