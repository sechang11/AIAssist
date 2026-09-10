package com.aitextassistant.generate

import com.aitextassistant.remix.BeatAnswer
import com.aitextassistant.remix.BeatParser
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
 * or an on-device model means replacing this class and nothing else, which is
 * the point of [BeatRewriter] being this small.
 */
class ClaudeBeatRewriter(private val apiKey: String) : BeatRewriter {

    private val client by lazy {
        AnthropicOkHttpClient.builder()
            .apiKey(apiKey)
            // Point this at your own server once one exists, and drop the key
            // from the app. The SDK speaks the Messages API shape either way.
            // .baseUrl("https://your-proxy.example.com")
            .build()
    }

    override suspend fun rewrite(
        whole: String,
        fragment: String,
        tones: List<String>,
    ): BeatAnswer? = withContext(Dispatchers.IO) {
        val params = MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(MAX_TOKENS)
            // One short fragment is not a hard problem, and this sits in front
            // of someone waiting to hit send. Effort is the latency knob; leave
            // adaptive thinking alone, which on Opus 5 is on by default.
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            // The Anthropic path cannot constrain decoding on Android, so it has
            // to ask for the shape in words. See Prompt for why that is separable.
            .system(Prompt.system(tones, shapeHint = true))
            .addUserMessage(Prompt.user(whole, fragment, tones))
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

        // No text block means the turn produced only thinking, or was declined.
        // A null answer costs this one beat, not the whole message: GridBuilder
        // falls back to the writer's own words.
        val text = message.content()
            .mapNotNull { block -> block.text().orElse(null)?.text() }
            .joinToString("\n")
            .trim()

        if (text.isEmpty()) null else BeatParser.parse(text)
    }

    private companion object {
        const val MODEL = "claude-opus-5"
        // Thinking tokens count against this. One beat's JSON is tiny.
        const val MAX_TOKENS = 2_000L
    }
}
