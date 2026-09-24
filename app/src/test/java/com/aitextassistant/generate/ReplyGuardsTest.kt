package com.aitextassistant.generate

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The guards, against the same fixtures eval/replies.py checks itself with.
 *
 * Neither side owns eval/reply_guards.json. If the Python mirror drifts from
 * this object, one of the two stops matching the file and says so.
 */
class ReplyGuardsTest {

    private val fixtures: JsonObject by lazy {
        // Unit tests run with the module directory as the working directory.
        val file = File("../eval/reply_guards.json")
        assertTrue("missing fixtures at ${file.absolutePath}", file.exists())
        Json.parseToJsonElement(file.readText()) as JsonObject
    }

    private fun cases(name: String): List<JsonArray> =
        (fixtures[name] as JsonArray).map { it.jsonArray }

    private fun JsonArray.str(index: Int) = this[index].jsonPrimitive.content

    @Test
    fun `labels are trimmed to something that fits a card`() {
        for (case in cases("tidyIntent")) {
            assertEquals("tidyIntent(${case.str(0)})", case.str(1), ReplyGuards.tidyIntent(case.str(0)))
        }
    }

    @Test
    fun `greetings and sign-offs are stripped`() {
        for (case in cases("tidyText")) {
            assertEquals("tidyText(${case.str(0)})", case.str(1), ReplyGuards.tidyText(case.str(0)))
        }
    }

    @Test
    fun `a time the incoming message never mentioned is an invention`() {
        for (case in cases("inventedTimes")) {
            val wanted = case[2].jsonArray.map { it.jsonPrimitive.content }
            assertEquals("on: ${case.str(1)}", wanted, ReplyGuards.inventedTimes(case.str(0), case.str(1)))
        }
    }

    @Test
    fun `an excuse the incoming message never gave is an invention`() {
        for (case in cases("inventedExcuses")) {
            val wanted = case[2].jsonArray.map { it.jsonPrimitive.content }
            assertEquals("on: ${case.str(1)}", wanted, ReplyGuards.inventedExcuses(case.str(0), case.str(1)))
        }
    }

    @Test
    fun `two curt words is a stub but a short question is not`() {
        for (case in cases("isStub")) {
            val wanted = case[1].jsonPrimitive.boolean
            assertEquals("isStub(${case.str(0)})", wanted, ReplyGuards.isStub(case.str(0)))
        }
    }

    @Test
    fun `punctuation does not make two replies different`() {
        for (case in cases("fingerprint")) {
            assertEquals("fingerprint(${case.str(0)})", case.str(1), ReplyGuards.fingerprint(case.str(0)))
        }
    }

    @Test
    fun `keep drops the bad ones and holds the order`() {
        val incoming = "you free for a call at some point today"
        val kept = ReplyGuards.keep(
            incoming,
            listOf(
                Reply("acknowledge, confirm", "sure, today works for me"),
                Reply("suggest a time", "how about 3pm?"),          // invented a time
                Reply("agree", "sure thing"),                        // a stub
                Reply("ask back", "what's it about?"),
                Reply("agree again", "Sure, today works for me!"),   // the first one again
                Reply("decline", "cant today im afraid, another time"),
            ),
            limit = 3,
        )
        assertEquals(
            listOf("sure, today works for me", "what's it about?", "cant today im afraid, another time"),
            kept.map { it.text },
        )
        assertEquals(listOf("acknowledge", "ask back", "decline"), kept.map { it.intent })
    }

    @Test
    fun `keep will not re-offer something already on screen`() {
        val kept = ReplyGuards.keep(
            "are you free saturday?",
            listOf(
                Reply("agree", "yeah saturday works"),
                Reply("ask", "what time were you thinking?"),
            ),
            alreadyOffered = listOf("Yeah saturday works!"),
            limit = 3,
        )
        assertEquals(listOf("what time were you thinking?"), kept.map { it.text })
    }

    @Test
    fun `an empty return is possible and is not a crash`() {
        val kept = ReplyGuards.keep("hello", listOf(Reply("x", "ok"), Reply("y", "sure thing")))
        assertTrue(kept.isEmpty())
    }
}
