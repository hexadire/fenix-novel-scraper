/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.novel

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

/** A chapter of a novel in the library; [file] is the name of its text on disk, or null while it is not downloaded. */
class StoredChapter(val url: String, var title: String, var file: String?)

/** A novel in the library: its details, its chapters and where each downloaded one is kept. */
class LibraryEntry(
    val id: String,
    var info: NovelInfo,
    var pluginName: String?,
    val uuid: String,
    val chapters: MutableList<StoredChapter>,
    var updatedAt: Long,
    var coverFile: String?,
) {
    fun downloadedCount(): Int = chapters.count { it.file != null }
}

/**
 * The novels that were downloaded: for each one a folder in the private files of the app with the text of every
 * chapter and the images, so that a download can be resumed, new chapters can be added later, and the EPUB can be made
 * again at any time.
 */
class NovelLibrary(context: Context) {
    private val root = File(context.applicationContext.filesDir, "novel_library")

    fun idFor(sourceUrl: String): String = NovelImages.hash(sourceUrl.trim().substringBefore('#').trimEnd('/'))

    fun directory(id: String): File = File(root, id)

    fun chaptersDirectory(id: String): File = File(directory(id), "chapters")

    fun imagesDirectory(id: String): File = File(directory(id), "images")

    fun list(): List<LibraryEntry> =
        root.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { load(it.name) }.sortedByDescending { it.updatedAt }

    fun load(id: String): LibraryEntry? {
        val file = File(directory(id), INFO_FILE)
        if (!file.isFile) return null
        return try {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            val infoJson = json.getJSONObject("info")
            val info = NovelInfo(
                sourceUrl = infoJson.optString("sourceUrl"),
                title = infoJson.optString("title"),
                author = infoJson.optString("author"),
                language = infoJson.optString("language", "en"),
                fileName = infoJson.optString("fileName"),
                coverUrl = infoJson.optString("coverUrl"),
                description = infoJson.optString("description"),
                subjects = infoJson.optString("subjects"),
                translator = infoJson.optString("translator"),
                publisher = infoJson.optString("publisher"),
                seriesName = infoJson.optString("seriesName"),
                seriesIndex = infoJson.optString("seriesIndex"),
            )
            val chapters = ArrayList<StoredChapter>()
            val array = json.optJSONArray("chapters") ?: JSONArray()
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                chapters.add(StoredChapter(item.getString("url"), item.optString("title"), item.optString("file", "").ifEmpty { null }))
            }
            LibraryEntry(
                id = id,
                info = info,
                pluginName = json.optString("plugin", "").ifEmpty { null },
                uuid = json.optString("uuid", UUID.randomUUID().toString()),
                chapters = chapters,
                updatedAt = json.optLong("updatedAt", 0L),
                coverFile = json.optString("cover", "").ifEmpty { null },
            )
        } catch (e: JSONException) {
            null
        } catch (e: IOException) {
            null
        }
    }

    @Throws(IOException::class)
    fun save(entry: LibraryEntry) {
        val folder = directory(entry.id)
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException("The library folder could not be made.")
        val info = entry.info
        val infoJson = JSONObject()
            .put("sourceUrl", info.sourceUrl).put("title", info.title).put("author", info.author)
            .put("language", info.language).put("fileName", info.fileName).put("coverUrl", info.coverUrl)
            .put("description", info.description).put("subjects", info.subjects).put("translator", info.translator)
            .put("publisher", info.publisher).put("seriesName", info.seriesName).put("seriesIndex", info.seriesIndex)
        val array = JSONArray()
        for (chapter in entry.chapters) {
            array.put(JSONObject().put("url", chapter.url).put("title", chapter.title).put("file", chapter.file ?: ""))
        }
        val json = JSONObject()
            .put("info", infoJson).put("plugin", entry.pluginName ?: "").put("uuid", entry.uuid)
            .put("updatedAt", entry.updatedAt).put("cover", entry.coverFile ?: "").put("chapters", array)
        val temporary = File(folder, "$INFO_FILE.tmp")
        temporary.writeText(json.toString(), Charsets.UTF_8)
        if (!temporary.renameTo(File(folder, INFO_FILE))) {
            temporary.delete()
            throw IOException("The library entry could not be saved.")
        }
    }

    fun delete(id: String): Boolean = directory(id).deleteRecursively()

    /** The size of what is kept for the novel, in bytes. */
    fun sizeOf(id: String): Long = directory(id).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun newEntry(info: NovelInfo, pluginName: String?): LibraryEntry =
        LibraryEntry(idFor(info.sourceUrl), info, pluginName, UUID.randomUUID().toString(), ArrayList(), System.currentTimeMillis(), null)

    private companion object {
        const val INFO_FILE = "info.json"
    }
}
