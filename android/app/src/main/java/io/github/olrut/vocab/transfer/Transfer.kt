package io.github.olrut.vocab.transfer

import io.github.olrut.vocab.data.Word
import io.github.olrut.vocab.data.WordDraft
import io.github.olrut.vocab.data.WordRepository
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

enum class FileFormat(val extension: String, val mimeType: String) {
    Csv("csv", "text/csv"),
    Json("json", "application/json");

    companion object {
        /** From a file name's extension; null if it is neither. */
        fun of(name: String): FileFormat? = entries.firstOrNull { name.lowercase().endsWith(".${it.extension}") }
    }
}

/** The file cannot be imported. The message is shown to the user as is. */
class ImportFileException(message: String) : Exception(message)

/**
 * Export and import of the word list (docs/SPEC.md § 3, Export / import), the same shape the
 * server version used, so its exports import here.
 */
object Transfer {
    /** Fields of an exported word, in CSV column order. */
    val COLUMNS = listOf("word", "definition", "example", "finnish", "ipa", "status", "learned_at", "created_at", "book_title", "book_author", "notes")

    private val json = Json { prettyPrint = true }

    private fun Word.fields(): List<String?> = listOf(word, definition, example, finnish, ipa, status, learnedAt, createdAt, bookTitle, bookAuthor, notes)

    fun export(words: List<Word>, format: FileFormat): String = when (format) {
        FileFormat.Csv -> Csv.write(listOf(COLUMNS) + words.map { it.fields() })
        FileFormat.Json -> json.encodeToString(
            JsonArray.serializer(),
            buildJsonArray {
                for (w in words) add(buildJsonObject { COLUMNS.zip(w.fields()).forEach { (k, v) -> put(k, v) } })
            },
        ) + "\n"
    }

    /** Parses a file in the export shape. Throws [ImportFileException] on any problem; nothing partial is returned. */
    fun parse(content: String, format: FileFormat): List<WordDraft> {
        val text = content.removePrefix("﻿")
        if (text.isBlank()) throw ImportFileException("The file is empty")
        return when (format) {
            FileFormat.Csv -> parseCsv(text)
            FileFormat.Json -> parseJson(text)
        }
    }

    private fun parseJson(text: String): List<WordDraft> {
        val data = try {
            Json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            throw ImportFileException("Not a valid JSON file")
        }
        if (data !is JsonArray) throw ImportFileException("Expected a JSON array of words")
        return data.mapIndexed { i, item ->
            val label = "Item ${i + 1}"
            if (item !is JsonObject) throw ImportFileException("$label: expected an object")
            record(label) { name -> textOf(item[name], label, name) }
        }
    }

    private fun textOf(e: JsonElement?, label: String, name: String): String? = when {
        e == null || e is JsonNull -> null
        e is JsonPrimitive && e.isString -> e.content
        else -> throw ImportFileException("$label: $name must be text")
    }

    private fun parseCsv(text: String): List<WordDraft> {
        val rows = try {
            Csv.parse(text)
        } catch (e: Csv.CsvException) {
            throw ImportFileException("Not a valid CSV file: ${e.message}")
        }
        val names = rows.first().map { it.trim().lowercase() }
        if ("word" !in names) throw ImportFileException("Missing column: word")
        // Row numbers count the header as row 1, as a spreadsheet shows them.
        return rows.drop(1).mapIndexed { i, cells ->
            val label = "Row ${i + 2}"
            if (cells.size != names.size) throw ImportFileException("$label: has ${cells.size} fields, the header has ${names.size}")
            val byName = names.zip(cells).toMap()
            record(label) { byName[it] }
        }
    }

    /** One word from its fields; blank text means no value. */
    private fun record(label: String, field: (String) -> String?): WordDraft {
        fun text(name: String) = field(name)?.trim()?.ifEmpty { null }
        fun fail(message: String): Nothing = throw ImportFileException("$label: $message")
        fun timestamp(name: String) = text(name)?.let {
            try {
                WordRepository.ISO.format(OffsetDateTime.parse(it, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant())
            } catch (e: DateTimeParseException) {
                fail("$name must be an ISO-8601 timestamp")
            }
        }

        val word = text("word")?.lowercase() ?: fail("word is required")
        if (word.length > WordRepository.MAX_WORD) fail("word is too long")
        val status = text("status")
        if (status != null && status != Word.LEARNING && status != Word.LEARNED) fail("status must be \"learning\" or \"learned\"")
        val notes = text("notes")
        if (notes != null && notes.length > WordRepository.MAX_NOTES) fail("notes are too long")
        val draft = WordDraft(
            word = word,
            definition = text("definition"),
            example = text("example"),
            finnish = text("finnish"),
            notes = notes,
            ipa = text("ipa"),
            source = Word.SOURCE_IMPORT,
            status = status ?: Word.LEARNING,
            bookTitle = text("book_title"),
            bookAuthor = text("book_author"),
            createdAt = timestamp("created_at"),
            learnedAt = timestamp("learned_at"),
        )
        try {
            WordRepository.validate(draft)
        } catch (e: Exception) {
            fail(e.message ?: "not valid")
        }
        return draft
    }
}
