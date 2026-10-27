package io.github.olrut.vocab.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerUrlTest {
    @Test
    fun `adds the api path and keeps the scheme, host and port`() {
        assertEquals("https://vocab.tail1234.ts.net/api/", apiBaseUrl("https://vocab.tail1234.ts.net"))
        assertEquals("http://10.0.2.2:3000/api/", apiBaseUrl("http://10.0.2.2:3000/"))
        assertEquals("https://h.ts.net/api/", apiBaseUrl("  https://h.ts.net/api  "))
    }

    @Test
    fun `assumes https when the scheme is left out`() {
        assertEquals("https://vocab.tail1234.ts.net/api/", apiBaseUrl("vocab.tail1234.ts.net"))
    }

    @Test
    fun `rejects what is not a server address`() {
        assertNull(apiBaseUrl(""))
        assertNull(apiBaseUrl("ftp://host"))
        assertNull(apiBaseUrl("https://"))
        assertNull(apiBaseUrl("not a url"))
    }
}
