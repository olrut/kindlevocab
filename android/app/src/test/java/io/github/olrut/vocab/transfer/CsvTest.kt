package io.github.olrut.vocab.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CsvTest {
    @Test
    fun `writes CRLF rows, quotes only what needs it, null is empty`() {
        val text = Csv.write(listOf(listOf("a", "b, c", null), listOf("say \"hi\"", " lead", "line\nbreak")))
        assertEquals("a,\"b, c\",\r\n\"say \"\"hi\"\"\",\" lead\",\"line\nbreak\"\r\n", text)
    }

    @Test
    fun `parses what it writes`() {
        val rows = listOf(listOf("word", "definition"), listOf("x, y", "\"q\" and\r\nmore"), listOf("", "trail "))
        assertEquals(rows, Csv.parse(Csv.write(rows)))
    }

    @Test
    fun `accepts LF, a BOM and blank lines`() {
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d")), Csv.parse("﻿a,b\n\nc,d\n"))
    }

    @Test
    fun `a quoted empty field on its own line is a row`() {
        assertEquals(listOf(listOf("a"), listOf("")), Csv.parse("a\n\"\"\n"))
    }

    @Test
    fun `rejects broken quoting`() {
        assertThrows(Csv.CsvException::class.java) { Csv.parse("\"open,b\n") }
        assertThrows(Csv.CsvException::class.java) { Csv.parse("\"a\"x,b\n") }
    }
}
