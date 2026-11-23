package cz.primevoice.translation

import cz.primevoice.core.ExecutionMode

data class LocalModelSpec(val mode: ExecutionMode, val name: String, val fileName: String,
    val bytes: Long, val sha256: String, val url: String?, val page: String, val license: String)

/** Immutable revisions and LFS SHA-256. Text-only GGUF; no vision projector is required for OCR input. */
object LocalModelCatalog {
    val standard = LocalModelSpec(ExecutionMode.STANDARD, "Qwen 3 1.7B Q4_K_M", "Qwen_Qwen3-1.7B-Q4_K_M.gguf",
        1282439584L, "72c5c3cb38fa32d5256e2fe30d03e7a64c6c79e668ad84057e3bd66e250b24fb",
        "https://huggingface.co/bartowski/Qwen_Qwen3-1.7B-GGUF/resolve/dcb19155b962dbb6389f4691a982043a8e651022/Qwen_Qwen3-1.7B-Q4_K_M.gguf",
        "https://huggingface.co/bartowski/Qwen_Qwen3-1.7B-GGUF", "Apache 2.0")
    val ultra = LocalModelSpec(ExecutionMode.ULTRA, "Gemma 3 4B Instruct QAT Q4_K_M", "google_gemma-3-4b-it-qat-Q4_K_M.gguf",
        2489894304L, "00165396c856791d12d935d8094b360c71243abd7475390d1efe4e109035b964",
        "https://huggingface.co/bartowski/google_gemma-3-4b-it-qat-GGUF/resolve/0e3202d4c2315e5d2796d83c476e0961e4680513/google_gemma-3-4b-it-qat-Q4_K_M.gguf",
        "https://huggingface.co/bartowski/google_gemma-3-4b-it-qat-GGUF", "Gemma Terms of Use · ai.google.dev/gemma/terms")
    fun forMode(mode: ExecutionMode) = when (mode) {
        ExecutionMode.STANDARD -> standard
        ExecutionMode.ULTRA -> ultra
        ExecutionMode.LIGHT -> null
    }
}
