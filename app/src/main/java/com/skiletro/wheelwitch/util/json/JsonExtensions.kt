package com.skiletro.wheelwitch.util.json

import org.json.JSONArray
import org.json.JSONObject

/**
 * Returns the string at [key] as a non-empty String, or null when the key is
 * absent, explicitly null, or maps to an empty string. Centralizes the
 * `if (!obj.isNull(k)) obj.optString(k, "").takeIf { it.isNotEmpty() } else null`
 * pattern used across the RWFC API parsers.
 */
fun JSONObject.optNonEmptyString(key: String): String? =
    if (isNull(key)) null
    else optString(key, "").takeIf { it.isNotEmpty() }

/**
 * Returns the first non-empty string found under any of [keys], or null when
 * none of the keys are present. Used by parsers that handle responses whose
 * field names vary across API revisions (camelCase / snake_case / short forms).
 */
fun JSONObject.optAnyString(vararg keys: String): String? =
    keys.asSequence()
        .map { optString(it, "") }
        .firstOrNull { it.isNotEmpty() }

/**
 * Returns the first non-null [JSONArray] found under any of [keys], or null
 * when none of the keys map to an array. Used by parsers that handle responses
 * whose field names vary across API revisions.
 */
fun JSONObject.optAnyArray(vararg keys: String): JSONArray? {
    for (key in keys) {
        val arr = optJSONArray(key)
        if (arr != null) return arr
    }
    return null
}

/**
 * Coerces a [jsonString] (which may be either a bare JSON array or an object
 * wrapping the array under one of [keys]) into a [JSONArray]. Returns null
 * when the input is an object but none of [keys] are present. Used by parsers
 * that handle both response shapes from the RWFC API.
 *
 * The API has historically returned leaderboards and track lists as either a
 * bare top-level array or an object such as `{"players": [...]}` or
 * `{"tracks": [...]}`. Callers pass every field name they have seen in the
 * wild.
 */
fun jsonArrayFromResponse(jsonString: String, vararg keys: String): JSONArray? {
    val trimmed = jsonString.trim()
    if (trimmed.startsWith("[")) return JSONArray(trimmed)
    val root = JSONObject(trimmed)
    return root.optAnyArray(*keys)
}

/** Maps each element of this array through [transform], skipping any non-object element. */
fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> {
    val result = ArrayList<T>(length())
    for (i in 0 until length()) {
        val obj = optJSONObject(i) ?: continue
        result.add(transform(obj))
    }
    return result
}
