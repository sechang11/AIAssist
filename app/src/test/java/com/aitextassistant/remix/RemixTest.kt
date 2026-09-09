package com.aitextassistant.remix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private fun slot(id: String, vararg alts: String, optional: Boolean = false) =
    Slot(id = id, label = id, optional = optional, alternatives = alts.toList())

private val TONES = listOf("Concise", "Warm", "Formal")

class DraftValidationTest {

    @Test
    fun `drops slots whose alternative count does not match the tones`() {
        val draft = RemixDraft(TONES, listOf(slot("a", "x", "y", "z"), slot("b", "x", "y")))
        assertEquals(listOf("a"), draft.validated()!!.slots.map { it.id })
    }

    @Test
    fun `rejects a draft with fewer than two tones`() {
        assertNull(RemixDraft(listOf("Only"), listOf(slot("a", "x"))).validated())
    }

    @Test
    fun `rejects a draft with no usable slots`() {
        assertNull(RemixDraft(TONES, listOf(slot("a", "x", "y"))).validated())
    }

    @Test
    fun `backfills a blank alternative so picking that tone does not delete the beat`() {
        val validated = RemixDraft(TONES, listOf(slot("a", "hello", "", "Good morning"))).validated()!!
        assertEquals(listOf("hello", "hello", "Good morning"), validated.slots.single().alternatives)
    }
}

class AssemblyTest {

    private val draft = RemixDraft(
        TONES,
        listOf(
            slot("greet", "Hey.", "Hey there!", "Good afternoon.", optional = true),
            slot("body", "Friday works.", "Friday works great for me.", "Friday would suit me well."),
            slot("sign", "Cheers.", "See you then!", "Kind regards.", optional = true),
        ),
    )

    @Test
    fun `starts on the first tone`() {
        assertEquals("Hey. Friday works. Cheers.", Remix(draft).assemble())
    }

    @Test
    fun `applying a tone moves every slot`() {
        val remix = Remix(draft).applyTone(2)
        assertEquals("Good afternoon. Friday would suit me well. Kind regards.", remix.assemble())
    }

    @Test
    fun `mixing tones per slot is what the whole thing is for`() {
        val remix = Remix(draft)
            .choose(draft.slots[0], 1)
            .choose(draft.slots[1], 2)
        assertEquals("Hey there! Friday would suit me well. Cheers.", remix.assemble())
    }

    @Test
    fun `a dropped slot leaves the rest intact`() {
        val remix = Remix(draft).drop(draft.slots[0])
        assertEquals("Friday works. Cheers.", remix.assemble())
    }

    @Test
    fun `restoring a beat adopts the tone the rest agree on`() {
        val remix = Remix(draft)
            .drop(draft.slots[0])
            .choose(draft.slots[1], 2)
            .choose(draft.slots[2], 2)
            .restore(draft.slots[0])
        assertEquals(2, remix.indexOf(draft.slots[0]))
        assertEquals(2, remix.uniformTone())
    }

    @Test
    fun `restoring into a mixed message leaves the beat as it was`() {
        val remix = Remix(draft)
            .drop(draft.slots[0])
            .choose(draft.slots[1], 2)
            .restore(draft.slots[0])
        assertEquals(0, remix.indexOf(draft.slots[0]))
        assertNull(remix.uniformTone())
    }

    @Test
    fun `opening a beat, then choosing or tapping again, closes the panel`() {
        val opened = Remix(draft).toggleOpen(draft.slots[1])
        assertEquals(draft.slots[1], opened.openSlot)
        assertNull(opened.choose(draft.slots[1], 2).openSlot)
        assertNull(opened.toggleOpen(draft.slots[1]).openSlot)
    }

    @Test
    fun `uniform tone reports null once the reader has mixed`() {
        assertEquals(0, Remix(draft).uniformTone())
        assertNull(Remix(draft).choose(draft.slots[1], 2).uniformTone())
    }

    @Test
    fun `a dropped slot does not count against uniformity`() {
        val remix = Remix(draft).applyTone(1).drop(draft.slots[0])
        assertEquals(1, remix.uniformTone())
    }

    @Test
    fun `joining fragments does not leave seams`() {
        assertEquals("Sorry, running late.", "Sorry  ,   running late.".tidy())
        assertEquals("Running late.", ", Running late.".tidy())
    }
}

class DraftParserTest {

    private val body = """
        {"tones":["Concise","Warm"],
         "slots":[{"id":"s1","label":"greet","optional":true,"alternatives":["Hi.","Hey there!"]},
                  {"id":"s2","label":"body","optional":false,"alternatives":["On my way.","I am on my way!"]}]}
    """.trimIndent()

    @Test
    fun `parses a clean response`() {
        val draft = DraftParser.parse(body)
        assertEquals(listOf("Concise", "Warm"), draft.tones)
        assertEquals(2, draft.slots.size)
        assertTrue(draft.slots.first().optional)
    }

    @Test
    fun `survives a code fence and a preamble`() {
        val wrapped = "Here you go:\n```json\n$body\n```\nHope that helps."
        assertEquals(2, DraftParser.parse(wrapped).slots.size)
    }

    @Test
    fun `renames duplicate ids so two slots cannot move together`() {
        val dupes = """
            {"tones":["A","B"],"slots":[
              {"id":"s1","alternatives":["one","uno"]},
              {"id":"s1","alternatives":["two","dos"]}]}
        """.trimIndent()
        val ids = DraftParser.parse(dupes).slots.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `skips a slot with a ragged alternatives list rather than failing outright`() {
        val ragged = """
            {"tones":["A","B"],"slots":[
              {"id":"s1","alternatives":["one","uno"]},
              {"id":"s2","alternatives":["two"]}]}
        """.trimIndent()
        assertEquals(listOf("s1"), DraftParser.parse(ragged).slots.map { it.id })
    }

    @Test
    fun `throws when there is no json at all`() {
        assertThrows(DraftParser.MalformedDraft::class.java) {
            DraftParser.parse("I cannot help with that.")
        }
    }

    @Test
    fun `throws when every slot is unusable`() {
        assertThrows(DraftParser.MalformedDraft::class.java) {
            DraftParser.parse("""{"tones":["A","B"],"slots":[{"id":"s1","alternatives":["only"]}]}""")
        }
    }
}
