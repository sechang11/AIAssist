package com.aitextassistant.remix

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Reads the model's JSON by hand rather than through @Serializable classes.
 * A missing field, an extra field, or one wrong type should cost a slot, not
 * the whole response - strict deserialization would throw on all three.
 */
object DraftParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    class MalformedDraft(message: String) : Exception(message)

    fun parse(raw: String): RemixDraft {
        val body = extractJsonObject(raw)
            ?: throw MalformedDraft("No JSON object in the response.")

        val root = try {
            json.parseToJsonElement(body) as? JsonObject
                ?: throw MalformedDraft("Response was JSON but not an object.")
        } catch (e: Exception) {
            throw MalformedDraft("Could not parse the response as JSON: ${e.message}")
        }

        val tones = (root["tones"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .orEmpty()

        val slots = (root["slots"] as? JsonArray).orEmpty().mapIndexedNotNull { i, element ->
            val obj = element as? JsonObject ?: return@mapIndexedNotNull null
            val alternatives = (obj["alternatives"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?: return@mapIndexedNotNull null
            Slot(
                id = (obj["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: "slot$i",
                label = (obj["label"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                optional = (obj["optional"] as? JsonPrimitive)?.booleanOrNull ?: false,
                alternatives = alternatives,
            )
        }

        return RemixDraft(tones, dedupeIds(slots)).validated()
            ?: throw MalformedDraft("No usable slots in the response.")
    }

    /**
     * Models sometimes reuse an id. Ids key the selection map, so duplicates
     * would make two slots move together.
     */
    private fun dedupeIds(slots: List<Slot>): List<Slot> {
        val seen = mutableSetOf<String>()
        return slots.mapIndexed { i, slot ->
            if (seen.add(slot.id)) slot else slot.copy(id = "${slot.id}_$i").also { seen.add(it.id) }
        }
    }

    /**
     * Survives code fences and any "Here's the JSON:" preamble by taking the
     * span from the first brace to the last.
     */
    internal fun extractJsonObject(raw: String): String? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        return if (start >= 0 && end > start) raw.substring(start, end + 1) else null
    }
}
