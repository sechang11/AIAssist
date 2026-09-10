package com.aitextassistant.generate

/**
 * One short fragment in, three phrasings out.
 *
 * The text up to [SHAPE_HINT] is byte-identical to `system()` in
 * eval/pipeline.py, and must stay that way. That is the prompt the eval scores
 * and, more importantly, the prompt a fine-tuned student is trained on: a model
 * taught on one wording and prompted with another loses part of what it learned,
 * silently and for no reason. Line wrapping counts, because tokens do.
 *
 * The shape hint is the one deliberate difference. Ollama and llama.cpp enforce
 * the response shape with a JSON schema at the sampler, so a local model needs
 * no instruction about it. The Anthropic path has no such enforcement on
 * Android, so it has to ask.
 *
 * Deliberately no worked example, either way. Three eval rounds showed small
 * models copy whatever example you give them: first a literal placeholder, then
 * the sample text itself, at which point they stopped reading the real message.
 */
internal object Prompt {

    /**
     * @param shapeHint append the JSON shape instruction. True for a backend
     *   that cannot constrain decoding, false for one that can.
     */
    fun system(tones: List<String>, shapeHint: Boolean): String {
        val numbered = tones.mapIndexed { i, t -> "${i + 1} ${t.lowercase()}" }.joinToString("; ")
        val n = tones.size
        val base = """
            You rewrite one short fragment of a personal message $n ways: $numbered.

            Rewrite the fragment only. The rest of the message is given for context and must
            not appear in your answer.

            Keep every name, date, time, number and place from the fragment, exactly as
            written, in all $n rewrites. Add nothing that is not in the fragment. Never
            change what it commits to: a no stays a no, a maybe stays a maybe, and every
            condition survives.

            Stay within about the length of the fragment. Match the writer's register in the
            casual rewrites: lowercase with no full stops stays lowercase with no full stops.

            Give the fragment a two-word label saying what beat it is, and mark it optional
            only if the whole message still reads correctly without it.
        """.trimIndent()

        return if (shapeHint) base + "\n\n" + SHAPE_HINT.replace("EXACTLY_N", n.toString()) else base
    }

    private const val SHAPE_HINT =
        "Reply with a JSON object alone, no preamble and no code fence, holding exactly " +
            "three keys: \"label\", a two-word string; \"optional\", true or false; and " +
            "\"alternatives\", an array of exactly EXACTLY_N strings in tone order."

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
