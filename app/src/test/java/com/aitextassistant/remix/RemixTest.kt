package com.aitextassistant.remix

import com.aitextassistant.generate.BeatRewriter
import com.aitextassistant.generate.GridBuilder
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `mixing tones per slot is what the whole thing is for`() {
        val remix = Remix(draft).choose(draft.slots[0], 1).choose(draft.slots[1], 2)
        assertEquals("Hey there! Friday would suit me well. Cheers.", remix.assemble())
    }

    @Test
    fun `a dropped slot leaves the rest intact`() {
        assertEquals("Friday works. Cheers.", Remix(draft).drop(draft.slots[0]).assemble())
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
        val remix = Remix(draft).drop(draft.slots[0]).choose(draft.slots[1], 2).restore(draft.slots[0])
        assertEquals(0, remix.indexOf(draft.slots[0]))
        assertNull(remix.uniformTone())
    }

    @Test
    fun `uniform tone reports null once the reader has mixed`() {
        assertEquals(0, Remix(draft).uniformTone())
        assertNull(Remix(draft).choose(draft.slots[1], 2).uniformTone())
    }

    @Test
    fun `a dropped slot does not count against uniformity`() {
        assertEquals(1, Remix(draft).applyTone(1).drop(draft.slots[0]).uniformTone())
    }

    @Test
    fun `joining fragments does not leave seams`() {
        assertEquals("Sorry, running late.", "Sorry  ,   running late.".tidy())
        assertEquals("Running late.", ", Running late.".tidy())
    }
}

/** Beats stream in one at a time, so a redraw must not undo the reader's work. */
class RebaseTest {

    private val first = RemixDraft(TONES, listOf(slot("s1", "a", "b", "c")))
    private val second = RemixDraft(TONES, listOf(slot("s1", "a", "b", "c"), slot("s2", "d", "e", "f")))

    @Test
    fun `a pick made before the next beat arrived survives it`() {
        val remix = Remix(first).choose(first.slots[0], 2).rebasedOn(second)
        assertEquals(2, remix.indexOf(second.slots[0]))
        assertEquals(0, remix.indexOf(second.slots[1]))
    }

    @Test
    fun `a beat left out stays left out`() {
        val remix = Remix(first).drop(first.slots[0]).rebasedOn(second)
        assertTrue(remix.isDropped(second.slots[0]))
    }

    @Test
    fun `an open picker for a beat that vanished does not linger`() {
        val remix = Remix(second).toggleOpen(second.slots[1]).rebasedOn(first)
        assertNull(remix.openSlot)
    }
}

class BeatSplitterTest {

    @Test
    fun `splits on full stops and on commas inside long runs`() {
        val beats = BeatSplitter.split(
            "hey so sorry i didnt get back to you sooner, this week has been mental. " +
                "friday still works for me if thats ok? really looking forward to it",
        )
        assertEquals(4, beats.size)
        assertEquals("hey so sorry i didnt get back to you sooner", beats[0])
        assertEquals("really looking forward to it", beats[3])
    }

    @Test
    fun `a short message is one beat, and that is correct rather than a failure`() {
        assertEquals(listOf("ok cool see you then"), BeatSplitter.split("ok cool see you then"))
    }

    @Test
    fun `leaves a short comma clause alone`() {
        // Under the length threshold, so the comma is punctuation, not a beat break.
        assertEquals(1, BeatSplitter.split("not sure yet, depends on my shift").size)
    }

    @Test
    fun `a stray tail joins the beat before it rather than standing alone`() {
        val beats = BeatSplitter.split("the survey came back with damp in the back bedroom. oh well.")
        assertEquals(1, beats.size)
    }

    @Test
    fun `never returns more than six beats`() {
        val many = (1..12).joinToString(" ") { "sentence number $it here." }
        assertEquals(6, BeatSplitter.split(many).size)
    }

    @Test
    fun `blank input yields nothing`() {
        assertEquals(emptyList<String>(), BeatSplitter.split("   "))
    }
}

class LostTokenTest {

    @Test
    fun `catches a lowercase weekday, which capitalisation alone would miss`() {
        assertEquals(listOf("friday"), BeatSplitter.lostTokens("friday still works", "it still works"))
    }

    @Test
    fun `catches a dropped number and a dropped name`() {
        assertEquals(listOf("11"), BeatSplitter.lostTokens("before 11", "before eleven"))
        assertEquals(listOf("Yasmin"), BeatSplitter.lostTokens("keys with Yasmin", "keys with her"))
    }

    @Test
    fun `ordinary words are meant to change and are not policed`() {
        assertEquals(
            emptyList<String>(),
            BeatSplitter.lostTokens("this week has been mental", "busy week"),
        )
    }

    @Test
    fun `a kept fact is not reported, whatever the case`() {
        assertEquals(emptyList<String>(), BeatSplitter.lostTokens("friday works", "Friday is fine"))
    }

    @Test
    fun `a lone capital is a label, and substring matching used to lose it`() {
        // "room B not room A" losing the A destroys the message, but "a" occurs
        // in nearly any sentence, so a substring check always thought it kept it.
        assertEquals(
            listOf("B", "A"),
            BeatSplitter.lostTokens("its in room B not room A", "its in room 1 not room 2"),
        )
        assertEquals(
            emptyList<String>(),
            BeatSplitter.lostTokens("its in room B not room A", "the meeting is in room B, not room A"),
        )
    }

    @Test
    fun `I is never load-bearing`() {
        assertEquals(emptyList<String>(), BeatSplitter.lostTokens("I will be late", "running late"))
    }

    @Test
    fun `a rewrite that collapses into a bare label is caught`() {
        // Observed: the 1.5B answered "have a great time though" with the beat's
        // own label. No date or name to lose, so the fact check saw nothing.
        assertTrue(BeatSplitter.collapsed("have a great time though", "concern"))
        assertTrue(BeatSplitter.collapsed("it was originally sent on the 12th", "12th"))
    }

    @Test
    fun `genuine compression is not mistaken for collapse`() {
        assertTrue(!BeatSplitter.collapsed("the quote came to 480 including delivery", "quote's 480"))
        assertTrue(!BeatSplitter.collapsed("this week has been mental", "busy week"))
        // A short fragment has nothing to collapse from.
        assertTrue(!BeatSplitter.collapsed("sure thing", "Absolutely"))
    }

    @Test
    fun `a collapsed rewrite falls back to the writer's words`() {
        val fake = object : BeatRewriter {
            override suspend fun rewrite(whole: String, fragment: String, tones: List<String>) =
                BeatAnswer("x", false, listOf("concern", "warning", "reminder"))
        }
        val slot = runBlocking {
            GridBuilder(fake).generate("have a great time though my friend", TONES).toList()
        }.last().slots.single()
        assertEquals(List(3) { "have a great time though my friend" }, slot.alternatives)
    }

    @Test
    fun `a rewrite that runs away past its beat is caught`() {
        // Observed: the model rewrote the whole message instead of the beat,
        // and once echoed its own instructions back into the answer.
        assertTrue(
            BeatSplitter.overran(
                "hey so sorry i didnt get back to you sooner",
                "Hey, sorry I didn't get back to you sooner. Friday still works for me if " +
                    "that's alright, and I'm really looking forward to it.",
            ),
        )
        assertTrue(
            BeatSplitter.overran(
                "ive got my sisters wedding.",
                "i hope you have a fantastic time at your sister's wedding! i'll be thinking of you",
            ),
        )
    }

    @Test
    fun `a formal expansion of a short beat is allowed`() {
        // Short beats legitimately double in the formal column, which is why
        // the ceiling has an additive floor rather than being purely a ratio.
        assertTrue(!BeatSplitter.overran("sure thing", "i confirm that my end is clear."))
        assertTrue(!BeatSplitter.overran("ok cool see you then", "Understood, I shall see you then."))
        assertTrue(!BeatSplitter.overran("this week has been mental", "It has been an unusually busy week"))
    }

    @Test
    fun `a runaway rewrite falls back to the writer's words`() {
        val fake = object : BeatRewriter {
            override suspend fun rewrite(whole: String, fragment: String, tones: List<String>) =
                BeatAnswer("x", false, List(3) {
                    "I am delighted to inform you at considerable length that this beat " +
                        "has been expanded well beyond anything the writer actually wrote."
                })
        }
        val slot = runBlocking {
            GridBuilder(fake).generate("friday still works for me", TONES).toList()
        }.last().slots.single()
        assertEquals(List(3) { "friday still works for me" }, slot.alternatives)
    }

    @Test
    fun `a short number still matches inside a longer one`() {
        // Word boundaries are for letters. "before 11" is kept by "before 11am".
        assertEquals(emptyList<String>(), BeatSplitter.lostTokens("before 11", "before 11am"))
        assertEquals(listOf("11"), BeatSplitter.lostTokens("before 11", "before eleven"))
    }
}

class BeatParserTest {

    @Test
    fun `parses a clean beat`() {
        val beat = BeatParser.parse(
            """{"label":"opening","optional":true,"alternatives":["Hi.","Hey there!","Good morning."]}""",
        )!!
        assertEquals("opening", beat.label)
        assertTrue(beat.optional)
        assertEquals(3, beat.alternatives.size)
    }

    @Test
    fun `survives a code fence and a preamble`() {
        val wrapped = "Sure:\n```json\n{\"alternatives\":[\"a\",\"b\"]}\n```\nHope that helps."
        assertEquals(listOf("a", "b"), BeatParser.parse(wrapped)!!.alternatives)
    }

    @Test
    fun `returns null rather than throwing on rubbish`() {
        assertNull(BeatParser.parse("I cannot help with that."))
        assertNull(BeatParser.parse("""{"label":"x"}"""))
        assertNull(BeatParser.parse("""{"alternatives":[]}"""))
    }
}

/** The rules that make a small model's output trustworthy. */
class GridBuilderTest {

    private class Fake(
        private val answer: (String) -> BeatAnswer?,
    ) : BeatRewriter {
        var calls = 0
        override suspend fun rewrite(whole: String, fragment: String, tones: List<String>): BeatAnswer? {
            calls++
            return answer(fragment)
        }
    }

    private fun build(rewriter: BeatRewriter, text: String) = runBlocking {
        GridBuilder(rewriter).generate(text, TONES).toList()
    }

    @Test
    fun `a rewrite that drops the date is replaced by the writer's own words`() {
        val fake = Fake { BeatAnswer("x", false, listOf("it still works", "it works", "that works")) }
        val drafts = build(fake, "friday still works")
        val alternatives = drafts.last().slots.single().alternatives
        assertEquals(listOf("friday still works", "friday still works", "friday still works"), alternatives)
    }

    @Test
    fun `a lossy beat is retried once before falling back`() {
        val fake = Fake { BeatAnswer("x", false, listOf("it works", "it works", "it works")) }
        build(fake, "friday still works")
        assertEquals(2, fake.calls)
    }

    @Test
    fun `a good rewrite passes straight through`() {
        val good = listOf("friday works", "friday still works for me", "Friday remains fine")
        val fake = Fake { BeatAnswer("answer", true, good) }
        val slot = build(fake, "friday still works").last().slots.single()
        assertEquals(good, slot.alternatives)
        assertEquals("answer", slot.label)
        assertTrue(slot.optional)
    }

    @Test
    fun `a beat the model could not answer keeps the original rather than vanishing`() {
        val slot = build(Fake { null }, "friday still works").last().slots.single()
        assertEquals(List(3) { "friday still works" }, slot.alternatives)
    }

    @Test
    fun `the wrong number of alternatives falls back rather than corrupting the grid`() {
        val fake = Fake { BeatAnswer("x", false, listOf("only one alternative")) }
        val slot = build(fake, "see you soon").last().slots.single()
        assertEquals(3, slot.alternatives.size)
    }

    @Test
    fun `one failing beat costs that beat, not the whole message`() {
        var call = 0
        val flaky = object : BeatRewriter {
            override suspend fun rewrite(whole: String, fragment: String, tones: List<String>): BeatAnswer {
                call++
                if (call == 1) throw IllegalStateException("network")
                return BeatAnswer("x", false, listOf("the second one", "that second one", "the second matter"))
            }
        }
        val slots = runBlocking {
            GridBuilder(flaky).generate(
                "the first thing happened. the second thing happened.", TONES,
            ).toList()
        }.last().slots
        assertEquals(2, slots.size)
        // The failed beat keeps the writer's words rather than disappearing.
        assertEquals(List(3) { "the first thing happened." }, slots[0].alternatives)
        assertEquals(listOf("the second one", "that second one", "the second matter"), slots[1].alternatives)
    }

    @Test
    fun `a total outage throws rather than returning the message unchanged`() {
        val dead = object : BeatRewriter {
            override suspend fun rewrite(whole: String, fragment: String, tones: List<String>): BeatAnswer =
                throw IllegalStateException("no network")
        }
        var thrown: Exception? = null
        try {
            runBlocking { GridBuilder(dead).generate("friday still works", TONES).toList() }
        } catch (e: Exception) {
            thrown = e
        }
        assertTrue("silence would look like a successful no-op rewrite", thrown != null)
    }

    @Test
    fun `the grid is emitted once per beat so the screen can fill in`() {
        val fake = Fake { BeatAnswer("x", false, listOf("a first way", "a second way", "a third way")) }
        val drafts = build(
            fake,
            "the first thing happened. the second thing happened. the third thing happened.",
        )
        assertEquals(listOf(1, 2, 3), drafts.map { it.slots.size })
    }
}
