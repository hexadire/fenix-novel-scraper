/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.novel

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Small helpers for the placeholders and the JSON paths of API based plugins. */
object NovelText {
    // Android's regex engine (ICU) wants every brace escaped, also inside a character class.
    private val PLACEHOLDER = Regex("\\{([^\\{\\}]+)\\}")

    /**
     * Replaces `{name}` by the value of the variable [name], and `{item.path}` by the value at that path of [item].
     * A placeholder that has no value becomes empty.
     */
    fun fill(template: String, variables: Map<String, String>, item: Any? = null): String =
        PLACEHOLDER.replace(template) { match ->
            val key = match.groupValues[1]
            if (key.startsWith("item.") && item != null) {
                stringify(path(item, key.removePrefix("item.")))
            } else {
                variables[key].orEmpty()
            }
        }

    /** Follows a path like `data.chapters[0].title` through JSON. Returns null when the path leads nowhere. */
    fun path(root: Any?, path: String): Any? {
        var current: Any? = root
        val tokens = Regex("[^.\\[\\]]+|\\[\\d+\\]").findAll(path).map { it.value }.toList()
        for (token in tokens) {
            current = when {
                token.startsWith("[") -> (current as? JSONArray)?.opt(token.substring(1, token.length - 1).toInt())
                current is JSONObject -> current.opt(token)
                current is JSONArray -> current.opt(token.toIntOrNull() ?: return null)
                else -> return null
            }
            if (current == null || current == JSONObject.NULL) return null
        }
        return current
    }

    fun stringify(value: Any?): String = when (value) {
        null, JSONObject.NULL -> ""
        is Double -> if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
        else -> value.toString()
    }

    /** The address without its fragment and query. */
    fun base(url: String): String = url.substringBefore('#').substringBefore('?')

    /** `https://host` of an address. */
    fun origin(url: String): String = try {
        val uri = URI(url)
        uri.scheme + "://" + uri.authority
    } catch (e: java.net.URISyntaxException) {
        ""
    }

    fun hostOf(url: String): String = try {
        URI(url.trim()).host.orEmpty()
    } catch (e: java.net.URISyntaxException) {
        ""
    }

    /** The number in a text such as "Chapter 12.5", or null. */
    fun numberIn(text: String): Double? =
        Regex("(\\d+(?:\\.\\d+)?)").find(text)?.groupValues?.get(1)?.toDoubleOrNull()

    /** A name that can be used for a file. */
    fun safeFileName(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_").replace(Regex("\\s+"), " ").trim().trim('.')
        return cleaned.ifEmpty { "novel" }.take(MAX_FILE_NAME)
    }

    fun escapeXml(text: String): String = text
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** Removes characters that cannot be in an XML document. */
    fun xmlSafe(text: String): String = text.replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]"), "")

    private const val MAX_FILE_NAME = 120
}
