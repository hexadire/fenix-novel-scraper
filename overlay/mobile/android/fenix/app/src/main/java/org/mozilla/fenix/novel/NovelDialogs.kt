/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.novel

import android.content.ClipData
import android.content.ClipboardManager
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Site settings: the selectors of a site, typed in by hand (the equivalent of the "default parser" of the extension). They
 * are kept as a plugin of their own, so they apply every time the site is read.
 */
class SiteSettingsDialog(private val activity: NovelScraperActivity, private val analysis: Analysis) {
    private val ui = NovelUi(activity)
    private val host = NovelText.hostOf(analysis.tocDocumentUrl).lowercase().removePrefix("www.")
    private val pluginId = "custom-" + host.replace(Regex("[^a-z0-9]+"), "-").trim('-')

    fun show() {
        val existing = activity.plugins.listImported().firstOrNull { it.plugin.id == pluginId }?.plugin
        val detected = NovelDetector.chapters(analysis.tocDocument, analysis.tocDocumentUrl)
        var chapterCss = existing?.toc?.chapters?.css?.joinToString(", ").orEmpty()
        if (chapterCss.isEmpty() && detected.selector != null) chapterCss = detected.selector + " a"
        var contentCss = existing?.chapter?.content?.css?.joinToString(", ").orEmpty()
        var titleCss = existing?.chapter?.title?.css?.joinToString(", ").orEmpty()
        var removeCss = existing?.chapter?.remove?.joinToString(", ").orEmpty()

        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(NovelUi.MARGIN), ui.dp(8), ui.dp(NovelUi.MARGIN), 0)
            addView(ui.note("CSS selectors for $host. Leave a field empty to let the app detect it."))
            addView(ui.field("Chapter links", chapterCss) { chapterCss = it }, ui.params(top = 8))
            addView(ui.field("Text of a chapter", contentCss) { contentCss = it }, ui.params(top = 8))
            addView(ui.field("Title of a chapter", titleCss) { titleCss = it }, ui.params(top = 8))
            addView(ui.field("Elements to remove (separated by commas)", removeCss) { removeCss = it }, ui.params(top = 8))
        }
        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle("Site settings")
            .setView(ScrollView(activity).apply { addView(box) })
            .setPositiveButton("Save and reload") { _, _ ->
                save(chapterCss, contentCss, titleCss, removeCss)
            }
            .setNeutralButton("Test a chapter") { _, _ ->
                test(chapterCss, contentCss, titleCss, removeCss)
            }
            .setNegativeButton(android.R.string.cancel, null)
        builder.show()
    }

    private fun pluginJson(chapterCss: String, contentCss: String, titleCss: String, removeCss: String): String {
        fun list(text: String): JSONArray {
            val array = JSONArray()
            text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { array.put(it) }
            return array
        }
        val json = JSONObject()
            .put("name", "Custom: $host")
            .put("hosts", JSONArray().put(host))
        if (chapterCss.isNotBlank()) json.put("toc", JSONObject().put("chapters", chapterCss.trim()))
        val chapter = JSONObject()
        if (contentCss.isNotBlank()) chapter.put("content", chapterCssList(contentCss))
        if (titleCss.isNotBlank()) chapter.put("title", titleCss.trim())
        if (removeCss.isNotBlank()) chapter.put("remove", list(removeCss))
        if (chapter.length() > 0) json.put("chapter", chapter)
        return json.toString(2)
    }

    /** A selector text as it is typed: one selector, which may hold commas (selector lists are valid CSS). */
    private fun chapterCssList(text: String): String = text.trim()

    private fun save(chapterCss: String, contentCss: String, titleCss: String, removeCss: String) {
        if (chapterCss.isBlank() && contentCss.isBlank() && titleCss.isBlank() && removeCss.isBlank()) {
            activity.toast("Nothing to save.")
            return
        }
        val json = pluginJson(chapterCss, contentCss, titleCss, removeCss)
        activity.launchSafely {
            val result = withContext(Dispatchers.IO) { activity.plugins.replace(pluginId, json) }
            if (result is NovelPluginStore.AddResult.Invalid) {
                activity.message("These settings are not valid", result.message)
            } else {
                activity.toast("Saved. Reading the page again...")
                activity.loadInScraper(analysis.tocDocumentUrl)
            }
        }
    }

    private fun test(chapterCss: String, contentCss: String, titleCss: String, removeCss: String) {
        val chapter = analysis.chapters.firstOrNull { it.selected } ?: analysis.chapters.firstOrNull()
        if (chapter == null) {
            activity.message("Test", "There is no chapter to test with.")
            return
        }
        val plugin = try {
            SitePlugin.parse(pluginJson(chapterCss, contentCss, titleCss, removeCss), builtIn = false)
        } catch (e: NovelException) {
            activity.message("These settings are not valid", e.message.orEmpty())
            return
        }
        activity.toast("Reading the chapter...")
        activity.launchSafely {
            val text = try {
                val result = withContext(Dispatchers.Default) { activity.engine.readChapter(plugin, chapter, emptySet()) }
                val xhtml = withContext(Dispatchers.Default) { result.prepared.xhtml() }
                val plain = org.jsoup.Jsoup.parse(xhtml).text()
                "Title: ${result.title}\n${plain.length} characters of text.\n\n" + plain.take(SAMPLE)
            } catch (e: NovelException) {
                "The chapter could not be read: ${e.message}"
            }
            activity.message("Test", text)
        }
    }

    private companion object {
        const val SAMPLE = 400
    }
}

/**
 * Asks Claude for a plugin: reads the first chapter, builds the request file (see [NovelClaudeRequest]) and offers to save
 * or copy it.
 */
class PluginRequestFlow(private val activity: NovelScraperActivity, private val analysis: Analysis) {
    private val ui = NovelUi(activity)

    fun start() {
        val first = analysis.chapters.firstOrNull()?.url.orEmpty()
        val field = ui.editText(first, hint = "https://... (chapter 1)") { }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(NovelUi.MARGIN), ui.dp(8), ui.dp(NovelUi.MARGIN), 0)
            addView(ui.note("The app has the table of contents page. Now it needs the first chapter: check the address and change it if it is not chapter 1."))
            addView(field, ui.params(top = 8))
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle("Create a plugin with Claude")
            .setView(box)
            .setPositiveButton("Read chapter 1 and build the file") { _, _ -> build(field.text.toString().trim()) }
            .setNeutralButton("Build without chapter 1") { _, _ -> finish(null) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun build(chapterUrl: String) {
        if (!chapterUrl.startsWith("http")) {
            activity.message("No chapter address", "Enter the address of the first chapter.")
            return
        }
        activity.toast("Reading chapter 1...")
        activity.launchSafely {
            try {
                val facts = withContext(Dispatchers.Default) { NovelClaudeRequest.readChapter(activity.fetcher, chapterUrl) }
                finish(facts)
            } catch (e: NovelException) {
                MaterialAlertDialogBuilder(activity)
                    .setTitle("Chapter 1 could not be read")
                    .setMessage("${e.message}\n\nBuild the file without it (Claude then has less to work with), or go back and change the address.")
                    .setPositiveButton("Build without chapter 1") { _, _ -> finish(null) }
                    .setNegativeButton("Back") { _, _ -> start() }
                    .show()
            }
        }
    }

    private fun finish(facts: NovelClaudeRequest.ChapterFacts?) {
        activity.launchSafely {
            val text = withContext(Dispatchers.Default) { NovelClaudeRequest.build(activity, analysis, facts) }
            val name = NovelClaudeRequest.fileName(analysis.tocDocumentUrl)
            MaterialAlertDialogBuilder(activity)
                .setTitle("Request file ready")
                .setMessage(
                    "$name (${text.length / BYTES_PER_KB} KB)\n\n" +
                        "1. Save the file or copy the text.\n" +
                        "2. Give it to Claude (attach the file or paste the text) and ask for the plugin.\n" +
                        "3. Copy Claude's answer.\n" +
                        "4. Open Plugins and choose Paste from clipboard (or import the file).\n" +
                        "5. Load the novel page again.",
                )
                .setPositiveButton("Save file") { _, _ -> save(name, text) }
                .setNeutralButton("Copy text") { _, _ ->
                    activity.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Plugin request", text))
                    activity.toast("Copied.")
                }
                .setNegativeButton("Close", null)
                .show()
        }
    }

    private fun save(name: String, text: String) {
        activity.pickMarkdown(name) { uri ->
            if (uri != null) {
                activity.launchSafely {
                    val saved = withContext(Dispatchers.IO) {
                        try {
                            activity.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } != null
                        } catch (e: IOException) {
                            false
                        }
                    }
                    activity.toast(if (saved) "File saved." else "The file could not be saved.")
                }
            }
        }
    }

    private companion object {
        const val BYTES_PER_KB = 1024
    }
}
