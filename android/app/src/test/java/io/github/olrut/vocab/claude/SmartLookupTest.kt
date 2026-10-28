package io.github.olrut.vocab.claude

import io.github.olrut.vocab.dictionary.Lookup
import io.github.olrut.vocab.dictionary.Sense
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class SmartLookupTest {
    private lateinit var server: MockWebServer
    private val claude get() = ClaudeLookup(baseUrl = server.url("/").toString().trimEnd('/'), maxRetries = 0)
    private var dictionaryAnswer: Lookup = Lookup.Found("/kæt/", "https://x/cat.mp3", listOf(Sense("noun", "A pet.", null)))
    private val asked = mutableListOf<Pair<String, String?>>()
    private val dictionary = { w: String, c: String? -> asked += w to c; dictionaryAnswer }
    private var config: ClaudeConfig? = ClaudeConfig("k", ClaudeModel.Haiku)

    private fun smart() = SmartLookup({ w, c -> dictionary(w, c) }, claude) { config }

    private fun claudeSays(text: String) = server.enqueue(
        MockResponse().setHeader("Content-Type", "application/json").setBody(
            """{"id":"m","type":"message","role":"assistant","model":"claude-haiku-5-5","content":[{"type":"text","text":${kotlinx.serialization.json.JsonPrimitive(text)}}],
               "stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":1,"output_tokens":1}}""",
        ),
    )

    private fun claudeFails(status: Int) = server.enqueue(MockResponse().setResponseCode(status).setBody("""{"type":"error","error":{"type":"x","message":"x"}}"""))

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `without a key only the dictionary is asked`() = runTest {
        config = null
        assertEquals(dictionaryAnswer, smart().lookup("cat", "A sentence."))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `with a key Claude's senses come with the dictionary's pronunciation`() = runTest {
        claudeSays("""{"candidates":[{"definition":"small furry pet","example":"The cat slept.","finnish":"kissa"}]}""")
        assertEquals(Lookup.Found("/kæt/", "https://x/cat.mp3", listOf(Sense(null, "small furry pet", "The cat slept.", "kissa"))), smart().lookup("cat", null))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `Claude's senses stand even when the dictionary has nothing`() = runTest {
        dictionaryAnswer = Lookup.Failed("The dictionary is busy (too many requests); try again in a minute")
        claudeSays("""{"candidates":[{"definition":"d","example":"e","finnish":"f"}]}""")
        assertEquals(Lookup.Found(null, null, listOf(Sense(null, "d", "e", "f"))), smart().lookup("cat", null))
    }

    @Test
    fun `not English from Claude is not found`() = runTest {
        claudeSays("""{"error":"not_english","suggestion":null}""")
        assertEquals(Lookup.NotFound, smart().lookup("kissa", null))
    }

    @Test
    fun `a passing Claude failure falls back to the dictionary`() = runTest {
        claudeFails(529)
        assertEquals(dictionaryAnswer, smart().lookup("cat", null))
        dictionaryAnswer = Lookup.Failed("No connection to the dictionary")
        claudeFails(529)
        assertEquals(Lookup.Failed("Claude is overloaded (HTTP 529); no connection to the dictionary"), smart().lookup("cat", null))
    }

    @Test
    fun `a key problem is reported, not hidden behind the dictionary`() = runTest {
        claudeFails(401)
        assertEquals(Lookup.Failed("Claude rejected the API key"), smart().lookup("cat", null))
    }
}
