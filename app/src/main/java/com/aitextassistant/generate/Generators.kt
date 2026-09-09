package com.aitextassistant.generate

import com.aitextassistant.BuildConfig

object Generators {

    val hasApiKey: Boolean get() = BuildConfig.ANTHROPIC_API_KEY.isNotBlank()

    /** Falls back to the offline stub so the app is never dead on arrival. */
    fun default(): VariantGenerator =
        if (hasApiKey) ClaudeVariantGenerator(BuildConfig.ANTHROPIC_API_KEY) else StubVariantGenerator()
}
