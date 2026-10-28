package io.github.olrut.vocab.dictionary

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class DictionaryTest {
    private lateinit var server: MockWebServer
    private lateinit var dictionary: Dictionary

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        dictionary = Dictionary(OkHttpClient.Builder().readTimeout(1, TimeUnit.SECONDS).build(), server.url("/entries/en/").toString())
    }

    @After
    fun tearDown() = server.shutdown()

    private fun reply(body: String, code: Int = 200) = server.enqueue(MockResponse().setResponseCode(code).setBody(body))

    @Test
    fun `takes the ipa, audio and one sense per part of speech first`() = runTest {
        reply(
            """
            [{"word":"run","phonetic":"/rʌn/","phonetics":[{"text":"/rʌn/","audio":""},{"audio":"https://x/run-us.mp3"}],
              "meanings":[
                {"partOfSpeech":"verb","definitions":[{"definition":"Move fast on foot.","example":"She ran home."},{"definition":"Manage a business."}]},
                {"partOfSpeech":"noun","definitions":[{"definition":"An act of running."}]}
              ]},
             {"word":"run","meanings":[{"partOfSpeech":"adjective","definitions":[{"definition":"Melted."}]}]}]
            """,
        )
        val r = dictionary.lookup("Run ") as Lookup.Found
        assertEquals("/entries/en/run", server.takeRequest().path)
        assertEquals("/rʌn/", r.ipa)
        assertEquals("https://x/run-us.mp3", r.audioUrl)
        assertEquals(
            listOf(
                Sense("verb", "Move fast on foot.", "She ran home."),
                Sense("noun", "An act of running.", null),
                Sense("adjective", "Melted.", null),
            ),
            r.senses,
        )
    }

    @Test
    fun `fills up to three senses from further definitions`() = runTest {
        reply(
            """[{"meanings":[{"partOfSpeech":"verb","definitions":[{"definition":"a"},{"definition":"b"},{"definition":"c"},{"definition":"d"}]}]}]""",
        )
        val r = dictionary.lookup("x") as Lookup.Found
        assertEquals(listOf("a", "b", "c"), r.senses.map { it.definition })
    }

    @Test
    fun `falls back to the entry phonetic and fixes protocol-relative audio`() = runTest {
        reply("""[{"phonetic":"/kæt/","phonetics":[{"audio":"//ssl.gstatic.com/cat.mp3"}],"meanings":[{"partOfSpeech":"noun","definitions":[{"definition":"A pet."}]}]}]""")
        val r = dictionary.lookup("cat") as Lookup.Found
        assertEquals("/kæt/", r.ipa)
        assertEquals("https://ssl.gstatic.com/cat.mp3", r.audioUrl)
    }

    @Test
    fun `prefers the US recording`() = runTest {
        reply("""[{"phonetics":[{"audio":"https://x/cat-uk.mp3"},{"audio":"https://x/cat-us.mp3"}],"meanings":[{"partOfSpeech":"noun","definitions":[{"definition":"A pet."}]}]}]""")
        assertEquals("https://x/cat-us.mp3", (dictionary.lookup("cat") as Lookup.Found).audioUrl)
    }

    @Test
    fun `no phonetics means no ipa and no audio`() = runTest {
        reply("""[{"meanings":[{"partOfSpeech":"noun","definitions":[{"definition":"A pet."}]}]}]""")
        val r = dictionary.lookup("cat") as Lookup.Found
        assertNull(r.ipa)
        assertNull(r.audioUrl)
    }

    @Test
    fun `encodes the word into the path`() = runTest {
        reply("[]", 404)
        dictionary.lookup("ice cream")
        assertEquals("/entries/en/ice%20cream", server.takeRequest().path)
    }

    @Test
    fun `a 404 is not found`() = runTest {
        reply("""{"title":"No Definitions Found"}""", 404)
        assertEquals(Lookup.NotFound, dictionary.lookup("qwzx"))
    }

    @Test
    fun `an entry without definitions is not found`() = runTest {
        reply("""[{"word":"x","meanings":[]}]""")
        assertEquals(Lookup.NotFound, dictionary.lookup("x"))
    }

    @Test
    fun `a server error or bad json is a failure`() = runTest {
        reply("oops", 500)
        assertTrue(dictionary.lookup("a") is Lookup.Failed)
        reply("{not json")
        assertTrue(dictionary.lookup("a") is Lookup.Failed)
    }

    @Test
    fun `no connection is a failure that says so`() = runTest {
        server.shutdown()
        val r = dictionary.lookup("a") as Lookup.Failed
        assertEquals("No connection to the dictionary", r.message)
    }

    @Test
    fun `an empty word is not looked up`() = runTest {
        assertEquals(Lookup.NotFound, dictionary.lookup("  "))
        assertEquals(0, server.requestCount)
    }
}
