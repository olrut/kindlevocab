package io.github.olrut.vocab.claude

import io.github.olrut.vocab.dictionary.Lookup
import io.github.olrut.vocab.dictionary.WordLookup
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * The app's lookup (docs/SPEC.md § 3): the dictionary alone, or, with a Claude key set, Claude's
 * senses (with Finnish, and the sense that fits a book sentence) plus the dictionary's IPA and
 * recording, asked at the same time. When Claude fails for a passing reason, the dictionary's
 * answer is used; a key problem is reported, so it gets fixed.
 */
class SmartLookup(
    private val dictionary: WordLookup,
    private val claude: ClaudeLookup,
    private val config: () -> ClaudeConfig?,
) : WordLookup {
    override suspend fun lookup(word: String, context: String?): Lookup {
        val cfg = config() ?: return dictionary.lookup(word, context)
        return coroutineScope {
            val dict = async { dictionary.lookup(word, context) }
            when (val c = claude.lookup(cfg, word, context)) {
                is ClaudeResult.Senses -> {
                    val d = dict.await() as? Lookup.Found
                    Lookup.Found(d?.ipa, d?.audioUrl, c.senses)
                }
                ClaudeResult.NotEnglish -> {
                    dict.cancel()
                    Lookup.NotFound
                }
                is ClaudeResult.Failed -> if (c.keyProblem) {
                    dict.cancel()
                    Lookup.Failed(c.message)
                } else {
                    dict.await().let { if (it is Lookup.Failed) Lookup.Failed("${c.message}; ${it.message.replaceFirstChar(Char::lowercase)}") else it }
                }
            }
        }
    }
}
