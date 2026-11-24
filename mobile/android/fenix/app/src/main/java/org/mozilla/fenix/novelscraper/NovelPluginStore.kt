/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.novelscraper

import android.content.Context
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONException
import org.json.JSONObject

/**
 * The plugins of the novel scraper that the person imported: JavaScript files in the format of the parsers of
 * WebToEpub (a class that extends `Parser` and is registered with `parserFactory.register`).
 *
 * Each plugin is kept as one small JSON file in the private files of the app, so it is gone only when the person deletes
 * it or the app data is cleared, and the backup (see `NovelPluginsSection`) can save it. The scraper page asks for the
 * plugins when it opens (see [NovelScraperBridge]) and runs the enabled ones.
 *
 * The class is safe to use from several threads: every method that reads or changes the files takes the same lock.
 */
internal class NovelPluginStore(context: Context) {
    private val directory = File(context.applicationContext.filesDir, DIRECTORY)

    /** One imported plugin. */
    data class Plugin(
        val id: String,
        val name: String,
        val enabled: Boolean,
        val hosts: List<String>,
        val source: String,
        val addedAt: Long,
    )

    /** What happened when a file or some text was added. */
    sealed class AddResult {
        data class Added(val plugin: Plugin) : AddResult()
        data class Duplicate(val plugin: Plugin) : AddResult()
        data class Invalid(val reason: Reason) : AddResult()
    }

    enum class Reason { EMPTY, TOO_LARGE, NOT_A_PLUGIN, NOT_SAVED }

    /** All plugins, sorted by name. Files that cannot be read are left out. */
    fun list(): List<Plugin> = synchronized(LOCK) {
        directory.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(FILE_EXTENSION) }
            .mapNotNull { read(it) }
            .sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    fun get(id: String): Plugin? = synchronized(LOCK) { read(fileFor(id)) }

    /**
     * Adds the plugin in [text] (a file, or text that was pasted: when it holds a code block of a chat answer only the
     * code is taken). [fileName] is used for the name when the code does not say what it is called.
     * A plugin with exactly the same code is not added twice.
     */
    fun add(text: String, fileName: String?, addedAt: Long = System.currentTimeMillis()): AddResult {
        val source = extractCode(text).trim()
        if (source.isEmpty()) return AddResult.Invalid(Reason.EMPTY)
        if (source.length > MAX_SOURCE_CHARS) return AddResult.Invalid(Reason.TOO_LARGE)
        if (!REGISTRATION.containsMatchIn(source)) return AddResult.Invalid(Reason.NOT_A_PLUGIN)
        return synchronized(LOCK) { addChecked(source, fileName, addedAt) }
    }

    private fun addChecked(source: String, fileName: String?, addedAt: Long): AddResult {
        val hash = sha256(source)
        val existing = list()
        val duplicate = existing.firstOrNull { sha256(it.source) == hash }
        if (duplicate != null) return AddResult.Duplicate(duplicate)

        val name = nameOf(source, fileName)
        val plugin = Plugin(
            id = freshId(name, existing.map { it.id }.toSet()),
            name = name,
            enabled = true,
            hosts = hostsOf(source),
            source = source,
            addedAt = addedAt,
        )
        return try {
            write(plugin)
            AddResult.Added(plugin)
        } catch (e: IOException) {
            AddResult.Invalid(Reason.NOT_SAVED)
        }
    }

    /** Turns a plugin on or off. Returns false when there is no such plugin or it could not be saved. */
    fun setEnabled(id: String, enabled: Boolean): Boolean = synchronized(LOCK) {
        val plugin = read(fileFor(id))
        if (plugin == null) {
            false
        } else {
            try {
                write(plugin.copy(enabled = enabled))
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

    // region Files

    private fun fileFor(id: String): File {
        // The id only holds the characters that makeId lets through, so it cannot leave the directory.
        require(ID_PATTERN.matches(id)) { "Not a plugin id." }
        return File(directory, id + FILE_EXTENSION)
    }

    private fun read(file: File): Plugin? {
        if (!file.isFile) return null
        return try {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            val hosts = json.optJSONArray(KEY_HOSTS)
            Plugin(
                id = json.getString(KEY_ID),
                name = json.getString(KEY_NAME),
                enabled = json.optBoolean(KEY_ENABLED, true),
                hosts = if (hosts == null) emptyList() else (0 until hosts.length()).map { hosts.getString(it) },
                source = json.getString(KEY_SOURCE),
                addedAt = json.optLong(KEY_ADDED_AT, 0L),
            )
        } catch (e: JSONException) {
            null
        } catch (e: IOException) {
            null
        }
    }

    @Throws(IOException::class)
    private fun write(plugin: Plugin) {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("The folder could not be made.")
        val json = JSONObject()
            .put(KEY_ID, plugin.id)
            .put(KEY_NAME, plugin.name)
            .put(KEY_ENABLED, plugin.enabled)
            .put(KEY_HOSTS, org.json.JSONArray(plugin.hosts))
            .put(KEY_SOURCE, plugin.source)
            .put(KEY_ADDED_AT, plugin.addedAt)
        val target = fileFor(plugin.id)
        val temporary = File(directory, plugin.id + TEMPORARY_EXTENSION)
        temporary.writeText(json.toString(), Charsets.UTF_8)
        if (!temporary.renameTo(target)) {
            temporary.delete()
            throw IOException("The plugin could not be saved.")
        }
    }

    // endregion

    internal companion object {
        const val DIRECTORY = "novel_plugins"
        const val MAX_SOURCE_CHARS = 1_000_000

        private val LOCK = Any()
        private const val FILE_EXTENSION = ".json"
        private const val TEMPORARY_EXTENSION = ".tmp"
        private const val KEY_ID = "id"
        private const val KEY_NAME = "name"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_HOSTS = "hosts"
        private const val KEY_SOURCE = "source"
        private const val KEY_ADDED_AT = "addedAt"
        private const val MAX_NAME_LENGTH = 40
        private val ID_PATTERN = Regex("[a-z0-9][a-z0-9_-]{0,59}")

        /** A plugin registers itself for a site, or for a rule that selects its sites. */
        private val REGISTRATION = Regex("""parserFactory\s*\.\s*register(?:Rule|UrlRule|ManualSelect)?\s*\(""")
        private val HOST_REGISTRATION = Regex("""parserFactory\s*\.\s*register\s*\(\s*["'`]([^"'`]+)["'`]""")
        private val CLASS_NAME = Regex("""class\s+([A-Za-z0-9_$]+)\s+extends\s+[A-Za-z0-9_$]*Parser""")
        private val FENCED_BLOCK = Regex("""```[ \t]*([A-Za-z0-9_+-]*)[ \t]*\r?\n([\s\S]*?)```""")

        /**
         * When [text] holds code blocks (the answer of a chat is pasted as it is), the code of the block that
         * registers a parser. Otherwise the text itself.
         */
        fun extractCode(text: String): String {
            if (!text.contains("```")) return text
            val blocks = FENCED_BLOCK.findAll(text).map { it.groupValues[2] }.toList()
            return blocks.firstOrNull { REGISTRATION.containsMatchIn(it) } ?: text
        }

        /** The sites the plugin registers itself for with `parserFactory.register("host", ...)`. */
        fun hostsOf(source: String): List<String> =
            HOST_REGISTRATION.findAll(source).map { it.groupValues[1] }.distinct().toList()

        /** The name of the class that extends Parser, or else the name of the file, or else the first site. */
        fun nameOf(source: String, fileName: String?): String {
            val fromClass = CLASS_NAME.find(source)?.groupValues?.get(1)?.removeSuffix("Parser")?.takeIf { it.isNotBlank() }
            val fromFile = fileName?.substringBeforeLast('.')?.trim()?.takeIf { it.isNotBlank() }
            val fromHost = hostsOf(source).firstOrNull()
            return (fromClass ?: fromFile ?: fromHost ?: "Plugin").take(MAX_NAME_LENGTH)
        }

        /** An id made of the name that none of [taken] has. */
        fun freshId(name: String, taken: Set<String>): String {
            val base = name.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-')
                .ifEmpty { "plugin" }.take(MAX_NAME_LENGTH)
            var candidate = base
            var number = 2
            while (candidate in taken) {
                candidate = "$base-$number"
                number++
            }
            return candidate
        }

        fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
