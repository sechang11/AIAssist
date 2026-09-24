package com.aitextassistant.generate

/**
 * What a suggested reply is not allowed to do, enforced after the model has
 * spoken rather than asked for in the prompt.
 *
 * This is the same division of labour the rewriter settled on. Every rule moved
 * into the prompt costs compliance on all the others: at eight paragraphs the
 * 7B was inventing times, writing two-word stubs and labelling replies with
 * whole sentences, all rules it had followed when there were four paragraphs.
 * Rules that can be checked are checked here, which frees the prompt to spend
 * its words on the one thing code cannot judge, which is whether a reply is a
 * sensible thing to say to this message.
 *
 * A dropped reply is not a failure. The suggester asks for more than it needs
 * so there is something to drop.
 *
 * Mirrored by eval/replies.py, and ReplyGuardsTest and that file share the
 * fixtures in eval/reply_guards.json so the two cannot drift.
 */
object ReplyGuards {

    private val WHITESPACE = Regex("\\s+")

    /**
     * Clock times, weekdays, months, ordinal dates and the relative words that
     * function as times. A reply containing one of these that the incoming
     * message did not is committing its reader to a time they never named.
     */
    private val TIMEY = Regex(
        "\\b(" +
            "\\d{1,2}[:.]\\d{2}\\s*(?:am|pm)?|" +
            "\\d{1,2}\\s*(?:am|pm)|" +
            "\\d{1,2}(?:st|nd|rd|th)|" +
            "monday|tuesday|wednesday|thursday|friday|saturday|sunday|" +
            // No "may". As a month it is rare in a text message and as a modal
            // verb it is everywhere, and a false positive here silently drops a
            // perfectly good reply.
            "january|february|march|april|june|july|august|september|" +
            "october|november|december|" +
            "today|tonight|tomorrow|weekend|noon|midnight" +
            ")\\b",
        RegexOption.IGNORE_CASE,
    )

    private val GREETING = Regex("^\\s*(hi|hey|hello|dear)\\b[\\s,!.]*", RegexOption.IGNORE_CASE)

    private val SIGNOFF = Regex(
        "[\\s,]*\\b(best regards|kind regards|warm regards|regards|sincerely|" +
            "best wishes|yours truly|cheers for now)\\b[\\s,.!]*$",
        RegexOption.IGNORE_CASE,
    )

    /** Anything that is not a letter or digit, for comparing two replies. */
    private val NOT_ALNUM = Regex("[^a-z0-9 ]")

    /**
     * A reply this short costs more to read than it would have cost to type, so
     * it is worse than offering nothing. The exception is a reply that is doing
     * real work in few words, which in practice means a question.
     */
    private const val MIN_WORDS = 3

    /** Longer than this is not a text message, whatever the model thinks. */
    private const val MAX_CHARS = 200

    private const val MAX_LABEL_WORDS = 3

    /**
     * A label for the card. The model is asked for three words and returns
     * "acknowledge receipt, provide brief update" often enough that asking
     * harder is not the answer.
     */
    fun tidyIntent(raw: String): String {
        val firstClause = raw.substringBefore(',').trim().trimEnd('.', '!', ':', ';')
        val words = firstClause.split(WHITESPACE).filter { it.isNotBlank() }
        if (words.isEmpty()) return "reply"
        return words.take(MAX_LABEL_WORDS).joinToString(" ").lowercase()
    }

    /** Greetings and sign-offs belong in an email, and the model knows better most of the time. */
    fun tidyText(raw: String): String =
        raw.trim().replace(GREETING, "").replace(SIGNOFF, "").trim()

    /**
     * Times in [text] that [incoming] never mentioned. Empty is the good case.
     *
     * Matched on the surface form, so "the 14th" answering "funeral is the 14th"
     * survives and "how about 3pm" answering "free for a call today" does not.
     */
    fun inventedTimes(incoming: String, text: String): List<String> {
        val said = TIMEY.findAll(incoming).map { it.value.lowercase() }.toSet()
        return TIMEY.findAll(text)
            .map { it.value.lowercase() }
            .filter { it !in said }
            .distinct()
            .toList()
    }

    fun isStub(text: String): Boolean {
        val words = text.split(WHITESPACE).filter { it.isNotBlank() }
        if (words.size >= MIN_WORDS) return false
        // "what time?" is two words doing a whole job. "sure thing" is not.
        return !text.contains('?')
    }

    fun tooLong(text: String): Boolean = text.length > MAX_CHARS

    /** For spotting two cards that say the same thing with different punctuation. */
    fun fingerprint(text: String): String =
        text.lowercase().replace(NOT_ALNUM, " ").replace(WHITESPACE, " ").trim()

    /**
     * Everything the model returned, minus what should never reach the screen,
     * minus duplicates of each other and of [alreadyOffered].
     *
     * Order is preserved: the model's first suggestion is usually its best, and
     * nothing here is a ranking.
     */
    fun keep(
        incoming: String,
        replies: List<Reply>,
        alreadyOffered: List<String> = emptyList(),
        limit: Int = 3,
    ): List<Reply> {
        val seen = alreadyOffered.map(::fingerprint).toMutableSet()
        val kept = mutableListOf<Reply>()
        for (reply in replies) {
            if (kept.size >= limit) break
            val text = tidyText(reply.text)
            if (text.isEmpty() || isStub(text) || tooLong(text)) continue
            if (inventedTimes(incoming, text).isNotEmpty()) continue
            val print = fingerprint(text)
            if (print.isEmpty() || !seen.add(print)) continue
            kept.add(Reply(intent = tidyIntent(reply.intent), text = text))
        }
        return kept
    }
}
