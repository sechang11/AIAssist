package com.aitextassistant.generate

import com.aitextassistant.remix.BeatAnswer
import com.aitextassistant.remix.BeatParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to an Ollama server on your own network. No API key, no per-message
 * cost, and the same models the eval scores, so what you see on the phone is
 * what the numbers describe.
 *
 * It sends a JSON schema with every request. Ollama constrains decoding to it,
 * which on the eval was worth roughly sixty points of validity on small models
 * and is the single reason a 1.5B is usable here at all. The Anthropic path
 * cannot do this on Android, and has to ask in prose instead.
 */
class OllamaBeatRewriter(
    private val host: String,
    private val model: String,
) : BeatRewriter {

    override suspend fun rewrite(
        whole: String,
        fragment: String,
        tones: List<String>,
    ): BeatAnswer? = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("model", model)
            .put("stream", false)
            .put("format", schema(tones.size))
            .put("options", JSONObject().put("temperature", 0.7).put("num_predict", 300))
            .put(
                "messages",
                JSONArray()
                    .put(turn("system", Prompt.system(tones, shapeHint = false)))
                    .put(turn("user", Prompt.user(whole, fragment, tones))),
            )

        val raw = try {
            post("$host/api/chat", body.toString())
        } catch (e: Exception) {
            throw GenerationException(
                "Could not reach the model at $host. Same wifi? Is ollama serve running?", e,
            )
        }

        val content = try {
            JSONObject(raw).getJSONObject("message").getString("content")
        } catch (e: Exception) {
            throw GenerationException("The server replied with something unexpected.", e)
        }
        BeatParser.parse(content)
    }

    private fun turn(role: String, content: String) =
        JSONObject().put("role", role).put("content", content)

    private fun post(url: String, payload: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 120_000
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            connection.outputStream.use { it.write(payload.toByteArray()) }
            if (connection.responseCode !in 200..299) {
                val detail = connection.errorStream?.bufferedReader()?.use(BufferedReader::readText)
                throw GenerationException("Server said ${connection.responseCode}. ${detail.orEmpty()}")
            }
            return connection.inputStream.bufferedReader().use(BufferedReader::readText)
        } finally {
            connection.disconnect()
        }
    }

    /** Exactly [n] alternatives, pinned. Without this a small model returns one. */
    private fun schema(n: Int): JSONObject = JSONObject()
        .put("type", "object")
        .put("required", JSONArray().put("label").put("optional").put("alternatives"))
        .put(
            "properties",
            JSONObject()
                .put("label", JSONObject().put("type", "string"))
                .put("optional", JSONObject().put("type", "boolean"))
                .put(
                    "alternatives",
                    JSONObject()
                        .put("type", "array")
                        .put("minItems", n)
                        .put("maxItems", n)
                        .put("items", JSONObject().put("type", "string")),
                ),
        )
}
