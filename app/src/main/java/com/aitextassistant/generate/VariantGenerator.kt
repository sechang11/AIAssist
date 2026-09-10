package com.aitextassistant.generate

import com.aitextassistant.remix.RemixDraft
import kotlinx.coroutines.flow.Flow

/** Default columns. Order matters: it is the order alternatives come back in. */
val DEFAULT_TONES: List<String> = listOf("Concise", "Warm", "Formal")

interface VariantGenerator {
    /**
     * Emits the grid as it grows, one beat at a time, so the screen fills in
     * rather than waiting on the slowest fragment. Throws on failure; the
     * caller shows the message.
     */
    fun generate(original: String, tones: List<String> = DEFAULT_TONES): Flow<RemixDraft>
}
