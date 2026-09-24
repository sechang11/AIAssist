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

    /**
     * Where the phone looks for a newer build. Left alone it follows the model
     * server to port [PUBLISH_PORT], because the same machine publishes both
     * and asking for the address twice is a way to get it wrong once.
     */
    var updateHost: String
        get() {
            val set = prefs.getString(UPDATES, "").orEmpty().trim().trimEnd('/')
            if (set.isNotBlank()) return set
            val host = ollamaHost
            if (host.isBlank()) return ""
            return host.substringBeforeLast(':') + ":" + PUBLISH_PORT
        }
        set(value) = prefs.edit().putString(UPDATES, value.trim().trimEnd('/')).apply()

    /** True when [updateHost] came from the model server rather than being typed. */
    val updateHostIsInherited: Boolean
        get() = prefs.getString(UPDATES, "").orEmpty().isBlank() && ollamaHost.isNotBlank()

    private companion object {
        const val HOST = "ollama_host"
        const val MODEL = "ollama_model"
        const val UPDATES = "update_host"
        const val PUBLISH_PORT = "8099"

        /**
         * The 7B, not the 1.5B. On a LAN server there is no reason to run the
         * small one: the eval has the 7B ahead on faithfulness by four points
         * and on the mechanical score by seven, and a machine that can serve
         * either answers in about the same time. The 1.5B matters for the
         * on-phone build, which is a different question.
         */
        const val DEFAULT_MODEL = "qwen2.5:7b-instruct"
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
