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

    companion object {
        /**
         * Ask for more than will be shown. [ReplyGuards] drops the replies that
         * invent a time or arrive as a two-word stub, and without headroom a
         * single dropped reply leaves a gap on screen.
         */
        fun overAsk(count: Int): Int = count + 2
    }
}

internal object ReplyPrompt {

    /**
     * Short on purpose. Every extra rule here cost compliance on all the others;
     * the mechanical ones now live in [ReplyGuards], which leaves this to say
     * only what code cannot check.
     */
    fun system(count: Int): String = """
        You suggest replies to a message someone has just received, so they can
        send one without typing it.

        Read what kind of message it is first. Something was asked, or proposed,
        or requested, or simply told to them. Only a question can be answered yes
        or no. Forcing a yes and a no onto a message that asked nothing gives you
        replies that all mean the same thing under different labels.

        Then give exactly $count replies that differ in what they do, not in how
        they sound. Three ways of agreeing is one reply printed three times.
        Prefer the ones they would have to stop and word carefully over the ones
        they could thumb out without thinking.

        If the message carries bad news, grief or illness, every reply must be a
        kind one, and none of them may weigh it up, negotiate with it or decline
        it. Vary only what they offer.

        Invent nothing about their life: no reason, time, place, name, job or
        excuse that the message did not already contain. Where it does not say
        who is in the wrong, do not decide.

        Each reply is one text message in ordinary texting register: contractions,
        lowercase, no greeting, no sign-off, no emoji. Label each with at most
        three words of your own for what it does here.

        Reply with a JSON object: "reading", a few words on what kind of message
        this is, then "replies", an array of exactly $count objects, each with
        "intent" and "text".
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
        val asked = ReplySuggester.overAsk(count)
        // "reading" first, and it is not decoration. Constrained decoding emits
        // the keys in this order, so the model has to name what kind of message
        // it is before it writes a single reply, and it stops forcing a yes and
        // a no onto messages that asked nothing. Nothing reads the field.
        val schema = OllamaClient.obj(
            listOf("reading", "replies"),
            JSONObject()
                .put("reading", OllamaClient.string)
                .put(
                    "replies",
                    OllamaClient.fixedArray(
                        asked,
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
            system = ReplyPrompt.system(asked),
            user = ReplyPrompt.user(incoming, avoid),
            schema = schema,
            // Higher than the rewriter uses. Three replies that differ is the
            // whole job here, and there is no original to drift away from.
            temperature = 0.9,
            maxTokens = 600,
        )
        return ReplyGuards.keep(incoming, ReplyPrompt.parse(raw, asked), avoid, count)
    }
}

class ClaudeReplySuggester(private val apiKey: String) : ReplySuggester {

    private val client by lazy { AnthropicOkHttpClient.builder().apiKey(apiKey).build() }

    override suspend fun suggest(
        incoming: String,
        avoid: List<String>,
        count: Int,
    ): List<Reply> = withContext(Dispatchers.IO) {
        val asked = ReplySuggester.overAsk(count)
        val params = MessageCreateParams.builder()
            .model("claude-opus-5")
            .maxTokens(2_000L)
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            .system(ReplyPrompt.system(asked))
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
        ReplyGuards.keep(incoming, ReplyPrompt.parse(text, asked), avoid, count)
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
