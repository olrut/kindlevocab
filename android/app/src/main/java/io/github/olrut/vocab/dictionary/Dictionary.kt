package io.github.olrut.vocab.dictionary

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** One meaning of a word, as offered when adding it. */
data class Sense(val partOfSpeech: String?, val definition: String, val example: String?)

sealed interface Lookup {
    data class Found(val ipa: String?, val audioUrl: String?, val senses: List<Sense>) : Lookup
    data object NotFound : Lookup
    data class Failed(val message: String) : Lookup
}

/** Looks a word up; [Dictionary] in the app, a fake in tests. */
fun interface WordLookup {
    suspend fun lookup(word: String): Lookup
}

/**
 * The Free Dictionary API (dictionaryapi.dev, docs/SPEC.md § 3): definitions, examples, IPA and a
 * recording for an English word. Free and keyless. Never throws, apart from cancellation.
 */
class Dictionary(
    private val client: OkHttpClient = defaultClient(),
    private val baseUrl: String = "https://api.dictionaryapi.dev/api/v2/entries/en/",
) : WordLookup {
    override suspend fun lookup(word: String): Lookup {
        val w = word.trim().lowercase()
        if (w.isEmpty()) return Lookup.NotFound
        return withContext(Dispatchers.IO) {
            try {
                val path = URLEncoder.encode(w, "UTF-8").replace("+", "%20")
                client.newCall(Request.Builder().url(baseUrl + path).build()).execute().use { res ->
                    when {
                        res.code == 404 -> Lookup.NotFound
                        !res.isSuccessful -> Lookup.Failed("The dictionary answered HTTP ${res.code}")
                        else -> parse(res.body!!.string())
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Lookup.Failed("No connection to the dictionary")
            } catch (e: SerializationException) {
                Lookup.Failed("The dictionary sent something unexpected")
            } catch (e: IllegalArgumentException) {
                Lookup.Failed("The dictionary sent something unexpected")
            }
        }
    }

    private fun parse(body: String): Lookup {
        val entries = json.decodeFromString<List<Entry>>(body)
        val phonetics = entries.flatMap { it.phonetics }
        val ipa = phonetics.firstNotNullOfOrNull { it.text?.ifBlank { null } } ?: entries.firstNotNullOfOrNull { it.phonetic?.ifBlank { null } }
        // A US recording when there is one, like the text-to-speech fallback (en-US).
        val audios = phonetics.mapNotNull { it.audio?.ifBlank { null } }
        val audio = audios.firstOrNull { it.endsWith("-us.mp3") } ?: audios.firstOrNull()
        val meanings = entries.flatMap { it.meanings }
        val all = meanings.map { m -> m.definitions.filter { it.definition.isNotBlank() }.map { Sense(m.partOfSpeech, it.definition.trim(), it.example?.trim()?.ifEmpty { null }) } }
        // The first sense of each part of speech, then the next ones, up to MAX_SENSES.
        val senses = (all.mapNotNull { it.firstOrNull() } + all.flatMap { it.drop(1) }).take(MAX_SENSES)
        if (senses.isEmpty()) return Lookup.NotFound
        return Lookup.Found(ipa, if (audio != null && audio.startsWith("//")) "https:$audio" else audio, senses)
    }

    @Serializable
    private data class Entry(val phonetic: String? = null, val phonetics: List<Phonetic> = emptyList(), val meanings: List<Meaning> = emptyList())

    @Serializable
    private data class Phonetic(val text: String? = null, val audio: String? = null)

    @Serializable
    private data class Meaning(val partOfSpeech: String? = null, val definitions: List<Definition> = emptyList())

    @Serializable
    private data class Definition(val definition: String = "", val example: String? = null)

    companion object {
        const val MAX_SENSES = 3
        private val json = Json { ignoreUnknownKeys = true }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}
