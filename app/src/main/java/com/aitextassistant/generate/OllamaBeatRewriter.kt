package com.aitextassistant.generate

import com.aitextassistant.remix.BeatAnswer
import com.aitextassistant.remix.BeatParser
import org.json.JSONObject

/**
 * Rewrites one beat using a model on your own network. No key, no per-message
 * cost, and the same models the eval scores, so what you see on the phone is
 * what the numbers describe.
 *
 * Sends a JSON schema with every request, which Ollama constrains decoding to.
 * The Anthropic path cannot do that on Android and has to ask in prose, which
 * is why [Prompt.system] takes a flag.
 */
class OllamaBeatRewriter(host: String, model: String) : BeatRewriter {

    private val client = OllamaClient(host, model)

    override suspend fun rewrite(
        whole: String,
        fragment: String,
        tones: List<String>,
    ): BeatAnswer? {
        val schema = OllamaClient.obj(
            listOf("label", "optional", "alternatives"),
            JSONObject()
                .put("label", OllamaClient.string)
                .put("optional", OllamaClient.boolean)
                // Pinned. Without this a small model returns one alternative.
                .put("alternatives", OllamaClient.fixedArray(tones.size, OllamaClient.string)),
        )
        val content = client.chat(
            system = Prompt.system(tones, shapeHint = false),
            user = Prompt.user(whole, fragment, tones),
            schema = schema,
        )
        return BeatParser.parse(content)
    }
}
