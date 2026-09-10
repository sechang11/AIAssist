package com.aitextassistant.remix

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** One beat as the model returns it, before the guarantee is applied. */
data class BeatAnswer(
    val label: String,
    val optional: Boolean,
    val alternatives: List<String>,
)

/**
 * Reads a single beat's JSON by hand rather than through @Serializable classes.
 * A missing field, an extra field or one wrong type should cost this beat, not
 * the whole message; strict deserialization would throw on all three.
 */
object BeatParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Null when there is nothing usable. The caller falls back to the original words. */
    fun parse(raw: String): BeatAnswer? {
        val body = extractJsonObject(raw) ?: return null
        val root = try {
            json.parseToJsonElement(body) as? JsonObject ?: return null
        } catch (e: Exception) {
            return null
        }

        val alternatives = (root["alternatives"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
            ?.filter { it.isNotEmpty() }
            ?: return null
        if (alternatives.isEmpty()) return null

        return BeatAnswer(
            label = (root["label"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty(),
            optional = (root["optional"] as? JsonPrimitive)?.booleanOrNull ?: false,
            alternatives = alternatives,
        )
    }

    /** Survives code fences and any "Here's the JSON:" preamble. */
    internal fun extractJsonObject(raw: String): String? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        return if (start >= 0 && end > start) raw.substring(start, end + 1) else null
    }
}
