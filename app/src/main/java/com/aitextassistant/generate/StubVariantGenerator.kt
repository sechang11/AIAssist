package com.aitextassistant.generate

import com.aitextassistant.remix.RemixDraft
import com.aitextassistant.remix.Slot
import kotlinx.coroutines.delay

/**
 * Offline stand-in so the remix screen can be built and demoed with no API key
 * and no network. The rewrites are mechanical string surgery, not paraphrases -
 * they exist to fill the grid with something structurally correct, and they will
 * look stupid. That is fine. Judge the interaction here, judge the writing
 * against [ClaudeVariantGenerator].
 */
class StubVariantGenerator : VariantGenerator {

    override suspend fun generate(original: String, tones: List<String>): RemixDraft {
        delay(400) // so the loading state is visible during UI work

        val slots = splitSentences(original).mapIndexed { i, sentence ->
            Slot(
                id = "stub$i",
                label = if (i == 0) "opening" else "part ${i + 1}",
                optional = i > 0 && sentence.length < 25,
                alternatives = listOf(concise(sentence), sentence, formal(sentence))
                    .take(tones.size)
                    .let { alts -> alts + List((tones.size - alts.size).coerceAtLeast(0)) { sentence } },
            )
        }
        return RemixDraft(tones, slots).validated()
            ?: RemixDraft(tones, listOf(Slot("stub0", "message", false, List(tones.size) { original })))
    }

    private fun splitSentences(text: String): List<String> =
        Regex("(?<=[.!?])\s+")
            .split(text.trim())
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .ifEmpty { listOf(text.trim()) }

    private fun concise(s: String): String =
        s.replace(Regex("\b(just|really|actually|basically|kind of|sort of|I think|I guess)\b\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\s+"), " ")
            .trim()

    private fun formal(s: String): String =
        CONTRACTIONS.entries
            .fold(s) { acc, (short, long) -> acc.replace(Regex("\b$short\b", RegexOption.IGNORE_CASE), long) }
            .replaceFirstChar { it.uppercase() }

    private companion object {
        val CONTRACTIONS = mapOf(
            "can't" to "cannot", "won't" to "will not", "don't" to "do not",
            "doesn't" to "does not", "didn't" to "did not", "I'm" to "I am",
            "I'll" to "I will", "I'd" to "I would", "I've" to "I have",
            "it's" to "it is", "that's" to "that is", "there's" to "there is",
            "we're" to "we are", "you're" to "you are", "isn't" to "is not",
            "wasn't" to "was not", "couldn't" to "could not", "wouldn't" to "would not",
            "thanks" to "thank you", "gonna" to "going to", "wanna" to "want to",
        )
    }
}
