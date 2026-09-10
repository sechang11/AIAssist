package com.aitextassistant.generate

import com.aitextassistant.remix.BeatAnswer
import kotlinx.coroutines.delay

/**
 * Offline stand-in so the remix screen can be built and demoed with no API key
 * and no network. These are mechanical string edits, not paraphrases; they fill
 * the grid with something structurally correct and they look stupid. Judge the
 * interaction here and the writing against [ClaudeBeatRewriter].
 */
class StubBeatRewriter : BeatRewriter {

    override suspend fun rewrite(
        whole: String,
        fragment: String,
        tones: List<String>,
    ): BeatAnswer {
        delay(180) // so the beats visibly stream in during UI work
        val made = listOf(concise(fragment), fragment, formal(fragment))
        return BeatAnswer(
            label = "part",
            optional = fragment.length < 25,
            alternatives = List(tones.size) { made.getOrElse(it) { fragment } },
        )
    }

    private fun concise(s: String): String =
        s.replace(FILLER, "").replace(Regex("\s+"), " ").trim().ifEmpty { s }

    private fun formal(s: String): String =
        CONTRACTIONS.entries
            .fold(s) { acc, (short, long) -> acc.replace(Regex("\b$short\b", RegexOption.IGNORE_CASE), long) }
            .replaceFirstChar { it.uppercase() }

    private companion object {
        val FILLER = Regex(
            "\b(just|really|actually|basically|kind of|sort of|I think|I guess)\b\s*",
            RegexOption.IGNORE_CASE,
        )
        val CONTRACTIONS = mapOf(
            "can't" to "cannot", "cant" to "cannot", "won't" to "will not",
            "don't" to "do not", "doesn't" to "does not", "didn't" to "did not",
            "didnt" to "did not", "I'm" to "I am", "im" to "I am", "I'll" to "I will",
            "I'd" to "I would", "I've" to "I have", "ill" to "I will",
            "it's" to "it is", "thats" to "that is", "that's" to "that is",
            "theres" to "there is", "we're" to "we are", "you're" to "you are",
            "isn't" to "is not", "wasn't" to "was not", "couldn't" to "could not",
            "wouldn't" to "would not", "gonna" to "going to", "wanna" to "want to",
        )
    }
}
