package cz.primevoice.translation

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import cz.primevoice.core.*
import cz.primevoice.data.DeviceCapabilities
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class ExecutionState(val selected: ExecutionMode = ExecutionMode.LIGHT,
    val effective: ExecutionMode = ExecutionMode.LIGHT, val recommended: ExecutionMode = ExecutionMode.LIGHT,
    val resources: DeviceResources = DeviceResources(), val busy: Boolean = false, val installed: Boolean = false,
    val verified: Set<ExecutionMode> = emptySet(), val engineStatus: String = "Kontroluji zařízení…",
    val backend: String = "LIGHT · ML Kit", val translationMs: Long = 0, val aiMemoryBytes: Long = 0,
    val message: String = "LIGHT je bezpečný výchozí režim. Modely se automaticky nestahují.")

class AdaptiveTranslationEngine(context: Context, private val scope: CoroutineScope,
    private val memory: DialogueContextManager, private val names: () -> List<String>,
    initial: ExecutionMode, val gate: InferenceGate) : Translator {
    val light = MlKitTranslationEngine(memory, names)
    val state = light.state
    val capabilities = DeviceCapabilities(context)
    val models = LocalModelStore(context)
    val cloud = CloudTranslation(context)
    private val client = LocalInferenceClient(context) {
        mutable.update { it.copy(effective = ExecutionMode.LIGHT, aiMemoryBytes = 0,
            message = "Proces AI skončil. Stejnou větu zpracuje dostupná lokální záloha.") }
    }
    private val mutable = MutableStateFlow(ExecutionState(selected = initial, busy = true, resources = capabilities.snapshot()))
    val execution = mutable.asStateFlow()
    private var operation: Job? = null
    private var loaded: ExecutionMode = ExecutionMode.LIGHT

    suspend fun initialize() {
        light.checkModels()
        val d = capabilities.snapshot()
        if (capabilities.eligibleAbi && !ExecutionPolicy.economical(d)) {
            try {
                val result = gate.run(heavy = true) { client.request(InferenceProtocol.PROBE) }
                capabilities.engineLinked = true
                mutable.update { it.copy(engineStatus = result) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(engineStatus = "Engine nedostupný: ${e.message}") } }
            finally { withContext(Dispatchers.Main.immediate) { unload() } }
        } else mutable.update { it.copy(engineStatus = "Toto zařízení používá úsporný LIGHT. Lokální AI vyžaduje 64bitový systém a více než 4 GB RAM.") }
        mutable.update { it.copy(busy = false) }; refresh()
    }
    fun refresh() {
        val resources = capabilities.snapshot()
        mutable.update { it.copy(resources = resources,
            installed = LocalModelCatalog.forMode(it.selected)?.let(models::installed) ?: false,
            recommended = if (it.effective in it.verified && !ExecutionPolicy.underPressure(resources)) it.effective
                else ExecutionPolicy.recommend(resources, it.verified)) }
        if (ExecutionPolicy.underPressure(resources) && mutable.value.effective != ExecutionMode.LIGHT) memoryPressure()
    }
    fun select(mode: ExecutionMode) {
        operation?.cancel(); models.pause(); unload()
        mutable.update { it.copy(selected = mode, effective = ExecutionMode.LIGHT, busy = false, backend = "LIGHT · ML Kit",
            message = if (mode == ExecutionMode.LIGHT) "LIGHT: offline ML Kit EN → CS a český Android TTS."
            else "Zvolen $mode. Do úspěšného ověření modelu překládá LIGHT (ML Kit).") }
        refresh()
    }
    fun rejection(): String? {
        val spec = LocalModelCatalog.forMode(mutable.value.selected) ?: return null
        return ExecutionPolicy.rejection(spec.mode, capabilities.snapshot(), spec.bytes, models.installed(spec))
    }
    fun prepare(wifiOnly: Boolean, uri: Uri? = null) {
        if (mutable.value.busy) return
        val spec = LocalModelCatalog.forMode(mutable.value.selected) ?: return
        operation = scope.launch {
            mutable.update { it.copy(busy = true, message = "Příprava modelu ${spec.name}…") }
            try {
                if (uri != null) models.import(spec, uri) else models.download(spec, wifiOnly)
                mutable.update { it.copy(message = "Soubor je ověřený. Stiskněte Ověřit a zapnout model.") }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(message = e.message ?: "Model se nepodařilo připravit.") } }
            finally { mutable.update { it.copy(busy = false) }; refresh() }
        }
    }
    fun deleteSelected() {
        if (mutable.value.busy) return
        val spec = LocalModelCatalog.forMode(mutable.value.selected) ?: return
        unload()
        operation = scope.launch {
            mutable.update { it.copy(busy = true, effective = ExecutionMode.LIGHT, verified = it.verified - spec.mode) }
            try { models.delete(spec); mutable.update { it.copy(message = "Model odstraněn. Používá se LIGHT.") } }
            catch (e: Exception) { mutable.update { it.copy(message = e.message.orEmpty()) } }
            finally { mutable.update { it.copy(busy = false) }; refresh() }
        }
    }
    fun activate() {
        if (mutable.value.busy || mutable.value.selected == ExecutionMode.LIGHT) return
        if (!state.value.ready) { mutable.update { it.copy(message = "Nejdříve připravte ML Kit pro bezpečnou zálohu.") }; return }
        val requested = mutable.value.selected
        operation = scope.launch {
            mutable.update { it.copy(busy = true, effective = ExecutionMode.LIGHT, message = "Ověřuji soubor, engine a české překlady…") }
            try {
                gate.run(heavy = true) {
                    var lastError = "Model není stažen."
                    for (mode in HybridPolicy.localOrder(requested).filter { it != ExecutionMode.LIGHT }) {
                        val spec = LocalModelCatalog.forMode(mode)!!
                        if (!models.installed(spec)) continue
                        try {
                            loadAndProbe(mode)
                            mutable.update { it.copy(effective = mode, backend = "$mode · ${spec.name}",
                                message = "${spec.name}: kontrola souboru, načtení a zkušební české překlady na tomto telefonu prošly. Kvalita všech vět není zaručena.") }
                            return@run
                        } catch (e: TimeoutCancellationException) { lastError = "Model překročil časový limit." }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { lastError = e.message ?: "Model selhal." }
                        withContext(Dispatchers.Main.immediate) { unload() }
                        mutable.update { it.copy(verified = it.verified - mode) }
                    }
                    withContext(Dispatchers.Main.immediate) { fallback("$lastError Používá se LIGHT. Pro ULTRA lze nejprve připravit STANDARD.") }
                }
            } catch (e: CancellationException) { withContext(NonCancellable + Dispatchers.Main.immediate) { unload() }; throw e }
            finally { mutable.update { it.copy(busy = false) }; refresh() }
        }
    }
    private suspend fun loadAndProbe(mode: ExecutionMode) {
        val spec = LocalModelCatalog.forMode(mode)!!
        withContext(Dispatchers.Main.immediate) { unload() }
        // Native process termination releases its entire heap before the next model starts.
        delay(200)
        ExecutionPolicy.rejection(mode, capabilities.snapshot(), spec.bytes, true)?.let { error(it) }
        check(models.verify(spec)) { "Model chybí nebo nesouhlasí SHA-256." }
        client.request(InferenceProtocol.LOAD, Bundle().apply { putString("mode", mode.name) }, 90_000)
        for ((index, source) in LocalTranslationChecks.probes.withIndex()) {
            val start = SystemClock.elapsedRealtime()
            val result = localTranslate(source, emptyList())
            check(LocalTranslationChecks.acceptable(source, result) && LocalTranslationChecks.passesProbe(index, result)) {
                "Model neprošel českým zkušebním překladem."
            }
            check(SystemClock.elapsedRealtime() - start <= 18_000) { "Model je na tomto telefonu příliš pomalý." }
        }
        loaded = mode
        mutable.update { it.copy(verified = it.verified + mode, aiMemoryBytes = client.memoryBytes) }
    }
    override suspend fun translate(text: String): String {
        val started = SystemClock.elapsedRealtime()
        var result: String? = null
        if (cloud.available()) {
            try {
                result = withTimeout(10_000) { cloud.translate(text, memory.snapshot()) }
                mutable.update { it.copy(backend = "ONLINE · Google Gemini / ${cloud.state.value.model}") }
            } catch (e: TimeoutCancellationException) { cloud.cancel() }
            catch (e: CancellationException) { cloud.cancel(); throw e }
            catch (_: Exception) { /* Same subtitle falls through to the existing local pipeline. */ }
        }
        if (result == null) result = gate.run(heavy = mutable.value.effective != ExecutionMode.LIGHT) {
            val startMode = mutable.value.effective
            var translated: String? = null
            for (mode in HybridPolicy.localOrder(startMode)) {
                if (mode == ExecutionMode.LIGHT) {
                    translated = light.translate(text)
                    mutable.update { it.copy(backend = "LIGHT · ML Kit") }; break
                }
                if (mode !in mutable.value.verified) continue
                try {
                    check(!ExecutionPolicy.underPressure(capabilities.snapshot())) { "Nedostatek volné paměti." }
                    if (loaded != mode) {
                        withContext(Dispatchers.Main.immediate) { unload() }; delay(200)
                        val spec = LocalModelCatalog.forMode(mode)!!
                        ExecutionPolicy.rejection(mode, capabilities.snapshot(), spec.bytes, true)?.let { error(it) }
                        client.request(InferenceProtocol.LOAD, Bundle().apply { putString("mode", mode.name) }, 15_000)
                        loaded = mode
                    }
                    translated = localTranslate(text, memory.snapshot())
                    check(LocalTranslationChecks.acceptable(text, translated)) { "AI vrátila neplatný překlad." }
                    mutable.update { it.copy(effective = mode, backend = "$mode · ${LocalModelCatalog.forMode(mode)!!.name}", aiMemoryBytes = client.memoryBytes) }
                    break
                } catch (e: TimeoutCancellationException) { /* A native timeout is recoverable for this same subtitle. */ }
                catch (e: CancellationException) { withContext(NonCancellable + Dispatchers.Main.immediate) { unload() }; throw e }
                catch (_: Exception) { }
                translated = null
                withContext(Dispatchers.Main.immediate) { unload() }
                mutable.update { it.copy(effective = ExecutionMode.LIGHT, verified = it.verified - mode,
                    message = "$mode selhal nebo je příliš pomalý. Zkouším nižší ověřený režim; stejná věta zůstává ve frontě.") }
            }
            checkNotNull(translated)
        }
        memory.remember(text, result)
        mutable.update { it.copy(translationMs = SystemClock.elapsedRealtime() - started) }
        return result
    }
    private suspend fun localTranslate(text: String, history: List<DialoguePair>): String =
        client.request(InferenceProtocol.TRANSLATE, Bundle().apply { putString("prompt", TranslationPrompt.build(text, history)) }, 20_000).trim()
    private fun unload() { client.shutdown(); loaded = ExecutionMode.LIGHT; mutable.update { it.copy(aiMemoryBytes = 0) } }
    fun fallback(reason: String) {
        unload()
        mutable.update { it.copy(effective = ExecutionMode.LIGHT, recommended = ExecutionMode.LIGHT,
            backend = "LIGHT · ML Kit", message = reason) }
    }
    fun memoryPressure() { operation?.cancel(); models.pause(); fallback("Android hlásí málo paměti. AI uvolněna; pokračuje LIGHT. Po uvolnění paměti lze zvolit STANDARD.") }
    fun cancelPreparation() { operation?.cancel(); models.pause(); fallback("Příprava pozastavena; staženou část lze obnovit.") }
    fun background() { operation?.cancel(); models.pause(); cloud.cancel(); fallback("AI uvolněna při odchodu z aplikace. Pro její další použití spusťte ověření modelu.") }
    suspend fun download(wifiOnly: Boolean) = light.download(wifiOnly)
    fun close() { operation?.cancel(); models.pause(); cloud.cancel(); unload(); light.close() }
}
