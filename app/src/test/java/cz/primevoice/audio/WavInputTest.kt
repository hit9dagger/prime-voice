package cz.primevoice.audio
import java.io.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WavInputTest {
    @Test fun readsExactPcmWithoutLoadingWholeFile() {
        val pcm = byteArrayOf(0, 0, -1, 127, 0, -128, 1, 0)
        WavPcmReader(ByteArrayInputStream(wav(chunk("fmt ", format()), chunk("data", pcm)))).use {
            assertEquals(8L, it.totalDataBytes)
            val buffer = ByteArray(6)
            assertEquals(6, it.read(buffer)); assertArrayEquals(pcm.copyOfRange(0, 6), buffer)
            assertEquals(2, it.read(buffer)); assertEquals(1, buffer[0].toInt())
            assertEquals(-1, it.read(buffer)); assertEquals(8L, it.sampleBytesRead)
        }
    }
    @Test fun supportsUnknownChunksAndOddPaddingBeforeAndAfterAudio() {
        val data = wav(chunk("JUNK", byteArrayOf(1, 2, 3)), chunk("fmt ", format()), chunk("data", byteArrayOf(3, 0)), chunk("LIST", byteArrayOf(9)))
        WavPcmReader(ByteArrayInputStream(data)).use {
            val buffer = ByteArray(2)
            assertEquals(2, it.read(buffer)); assertArrayEquals(byteArrayOf(3, 0), buffer); assertEquals(-1, it.read(buffer))
        }
    }
    @Test fun handlesStreamsReturningSingleBytes() {
        val source = ByteArrayInputStream(wav(chunk("fmt ", format()), chunk("data", byteArrayOf(7, 0))))
        val slow = object : InputStream() {
            override fun read(): Int = source.read()
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = source.read(bytes, offset, minOf(length, 1))
        }
        WavPcmReader(slow).use { val bytes = ByteArray(2); assertEquals(2, it.read(bytes)); assertEquals(7, bytes[0].toInt()) }
    }
    @Test(expected = IOException::class) fun rejectsStereo() {
        WavPcmReader(ByteArrayInputStream(wav(chunk("fmt ", format(channels = 2)), chunk("data", byteArrayOf(0, 0)))))
    }
    @Test(expected = IOException::class) fun rejectsWrongSampleRate() {
        WavPcmReader(ByteArrayInputStream(wav(chunk("fmt ", format(rate = 48_000)), chunk("data", byteArrayOf(0, 0)))))
    }
    @Test(expected = IOException::class) fun rejectsFloatEncoding() {
        WavPcmReader(ByteArrayInputStream(wav(chunk("fmt ", format(encoding = 3)), chunk("data", byteArrayOf(0, 0)))))
    }
    @Test(expected = IOException::class) fun rejectsDataBeforeFormat() {
        WavPcmReader(ByteArrayInputStream(wav(chunk("data", byteArrayOf(0, 0)), chunk("fmt ", format()))))
    }
    @Test(expected = IOException::class) fun rejectsOddPcmLength() {
        WavPcmReader(ByteArrayInputStream(wav(chunk("fmt ", format()), chunk("data", byteArrayOf(1)))))
    }
    @Test(expected = EOFException::class) fun detectsTruncatedAudioInsteadOfReturningPartialSuccess() {
        val valid = wav(chunk("fmt ", format()), chunk("data", byteArrayOf(1, 0, 2, 0)))
        WavPcmReader(ByteArrayInputStream(valid.copyOf(valid.size - 1))).use { it.read(ByteArray(4)) }
    }
    @Test(expected = EOFException::class) fun detectsMissingOddMetadataPadding() {
        val valid = wav(chunk("fmt ", format()), chunk("data", byteArrayOf(1, 0)), chunk("JUNK", byteArrayOf(9)))
        WavPcmReader(ByteArrayInputStream(valid.copyOf(valid.size - 1))).use { it.read(ByteArray(2)) }
    }
    @Test(expected = IOException::class) fun rejectsChunkLengthOutsideRiff() {
        val valid = wav(chunk("fmt ", format()), chunk("data", byteArrayOf(1, 0)))
        valid[40] = 100
        WavPcmReader(ByteArrayInputStream(valid))
    }
    @Test(expected = IOException::class) fun rejectsDuplicateDataChunks() {
        val valid = wav(chunk("fmt ", format()), chunk("data", byteArrayOf(1, 0)), chunk("data", byteArrayOf(2, 0)))
        WavPcmReader(ByteArrayInputStream(valid)).use { it.read(ByteArray(2)) }
    }
    @Test(expected = IOException::class) fun rejectsUnboundedRiffLength() {
        val valid = wav(chunk("fmt ", format()), chunk("data", byteArrayOf()))
        for (index in 4..7) valid[index] = -1
        WavPcmReader(ByteArrayInputStream(valid))
    }
    @Test fun closesInputWhenHeaderIsInvalid() {
        val source = TrackingInput(byteArrayOf(1, 2))
        try { WavPcmReader(source); fail("Expected invalid header") } catch (_: IOException) { assertTrue(source.closed) }
    }
    @Test fun fileSourceConvertsSignedLittleEndianPcmAndClosesStream() = runBlocking {
        val source = TrackingInput(wav(chunk("fmt ", format()), chunk("data", byteArrayOf(0, 0, -1, 127, 0, -128, -1, -1))))
        val result = mutableListOf<Short>()
        WavInput.read(source) { samples, count -> result.addAll(samples.take(count)) }
        assertEquals(listOf<Short>(0, 32767, -32768, -1), result); assertTrue(source.closed)
    }
    @Test fun cancellationInConsumerClosesTheFile() = runBlocking {
        val source = TrackingInput(wav(chunk("fmt ", format()), chunk("data", ByteArray(1280))))
        try { WavInput.read(source) { _, _ -> throw CancellationException("TEST_CANCEL") }; fail("Expected cancellation") }
        catch (_: CancellationException) { assertTrue(source.closed) }
    }
    private class TrackingInput(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false
        override fun close() { closed = true; super.close() }
    }
    private fun format(channels: Int = 1, rate: Int = 16_000, encoding: Int = 1): ByteArray = ByteArrayOutputStream().apply {
        u16(encoding); u16(channels); u32(rate); u32(rate * channels * 2); u16(channels * 2); u16(16)
    }.toByteArray()
    private fun chunk(id: String, bytes: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        write(id.toByteArray(Charsets.US_ASCII)); u32(bytes.size); write(bytes); if (bytes.size % 2 != 0) write(0)
    }.toByteArray()
    private fun wav(vararg chunks: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        write("RIFF".toByteArray(Charsets.US_ASCII)); u32(4 + chunks.sumOf { it.size }); write("WAVE".toByteArray(Charsets.US_ASCII)); chunks.forEach { write(it) }
    }.toByteArray()
    private fun ByteArrayOutputStream.u16(value: Int) { write(value and 255); write((value ushr 8) and 255) }
    private fun ByteArrayOutputStream.u32(value: Int) { for (shift in 0..24 step 8) write((value ushr shift) and 255) }
}
