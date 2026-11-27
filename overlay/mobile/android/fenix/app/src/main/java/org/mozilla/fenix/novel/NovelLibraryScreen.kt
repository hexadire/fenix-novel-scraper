/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.novel

import android.net.Uri
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** The novels that were downloaded: save the EPUB again, look for new chapters, or delete them. */
class NovelLibraryScreen(activity: NovelScraperActivity) : NovelScreen(activity) {
    override val title: String = "Library"

    private lateinit var list: LinearLayout

    override fun build(): View {
        list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(NovelUi.MARGIN), ui.dp(NovelUi.MARGIN), ui.dp(NovelUi.MARGIN), ui.dp(NovelUi.MARGIN))
        }
        return ScrollView(activity).apply { addView(list) }
    }

    override fun onShown() {
        activity.launchSafely {
            val entries = withContext(Dispatchers.IO) { activity.library.list().map { it to activity.library.sizeOf(it.id) } }
            list.removeAllViews()
            if (entries.isEmpty()) {
                list.addView(ui.note("Nothing here yet. Novels you download are kept here, so you can continue a download, add new chapters, and save the EPUB again."))
            }
            for ((entry, size) in entries) {
                val info = entry.info
                val line = listOf(
                    info.author.ifBlank { null },
                    "${entry.downloadedCount()} of ${entry.chapters.size} chapters",
                    "%.1f MB".format(size / BYTES_PER_MB),
                ).filterNotNull().joinToString("  ·  ")
                val row = ui.column(ui.text(info.title.ifBlank { entry.id }, bold = true), ui.note(line))
                row.setPadding(0, ui.dp(10), 0, ui.dp(10))
                row.setOnClickListener { actions(entry) }
                list.addView(row, ui.params())
            }
        }
    }

    private fun actions(entry: LibraryEntry) {
        val items = arrayOf("Look for new chapters", "Save the EPUB again", "Delete")
        MaterialAlertDialogBuilder(activity)
            .setTitle(entry.info.title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> activity.loadInScraper(entry.info.sourceUrl)
                    1 -> saveAgain(entry)
                    else -> confirmDelete(entry)
                }
            }
            .show()
    }

    private fun saveAgain(entry: LibraryEntry) {
        if (entry.downloadedCount() == 0) {
            activity.message("Nothing to save", "No chapter of this novel is downloaded.")
            return
        }
        activity.pickEpub(NovelText.safeFileName(entry.info.fileName.ifBlank { entry.info.title }) + ".epub") { uri ->
            if (uri != null) write(entry, uri)
        }
    }

    private fun write(entry: LibraryEntry, uri: Uri) {
        activity.toast("Writing the EPUB...")
        activity.launchSafely {
            val problem = withContext(Dispatchers.IO) {
                val temporary = File(activity.cacheDir, "novel-${entry.id}.epub")
                try {
                    FileOutputStream(temporary).use { EpubWriter(activity.library, activity.prefs).write(entry, it) }
                    val output = activity.contentResolver.openOutputStream(uri, "wt")
                    if (output == null) {
                        "The file could not be opened for writing."
                    } else {
                        output.use { stream -> temporary.inputStream().use { it.copyTo(stream) } }
                        null
                    }
                } catch (e: NovelException) {
                    e.message
                } catch (e: IOException) {
                    e.message ?: "Writing the file failed."
                } finally {
                    temporary.delete()
                }
            }
            if (problem == null) activity.toast("The EPUB was saved.") else activity.message("Could not save", problem)
        }
    }

    private fun confirmDelete(entry: LibraryEntry) {
        MaterialAlertDialogBuilder(activity)
            .setTitle("Delete from the library?")
            .setMessage("The downloaded chapters of \"${entry.info.title}\" are removed from the app. EPUB files you saved are not touched.")
            .setPositiveButton("Delete") { _, _ ->
                activity.launchSafely {
                    withContext(Dispatchers.IO) { activity.library.delete(entry.id) }
                    onShown()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private companion object {
        const val BYTES_PER_MB = 1024.0 * 1024.0
    }
}
