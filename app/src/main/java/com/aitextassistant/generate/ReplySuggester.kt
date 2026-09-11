package com.aitextassistant.generate

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.json.JSONObject

/** One suggested reply. [intent] is a two or three word label, not a tone. */
data class Reply(val intent: String, val text: String)

/**
 * Answering a message someone sent you, rather than rewriting one you wrote.
 *
 * Deliberately not the beat pipeline. Rewriting has an original to be faithful
 * to, and the whole apparatus of guards exists to protect it. A reply has no
 * original: there is nothing to preserve, so the risk is the opposite one, of
 * inventing a commitment on your behalf. Hence the prompt below spends its
 * words on that rather than on structure.
 */
interface ReplySuggester {
    /**
     * @param avoid replies already offered, so "show me three more" produces
     *   genuinely different ones rather than rewording the same three.
     */
    suspend fun suggest(incoming: String, avoid: List<String> = emptyList(), count: Int = 3): List<Reply>
}

internal object ReplyPrompt {

    fun system(count: Int): String = """
        You suggest replies to a message someone has just received.

        Give exactly $count replies that take genuinely different positions. Agreeing,
        declining, deferring, asking something back. Not $count phrasings of one answer:
        if two of them could both be sent, they are not different enough.

        Each is one short text message, the length a person actually sends from a
        phone. Ordinary texting register: contractions, lowercase is fine, no
        greeting and no sign-off.

        Invent nothing about the sender's life. No reasons, no times, no names, no
        excuses that were not already in the message they received. If a reply
        needs a reason to make sense, leave the reason out rather than making one
        up; they can add the true one themselves.

        Give each a two or three word label for what it does, not how it sounds.

        Reply with a JSON object holding one key, "replies", an array of exactly
        $count objects, each with "intent" and "text".
    """.trimIndent()

    fun user(incoming: String, avoid: List<String>): String {
        val base = "Message they received:\n<<<\n" + incoming + "\n>>>"
        if (avoid.isEmpty()) return base
        return base + "\n\nAlready offered, so do not repeat or lightly reword any of these:\n" +
            avoid.joinToString("\n") { "- $it" }
    }

    fun parse(raw: String, count: Int): List<Reply> {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyList()
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        val root = try {
            json.parseToJsonElement(raw.substring(start, end + 1)) as? JsonObject ?: return emptyList()
        } catch (e: Exception) {
            return emptyList()
        }
        return (root["replies"] as? JsonArray).orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val text = (obj["text"] as? JsonPrimitive)?.contentOrNull?.trim()
            if (text.isNullOrEmpty()) return@mapNotNull null
            Reply(
                intent = (obj["intent"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                    .ifEmpty { "reply" },
                text = text,
            )
        }.take(count)
    }
}

class OllamaReplySuggester(host: String, model: String) : ReplySuggester {

    private val client = OllamaClient(host, model)

    override suspend fun suggest(incoming: String, avoid: List<String>, count: Int): List<Reply> {
        val schema = OllamaClient.obj(
            listOf("replies"),
            JSONObject().put(
                "replies",
                OllamaClient.fixedArray(
                    count,
                    OllamaClient.obj(
                        listOf("intent", "text"),
                        JSONObject()
                            .put("intent", OllamaClient.string)
                            .put("text", OllamaClient.string),
                    ),
                ),
            ),
        )
        val raw = client.chat(
            system = ReplyPrompt.system(count),
            user = ReplyPrompt.user(incoming, avoid),
            schema = schema,
            // Higher than the rewriter uses. Three replies that differ is the
            // whole job here, and there is no original to drift away from.
            temperature = 0.9,
            maxTokens = 400,
        )
        return ReplyPrompt.parse(raw, count)
    }
}

class ClaudeReplySuggester(private val apiKey: String) : ReplySuggester {

    private val client by lazy { AnthropicOkHttpClient.builder().apiKey(apiKey).build() }

    override suspend fun suggest(
        incoming: String,
        avoid: List<String>,
        count: Int,
    ): List<Reply> = withContext(Dispatchers.IO) {
        val params = MessageCreateParams.builder()
            .model("claude-opus-5")
            .maxTokens(2_000L)
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            .system(ReplyPrompt.system(count))
            .addUserMessage(ReplyPrompt.user(incoming, avoid))
            .build()

        val message = try {
            client.messages().create(params)
        } catch (e: Exception) {
            throw GenerationException("Could not reach the API. Check your connection.", e)
        }
        val text = message.content()
            .mapNotNull { block -> block.text().orElse(null)?.text() }
            .joinToString("\n")
        ReplyPrompt.parse(text, count)
    }
}

/** Offline stand-in. Obviously not real suggestions; it keeps the screen testable. */
class StubReplySuggester : ReplySuggester {

    override suspend fun suggest(incoming: String, avoid: List<String>, count: Int): List<Reply> {
        delay(250)
        val pool = listOf(
            Reply("yes", "yeah that works for me"),
            Reply("no", "sorry, cant do that one"),
            Reply("defer", "let me check and come back to you"),
            Reply("ask back", "what time were you thinking?"),
            Reply("yes, later", "works but could we push it back a bit?"),
            Reply("thanks", "thanks for letting me know"),
        )
        return pool.filter { it.text !in avoid }.shuffled().take(count)
    }
}

object Suggesters {
    fun default(context: android.content.Context): ReplySuggester {
        val settings = Settings(context)
        return when {
            settings.usingOllama -> OllamaReplySuggester(settings.ollamaHost, settings.ollamaModel)
            Generators.hasApiKey -> ClaudeReplySuggester(com.aitextassistant.BuildConfig.ANTHROPIC_API_KEY)
            else -> StubReplySuggester()
        }
    }
}
