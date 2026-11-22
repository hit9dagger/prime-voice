package cz.primevoice.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Recovery stays inside the original queue item; it cannot insert or reorder a dialogue. */
class RecoveringTranslator(private val primary: Translator, private val fallback: Translator,
    private val onFailure: suspend (Exception) -> Unit) : Translator {
    override suspend fun translate(text: String): String {
        try { return primary.translate(text) }
        catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive() // A canceled queue item must not be translated again.
            onFailure(e)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { onFailure(e) }
        return fallback.translate(text)
    }
}
