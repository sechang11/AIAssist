package com.aitextassistant.generate

import com.aitextassistant.remix.BeatAnswer
import com.aitextassistant.remix.BeatSplitter
import com.aitextassistant.remix.RemixDraft
import com.aitextassistant.remix.Slot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Rewrites one short fragment. The only thing a backend has to implement. */
interface BeatRewriter {
    suspend fun rewrite(whole: String, fragment: String, tones: List<String>): BeatAnswer?
}

/**
 * Turns a message into a grid one beat at a time, and holds the rules that make
 * the result trustworthy.
 *
 * A port of eval/pipeline.py's build_grid, where the numbers came from: on the
 * twenty-message test set this took a 1.5B model from 5% to 65% clean, and made
 * it score the same as a model five times larger. Keep the two in step.
 *
 * Emits the grid as it grows, so the screen fills in beat by beat instead of
 * waiting on the slowest one. The beats are independent by construction, which
 * is exactly why they can be shown out of order in time.
 */
class GridBuilder(private val rewriter: BeatRewriter) : VariantGenerator {

    override fun generate(original: String, tones: List<String>): Flow<RemixDraft> = flow {
        val beats = BeatSplitter.split(original)
        if (beats.isEmpty()) return@flow

        val slots = mutableListOf<Slot>()
        var failure: Exception? = null
        var answered = 0

        beats.forEachIndexed { index, fragment ->
            // One beat failing should cost that beat, not the message.
            val slot = try {
                buildSlot(original, fragment, index, tones).also { answered++ }
            } catch (e: Exception) {
                failure = e
                unchanged(fragment, index, tones)
            }
            slots.add(slot)
            emit(RemixDraft(tones, slots.toList()))
        }

        // But a total outage must not read as a successful rewrite that happens
        // to be identical to what the reader already wrote. Count the beats that
        // answered rather than inspecting the text: the guarantee legitimately
        // reverts a beat to the original, so identical output proves nothing.
        val error = failure
        if (error != null && answered == 0) throw error
    }

    private fun unchanged(fragment: String, index: Int, tones: List<String>) = Slot(
        id = "s${index + 1}",
        label = "part ${index + 1}",
        optional = false,
        alternatives = List(tones.size) { fragment },
    )

    private suspend fun buildSlot(
        whole: String,
        fragment: String,
        index: Int,
        tones: List<String>,
    ): Slot {
        var answer = rewriter.rewrite(whole, fragment, tones)

        // A lossy beat is cheap to retry on its own, which is only affordable
        // because the unit of failure is now a fragment, not the whole message.
        if (answer != null && damage(fragment, answer) > 0) {
            val second = rewriter.rewrite(whole, fragment, tones)
            if (second != null && damage(fragment, second) < damage(fragment, answer)) {
                answer = second
            }
        }

        val offered = answer?.alternatives.orEmpty()
        val alternatives = if (offered.size == tones.size) {
            offered
        } else {
            // Better a beat the reader cannot retone than a beat that vanishes.
            List(tones.size) { fragment }
        }

        // The guarantee. A rewrite that dropped a date, a name or a number, or
        // collapsed into a bare label, is not a worse rewrite; it is a
        // different message, so it is never shown. Losing the retoning on one
        // beat is a far smaller harm than losing the time the writer agreed to
        // meet, and an unchanged beat is visibly unchanged.
        val safe = alternatives.map { alternative ->
            val lost = BeatSplitter.lostTokens(fragment, alternative).isNotEmpty()
            if (lost || BeatSplitter.collapsed(fragment, alternative)) fragment else alternative
        }

        return Slot(
            id = "s${index + 1}",
            label = answer?.label?.takeIf { it.isNotBlank() } ?: "part ${index + 1}",
            optional = answer?.optional ?: false,
            alternatives = safe,
        )
    }

    /** How much of the writer's meaning this answer would cost, if shown. */
    private fun damage(fragment: String, answer: BeatAnswer): Int =
        answer.alternatives.sumOf { alternative ->
            BeatSplitter.lostTokens(fragment, alternative).size +
                if (BeatSplitter.collapsed(fragment, alternative)) 1 else 0
        }
}
