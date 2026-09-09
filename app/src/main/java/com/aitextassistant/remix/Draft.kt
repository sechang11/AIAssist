package com.aitextassistant.remix

/**
 * One beat of a message: a greeting, an apology, the actual answer, a caveat,
 * a sign-off. [alternatives] holds one phrasing per tone, in the same order as
 * [RemixDraft.tones].
 */
data class Slot(
    val id: String,
    val label: String,
    val optional: Boolean,
    val alternatives: List<String>,
)

/**
 * A grid of phrasings. Reading down column `i` of every slot gives a coherent
 * whole message in tone `i`; picking a different column per slot is the mix and
 * match. Generating the grid directly, rather than generating N free-form
 * rewrites and trying to align them afterwards, is what makes the mixing safe -
 * there is no alignment step that can get the beats out of sync.
 */
data class RemixDraft(
    val tones: List<String>,
    val slots: List<Slot>,
) {
    /**
     * Drops anything the model got structurally wrong rather than failing the
     * whole response. Returns null when nothing usable is left.
     */
    fun validated(): RemixDraft? {
        val cleanTones = tones.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleanTones.size < 2) return null

        val keptSlots = slots.mapNotNull { slot ->
            val alts = slot.alternatives.map { it.trim() }
            when {
                alts.size != cleanTones.size -> null
                alts.all { it.isEmpty() } -> null
                // A blank in one column would silently delete that beat when the
                // reader picks that tone. Backfill from the first non-blank instead.
                else -> slot.copy(
                    alternatives = alts.map { alt -> alt.ifEmpty { alts.first { it.isNotEmpty() } } },
                )
            }
        }
        return if (keptSlots.isEmpty()) null else RemixDraft(cleanTones, keptSlots)
    }
}

/**
 * A [RemixDraft] plus the reader's current picks and which beat, if any, has its
 * alternatives showing. Immutable; every edit returns a copy.
 */
data class Remix(
    val draft: RemixDraft,
    val choice: Map<String, Int> = draft.slots.associate { it.id to 0 },
    val dropped: Set<String> = emptySet(),
    val open: String? = null,
) {
    /** The beats that make up the message right now, in order. */
    val kept: List<Slot> get() = draft.slots.filterNot { it.id in dropped }

    val droppedSlots: List<Slot> get() = draft.slots.filter { it.id in dropped }

    val openSlot: Slot? get() = draft.slots.firstOrNull { it.id == open }

    fun indexOf(slot: Slot): Int = (choice[slot.id] ?: 0).coerceIn(0, slot.alternatives.lastIndex)

    fun textOf(slot: Slot): String = slot.alternatives[indexOf(slot)]

    fun isDropped(slot: Slot): Boolean = slot.id in dropped

    fun isOpen(slot: Slot): Boolean = open == slot.id

    fun toggleOpen(slot: Slot): Remix = copy(open = if (isOpen(slot)) null else slot.id)

    fun close(): Remix = copy(open = null)

    fun choose(slot: Slot, index: Int): Remix =
        copy(choice = choice + (slot.id to index.coerceIn(0, slot.alternatives.lastIndex)), open = null)

    fun drop(slot: Slot): Remix = copy(dropped = dropped + slot.id, open = null)

    /**
     * Bringing a beat back must not drop a stale phrasing into a message the
     * reader has since retoned, so it adopts whatever tone the rest agree on.
     */
    fun restore(slot: Slot): Remix {
        val agreed = uniformTone()
        return copy(
            dropped = dropped - slot.id,
            choice = if (agreed == null) choice else choice + (slot.id to agreed),
            open = null,
        )
    }

    /** Snap every slot back to one tone. This is the "just give me a version" button. */
    fun applyTone(index: Int): Remix =
        copy(
            choice = draft.slots.associate { it.id to index.coerceIn(0, it.alternatives.lastIndex) },
            open = null,
        )

    /**
     * The tone index every kept slot agrees on, or null once the reader has
     * mixed. Drives whether a tone chip reads as selected.
     */
    fun uniformTone(): Int? {
        if (kept.isEmpty()) return null
        val first = indexOf(kept.first())
        return if (kept.all { indexOf(it) == first }) first else null
    }

    fun assemble(): String =
        kept.map { textOf(it) }
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .tidy()
}

/**
 * Fragments written to stand alone leave seams when concatenated: a doubled
 * space, a space pushed in front of a comma, a stray leading punctuation mark
 * once the slot before it is dropped.
 */
internal fun String.tidy(): String =
    trim()
        .replace(Regex("\s+"), " ")
        .replace(Regex("\s+([,.!?;:])"), "$1")
        .replace(Regex("^[,.;:!?]+\s*"), "")
        .trim()
