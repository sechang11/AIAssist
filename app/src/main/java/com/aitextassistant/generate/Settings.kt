package com.aitextassistant.generate

import android.content.Context

/**
 * Where the rewrites come from. Three backends, picked by what is configured.
 *
 * The Ollama option exists because a model on your own machine costs nothing
 * per message, needs no key, and is the same thing the eval scores, so what you
 * see on the phone is what the numbers describe. It is also the only way to try
 * this on a real device without either an API bill or a several hundred
 * megabyte download.
 */
class Settings(context: Context) {

    private val prefs = context.getSharedPreferences("remix", Context.MODE_PRIVATE)

    /** e.g. http://192.168.0.45:11434 . Blank means fall back. */
    var ollamaHost: String
        get() = prefs.getString(HOST, "").orEmpty().trim().trimEnd('/')
        set(value) = prefs.edit().putString(HOST, value.trim().trimEnd('/')).apply()

    var ollamaModel: String
        get() = prefs.getString(MODEL, DEFAULT_MODEL).orEmpty().ifBlank { DEFAULT_MODEL }
        set(value) = prefs.edit().putString(MODEL, value.trim()).apply()

    val usingOllama: Boolean get() = ollamaHost.isNotBlank()

    private companion object {
        const val HOST = "ollama_host"
        const val MODEL = "ollama_model"
        const val DEFAULT_MODEL = "qwen2.5:1.5b-instruct"
    }
}

object Generators {

    val hasApiKey: Boolean get() = com.aitextassistant.BuildConfig.ANTHROPIC_API_KEY.isNotBlank()

    /** What the current configuration will actually use, for the UI to say so. */
    fun describe(context: Context): String {
        val settings = Settings(context)
        return when {
            settings.usingOllama -> "${settings.ollamaModel} on ${settings.ollamaHost}"
            hasApiKey -> "Claude"
            else -> "the offline stand-in, which barely rewrites anything"
        }
    }

    /**
     * A server you point it at wins over the API key, because that is the
     * explicit choice. Neither one configured falls back to the stub so the app
     * is never dead on arrival, though it will look like it does nothing.
     */
    fun default(context: Context): VariantGenerator {
        val settings = Settings(context)
        val rewriter: BeatRewriter = when {
            settings.usingOllama -> OllamaBeatRewriter(settings.ollamaHost, settings.ollamaModel)
            hasApiKey -> ClaudeBeatRewriter(com.aitextassistant.BuildConfig.ANTHROPIC_API_KEY)
            else -> StubBeatRewriter()
        }
        return GridBuilder(rewriter)
    }
}
