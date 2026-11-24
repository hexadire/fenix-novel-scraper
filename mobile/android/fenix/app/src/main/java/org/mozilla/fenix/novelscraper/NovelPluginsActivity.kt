/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.novelscraper

import android.content.ClipboardManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.fenix.R

/**
 * The screen, opened from Settings, where the plugins of the novel scraper are imported (from a file or from the
 * clipboard), switched on and off, exported and deleted. The plugins are kept by [NovelPluginStore].
 *
 * The views are made in code, like in the backup screen, so that the screen does not depend on the layouts of the rest
 * of the app.
 */
class NovelPluginsActivity : AppCompatActivity() {
    private lateinit var store: NovelPluginStore
    private lateinit var heading: TextView
    private lateinit var list: LinearLayout

    private var pendingExport: NovelPluginStore.Plugin? = null

    private val chooseFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        onFilesChosen(uris)
    }

    private val chooseExportDestination = registerForActivityResult(
        ActivityResultContracts.CreateDocument(MIME_TYPE_EXPORT),
    ) { uri -> onExportDestinationChosen(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = NovelPluginStore(this)
        setContentView(buildScreen())
        refresh()
    }

    // region Screen

    private fun buildScreen(): android.view.View {
        val toolbar = MaterialToolbar(this).apply {
            setTitle(R.string.novel_plugins_title)
            setNavigationIcon(iconsR.drawable.mozac_ic_back_24)
            setNavigationContentDescription(R.string.novel_plugins_back)
            setNavigationOnClickListener { finish() }
        }

        heading = TextView(this).apply {
            textSize = HEADING_SIZE
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(SCREEN_MARGIN), dp(SCREEN_MARGIN), dp(SCREEN_MARGIN), dp(SCREEN_MARGIN))
            addView(paragraph(getString(R.string.novel_plugins_intro)))
            addView(note(getString(R.string.novel_plugins_how_to)), spaced(top = SMALL_GAP))
            addView(button(R.string.novel_plugins_import_file, outlined = false) { chooseFiles.launch(arrayOf(ANY_TYPE)) }, spaced(top = BIG_GAP))
            addView(button(R.string.novel_plugins_paste, outlined = true) { pasteFromClipboard() }, spaced(top = SMALL_GAP))
            addView(heading, spaced(top = BIG_GAP * 2))
            addView(list, spaced(top = SMALL_GAP))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(toolbar, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(ScrollView(this@NovelPluginsActivity).apply { addView(body) })
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        return root
    }

    /** Reads the plugins and shows them. */
    private fun refresh() {
        lifecycleScope.launch {
            val plugins = withContext(Dispatchers.IO) { store.list() }
            heading.text = getString(R.string.novel_plugins_list_title, plugins.size)
            list.removeAllViews()
            if (plugins.isEmpty()) {
                list.addView(note(getString(R.string.novel_plugins_empty)))
            }
            for (plugin in plugins) {
                list.addView(row(plugin), spaced(top = BIG_GAP))
            }
        }
    }

    private fun row(plugin: NovelPluginStore.Plugin): android.view.View {
        val title = TextView(this).apply {
            text = plugin.name
            textSize = BODY_SIZE
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val switch = SwitchCompat(this).apply {
            isChecked = plugin.enabled
            setOnCheckedChangeListener { _, checked ->
                lifecycleScope.launch {
                    val saved = withContext(Dispatchers.IO) { store.setEnabled(plugin.id, checked) }
                    if (!saved) {
                        toast(R.string.novel_plugins_not_saved)
                        refresh()
                    }
                }
            }
        }
        val titleLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(switch)
        }
        val sites = if (plugin.hosts.isEmpty()) {
            getString(R.string.novel_plugins_no_sites)
        } else {
            getString(R.string.novel_plugins_sites, plugin.hosts.joinToString(", "))
        }
        val size = getString(R.string.novel_plugins_size, plugin.source.length / BYTES_PER_KB + 1)
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button(R.string.novel_plugins_export, outlined = true) { startExport(plugin) })
            addView(button(R.string.novel_plugins_delete, outlined = true) { confirmDelete(plugin) })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(titleLine)
            addView(note(sites))
            addView(note(size))
            addView(buttons, spaced(top = SMALL_GAP))
        }
    }

    // endregion

    // region Import

    private fun onFilesChosen(uris: List<Uri>) {
        if (uris.isEmpty()) return
        lifecycleScope.launch {
            val messages = withContext(Dispatchers.IO) { uris.map { importUri(it) } }
            showSummary(messages)
            refresh()
        }
    }

    private fun importUri(uri: Uri): String {
        val name = fileName(uri)
        val bytes = try {
            contentResolver.openInputStream(uri)?.use { readLimited(it) }
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        }
        if (bytes == null) return name + ": " + getString(R.string.novel_plugins_unreadable)
        return describe(name, store.add(String(bytes, Charsets.UTF_8), name))
    }

    private fun pasteFromClipboard() {
        val text = getSystemService(ClipboardManager::class.java)
            ?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
        if (text.isNullOrBlank()) {
            toast(R.string.novel_plugins_clipboard_empty)
            return
        }
        lifecycleScope.launch {
            val message = withContext(Dispatchers.IO) { describe(getString(R.string.novel_plugins_paste), store.add(text, null)) }
            showSummary(listOf(message))
            refresh()
        }
    }

    private fun describe(label: String, result: NovelPluginStore.AddResult): String = when (result) {
        is NovelPluginStore.AddResult.Added -> getString(R.string.novel_plugins_added, result.plugin.name)
        is NovelPluginStore.AddResult.Duplicate -> getString(R.string.novel_plugins_duplicate, result.plugin.name)
        is NovelPluginStore.AddResult.Invalid -> label + ": " + getString(
            when (result.reason) {
                NovelPluginStore.Reason.EMPTY -> R.string.novel_plugins_empty_input
                NovelPluginStore.Reason.TOO_LARGE -> R.string.novel_plugins_too_large
                NovelPluginStore.Reason.NOT_A_PLUGIN -> R.string.novel_plugins_not_a_plugin
                NovelPluginStore.Reason.NOT_SAVED -> R.string.novel_plugins_not_saved
            },
        )
    }

    private fun showSummary(messages: List<String>) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.novel_plugins_import_result)
            .setMessage(messages.joinToString("\n\n"))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun fileName(uri: Uri): String {
        val fromProvider = try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (e: SecurityException) {
            null
        }
        return fromProvider ?: uri.lastPathSegment ?: "plugin.js"
    }

    /** The bytes of the stream, or null when it holds more than a plugin may be. */
    private fun readLimited(input: InputStream): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            if (output.size() > NovelPluginStore.MAX_SOURCE_CHARS * 4) return null
        }
        return output.toByteArray()
    }

    // endregion

    // region Export and delete

    private fun startExport(plugin: NovelPluginStore.Plugin) {
        pendingExport = plugin
        chooseExportDestination.launch(plugin.name + ".js")
    }

    private fun onExportDestinationChosen(uri: Uri?) {
        val plugin = pendingExport
        pendingExport = null
        if (uri == null || plugin == null) return
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openOutputStream(uri)?.use { it.write(plugin.source.toByteArray(Charsets.UTF_8)) } != null
                } catch (e: IOException) {
                    false
                }
            }
            toast(if (saved) R.string.novel_plugins_exported else R.string.novel_plugins_not_saved)
        }
    }

    private fun confirmDelete(plugin: NovelPluginStore.Plugin) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.novel_plugins_delete_title)
            .setMessage(getString(R.string.novel_plugins_delete_message, plugin.name))
            .setPositiveButton(R.string.novel_plugins_delete) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { store.delete(plugin.id) }
                    refresh()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // endregion

    // region Small helpers

    private fun toast(@StringRes text: Int) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun themeColor(attribute: Int): Int {
        val value = TypedValue()
        theme.resolveAttribute(attribute, value, true)
        return if (value.resourceId != 0) getColor(value.resourceId) else value.data
    }

    private fun spaced(top: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    private fun paragraph(text: String) = TextView(this).apply {
        this.text = text
        textSize = BODY_SIZE
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text
        textSize = NOTE_SIZE
        setTextColor(themeColor(android.R.attr.textColorSecondary))
    }

    private fun button(@StringRes label: Int, outlined: Boolean, onClick: () -> Unit): MaterialButton {
        val button = if (outlined) {
            MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        } else {
            MaterialButton(this)
        }
        return button.apply {
            setText(label)
            setOnClickListener { onClick() }
        }
    }

    // endregion

    private companion object {
        const val MIME_TYPE_EXPORT = "text/javascript"
        const val ANY_TYPE = "*/*"
        const val BYTES_PER_KB = 1024
        const val BUFFER_BYTES = 8192
        const val SCREEN_MARGIN = 16
        const val SMALL_GAP = 8
        const val BIG_GAP = 16
        const val BODY_SIZE = 16f
        const val NOTE_SIZE = 14f
        const val HEADING_SIZE = 18f
    }
}
