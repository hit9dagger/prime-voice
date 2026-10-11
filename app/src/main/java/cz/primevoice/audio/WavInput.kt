package cz.primevoice.audio

import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** File source for same PCM pipeline. Owns/closes input; onBlock reuses its buffer. */
object WavInput {
    suspend fun read(input: InputStream, onBlock: suspend (ShortArray, Int) -> Unit) = withContext(Dispatchers.IO) {
        WavPcmReader(input).use { reader ->
            val bytes = ByteArray(640)
            val samples = ShortArray(320)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = reader.read(bytes)
                if (count < 0) break
                for (index in 0 until count / 2) {
                    samples[index] = ((bytes[index * 2].toInt() and 255) or (bytes[index * 2 + 1].toInt() shl 8)).toShort()
                }
                onBlock(samples, count / 2)
            }
        }
    }
}
class WavPcmReader(private val input: InputStream) : Closeable {
    val sampleRate = 16_000
    val totalDataBytes: Long
    var sampleBytesRead: Long = 0
        private set
    private val header = ByteArray(16)
    private val skipBuffer = ByteArray(8192)
    private var riffRemaining = 0L
    private var audioRemaining = 0L
    private var metadataBytes = 0L
    private var tailChecked = false
    private var closed = false

    init {
        totalDataBytes = try { findData() } catch (failure: Exception) {
            try { input.close() } catch (closeFailure: Exception) { failure.addSuppressed(closeFailure) }
            throw failure
        }
        audioRemaining = totalDataBytes
    }
    fun read(buffer: ByteArray): Int {
        if (closed) throw IOException("WAV_CLOSED")
        require(buffer.size % 2 == 0) { "WAV_BUFFER_MUST_BE_EVEN" }
        if (buffer.isEmpty()) return 0
        if (audioRemaining == 0L) { checkTail(); return -1 }
        val wanted = minOf(buffer.size.toLong(), audioRemaining).toInt()
        var offset = 0
        while (offset < wanted) {
            val count = readSome(buffer, offset, wanted - offset)
            if (count < 0) throw EOFException("WAV_TRUNCATED_AUDIO")
            offset += count
            audioRemaining -= count
            sampleBytesRead += count
        }
        if (audioRemaining == 0L) checkTail()
        return offset
    }
    override fun close() { if (!closed) { closed = true; input.close() } }
    private fun findData(): Long {
        readHeader(4)
        if (idAt(0) != "RIFF") throw IOException("WAV_NOT_RIFF")
        readHeader(4)
        val riffSize = uint32(0)
        if (riffSize < 4L) throw IOException("WAV_INVALID_RIFF_LENGTH")
        if (riffSize > MAX_DATA_BYTES + MAX_METADATA_BYTES + 4L) throw IOException("WAV_TOO_LARGE")
        readHeader(4)
        if (idAt(0) != "WAVE") throw IOException("WAV_NOT_WAVE")
        riffRemaining = riffSize - 4L
        var formatFound = false
        while (riffRemaining > 0L) {
            val chunk = nextChunk()
            accountMetadata(8L)
            if (chunk.id == "data") {
                if (!formatFound) throw IOException("WAV_FORMAT_MUST_PRECEDE_DATA")
                if (chunk.size % 2L != 0L) throw IOException("WAV_INCOMPLETE_PCM_SAMPLE")
                if (chunk.size > MAX_DATA_BYTES) throw IOException("WAV_DURATION_EXCEEDS_30_MINUTES")
                riffRemaining -= chunk.paddedSize
                return chunk.size
            }
            accountMetadata(chunk.paddedSize)
            if (chunk.id == "fmt ") {
                if (formatFound) throw IOException("WAV_DUPLICATE_FORMAT")
                parseFormat(chunk.size)
                formatFound = true
                drain(chunk.paddedSize - 16L)
            } else { drain(chunk.paddedSize) }
            riffRemaining -= chunk.paddedSize
        }
        throw IOException("WAV_MISSING_DATA")
    }
    private fun parseFormat(size: Long) {
        if (size < 16L) throw IOException("WAV_FORMAT_TOO_SHORT")
        readHeader(16)
        if (uint16(0) != 1) throw IOException("WAV_REQUIRES_PCM")
        if (uint16(2) != 1) throw IOException("WAV_REQUIRES_MONO")
        if (uint32(4) != 16_000L) throw IOException("WAV_REQUIRES_16000_HZ")
        if (uint16(14) != 16) throw IOException("WAV_REQUIRES_PCM16")
        if (uint16(12) != 2 || uint32(8) != 32_000L) throw IOException("WAV_INCONSISTENT_FORMAT")
    }
    private fun nextChunk(): Chunk {
        if (riffRemaining < 8L) throw IOException("WAV_TRUNCATED_CHUNK_HEADER")
        readHeader(8)
        val chunk = Chunk(idAt(0), uint32(4))
        riffRemaining -= 8L
        if (chunk.paddedSize > riffRemaining) throw IOException("WAV_CHUNK_OUTSIDE_RIFF")
        return chunk
    }
    private fun checkTail() {
        if (tailChecked) return
        while (riffRemaining > 0L) {
            val chunk = nextChunk()
            if (chunk.id == "data" || chunk.id == "fmt ") throw IOException("WAV_MULTIPLE_AUDIO_OR_FORMAT_CHUNKS")
            accountMetadata(8L + chunk.paddedSize)
            drain(chunk.paddedSize)
            riffRemaining -= chunk.paddedSize
        }
        tailChecked = true
    }
    private fun accountMetadata(count: Long) {
        metadataBytes += count
        if (metadataBytes > MAX_METADATA_BYTES) throw IOException("WAV_METADATA_EXCEEDS_1_MIB")
    }
    private fun readHeader(size: Int) {
        var offset = 0
        while (offset < size) {
            val count = readSome(header, offset, size - offset)
            if (count < 0) throw EOFException("WAV_TRUNCATED_HEADER")
            offset += count
        }
    }
    private fun drain(size: Long) {
        var remaining = size
        while (remaining > 0L) {
            val count = readSome(skipBuffer, 0, minOf(remaining, skipBuffer.size.toLong()).toInt())
            if (count < 0) throw EOFException("WAV_TRUNCATED_METADATA")
            remaining -= count
        }
    }
    private fun readSome(target: ByteArray, offset: Int, length: Int): Int {
        val count = input.read(target, offset, length)
        if (count != 0) return count
        val oneByte = input.read()
        if (oneByte < 0) return -1
        target[offset] = oneByte.toByte()
        return 1
    }
    private fun idAt(offset: Int) = String(header, offset, 4, Charsets.US_ASCII)
    private fun uint16(offset: Int): Int = (header[offset].toInt() and 255) or ((header[offset + 1].toInt() and 255) shl 8)
    private fun uint32(offset: Int): Long = (header[offset].toLong() and 255L) or ((header[offset + 1].toLong() and 255L) shl 8) or ((header[offset + 2].toLong() and 255L) shl 16) or ((header[offset + 3].toLong() and 255L) shl 24)
    private data class Chunk(val id: String, val size: Long) { val paddedSize: Long get() = size + (size and 1L) }
    companion object {
        const val MAX_DATA_BYTES = 16_000L * 2L * 30L * 60L
        private const val MAX_METADATA_BYTES = 1_048_576L
    }
}
