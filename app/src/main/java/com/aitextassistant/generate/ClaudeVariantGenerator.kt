package com.aitextassistant.generate

import com.aitextassistant.remix.DraftParser
import com.aitextassistant.remix.RemixDraft
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.RateLimitException
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GenerationException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The only file that knows about the Anthropic SDK. Swapping in a backend proxy
 * or an on-device model means replacing this class and nothing else.
 */
class ClaudeVariantGenerator(private val apiKey: String) : VariantGenerator {

    private val client by lazy {
        AnthropicOkHttpClient.builder()
            .apiKey(apiKey)
            // Point this at your own server once one exists, and drop the key from
            // the app. The SDK speaks the Messages API shape either way.
            // .baseUrl("https://your-proxy.example.com")
            .build()
    }

    override suspend fun generate(original: String, tones: List<String>): RemixDraft =
        withContext(Dispatchers.IO) {
            val params = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(MAX_TOKENS)
                // Rewriting one text message is not a hard problem, and this sits in
                // front of someone waiting to hit send. Effort is the latency knob;
                // leave adaptive thinking alone, which on Opus 5 is on by default.
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                .system(Prompt.SYSTEM)
                .addUserMessage(Prompt.user(original, tones))
                .build()

            val message = try {
                client.messages().create(params)
            } catch (e: NotFoundException) {
                throw GenerationException("Model $MODEL was rejected. Check the model id.", e)
            } catch (e: RateLimitException) {
                throw GenerationException("Rate limited. Try again in a moment.", e)
            } catch (e: AnthropicServiceException) {
                throw GenerationException("The API refused the request: ${e.message}", e)
            } catch (e: Exception) {
                throw GenerationException("Could not reach the API. Check your connection.", e)
            }

            // No text block means the turn produced only thinking, or the request was
            // declined. Either way there is nothing to parse.
            val text = message.content()
                .mapNotNull { block -> block.text().orElse(null)?.text() }
                .joinToString("\n")
                .trim()

            if (text.isEmpty()) throw GenerationException("The model returned no text.")

            try {
                DraftParser.parse(text)
            } catch (e: DraftParser.MalformedDraft) {
                throw GenerationException("The model's reply was not in the expected shape.", e)
            }
        }

    private companion object {
        const val MODEL = "claude-opus-5"
        // Thinking tokens count against this, so it is well above what the JSON needs.
        const val MAX_TOKENS = 8_000L
    }
}
