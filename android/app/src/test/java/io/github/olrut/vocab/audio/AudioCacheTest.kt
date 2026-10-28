package io.github.olrut.vocab.audio

import io.github.olrut.vocab.data.Word
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioCacheTest {
    private lateinit var server: MockWebServer
    private lateinit var dir: File
    private lateinit var cache: AudioCache

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        dir = Files.createTempDirectory("audio").toFile()
        cache = AudioCache(File(dir, "sub"), OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
        dir.deleteRecursively()
    }

    private fun word(url: String?) = Word(id = 7, word = "cat", audioUrl = url)

    @Test
    fun `downloads once and then serves the file`() = runTest {
        server.enqueue(MockResponse().setBody("mp3 bytes"))
        val url = server.url("/cat.mp3").toString()
        val f = cache.get(word(url))!!
        assertEquals("mp3 bytes", f.readText())
        assertEquals(f, cache.get(word(url)))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `an unsaved word is downloaded each time and not cached under an id`() = runTest {
        server.enqueue(MockResponse().setBody("one"))
        server.enqueue(MockResponse().setBody("two"))
        val unsaved = Word(word = "cat", audioUrl = server.url("/cat.mp3").toString())
        assertEquals("one", cache.get(unsaved)!!.readText())
        assertEquals("two", cache.get(unsaved.copy(word = "dog"))!!.readText())
        assertFalse(cache.has(0))
    }

    @Test
    fun `no url means no recording and no request`() = runTest {
        assertNull(cache.get(word(null)))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a failed or too large download leaves nothing behind`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(cache.get(word(server.url("/a").toString())))
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(5 * 1024 * 1024 + 1))))
        assertNull(cache.get(word(server.url("/b").toString())))
        assertFalse(cache.has(7))
        assertEquals(emptyList<String>(), File(dir, "sub").list()?.toList().orEmpty())
    }
}
