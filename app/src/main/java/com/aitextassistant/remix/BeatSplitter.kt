package com.aitextassistant.remix

/**
 * Cuts a message into beats using punctuation and length alone, and decides
 * which words a rewrite is not allowed to lose.
 *
 * A direct port of eval/pipeline.py. If you change one, change the other, or
 * the eval numbers stop predicting what the app does.
 *
 * No model is involved on purpose. The one-shot approach asked a model to
 * segment, paraphrase and keep its bookkeeping straight in a single pass, and
 * measurement showed the bookkeeping was what broke: output stayed fluent while
 * the content went wrong. Splitting in code cannot hallucinate a beat, cannot
 * drop a clause, costs nothing, and takes no time.
 */
object BeatSplitter {

    private val SENTENCE = Regex("(?<=[.!?])\\s+")
    private val COMMA = Regex(",\\s+")
    private val WHITESPACE = Regex("\\s+")
    private val TOKEN = Regex("[A-Za-z0-9:.]*[A-Za-z0-9][A-Za-z0-9:.]*")

    /**
     * A comma inside a long run is usually a real beat boundary in a text
     * message, where people rarely bother with full stops. Below this length it
     * usually is not.
     */
    private const val SPLIT_OVER = 45
    private const val MERGE_UNDER = 14
    private const val MAX_BEATS = 6

    /** Lowercase and still load-bearing. Capitalisation alone misses every "friday". */
    private val ALWAYS_HARD = setOf(
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
        "today", "tomorrow", "tonight", "weekend", "january", "february", "march",
        "april", "may", "june", "july", "august", "september", "october", "november",
        "december",
    )

    fun split(text: String): List<String> {
        val cleaned = text.replace(WHITESPACE, " ").trim()
        if (cleaned.isEmpty()) return emptyList()

        val sentences = SENTENCE.split(cleaned).map { it.trim() }.filter { it.isNotEmpty() }

        val finer = sentences.flatMap { piece ->
            if (piece.length > SPLIT_OVER && piece.contains(',')) {
                COMMA.split(piece).map { it.trim() }.filter { it.isNotEmpty() }
            } else {
                listOf(piece)
            }
        }

        // A three-word tail is not a beat, it belongs to the one before it.
        val merged = mutableListOf<String>()
        for (piece in finer) {
            if (merged.isNotEmpty() && piece.length < MERGE_UNDER) {
                merged[merged.lastIndex] = (merged.last().trimEnd(',') + " " + piece).trim()
            } else {
                merged.add(piece)
            }
        }

        val capped = if (merged.size > MAX_BEATS) {
            merged.take(MAX_BEATS - 1) + merged.drop(MAX_BEATS - 1).joinToString(" ")
        } else {
            merged
        }

        return capped.map { it.trimEnd(',').trim() }.filter { it.isNotEmpty() }
    }

    /**
     * Which of the fragment's load-bearing tokens the rewrite dropped: days,
     * times, numbers and names. Ordinary words are meant to change, so they are
     * not checked; protecting every noun would leave nothing to paraphrase.
     */
    fun lostTokens(fragment: String, rewrite: String): List<String> {
        val haystack = rewrite.lowercase()
        return TOKEN.findAll(fragment)
            .map { it.value.trim('.') }
            .filter { it.isNotEmpty() && isLoadBearing(it) }
            .filter { !haystack.contains(it.lowercase()) }
            .toList()
    }

    private fun isLoadBearing(token: String): Boolean = when {
        token.any { it.isDigit() } -> true
        token.lowercase() in ALWAYS_HARD -> true
        token.first().isUpperCase() && token.length > 2 && !token.equals("the", true) -> true
        else -> false
    }
}
