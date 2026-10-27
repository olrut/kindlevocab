package io.github.olrut.vocab.api

import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class ApiClientTest {
    private val server = MockWebServer()
    private var token: String? = "tok"
    private var unauthorized = 0
    private lateinit var api: VocabApi

    @Before
    fun setUp() {
        server.start()
        api = createApi(server.url("/api/").toString(), token = { token }, onUnauthorized = { unauthorized++ })
    }

    @After
    fun tearDown() = server.shutdown()

    private fun reply(body: String, code: Int = 200) =
        server.enqueue(MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body))

    @Test
    fun `lists users without a token header when logged out`() = runTest {
        token = null
        reply("""[{"id":1,"name":"olli","hasPin":true}]""")
        assertEquals(listOf(User(1, "olli", true)), api.users())
        val req = server.takeRequest()
        assertEquals("/api/users", req.path)
        assertNull(req.getHeader("Authorization"))
    }

    @Test
    fun `logs in as an android device, leaving out a missing PIN`() = runTest {
        reply("""{"token":"new","user":{"id":1,"name":"olli"}}""")
        val res = api.login(LoginRequest(userId = 1, pin = null, device = "android:Pixel"))
        assertEquals("new", res.token)
        assertEquals("olli", res.user.name)
        assertEquals("""{"userId":1,"device":"android:Pixel"}""", server.takeRequest().body.readUtf8())
    }

    @Test
    fun `reads the learning list with the bearer token, ignoring fields it does not know`() = runTest {
        reply(
            """[{"id":42,"word":"ubiquitous","definition":"everywhere","example":"It is.","finnish":"kaikkialla",
               "ipa":"/x/","hasAudio":true,"source":"kindle","status":"learning","learnedAt":null,"bookTitle":"Dune",
               "bookAuthor":null,"notes":null,"createdAt":"a","updatedAt":"b","deletedAt":null,"future":1}]""",
        )
        val words = api.words("learning")
        assertEquals(Word(42, "ubiquitous", "everywhere", "It is.", "kaikkialla", "/x/", true, "learning"), words.single())
        val req = server.takeRequest()
        assertEquals("/api/words?status=learning", req.path)
        assertEquals("Bearer tok", req.getHeader("Authorization"))
    }

    @Test
    fun `patches only the status`() = runTest {
        reply("""{"id":42,"word":"w","definition":"d","status":"learned"}""")
        api.setStatus(42, StatusPatch("learned"))
        val req = server.takeRequest()
        assertEquals("PATCH", req.method)
        assertEquals("/api/words/42", req.path)
        assertEquals("""{"status":"learned"}""", req.body.readUtf8())
    }

    @Test
    fun `a 401 calls onUnauthorized and still fails the call`() = runTest {
        reply("""{"error":"unauthorized"}""", 401)
        try {
            api.words("learning")
            fail("expected an HttpException")
        } catch (e: HttpException) {
            assertEquals(401, e.code())
        }
        assertEquals(1, unauthorized)
    }

    @Test
    fun `a 401 from login (no token yet) is not a lost session`() = runTest {
        token = null
        reply("""{"error":"unauthorized"}""", 401)
        runCatching { api.users() }
        assertEquals(0, unauthorized)
    }

    @Test
    fun `audio comes back as bytes`() = runTest {
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/mpeg").setBody(Buffer().write(byteArrayOf(1, 2, 3))))
        assertArrayEquals(byteArrayOf(1, 2, 3), api.audio(42).bytes())
        assertEquals("/api/audio/42", server.takeRequest().path)
    }

    @Test
    fun `error messages come from the server's error field`() {
        fun http(code: Int, body: String) = HttpException(Response.error<Unit>(code, body.toResponseBody()))
        assertEquals("wrong PIN", errorMessage(http(403, """{"error":"wrong PIN"}""")))
        assertEquals("HTTP 500", errorMessage(http(500, "oops")))
        assertEquals("Cannot reach the server", errorMessage(java.net.ConnectException("refused")))
    }
}
