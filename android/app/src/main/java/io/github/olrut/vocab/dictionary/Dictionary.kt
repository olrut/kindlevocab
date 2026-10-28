package io.github.olrut.vocab.dictionary

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** One meaning of a word, as offered when adding it. [finnish] comes only from Claude. */
data class Sense(val partOfSpeech: String?, val definition: String, val example: String?, val finnish: String? = null)

sealed interface Lookup {
    data class Found(val ipa: String?, val audioUrl: String?, val senses: List<Sense>) : Lookup
    data object NotFound : Lookup
    data class Failed(val message: String) : Lookup
}

/** Looks a word up; [context] is the sentence it was seen in (Kindle), when known. */
fun interface WordLookup {
    suspend fun lookup(word: String, context: String?): Lookup
}

/**
 * The Free Dictionary API (dictionaryapi.dev, docs/SPEC.md § 3): definitions, examples, IPA and a
 * recording for an English word. Free and keyless, but rate limited: a 429 is retried after the
 * time it asks for (or a growing pause). Ignores the context. Never throws, apart from cancellation.
 */
class Dictionary(
    private val client: OkHttpClient = defaultClient(),
    private val baseUrl: String = "https://api.dictionaryapi.dev/api/v2/entries/en/",
    private val pause: suspend (Long) -> Unit = { delay(it) },
) : WordLookup {
    override suspend fun lookup(word: String, context: String?): Lookup {
        val w = word.trim().lowercase()
        if (w.isEmpty()) return Lookup.NotFound
        val path = URLEncoder.encode(w, "UTF-8").replace("+", "%20")
        var attempt = 0
        while (true) {
            val (result, retryAfterMs) = fetch(path)
            if (retryAfterMs == null) return result
            if (++attempt > MAX_RETRIES) return Lookup.Failed("The dictionary is busy (too many requests); try again in a minute")
            pause(retryAfterMs.coerceAtMost(MAX_PAUSE_MS).coerceAtLeast(BASE_PAUSE_MS shl (attempt - 1)))
        }
    }

    /** The result, or (on a 429) how long the server asks to wait. */
    private suspend fun fetch(path: String): Pair<Lookup, Long?> =
        withContext(Dispatchers.IO) {
            try {
                client.newCall(Request.Builder().url(baseUrl + path).build()).execute().use { res ->
                    when {
                        res.code == 429 -> Lookup.Failed("busy") to (res.header("Retry-After")?.trim()?.toLongOrNull()?.times(1000) ?: 0L)
                        res.code == 404 -> Lookup.NotFound to null
                        !res.isSuccessful -> Lookup.Failed("The dictionary answered HTTP ${res.code}") to null
                        else -> parse(res.body!!.string()) to null
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Lookup.Failed("No connection to the dictionary") to null
            } catch (e: SerializationException) {
                Lookup.Failed("The dictionary sent something unexpected") to null
            } catch (e: IllegalArgumentException) {
                Lookup.Failed("The dictionary sent something unexpected") to null
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
        const val MAX_RETRIES = 3
        /** The pause before the first retry when the server does not say; doubles each time. */
        const val BASE_PAUSE_MS = 2_000L
        const val MAX_PAUSE_MS = 30_000L
        private val json = Json { ignoreUnknownKeys = true }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}
