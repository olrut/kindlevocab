package io.github.olrut.vocab.transfer

import io.github.olrut.vocab.data.Word
import io.github.olrut.vocab.data.WordDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class TransferTest {
    private val words = listOf(
        Word(
            id = 1, word = "serendipity", definition = "a happy accident", example = "By serendipity, we met.", finnish = "onnekas sattuma",
            ipa = "/ˌsɛɹ.ən.ˈdɪp.ɪ.ti/", status = Word.LEARNED, learnedAt = "2026-03-02T10:00:00.000Z", createdAt = "2026-03-01T10:00:00.000Z",
            bookTitle = "A Book, Vol. 1", bookAuthor = "Someone", notes = "line one\nline \"two\"",
        ),
        Word(id = 2, word = "qwzx", createdAt = "2026-03-03T10:00:00.000Z"),
    )

    private fun expectError(message: String, content: String, format: FileFormat) {
        try {
            Transfer.parse(content, format)
            fail("expected: $message")
        } catch (e: ImportFileException) {
            assertEquals(message, e.message)
        }
    }

    @Test
    fun `csv export has the header and every field`() {
        val csv = Transfer.export(words, FileFormat.Csv)
        val lines = csv.split("\r\n")
        assertEquals("word,definition,example,finnish,ipa,status,learned_at,created_at,book_title,book_author,notes", lines[0])
        // The notes' line break is inside quotes; rows end in CRLF.
        assertEquals("qwzx,,,,,learning,,2026-03-03T10:00:00.000Z,,,", lines[2])
        assertEquals(4, lines.size)
    }

    @Test
    fun `json export uses snake case keys and null`() {
        val json = Transfer.export(words.drop(1), FileFormat.Json)
        assertEquals(true, json.contains("\"learned_at\": null"))
        assertEquals(true, json.contains("\"created_at\": \"2026-03-03T10:00:00.000Z\""))
    }

    @Test
    fun `both formats import what they export`() {
        for (format in FileFormat.entries) {
            val drafts = Transfer.parse(Transfer.export(words, format), format)
            assertEquals(
                WordDraft(
                    word = "serendipity", definition = "a happy accident", example = "By serendipity, we met.", finnish = "onnekas sattuma",
                    notes = "line one\nline \"two\"", ipa = "/ˌsɛɹ.ən.ˈdɪp.ɪ.ti/", source = Word.SOURCE_IMPORT, status = Word.LEARNED,
                    bookTitle = "A Book, Vol. 1", bookAuthor = "Someone", createdAt = "2026-03-01T10:00:00.000Z", learnedAt = "2026-03-02T10:00:00.000Z",
                ),
                drafts[0],
            )
            assertEquals(WordDraft(word = "qwzx", source = Word.SOURCE_IMPORT, createdAt = "2026-03-03T10:00:00.000Z"), drafts[1])
        }
    }

    @Test
    fun `csv columns may be in any order and only word is required, blanks are null`() {
        val drafts = Transfer.parse("﻿Definition, Word ,status\r\n  ,  Hello ,\r\n", FileFormat.Csv)
        assertEquals(WordDraft(word = "hello", source = Word.SOURCE_IMPORT), drafts.single())
        assertNull(drafts.single().definition)
    }

    @Test
    fun `timestamps with an offset are stored in UTC`() {
        val d = Transfer.parse("word,created_at\nx,2026-03-01T12:00:00+02:00\n", FileFormat.Csv).single()
        assertEquals("2026-03-01T10:00:00.000Z", d.createdAt)
    }

    @Test
    fun `the first bad row is named and nothing is returned`() {
        expectError("The file is empty", " \n", FileFormat.Csv)
        expectError("Missing column: word", "definition\nx\n", FileFormat.Csv)
        expectError("Row 3: has 1 fields, the header has 2", "word,definition\na,b\nc\n", FileFormat.Csv)
        expectError("Row 2: word is required", "word,definition\n ,b\n", FileFormat.Csv)
        expectError("Row 2: word is too long", "word\n${"x".repeat(101)}\n", FileFormat.Csv)
        expectError("Row 2: status must be \"learning\" or \"learned\"", "word,status\na,done\n", FileFormat.Csv)
        expectError("Row 2: created_at must be an ISO-8601 timestamp", "word,created_at\na,yesterday\n", FileFormat.Csv)
        expectError("Row 2: notes are too long", "word,notes\na,${"n".repeat(10_001)}\n", FileFormat.Csv)
        expectError("Row 2: A field can be at most 2000 characters", "word,definition\na,${"d".repeat(2001)}\n", FileFormat.Csv)
        expectError("Not a valid CSV file: unterminated quoted field", "word\n\"a\n", FileFormat.Csv)
    }

    @Test
    fun `json problems are named`() {
        expectError("Not a valid JSON file", "{nope", FileFormat.Json)
        expectError("Expected a JSON array of words", "{\"word\":\"a\"}", FileFormat.Json)
        expectError("Item 2: expected an object", "[{\"word\":\"a\"},3]", FileFormat.Json)
        expectError("Item 1: definition must be text", "[{\"word\":\"a\",\"definition\":5}]", FileFormat.Json)
    }

    @Test
    fun `the format comes from the file name`() {
        assertEquals(FileFormat.Csv, FileFormat.of("vocab-2026.CSV"))
        assertEquals(FileFormat.Json, FileFormat.of("x.json"))
        assertNull(FileFormat.of("vocab.db"))
    }
}
