package cz.primevoice.core

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlin.test.*

class HybridAndDownloadTest {
    @Test fun offlineIsAbsoluteEvenWithConsentAndKeyAndInternet() {
        assertFalse(HybridPolicy.useCloud(NetworkMode.OFFLINE_ONLY, true, true, true))
        for (mode in NetworkMode.entries) {
            assertFalse(HybridPolicy.useCloud(mode, false, true, true))
            assertFalse(HybridPolicy.useCloud(mode, true, false, true))
            assertFalse(HybridPolicy.useCloud(mode, true, true, false))
        }
        assertTrue(HybridPolicy.useCloud(NetworkMode.PREFER_ONLINE, true, true, true))
        assertTrue(HybridPolicy.useCloud(NetworkMode.AUTOMATIC, true, true, true))
    }
    @Test fun ultraFallbackTriesStandardBeforeLight() {
        assertEquals(listOf(ExecutionMode.ULTRA, ExecutionMode.STANDARD, ExecutionMode.LIGHT), HybridPolicy.localOrder(ExecutionMode.ULTRA))
        assertEquals(listOf(ExecutionMode.LIGHT), HybridPolicy.localOrder(ExecutionMode.LIGHT))
    }
    @Test fun promptIncludesOnlyLastTwoActualDialoguesAndWholeSubtitle() {
        val history = (1..8).map { DialoguePair("English $it", "Česky $it") }
        val prompt = TranslationPrompt.build("I told you.\nWe must go!", history)
        assertFalse(prompt.contains("English 6")); assertTrue(prompt.contains("English 7"))
        assertTrue(prompt.contains("Česky 8")); assertTrue(prompt.contains("I told you.\nWe must go!"))
    }
    @Test fun resumesVerifiedFileWithHttpRange() = runBlocking { fixture { root, payload, hash, server ->
        var range: String? = null
        server.createContext("/model") { exchange ->
            range = exchange.requestHeaders.getFirst("Range")
            exchange.responseHeaders.add("Content-Range", "bytes 4000-${payload.lastIndex}/${payload.size}")
            exchange.sendResponseHeaders(206, (payload.size - 4000).toLong())
            exchange.responseBody.use { it.write(payload, 4000, payload.size - 4000) }
        }
        val part = root.resolve("model.part").toFile(); part.writeBytes(payload.copyOfRange(0,4000))
        val dest = root.resolve("model.gguf").toFile()
        ResumableModelDownload().download(url(server), part, dest, payload.size.toLong(), hash)
        assertEquals("bytes=4000-", range); assertContentEquals(payload, dest.readBytes()); assertFalse(part.exists())
    } }
    @Test fun serverIgnoringRangeRestartsInsteadOfCorruptingFile() = runBlocking { fixture { root, payload, hash, server ->
        server.createContext("/model") { e -> e.sendResponseHeaders(200, payload.size.toLong()); e.responseBody.use { it.write(payload) } }
        val part = root.resolve("part").toFile().apply { writeBytes(payload.copyOfRange(0,4000)) }
        val dest = root.resolve("ready").toFile()
        ResumableModelDownload().download(url(server), part, dest, payload.size.toLong(), hash)
        assertContentEquals(payload,dest.readBytes())
    } }
    @Test fun wrongChecksumNeverReplacesExistingModel() = runBlocking { fixture { root, payload, _, server ->
        server.createContext("/model") { e -> e.sendResponseHeaders(200, payload.size.toLong()); e.responseBody.use { it.write(payload) } }
        val part = root.resolve("part").toFile(); val dest = root.resolve("ready").toFile().apply { writeText("previous verified model") }
        assertFailsWith<IllegalStateException> { ResumableModelDownload().download(url(server), part, dest, payload.size.toLong(), "0".repeat(64)) }
        assertEquals("previous verified model",dest.readText()); assertFalse(part.exists())
    } }
    @Test fun badRangePreservesPartialForRetry() = runBlocking { fixture { root, payload, hash, server ->
        server.createContext("/model") { e -> e.responseHeaders.add("Content-Range","bytes 0-${payload.lastIndex}/${payload.size}"); e.sendResponseHeaders(206,0); e.close() }
        val part = root.resolve("part").toFile().apply { writeBytes(payload.copyOfRange(0,4000)) }
        assertFailsWith<IllegalStateException> { ResumableModelDownload().download(url(server), part, root.resolve("ready").toFile(), payload.size.toLong(), hash) }
        assertEquals(4000L,part.length()); assertFalse(root.resolve("ready").toFile().exists())
    } }
    @Test fun lossOfWifiOrStoragePausesWithoutActivatingPartialFile() = runBlocking { fixture { root, payload, hash, server ->
        val part = root.resolve("part").toFile().apply { writeBytes(payload.copyOfRange(0,4000)) }
        assertFailsWith<IllegalStateException> { ResumableModelDownload().download(url(server), part, root.resolve("ready").toFile(), payload.size.toLong(), hash,
            beforeChunk = { error("Wi-Fi or storage unavailable") }) }
        assertEquals(4000L,part.length()); assertFalse(root.resolve("ready").toFile().exists())
    } }
    private fun url(server: HttpServer) = "http://127.0.0.1:${server.address.port}/model"
    private suspend fun fixture(block: suspend (java.nio.file.Path, ByteArray, String, HttpServer) -> Unit) {
        val root = Files.createTempDirectory("prime-download-test")
        val payload = ByteArray(32768) { (it % 251).toByte() }
        val hash = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1",0),0).apply { start() }
        try { block(root, payload, hash, server) } finally { server.stop(0); root.toFile().deleteRecursively() }
    }
}
