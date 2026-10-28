package io.github.olrut.vocab.claude

import io.github.olrut.vocab.dictionary.Sense
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Against a fake Messages API: no real Claude calls in tests. */
class ClaudeLookupTest {
    private lateinit var server: MockWebServer
    private lateinit var claude: ClaudeLookup

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        claude = ClaudeLookup(baseUrl = server.url("/").toString().trimEnd('/'), maxRetries = 0)
    }

    @After
    fun tearDown() = server.shutdown()

    private val config = ClaudeConfig("sk-ant-test", ClaudeModel.Opus)

    private fun reply(text: String, stop: String = "end_turn", status: Int = 200) = server.enqueue(
        MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(
            """{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5",
               "content":[{"type":"thinking","thinking":"","signature":"s"},{"type":"text","text":${JsonPrimitive(text)}}],
               "stop_reason":"$stop","stop_sequence":null,
               "usage":{"input_tokens":10,"output_tokens":20}}""",
        ),
    )

    private fun error(status: Int, type: String) = server.enqueue(
        MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json")
            .setBody("""{"type":"error","error":{"type":"$type","message":"nope"}}"""),
    )

    private fun body(r: RecordedRequest): JsonObject = Json.parseToJsonElement(r.body.readUtf8()).jsonObject

    @Test
    fun `asks with the prompt, the key and low effort, and reads the candidates`() = runTest {
        reply("""{"candidates":[{"definition":"cheerfully optimistic","example":"She was sanguine about it.","finnish":"toiveikas, optimistinen"}]}""")
        val r = claude.lookup(config, "Sanguine ", null)
        assertEquals(ClaudeResult.Senses(listOf(Sense(null, "cheerfully optimistic", "She was sanguine about it.", "toiveikas, optimistinen"))), r)

        val req = server.takeRequest()
        assertEquals("/v1/messages", req.requestUrl!!.encodedPath)
        assertEquals("sk-ant-test", req.getHeader("x-api-key"))
        assertTrue(req.getHeader("anthropic-beta")!!.contains("server-side-fallback-2026-07-01"))
        val b = body(req)
        assertEquals("claude-opus-5-5", b["model"]!!.jsonPrimitive.content)
        assertEquals("low", b["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals("default", b["fallbacks"]!!.jsonPrimitive.content)
        assertTrue(b["system"].toString().contains("native language is Finnish"))
        assertFalse(b["system"].toString().contains("saw the word in this sentence"))
        // The word goes in normalised, as the only user message.
        assertTrue(b["messages"].toString().contains("\"sanguine\""))
    }

    @Test
    fun `haiku is asked without the fallback, which it does not support`() = runTest {
        reply("""{"candidates":[{"definition":"d","example":"e","finnish":"f"}]}""")
        claude.lookup(config.copy(model = ClaudeModel.Haiku), "x", null)
        val req = server.takeRequest()
        val b = body(req)
        assertEquals("claude-haiku-5-5", b["model"]!!.jsonPrimitive.content)
        assertNull(b["fallbacks"])
        assertFalse(req.getHeader("anthropic-beta")?.contains("server-side-fallback") ?: false)
    }

    @Test
    fun `with a sentence, one sense comes back with the sentence as its example`() = runTest {
        reply("```json\n{\"candidates\":[{\"definition\":\"hopeful\",\"example\":\"a paraphrase\",\"finnish\":\"toiveikas\"}]}\n```")
        val r = claude.lookup(config, "sanguine", "He was sanguine about the voyage.")
        assertEquals(ClaudeResult.Senses(listOf(Sense(null, "hopeful", "He was sanguine about the voyage.", "toiveikas"))), r)
        assertTrue(body(server.takeRequest())["system"].toString().contains("He was sanguine about the voyage."))
    }

    @Test
    fun `several senses for a sentence is a bad reply`() = runTest {
        reply("""{"candidates":[{"definition":"a","example":"b","finnish":"c"},{"definition":"d","example":"e","finnish":"f"}]}""")
        assertEquals(ClaudeResult.Failed("Claude sent an answer the app could not use", keyProblem = false), claude.lookup(config, "x", "A sentence."))
    }

    @Test
    fun `not English is its own result`() = runTest {
        reply("""{"error":"not_english","suggestion":"sanguine"}""")
        assertEquals(ClaudeResult.NotEnglish, claude.lookup(config, "sangine", null))
    }

    @Test
    fun `malformed replies, refusals and cut-off answers fail`() = runTest {
        reply("not json")
        reply("""{"candidates":[]}""")
        reply("""{"candidates":[{"definition":"","example":"e","finnish":"f"}]}""")
        reply("{}", stop = "refusal")
        reply("""{"candidates":[""", stop = "max_tokens")
        repeat(3) { assertEquals(ClaudeResult.Failed("Claude sent an answer the app could not use", keyProblem = false), claude.lookup(config, "x", null)) }
        assertEquals(ClaudeResult.Failed("Claude declined to answer", keyProblem = false), claude.lookup(config, "x", null))
        assertEquals(ClaudeResult.Failed("Claude's answer was cut off", keyProblem = false), claude.lookup(config, "x", null))
    }

    @Test
    fun `a rejected key is a key problem, busy and overloaded are not`() = runTest {
        error(401, "authentication_error")
        error(403, "permission_error")
        error(402, "billing_error")
        error(429, "rate_limit_error")
        error(529, "overloaded_error")
        assertEquals(ClaudeResult.Failed("Claude rejected the API key", keyProblem = true), claude.lookup(config, "x", null))
        assertEquals(ClaudeResult.Failed("Claude rejected the API key", keyProblem = true), claude.lookup(config, "x", null))
        assertEquals(ClaudeResult.Failed("Claude: billing problem (check the account's credit)", keyProblem = true), claude.lookup(config, "x", null))
        assertEquals(ClaudeResult.Failed("Claude is busy (too many requests)", keyProblem = false), claude.lookup(config, "x", null))
        assertEquals(ClaudeResult.Failed("Claude is overloaded (HTTP 529)", keyProblem = false), claude.lookup(config, "x", null))
    }

    @Test
    fun `no connection fails without a key problem`() = runTest {
        server.shutdown()
        assertEquals(ClaudeResult.Failed("No connection to Claude", keyProblem = false), claude.lookup(config, "x", null))
    }

    @Test
    fun `an empty word is not sent`() = runTest {
        assertEquals(ClaudeResult.NotEnglish, claude.lookup(config, "  ", null))
        assertEquals(0, server.requestCount)
    }
}
