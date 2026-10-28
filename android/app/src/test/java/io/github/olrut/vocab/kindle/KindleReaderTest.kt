package io.github.olrut.vocab.kindle

import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/** Builds Kindle-shaped databases (the Vocabulary Builder's schema) and reads them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KindleReaderTest {
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("kindle").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun db(name: String = "vocab.db", build: SQLiteDatabase.() -> Unit): File {
        val f = File(dir, name)
        SQLiteDatabase.openOrCreateDatabase(f, null).use { it.build() }
        return f
    }

    private fun SQLiteDatabase.kindleSchema() {
        execSQL("CREATE TABLE WORDS (id TEXT PRIMARY KEY, word TEXT, stem TEXT, lang TEXT, category INTEGER DEFAULT 0, timestamp INTEGER DEFAULT 0, profileid TEXT)")
        execSQL("CREATE TABLE LOOKUPS (id TEXT PRIMARY KEY, word_key TEXT, book_key TEXT, dict_key TEXT, pos TEXT, usage TEXT, timestamp INTEGER DEFAULT 0)")
        execSQL("CREATE TABLE BOOK_INFO (id TEXT PRIMARY KEY, asin TEXT, guid TEXT, lang TEXT, title TEXT, authors TEXT)")
    }

    private fun SQLiteDatabase.word(id: String, word: String, stem: String?, ts: Long, lang: String = "en", category: Int = 0) =
        execSQL("INSERT INTO WORDS (id, word, stem, lang, category, timestamp) VALUES (?, ?, ?, ?, ?, ?)", arrayOf(id, word, stem, lang, category, ts))

    private fun SQLiteDatabase.lookup(id: String, wordKey: String, book: String?, usage: String?, ts: Long) =
        execSQL("INSERT INTO LOOKUPS (id, word_key, book_key, usage, timestamp) VALUES (?, ?, ?, ?, ?)", arrayOf(id, wordKey, book, usage, ts))

    private fun SQLiteDatabase.book(id: String, title: String, authors: String) =
        execSQL("INSERT INTO BOOK_INFO (id, title, authors) VALUES (?, ?, ?)", arrayOf(id, title, authors))

    @Test
    fun `reads English words with their newest sentence and book, newest first`() {
        val f = db {
            kindleSchema()
            book("b1", "  Middlemarch ", "George Eliot")
            book("b2", "Dune", "Frank Herbert")
            word("en:Ephemeral", "Ephemeral", "ephemeral", 1000)
            word("en:sanguine", "sanguine", "Sanguine", 3000, category = 1)
            word("fi:kissa", "kissa", "kissa", 5000, lang = "fi")
            word("de:Haus", "Haus", "haus", 6000, lang = "de-DE")
            lookup("l1", "en:Ephemeral", "b1", "Old sentence.", 1000)
            lookup("l2", "en:Ephemeral", "b2", "  Newer ephemeral sentence. ", 2000)
        }
        val v = KindleReader.read(f)
        assertEquals(listOf("sanguine", "ephemeral"), v.words.map { it.word })
        val e = v.words[1]
        assertEquals("Newer ephemeral sentence.", e.usage)
        assertEquals("Dune", e.bookTitle)
        assertEquals("Frank Herbert", e.bookAuthor)
        assertEquals(2000L, e.lookupAt)
        assertEquals(false, e.mastered)
        val s = v.words[0]
        assertEquals(true, s.mastered)
        assertNull(s.usage)
        assertNull(s.lookupAt)
        assertEquals(3000L, v.maxTimestamp)
    }

    @Test
    fun `inflected forms merge into their stem`() {
        val f = db {
            kindleSchema()
            book("b1", "Book", "A")
            word("en:ran", "ran", "run", 1000, category = 1)
            word("en:running", "running", "run", 3000, category = 0)
            lookup("l1", "en:ran", "b1", "She ran.", 5000)
            lookup("l2", "en:running", "b1", "Running late.", 3000)
        }
        val w = KindleReader.read(f).words.single()
        assertEquals("run", w.word)
        // The newest WORDS row sets the category and timestamp; the newest lookup the sentence.
        assertEquals(false, w.mastered)
        assertEquals(3000L, w.timestamp)
        assertEquals("She ran.", w.usage)
    }

    @Test
    fun `en variants count as English and a missing stem falls back to the word`() {
        val f = db {
            kindleSchema()
            word("a", "Colour", null, 1, lang = "en-GB")
            word("b", "Gotten", "", 2, lang = "EN_us")
        }
        assertEquals(setOf("colour", "gotten"), KindleReader.read(f).words.map { it.word }.toSet())
    }

    @Test
    fun `works without the optional tables and columns`() {
        val f = db {
            execSQL("CREATE TABLE WORDS (id TEXT, word TEXT)")
            execSQL("INSERT INTO WORDS VALUES ('1', 'Lone')")
        }
        val v = KindleReader.read(f)
        assertEquals("lone", v.words.single().word)
        assertEquals(0L, v.words.single().timestamp)
    }

    @Test
    fun `values of the wrong type are ignored and long text is cut`() {
        val f = db {
            kindleSchema()
            book("b1", "T".repeat(600), "A")
            word("w1", "x".repeat(101), "y".repeat(101), 1)
            word("w2", "fine", "fine", 2)
            execSQL("INSERT INTO WORDS (id, word, stem, lang, category, timestamp) VALUES ('w3', X'6869', X'6869', 'en', 0, 'soon')")
            lookup("l1", "w2", "b1", "u".repeat(2500), 2)
        }
        val v = KindleReader.read(f)
        val w = v.words.single()
        assertEquals("fine", w.word)
        assertEquals(2000, w.usage!!.length)
        assertEquals(500, w.bookTitle!!.length)
    }

    @Test
    fun `a file that is not a Kindle vocab db is refused`() {
        val garbage = File(dir, "garbage.db").apply { writeText("this is not sqlite at all, just text") }
        val noWords = db("other.db") { execSQL("CREATE TABLE notes (id INTEGER)") }
        val noColumns = db("cols.db") { execSQL("CREATE TABLE WORDS (foo TEXT)") }
        for ((f, message) in listOf(garbage to "This is not a Kindle vocab.db file", noWords to "No WORDS table: this is not a Kindle vocab.db", noColumns to "The WORDS table has no id/word columns")) {
            try {
                KindleReader.read(f)
                fail("expected ${f.name} to be refused")
            } catch (e: KindleFileException) {
                assertEquals(message, e.message)
            }
        }
    }

    @Test
    fun `an empty Kindle list has no timestamp`() {
        val v = KindleReader.read(db { kindleSchema() })
        assertEquals(emptyList<KindleWord>(), v.words)
        assertNull(v.maxTimestamp)
    }
}
