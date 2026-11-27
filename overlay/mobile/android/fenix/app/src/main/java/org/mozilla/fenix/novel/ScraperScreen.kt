/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.novel

import android.content.ClipboardManager
import android.text.InputType
import android.view.Menu
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The main screen: the address of a novel, its details, its chapters, and the download. */
class ScraperScreen(activity: NovelScraperActivity) : NovelScreen(activity) {
    override val title: String = "Novel scraper"

    private var analysis: Analysis? = null
    private var loading = false
    private var lastRequest: DownloadRequest? = null
    private var showMore = false

    private lateinit var urlField: EditText
    private lateinit var status: TextView
    private lateinit var details: LinearLayout
    private lateinit var progressBox: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var logText: TextView
    private lateinit var stopButton: MaterialButton
    private lateinit var resumeButton: MaterialButton
    private var chapterSummary: TextView? = null

    override fun build(): View {
        urlField = ui.editText(activity.prefs.lastUrl, hint = "Address of the page that lists the chapters") { }
        urlField.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        status = ui.note("")
        details = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = false }
        progressText = ui.text("")
        logText = ui.note("")
        stopButton = ui.button("Stop") { NovelJob.cancel() }
        resumeButton = ui.button("Resume", primary = true) {
            lastRequest?.let { NovelJob.start(activity, it) }
        }
        progressBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(ui.heading("Download"))
            addView(progressBar, ui.params(top = 8))
            addView(progressText, ui.params(top = 4))
            addView(ui.row(stopButton, resumeButton), ui.params(top = 8))
            addView(logText, ui.params(top = 8))
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(NovelUi.MARGIN), ui.dp(NovelUi.MARGIN), ui.dp(NovelUi.MARGIN), ui.dp(NovelUi.MARGIN))
            addView(ui.note("Novel page"))
            addView(urlField)
            addView(
                ui.row(
                    ui.button("Load", primary = true) { analyse() },
                    ui.button("Paste") { pasteAddress() },
                    ui.button("Clear") { clear() },
                ),
                ui.params(top = 8),
            )
            addView(status, ui.params(top = 8))
            addView(details, ui.params(top = NovelUi.GAP))
            addView(progressBox, ui.params(top = NovelUi.GAP * 2))
        }
        showProgress()
        return ScrollView(activity).apply { addView(content) }
    }

    override fun buildMenu(menu: Menu) {
        menu.add("Library").setOnMenuItemClickListener {
            activity.show(NovelLibraryScreen(activity))
            true
        }
        menu.add("Plugins").setOnMenuItemClickListener {
            activity.show(NovelPluginsScreen(activity))
            true
        }
        menu.add("Options").setOnMenuItemClickListener {
            activity.show(NovelOptionsScreen(activity))
            true
        }
        menu.add("Help").setOnMenuItemClickListener {
            activity.message("How it works", HELP)
            true
        }
    }

    override fun onShown() {
        chapterSummary?.text = selectionText()
        showProgress()
    }

    // region Loading a page

    /** Reads the novel page at [address] (called with the page that was open in the browser). */
    fun load(address: String) {
        urlField.setText(address)
        analyse()
    }

    private fun pasteAddress() {
        val clip = activity.getSystemService(ClipboardManager::class.java)?.primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(activity)?.toString()?.trim()
        if (text.isNullOrEmpty()) activity.toast("The clipboard has no text.") else urlField.setText(text)
    }

    private fun clear() {
        urlField.setText("")
        analysis = null
        details.removeAllViews()
        status.text = ""
    }

    private fun analyse() {
        val address = urlField.text.toString().trim()
        if (address.isEmpty()) {
            status.text = "Type or paste the address of a novel page."
            return
        }
        if (loading) return
        loading = true
        status.text = "Reading the page..."
        activity.prefs.lastUrl = address
        activity.lifecycleScope.launch {
            try {
                val plugin = activity.engine.pluginFor(address)
                val result = withContext(Dispatchers.Default) {
                    activity.engine.analyze(address, plugin) { message -> activity.runOnUiThread { status.text = message } }
                }
                analysis = result
                showAnalysis(result)
                if (plugin == null) offerPlugin(result)
            } catch (e: NovelException) {
                status.text = "Could not read the page: ${e.message}"
            } finally {
                loading = false
            }
        }
    }

    private fun pluginLabel(result: Analysis): String {
        val plugin = result.plugin ?: return "Automatic detection (no plugin for this site)"
        return (if (plugin.builtIn) "Built-in plugin: " else "Plugin you imported: ") + plugin.name
    }

    private fun selectionText(): String {
        val chapters = analysis?.chapters ?: return ""
        return "${chapters.count { it.selected }} of ${chapters.size} chapters selected"
    }

    private fun showAnalysis(result: Analysis) {
        status.text = ""
        details.removeAllViews()
        val info = result.info
        details.addView(ui.text(pluginLabel(result), bold = true))
        details.addView(ui.note("${result.chapters.size} chapters found"))
        for (warning in result.warnings) details.addView(ui.note(warning), ui.params(top = 4))
        val stored = activity.library.load(activity.library.idFor(info.sourceUrl))
        if (stored != null && stored.downloadedCount() > 0) {
            details.addView(
                ui.note("In the library: ${stored.downloadedCount()} chapters are downloaded already; only the others are read again."),
                ui.params(top = 4),
            )
        }
        details.addView(ui.field("Title", info.title) { info.title = it }, ui.params(top = NovelUi.GAP))
        details.addView(ui.field("Author", info.author) { info.author = it }, ui.params(top = 8))
        details.addView(ui.field("Language", info.language) { info.language = it }, ui.params(top = 8))
        details.addView(ui.field("File name", info.fileName) { info.fileName = it }, ui.params(top = 8))
        details.addView(ui.field("Cover image address", info.coverUrl) { info.coverUrl = it }, ui.params(top = 8))
        val more = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (showMore) View.VISIBLE else View.GONE
            addView(ui.field("Description", info.description, multiline = true) { info.description = it }, ui.params(top = 8))
            addView(ui.field("Subjects (separated by commas)", info.subjects) { info.subjects = it }, ui.params(top = 8))
            addView(ui.field("Translator", info.translator) { info.translator = it }, ui.params(top = 8))
            addView(ui.field("Publisher", info.publisher) { info.publisher = it }, ui.params(top = 8))
            addView(ui.field("Series name", info.seriesName) { info.seriesName = it }, ui.params(top = 8))
            addView(ui.field("Series number", info.seriesIndex) { info.seriesIndex = it }, ui.params(top = 8))
        }
        val toggle = ui.button(if (showMore) "Fewer details" else "More details") { }
        toggle.setOnClickListener {
            showMore = !showMore
            more.visibility = if (showMore) View.VISIBLE else View.GONE
            toggle.text = if (showMore) "Fewer details" else "More details"
        }
        details.addView(toggle, ui.params(top = 8))
        details.addView(more)

        val summary = ui.text(selectionText(), bold = true)
        chapterSummary = summary
        details.addView(summary, ui.params(top = NovelUi.GAP * 2))
        details.addView(ui.button("Choose chapters") { activity.show(ChaptersScreen(activity, result)) }, ui.params(top = 4))
        details.addView(
            ui.row(
                ui.button("Site settings") { SiteSettingsDialog(activity, result).show() },
                ui.button("Plugin request") { PluginRequestFlow(activity, result).start() },
            ),
            ui.params(top = 8),
        )
        details.addView(ui.button("Download EPUB", primary = true) { download(result) }, ui.params(top = NovelUi.GAP))
    }

    private fun offerPlugin(result: Analysis) {
        val host = NovelText.hostOf(result.tocDocumentUrl).removePrefix("www.")
        if (host in activity.prefs.noPromptHosts) return
        MaterialAlertDialogBuilder(activity)
            .setTitle("No plugin for $host")
            .setMessage(
                "This site has no plugin yet, so the chapters were found by automatic detection, which does not always work.\n\n" +
                    "Create a plugin? The app reads the table of contents and the first chapter and prepares a request file " +
                    "for Claude. Claude writes the plugin and you import it under Plugins.",
            )
            .setPositiveButton("Yes, create a plugin") { _, _ -> PluginRequestFlow(activity, result).start() }
            .setNegativeButton("No, use detection", null)
            .setNeutralButton("Don't ask again for this site") { _, _ ->
                activity.prefs.noPromptHosts = activity.prefs.noPromptHosts + host
            }
            .show()
    }

    // endregion

    // region Download

    private fun fileNameFor(info: NovelInfo, count: Int): String {
        val template = activity.prefs.filenameTemplate.ifBlank { "%Filename%" }
        val name = template
            .replace("%Filename%", info.fileName.ifBlank { info.title })
            .replace("%Title%", info.title)
            .replace("%Author%", info.author)
            .replace("%Count%", count.toString())
        return NovelText.safeFileName(name)
    }

    private fun download(result: Analysis) {
        if (NovelJob.isRunning()) {
            activity.toast("A download is running already.")
            return
        }
        val chosen = result.chapters.filter { it.selected }
        if (chosen.isEmpty()) {
            activity.message("Nothing to download", "Choose at least one chapter.")
            return
        }
        if (result.info.title.isBlank()) {
            activity.message("No title", "Give the novel a title.")
            return
        }
        activity.pickEpub(fileNameFor(result.info, chosen.size) + ".epub") { uri ->
            if (uri != null) {
                val request = DownloadRequest(
                    result.info.copy(),
                    chosen.map { ChapterRef(it.url, it.title) },
                    result.plugin,
                    uri,
                )
                lastRequest = request
                NovelJob.start(activity, request)
                showProgress()
            }
        }
    }

    /** Shows the state of the download (called when it changes). */
    fun showProgress() {
        if (!::progressBox.isInitialized) return
        val state = NovelJob.status
        val lines = NovelJob.log()
        val hasHistory = state.running || state.finished || lines.isNotEmpty()
        progressBox.visibility = if (hasHistory) View.VISIBLE else View.GONE
        progressBar.max = maxOf(1, state.total)
        progressBar.progress = state.done
        progressText.text = state.error?.let { "Problem: $it" } ?: if (state.total > 0) "${state.done} / ${state.total}  ${state.message}" else state.message
        logText.text = lines.takeLast(MAX_LOG_SHOWN).joinToString("\n")
        stopButton.visibility = if (state.running) View.VISIBLE else View.GONE
        resumeButton.visibility = if (!state.running && state.error != null && lastRequest != null) View.VISIBLE else View.GONE
    }

    // endregion

    private companion object {
        const val MAX_LOG_SHOWN = 8
        const val HELP = "1. Open a novel's page in the browser (the page that lists its chapters) and choose Download novel in " +
            "the menu, or paste its address here and tap Load.\n\n" +
            "2. Check the details, tap Choose chapters if you want only some, then Download EPUB and pick where to save it.\n\n" +
            "3. Sites with a plugin are read exactly as the plugin says; other sites are read by automatic detection, and you can " +
            "ask for a plugin (Plugin request) or set the selectors yourself (Site settings).\n\n" +
            "Downloaded chapters are kept in the library, so a stopped download continues where it stopped and new chapters can " +
            "be added later."
    }
}
