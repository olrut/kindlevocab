package io.github.olrut.vocab.claude

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams
import io.github.olrut.vocab.dictionary.Sense
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Duration

/** The models offered in the app; [id] is the API's model id. */
enum class ClaudeModel(val id: String, val label: String, val fallback: Boolean) {
    Opus("claude-opus-5-5", "Claude Opus 5.5 (best)", fallback = true),
    Sonnet("claude-sonnet-5-5", "Claude Sonnet 5.5", fallback = true),
    // Haiku has no server-side refusal fallback.
    Haiku("claude-haiku-5-5", "Claude Haiku 5.5 (cheapest)", fallback = false),
}

data class ClaudeConfig(val apiKey: String, val model: ClaudeModel)

sealed interface ClaudeResult {
    data class Senses(val senses: List<Sense>) : ClaudeResult
    data object NotEnglish : ClaudeResult
    /** [keyProblem]: the key or its account must be fixed; otherwise it may pass (busy, network, a bad answer). */
    data class Failed(val message: String, val keyProblem: Boolean) : ClaudeResult
}

/**
 * Word lookups with Claude (docs/SPEC.md § 3), with the user's own API key: definitions, examples
 * and Finnish equivalents, and with a sentence from a book, the sense that fits it. The key goes
 * only to the Anthropic API. Never throws, apart from cancellation.
 */
class ClaudeLookup(private val baseUrl: String? = null, private val maxRetries: Int = 2) {
    private var client: Pair<String, AnthropicClient>? = null

    @Synchronized
    private fun client(key: String): AnthropicClient {
        client?.let { (k, c) -> if (k == key) return c }
        val builder = AnthropicOkHttpClient.builder().apiKey(key).maxRetries(maxRetries).timeout(Duration.ofSeconds(60))
        baseUrl?.let(builder::baseUrl)
        return builder.build().also { client = key to it }
    }

    suspend fun lookup(config: ClaudeConfig, word: String, context: String?): ClaudeResult {
        val w = word.trim().lowercase()
        if (w.isEmpty()) return ClaudeResult.NotEnglish
        return withContext(Dispatchers.IO) {
            try {
                ask(config, w, context?.trim()?.ifEmpty { null })
            } catch (e: CancellationException) {
                throw e
            } catch (e: AnthropicServiceException) {
                failure(e.statusCode())
            } catch (e: AnthropicIoException) {
                ClaudeResult.Failed("No connection to Claude", keyProblem = false)
            }
        }
    }

    private fun ask(config: ClaudeConfig, word: String, context: String?): ClaudeResult {
        val params = MessageCreateParams.builder()
            .model(config.model.id)
            // Thinking is on and counts toward max_tokens; low effort keeps it short, and this
            // leaves room for it plus the ~150-token answer.
            .maxTokens(4096L)
            .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build())
            .system(systemPrompt(context))
            .addUserMessage(word)
        if (config.model.fallback) {
            // A declined request is retried on another model, chosen by the API.
            params.addBeta("server-side-fallback-2026-07-01").putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }
        val message = client(config.apiKey).beta().messages().create(params.build())
        when (message.stopReason().orElse(null)) {
            BetaStopReason.REFUSAL -> return ClaudeResult.Failed("Claude declined to answer", keyProblem = false)
            BetaStopReason.MAX_TOKENS -> return ClaudeResult.Failed("Claude's answer was cut off", keyProblem = false)
            else -> {}
        }
        val text = message.content().joinToString("") { block -> block.text().map { it.text() }.orElse("") }
        return parse(text, context) ?: ClaudeResult.Failed("Claude sent an answer the app could not use", keyProblem = false)
    }

    private fun failure(status: Int): ClaudeResult.Failed = when (status) {
        401, 403 -> ClaudeResult.Failed("Claude rejected the API key", keyProblem = true)
        402 -> ClaudeResult.Failed("Claude: billing problem (check the account's credit)", keyProblem = true)
        429 -> ClaudeResult.Failed("Claude is busy (too many requests)", keyProblem = false)
        else -> if (status >= 500) {
            ClaudeResult.Failed("Claude is overloaded (HTTP $status)", keyProblem = false)
        } else {
            ClaudeResult.Failed("Claude answered HTTP $status", keyProblem = false)
        }
    }

    @Serializable
    private data class Reply(val candidates: List<Candidate>? = null, val error: String? = null)

    @Serializable
    private data class Candidate(val definition: String = "", val example: String = "", val finnish: String = "")

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        // The prompt of version 1's server (docs/SPEC.md § 3).
        private const val SYSTEM_PROMPT = """You are a dictionary for an English learner whose native language is Finnish.
Given one English word, return ONLY a JSON object, no prose:

{"candidates":[{"definition":"...","example":"...","finnish":"..."}]}

Rules:
- 1 to 3 candidates, most common meaning first. Separate candidates only for clearly different senses.
- definition: plain English, max 12 words, no leading article, no repetition of the word.
- example: one natural sentence using the word, max 15 words.
- finnish: 1-3 Finnish equivalents separated by commas, base form.
- If the input is not an English word or is misspelled, return {"error":"not_english","suggestion":"<likely intended word or null>"}."""

        fun systemPrompt(context: String?): String = if (context == null) SYSTEM_PROMPT else SYSTEM_PROMPT + """

The user saw the word in this sentence: "$context"
Return exactly one candidate: the sense that fits this sentence. Use the given sentence verbatim as the example.
If the word in the sentence is an inflected form, still define the base form."""

        /** The senses in a reply, or null if it is not usable. With a sentence, exactly one, with the sentence as its example. */
        fun parse(text: String, context: String?): ClaudeResult? {
            val body = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val reply = try {
                json.decodeFromString<Reply>(body)
            } catch (e: Exception) {
                return null
            }
            if (reply.error == "not_english") return ClaudeResult.NotEnglish
            val candidates = reply.candidates?.map { Candidate(it.definition.trim(), it.example.trim(), it.finnish.trim()) } ?: return null
            if (candidates.isEmpty() || candidates.size > 3 || candidates.any { it.definition.isEmpty() || it.example.isEmpty() || it.finnish.isEmpty() }) return null
            if (context != null && candidates.size != 1) return null
            // The book's sentence is the example; don't trust the model to copy it exactly.
            return ClaudeResult.Senses(candidates.map { Sense(null, it.definition, context ?: it.example, it.finnish) })
        }
    }
}
