package com.skiletro.wheelwitch.util.json

import org.json.JSONArray
import org.json.JSONObject

/**
 * Returns the string-coerced value at [key], or null for an absent, null, or empty value.
 */
fun JSONObject.optNonEmptyString(key: String): String? =
    if (isNull(key)) null
    else optString(key, "").takeIf { it.isNotEmpty() }

/**
 * Returns the first non-empty string-coerced value under [keys], or null if none is found.
 */
fun JSONObject.optAnyString(vararg keys: String): String? =
    keys.asSequence()
        .map { optString(it, "") }
        .firstOrNull { it.isNotEmpty() }

/**
 * Returns the first [JSONArray] under [keys], or null if no value is an array.
 */
fun JSONObject.optAnyArray(vararg keys: String): JSONArray? {
    for (key in keys) {
        val arr = optJSONArray(key)
        if (arr != null) return arr
    }
    return null
}

/**
 * Reads a bare JSON array or the first array under [keys] in an object.
 * Returns null for an object without a matching array; malformed JSON throws.
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
