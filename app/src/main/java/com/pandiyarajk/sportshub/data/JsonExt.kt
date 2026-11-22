// Author: Pandiyaraj Karuppasamy
// Date: Oct-17-2026

package com.pandiyarajk.sportshub.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.OffsetDateTime

// Every field is optional on purpose: formats differ across Tests, ODIs, T20s and scheduled
// matches, so a missing field is a display state, never an error. These helpers return blank
// or empty rather than throwing.

internal fun JsonElement?.at(key: String): JsonElement? = (this as? JsonObject)?.get(key)

internal fun JsonElement?.list(): List<JsonElement> = (this as? JsonArray) ?: emptyList()

internal fun JsonElement?.text(): String =
    (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content.orEmpty()

/**
 * ESPN writes a wicket-keeper's dagger in dismissal text as an HTML entity ("c &dagger;Pant b
 * Bumrah"). Turned into the characters before parsing, so every parser sees "c †Pant b Bumrah".
 * `&quot;` is left alone: inside a JSON string it would break the document.
 */
internal fun decodeEntities(body: String): String =
    if ('&' !in body) body
    else body.replace("&dagger;", "†").replace("&Dagger;", "‡").replace("&amp;", "&").replace("&#39;", "'").replace("&apos;", "'")

/** ESPN sends counts as floats (6.0) in some places and strings or ints (6) in others. */
internal fun JsonElement?.int(): Int = text().let { it.toIntOrNull() ?: it.toDoubleOrNull()?.toInt() } ?: 0

/** Parses ESPN timestamps, with or without seconds (2026-09-20T13:00Z), to epoch millis. */
internal fun parseMillis(iso: String): Long? =
    runCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()
