/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.novel

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.provider.OpenableColumns
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * The plugins: import them from files or from the clipboard (a pasted answer of Claude works as it is), switch them on and
 * off, export or delete the ones you imported, and look through the plugins that come with the app.
 */
class NovelPluginsScreen(activity: NovelScraperActivity) : NovelScreen(activity) {
    override val title: String = "Plugins"

    private lateinit var importedBox: LinearLayout
    private lateinit var builtInBox: LinearLayout
    private var search = ""

    override fun build(): View {
        importedBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        builtInBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val searchField = ui.editText("", hint = "Find a site among the built-in plugins") { text ->
            search = text
            showBuiltIn()
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(NovelUi.MARGIN), ui.dp(NovelUi.MARGIN), ui.dp(NovelUi.MARGIN), ui.dp(NovelUi.MARGIN))
            addView(
                ui.text(
                    "A plugin teaches the scraper how to read one website. When a site has no plugin, the scraper offers to prepare " +
                        "a request file for Claude. Import the JSON that Claude writes here, and the scraper uses it from then on.",
                ),
            )
            addView(ui.button("Import from file", primary = true) { importFiles() }, ui.params(top = NovelUi.GAP))
            addView(ui.button("Paste from clipboard") { pasteFromClipboard() }, ui.params(top = 8))
            addView(ui.button("Show the plugin format") { showGuide() }, ui.params(top = 8))
            addView(ui.heading("Imported plugins"), ui.params(top = NovelUi.GAP * 2))
            addView(importedBox, ui.params(top = 4))
            addView(ui.heading("Built-in plugins"), ui.params(top = NovelUi.GAP * 2))
            addView(searchField, ui.params(top = 4))
            addView(builtInBox, ui.params(top = 4))
        }
        return ScrollView(activity).apply { addView(content) }
    }

    override fun onShown() {
        showImported()
        showBuiltIn()
    }

    // region Imported

    private fun showImported() {
        activity.launchSafely {
            val entries = withContext(Dispatchers.IO) { activity.plugins.listImported() }
            importedBox.removeAllViews()
            if (entries.isEmpty()) importedBox.addView(ui.note("No plugins imported yet."))
            for (entry in entries) {
                val plugin = entry.plugin
                val switch = ui.switch(plugin.name, entry.enabled) { enabled ->
                    activity.launchSafely { withContext(Dispatchers.IO) { activity.plugins.setEnabled(plugin.id, enabled) } }
                }
                val buttons = ui.row(
                    ui.button("Export") { export(plugin) },
                    ui.button("Delete") { confirmDelete(plugin) },
                )
                importedBox.addView(switch, ui.params(top = NovelUi.GAP))
                importedBox.addView(ui.note("Sites: " + plugin.hosts.joinToString(", ")))
                importedBox.addView(buttons, ui.params(top = 4))
            }
        }
    }

    private fun importFiles() {
        activity.pickFiles { uris ->
            if (uris.isNotEmpty()) {
                activity.launchSafely {
                    val messages = withContext(Dispatchers.IO) { uris.map { importUri(it) } }
                    activity.message("Import", messages.joinToString("\n\n"))
                    showImported()
                }
            }
        }
    }

    private fun importUri(uri: Uri): String {
        val name = fileName(uri)
        val bytes = try {
            activity.contentResolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(BUFFER)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    if (output.size() > MAX_BYTES) return "$name: The file is too large for a plugin."
                }
                output.toByteArray()
            }
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        }
        if (bytes == null) return "$name: The file could not be read."
        return describe(name, activity.plugins.add(String(bytes, Charsets.UTF_8)))
    }

    private fun pasteFromClipboard() {
        val text = activity.getSystemService(ClipboardManager::class.java)
            ?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(activity)?.toString()
        if (text.isNullOrBlank()) {
            activity.toast("The clipboard has no text.")
            return
        }
        activity.launchSafely {
            val message = withContext(Dispatchers.IO) { describe("Clipboard", activity.plugins.add(text)) }
            activity.message("Import", message)
            showImported()
        }
    }

    private fun describe(label: String, result: NovelPluginStore.AddResult): String = when (result) {
        is NovelPluginStore.AddResult.Added -> "Imported: ${result.entry.plugin.name} (${result.entry.plugin.hosts.joinToString(", ")})"
        is NovelPluginStore.AddResult.Duplicate -> "Already imported: ${result.entry.plugin.name}"
        is NovelPluginStore.AddResult.Invalid -> "$label: ${result.message}"
    }

    private fun fileName(uri: Uri): String {
        val fromProvider = try {
            activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (e: SecurityException) {
            null
        }
        return fromProvider ?: uri.lastPathSegment ?: "plugin.json"
    }

    private fun export(plugin: SitePlugin) {
        activity.pickJson(plugin.id + ".json") { uri ->
            if (uri != null) {
                activity.launchSafely {
                    val saved = withContext(Dispatchers.IO) {
                        try {
                            activity.contentResolver.openOutputStream(uri, "wt")?.use { it.write(plugin.source.toByteArray(Charsets.UTF_8)) } != null
                        } catch (e: IOException) {
                            false
                        }
                    }
                    activity.toast(if (saved) "Plugin saved." else "The plugin could not be saved.")
                }
            }
        }
    }

    private fun confirmDelete(plugin: SitePlugin) {
        MaterialAlertDialogBuilder(activity)
            .setTitle("Delete this plugin?")
            .setMessage("${plugin.name} will be removed. For its sites the scraper goes back to the built-in plugin or to automatic detection.")
            .setPositiveButton("Delete") { _, _ ->
                activity.launchSafely {
                    withContext(Dispatchers.IO) { activity.plugins.delete(plugin.id) }
                    showImported()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // endregion

    // region Built-in

    private fun showBuiltIn() {
        activity.launchSafely {
            val all = withContext(Dispatchers.IO) { activity.plugins.builtIn() }
            val needle = search.trim().lowercase()
            val shown = if (needle.isEmpty()) all.take(MAX_SHOWN) else all.filter { plugin ->
                plugin.name.lowercase().contains(needle) || plugin.hosts.any { it.lowercase().contains(needle) }
            }.take(MAX_SHOWN)
            builtInBox.removeAllViews()
            builtInBox.addView(ui.note("${all.size} plugins come with the app" + if (all.size > shown.size) " (showing ${shown.size}; search to find others)" else ""))
            for (plugin in shown) {
                val switch = ui.switch(plugin.name, activity.plugins.isBuiltInEnabled(plugin.id)) { enabled ->
                    activity.plugins.setBuiltInEnabled(plugin.id, enabled)
                }
                builtInBox.addView(switch, ui.params(top = 8))
                builtInBox.addView(ui.note(plugin.hosts.joinToString(", ")))
            }
        }
    }

    // endregion

    private fun showGuide() {
        val guide = try {
            activity.assets.open("novel/plugin_guide.md").bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: IOException) {
            "The guide is not available."
        }
        val text = ui.note(guide)
        text.setTextIsSelectable(true)
        val scroll = ScrollView(activity).apply {
            setPadding(ui.dp(NovelUi.MARGIN), ui.dp(8), ui.dp(NovelUi.MARGIN), 0)
            addView(text)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle("Plugin format")
            .setView(scroll)
            .setPositiveButton("Copy") { _, _ ->
                activity.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Plugin format", guide))
                activity.toast("Copied.")
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private companion object {
        const val BUFFER = 8192
        const val MAX_BYTES = 2_000_000
        const val MAX_SHOWN = 40
    }
}
