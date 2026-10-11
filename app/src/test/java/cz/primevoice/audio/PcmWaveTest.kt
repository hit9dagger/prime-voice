package cz.primevoice.audio
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PcmWaveTest {
    private fun u16(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte())
    private fun u32(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())
    private fun chunk(name: String, data: ByteArray): ByteArray = name.toByteArray(Charsets.US_ASCII) + u32(data.size) + data +
        if (data.size % 2 == 1) byteArrayOf(0) else byteArrayOf()
    private fun wave(rate: Int = 22050, channels: Int = 1, format: Int = 1, bits: Int = 16, frames: Int = 22050, extras: Boolean = false): ByteArray {
        val align = channels * bits / 8
        val fmt = u16(format) + u16(channels) + u32(rate) + u32(rate * align) + u16(align) + u16(bits) + if (extras) u16(0) else byteArrayOf()
        val chunks = chunk("fmt ", fmt) + (if (extras) chunk("LIST", byteArrayOf(1, 2, 3)) else byteArrayOf()) +
            chunk("data", ByteArray(frames * align)) + (if (extras) chunk("JUNK", byteArrayOf(7)) else byteArrayOf())
        return "RIFF".toByteArray() + u32(chunks.size + 4) + "WAVE".toByteArray() + chunks
    }
    private fun <T> using(bytes: ByteArray, block: (File) -> T): T {
        val file = File.createTempFile("prime-wave", ".wav")
        return try { file.writeBytes(bytes); block(file) } finally { file.delete() }
    }
    @Test fun piperMonoDurationAndFrames() = using(wave()) {
        val parsed = PcmWave.read(it)
        assertEquals(22050, parsed.sampleRate); assertEquals(1, parsed.channels)
        assertEquals(1000L, parsed.durationMs); assertEquals(44100L, parsed.dataBytes); assertEquals(44L, parsed.dataOffset)
    }
    @Test fun stereoExtendedFormatOddChunksAndTrailingMetadata() = using(wave(rate = 48000, channels = 2, frames = 48000, extras = true)) {
        val parsed = PcmWave.read(it)
        assertEquals(48000, parsed.sampleRate); assertEquals(2, parsed.channels)
        assertEquals(1000L, parsed.durationMs); assertEquals(192000L, parsed.dataBytes); assertEquals(58L, parsed.dataOffset)
    }
    @Test fun roundsShortDurationUp() = using(wave(rate = 22050, frames = 1)) { assertEquals(1L, PcmWave.read(it).durationMs) }
    @Test fun rejectsTruncatedData() {
        using(wave().dropLast(1).toByteArray()) { assertThrows(SpeechOutputException::class.java) { PcmWave.read(it) } }
    }
    @Test fun rejectsFloatAndUnsupportedBitDepth() {
        using(wave(format = 3, bits = 32)) { assertThrows(SpeechOutputException::class.java) { PcmWave.read(it) } }
        using(wave(bits = 8)) { assertThrows(SpeechOutputException::class.java) { PcmWave.read(it) } }
    }
    @Test fun rejectsUnboundedChunkLength() {
        val bytes = wave(); for (i in 40..43) bytes[i] = 0xff.toByte()
        using(bytes) { assertThrows(SpeechOutputException::class.java) { PcmWave.read(it) } }
    }
    @Test fun rejectsEmptyPcm() { using(wave(frames = 0)) { assertThrows(SpeechOutputException::class.java) { PcmWave.read(it) } } }
}
