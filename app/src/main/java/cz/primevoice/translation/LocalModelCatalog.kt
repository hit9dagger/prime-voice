package cz.primevoice.translation

import cz.primevoice.core.ExecutionMode

data class LocalModelSpec(val mode: ExecutionMode, val name: String, val fileName: String,
    val bytes: Long, val sha256: String, val url: String?, val page: String)

/** Exact immutable publisher revisions + LFS SHA-256, verified against the publisher's metadata. */
object LocalModelCatalog {
    const val standardBlockedReason = "STANDARD používá ML Kit. Kandidát Qwen3 0.6B s LiteRT-LM 0.16.1 při skutečném testu na Linuxu nevracel český překlad, proto není v této verzi povolen."
    val standard = LocalModelSpec(ExecutionMode.STANDARD, "Qwen3 0.6B (LiteRT-LM)", "qwen3-0.6b.litertlm",
        613722264L, "c5f39b4191926786c3e6668b62af4d6eb15248dfc7a5348252a9b406f33f2108",
        "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/8d42ab5f1db14cad987fac57aeb17d070f9e180d/Qwen3-0.6B.litertlm",
        "https://huggingface.co/litert-community/Qwen3-0.6B")
    // The publisher requires acceptance of Gemma terms. No mirrors, embedded credentials or bypass.
    val ultra = LocalModelSpec(ExecutionMode.ULTRA, "Gemma 3n E2B int4 (LiteRT-LM)", "gemma-3n-e2b.litertlm",
        3655827456L, "2ed7bc3a0026c93d5b8a4544b352d9d00cd66ff0bac3ef6a20ac3d2cba4010d6", null,
        "https://huggingface.co/google/gemma-3n-E2B-it-litert-lm/blob/c03b6f60b8da6c5400b6838a2cf26420f80c0a01/gemma-3n-E2B-it-int4.litertlm")
    fun forMode(mode: ExecutionMode) = when (mode) {
        ExecutionMode.STANDARD -> standard
        ExecutionMode.ULTRA -> ultra
        ExecutionMode.LIGHT -> null
    }
}
