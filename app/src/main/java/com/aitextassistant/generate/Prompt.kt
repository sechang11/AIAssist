package com.aitextassistant.generate

/**
 * One short fragment in, three phrasings out. A port of eval/pipeline.py's
 * prompt, which is where these words were measured; keep the two in step.
 *
 * Deliberately has no worked example. Three earlier rounds showed small models
 * copy whatever example you give them: a placeholder got echoed literally, and
 * when it was replaced with realistic text they plagiarised the text instead
 * and stopped reading the actual message. The response schema carries the
 * shape, so the prompt does not have to.
 */
internal object Prompt {

    fun system(tones: List<String>): String {
        val numbered = tones.mapIndexed { i, t -> "${i + 1} ${t.lowercase()}" }.joinToString("; ")
        return """
            You rewrite one short fragment of a personal message ${tones.size} ways: $numbered.

            Rewrite the fragment only. The rest of the message is given for context and
            must not appear in your answer.

            Keep every name, date, time, number and place from the fragment, exactly as
            written, in all ${tones.size} rewrites. Add nothing that is not in the fragment.
            Never change what it commits to: a no stays a no, a maybe stays a maybe, and
            every condition survives.

            Stay within about the length of the fragment. Match the writer's register in
            the casual rewrites: lowercase with no full stops stays lowercase with no
            full stops.

            Give the fragment a two-word label saying what beat it is, and mark it
            optional only if the whole message still reads correctly without it.

            Reply with a JSON object alone, no preamble and no code fence, holding
            exactly three keys: "label", a two-word string; "optional", true or false;
            and "alternatives", an array of exactly ${tones.size} strings in tone order.
        """.trimIndent()
    }

    /**
     * Built by concatenation on purpose. trimIndent() runs after interpolation,
     * so a multi-line message pasted into a raw string would drag the common
     * indent to zero and leave the surrounding template indented.
     */
    fun user(whole: String, fragment: String, tones: List<String>): String =
        "Whole message, for context only:\n<<<\n" + whole + "\n>>>\n\n" +
            "Rewrite just this fragment:\n<<<\n" + fragment + "\n>>>\n\n" +
            "Tones in order: " + tones.joinToString(", ") + "."
}
