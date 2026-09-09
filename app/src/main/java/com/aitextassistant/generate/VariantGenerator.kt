package com.aitextassistant.generate

import com.aitextassistant.remix.RemixDraft

/** Default columns. Order matters: it is the order the model writes alternatives in. */
val DEFAULT_TONES: List<String> = listOf("Concise", "Warm", "Formal")

interface VariantGenerator {
    /** Runs on a background dispatcher. Throws on failure; the caller shows the message. */
    suspend fun generate(original: String, tones: List<String> = DEFAULT_TONES): RemixDraft
}
