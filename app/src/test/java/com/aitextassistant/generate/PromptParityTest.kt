package com.aitextassistant.generate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The eval scores one exact prompt, and a fine-tuned student is trained on that
 * same text. If this drifts from `system()` in eval/pipeline.py, the eval
 * numbers stop describing the app, and a trained model quietly loses some of
 * what it learned. Wrapping counts, because tokens do.
 *
 * So the Python text is pinned here verbatim and an edit to either side fails.
 */
class PromptParityTest {

    private val fromPipelinePy: String = listOf(
        "You rewrite one short fragment of a personal message 3 ways: 1 concise; 2 warm; 3 formal.",
        "",
        "Rewrite the fragment only. The rest of the message is given for context and must",
        "not appear in your answer.",
        "",
        "Keep every name, date, time, number and place from the fragment, exactly as",
        "written, in all 3 rewrites. Add nothing that is not in the fragment. Never",
        "change what it commits to: a no stays a no, a maybe stays a maybe, and every",
        "condition survives.",
        "",
        "Stay within about the length of the fragment. Match the writer's register in the",
        "casual rewrites: lowercase with no full stops stays lowercase with no full stops.",
        "",
        "Give the fragment a two-word label saying what beat it is, and mark it optional",
        "only if the whole message still reads correctly without it.",
    ).joinToString("\n")

    @Test
    fun `the app prompt matches the one the eval scores and a student learns`() {
        assertEquals(fromPipelinePy, Prompt.system(DEFAULT_TONES, shapeHint = false))
    }

    @Test
    fun `the shape hint only ever appends`() {
        val hinted = Prompt.system(DEFAULT_TONES, shapeHint = true)
        assertTrue(hinted.startsWith(fromPipelinePy))
        assertTrue(hinted.contains("\"alternatives\", an array of exactly 3 strings"))
    }

    @Test
    fun `the user turn names the fragment and the whole message separately`() {
        val turn = Prompt.user("the whole thing", "one beat", DEFAULT_TONES)
        assertTrue(turn.contains("the whole thing"))
        assertTrue(turn.contains("one beat"))
        assertTrue(turn.indexOf("for context only") < turn.indexOf("Rewrite just this fragment"))
    }
}
