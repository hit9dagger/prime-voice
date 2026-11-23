package cz.primevoice.translation

/** Called only on the private inference process's single worker, never from camera/UI. */
internal object GgufNative {
    init { System.loadLibrary("primevoice") }
    external fun probe(): String
    external fun load(file: String, threads: Int)
    external fun generate(input: ByteArray, timeout: Int): ByteArray
    external fun close()
}
