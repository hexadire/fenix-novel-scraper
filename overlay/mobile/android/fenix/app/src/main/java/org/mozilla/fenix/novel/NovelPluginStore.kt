/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.novel

import android.content.Context
import mozilla.components.support.base.log.logger.Logger
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * The plugins of the novel scraper: the ones that come with the app (`assets/novel/builtin_plugins.json`) and the ones
 * the person imported or made (kept as JSON files in the private files of the app, and saved by the backup).
 *
 * A plugin that was imported wins over a built-in one for the same site, so that a plugin can be fixed or replaced.
 */
class NovelPluginStore(context: Context) {
    private val app = context.applicationContext
    private val directory = File(app.filesDir, DIRECTORY)
    private val prefs = NovelPrefs(app)

    /** An imported plugin with what the person decided about it. */
    class Entry(val plugin: SitePlugin, val enabled: Boolean, val addedAt: Long)

    sealed class AddResult {
        class Added(val entry: Entry) : AddResult()
        class Duplicate(val entry: Entry) : AddResult()
        class Invalid(val message: String) : AddResult()
    }

    // region Finding

    /** The plugin for [host]: an enabled imported one first, then an enabled built-in one. */
    fun find(host: String): SitePlugin? {
        val imported = listImported().firstOrNull { it.enabled && it.plugin.handles(host) }
        if (imported != null) return imported.plugin
        val disabled = prefs.disabledBuiltIns
        return builtIn().firstOrNull { it.id !in disabled && it.handles(host) }
    }

    /** The built-in plugins (read once; a plugin that cannot be read is left out). */
    fun builtIn(): List<SitePlugin> = synchronized(LOCK) {
        cachedBuiltIn ?: loadBuiltIn().also { cachedBuiltIn = it }
    }

    private fun loadBuiltIn(): List<SitePlugin> {
        val list = ArrayList<SitePlugin>()
        try {
            val text = app.assets.open(BUILT_IN_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val array = JSONArray(text)
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                try {
                    list.add(SitePlugin.parse(item.toString(), builtIn = true, idHint = "builtin-" + item.optString("id", i.toString())))
                } catch (e: NovelException) {
                    logger.warn("A built-in plugin was left out: ${e.message}")
                } catch (e: RuntimeException) {
                    logger.warn("A built-in plugin was left out: ${e.message}")
                }
            }
        } catch (e: IOException) {
            logger.warn("The built-in plugins could not be read.", e)
        } catch (e: JSONException) {
            logger.warn("The built-in plugins are not valid JSON.", e)
        }
        return list
    }

    fun setBuiltInEnabled(id: String, enabled: Boolean) {
        val disabled = prefs.disabledBuiltIns.toMutableSet()
        if (enabled) disabled.remove(id) else disabled.add(id)
        prefs.disabledBuiltIns = disabled
    }

    fun isBuiltInEnabled(id: String): Boolean = id !in prefs.disabledBuiltIns

    // endregion

    // region Imported plugins

    fun listImported(): List<Entry> = synchronized(LOCK) {
        directory.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(FILE_EXTENSION) }
            .mapNotNull { read(it) }
            .sortedBy { it.plugin.name.lowercase() }
    }

    /**
     * Adds the plugin in [text]: a JSON file, or text that was pasted (when it holds code blocks, the JSON one is taken).
     * A plugin with the same content is not added twice.
     */
    fun add(text: String, addedAt: Long = System.currentTimeMillis(), idHint: String? = null): AddResult {
        val json = extractJson(text) ?: return AddResult.Invalid("There is no JSON in this text.")
        if (json.length > MAX_CHARS) return AddResult.Invalid("The plugin is too large.")
        val plugin = try {
            SitePlugin.parse(json, builtIn = false, idHint = idHint)
        } catch (e: NovelException) {
            return AddResult.Invalid(e.message ?: "The plugin is not valid.")
        }
        return synchronized(LOCK) { addChecked(json, plugin, addedAt) }
    }

    private fun addChecked(json: String, plugin: SitePlugin, addedAt: Long): AddResult {
        val existing = listImported()
        val hash = sha256(json)
        val duplicate = existing.firstOrNull { sha256(it.plugin.source) == hash }
        if (duplicate != null) return AddResult.Duplicate(duplicate)
        val taken = existing.map { it.plugin.id }.toSet()
        var id = plugin.id
        var number = 2
        while (id in taken) {
            id = plugin.id + "-" + number
            number++
        }
        val entry = Entry(SitePlugin.parse(json, builtIn = false, idHint = id), true, addedAt)
        return try {
            write(entry)
            AddResult.Added(entry)
        } catch (e: IOException) {
            AddResult.Invalid("The plugin could not be saved.")
        }
    }

    /** Replaces the plugin that has this id (used for plugins that are edited in the app). */
    fun replace(id: String, text: String): AddResult = synchronized(LOCK) {
        delete(id)
        add(text, idHint = id)
    }

    fun setEnabled(id: String, enabled: Boolean): Boolean = synchronized(LOCK) {
        val entry = read(fileFor(id))
        if (entry == null) {
            false
        } else {
            try {
                write(Entry(entry.plugin, enabled, entry.addedAt))
                true
            } catch (e: IOException) {
                false
            }
        }
    }

    fun delete(id: String): Boolean = synchronized(LOCK) {
        val file = fileFor(id)
        !file.exists() || file.delete()
    }

    // endregion

    private fun fileFor(id: String): File {
        require(ID_PATTERN.matches(id)) { "Not a plugin id." }
        return File(directory, id + FILE_EXTENSION)
    }

    private fun read(file: File): Entry? {
        if (!file.isFile) return null
        return try {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            val plugin = SitePlugin.parse(json.getString("source"), builtIn = false, idHint = json.getString("id"))
            Entry(plugin, json.optBoolean("enabled", true), json.optLong("addedAt", 0L))
        } catch (e: JSONException) {
            null
        } catch (e: IOException) {
            null
        } catch (e: NovelException) {
            null
        } catch (e: RuntimeException) {
            null
        }
    }

    @Throws(IOException::class)
    private fun write(entry: Entry) {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("The folder could not be made.")
        val json = JSONObject()
            .put("id", entry.plugin.id)
            .put("enabled", entry.enabled)
            .put("addedAt", entry.addedAt)
            .put("source", entry.plugin.source)
        val target = fileFor(entry.plugin.id)
        val temporary = File(directory, entry.plugin.id + ".tmp")
        temporary.writeText(json.toString(), Charsets.UTF_8)
        if (!temporary.renameTo(target)) {
            temporary.delete()
            throw IOException("The plugin could not be saved.")
        }
    }

    companion object {
        const val BUILT_IN_ASSET = "novel/builtin_plugins.json"
        private const val DIRECTORY = "novel_plugins_json"
        private const val FILE_EXTENSION = ".json"
        private const val MAX_CHARS = 500_000
        private val ID_PATTERN = Regex("[a-z0-9][a-z0-9_-]{0,79}")
        private val FENCED = Regex("```[ \\t]*[A-Za-z0-9_+-]*[ \\t]*\\r?\\n([\\s\\S]*?)```")
        private val LOCK = Any()
        private val logger = Logger("NovelPluginStore")

        @Volatile
        private var cachedBuiltIn: List<SitePlugin>? = null

        /**
         * The JSON in [text]. A pasted chat answer holds it in a code block; a file holds only the JSON. Returns null when
         * there is none.
         */
        fun extractJson(text: String): String? {
            val candidates = ArrayList<String>()
            candidates.add(text.trim())
            if (text.contains("```")) FENCED.findAll(text).forEach { candidates.add(it.groupValues[1].trim()) }
            val first = text.indexOf('{')
            val last = text.lastIndexOf('}')
            if (first in 0 until last) candidates.add(text.substring(first, last + 1))
            for (candidate in candidates) {
                if (!candidate.startsWith("{")) continue
                try {
                    val json = JSONObject(candidate)
                    if (json.has("hosts") || json.has("name")) return candidate
                } catch (e: JSONException) {
                    continue
                }
            }
            return null
        }

        fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
