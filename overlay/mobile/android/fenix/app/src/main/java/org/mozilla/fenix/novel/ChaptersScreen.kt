/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.novel

import android.content.ClipData
import android.content.ClipboardManager
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * The list of chapters: choose which ones are downloaded, rename them, change the order, filter by text, select a range,
 * and edit or copy the addresses.
 */
class ChaptersScreen(activity: NovelScraperActivity, private val analysis: Analysis) : NovelScreen(activity) {
    override val title: String = "Chapters"

    private var filter = ""
    private var showUrls = false
    private var visible: List<Int> = emptyList()

    private lateinit var counter: TextView
    private lateinit var list: RecyclerView
    private val adapter = ChapterAdapter()

    override fun build(): View {
        counter = ui.text("", bold = true)
        val filterField = ui.editText("", hint = "Filter by title or address") { text ->
            filter = text
            refresh()
        }
        val fromField = ui.editText("", hint = "From #") { }
        val toField = ui.editText("", hint = "To #") { }
        fromField.inputType = InputType.TYPE_CLASS_NUMBER
        toField.inputType = InputType.TYPE_CLASS_NUMBER
        val rangeButton = ui.button("Select range") {
            val from = fromField.text.toString().toIntOrNull()
            val to = toField.text.toString().toIntOrNull()
            if (from == null || to == null || from < 1 || to < from) {
                activity.toast("Enter the first and the last number of the range (1 is the first chapter in the list).")
            } else {
                analysis.chapters.forEachIndexed { index, chapter -> chapter.selected = (index + 1) in from..to }
                refresh()
            }
        }
        list = RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity)
            adapter = this@ChaptersScreen.adapter
        }
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(NovelUi.MARGIN), ui.dp(8), ui.dp(NovelUi.MARGIN), ui.dp(8))
            addView(counter)
            addView(filterField, ui.params(top = 4))
            addView(ui.row(fromField, toField, rangeButton), ui.params(top = 4))
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(header, ui.params())
            addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    override fun buildMenu(menu: Menu) {
        menu.add("Select all (shown)").setOnMenuItemClickListener {
            setShown(true)
            true
        }
        menu.add("Unselect all (shown)").setOnMenuItemClickListener {
            setShown(false)
            true
        }
        menu.add("Reverse order").setOnMenuItemClickListener {
            analysis.chapters.reverse()
            refresh()
            true
        }
        menu.add("Edit addresses").setOnMenuItemClickListener {
            editUrls()
            true
        }
        menu.add("Copy addresses").setOnMenuItemClickListener {
            copyUrls()
            true
        }
        val show = menu.add("Show addresses")
        show.isCheckable = true
        show.isChecked = showUrls
        show.setOnMenuItemClickListener {
            showUrls = !showUrls
            show.isChecked = showUrls
            adapter.notifyDataSetChanged()
            true
        }
    }

    override fun onShown() = refresh()

    private fun refresh() {
        val needle = filter.trim().lowercase()
        visible = analysis.chapters.indices.filter {
            needle.isEmpty() ||
                analysis.chapters[it].title.lowercase().contains(needle) ||
                analysis.chapters[it].url.lowercase().contains(needle)
        }
        counter.text = "${analysis.chapters.count { it.selected }} of ${analysis.chapters.size} selected" +
            if (needle.isNotEmpty()) " (${visible.size} shown)" else ""
        adapter.notifyDataSetChanged()
    }

    private fun setShown(selected: Boolean) {
        for (index in visible) analysis.chapters[index].selected = selected
        refresh()
    }

    private fun editUrls() {
        val box = EditText(activity).apply {
            setText(analysis.chapters.joinToString("\n") { it.url })
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_VARIATION_URI
            minLines = 8
            textSize = NOTE_SIZE
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle("Chapter addresses")
            .setMessage("One address per line, in reading order. Remove lines, add lines, or change the order.")
            .setView(box)
            .setPositiveButton("Apply") { _, _ ->
                val known = analysis.chapters.associateBy { it.url }
                val lines = box.text.toString().lines().map { it.trim() }.filter { it.startsWith("http") }.distinct()
                analysis.chapters.clear()
                for (line in lines) analysis.chapters.add(known[line] ?: ChapterRef(line, line.substringAfterLast('/')))
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun copyUrls() {
        val chosen = analysis.chapters.filter { it.selected }
        val clipboard = activity.getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("Chapter addresses", chosen.joinToString("\n") { it.url }))
        activity.toast("${chosen.size} addresses copied.")
    }

    private inner class ChapterAdapter : RecyclerView.Adapter<ChapterAdapter.Holder>() {
        inner class Holder(val box: CheckBox, val titleField: EditText, val urlText: TextView, root: View) : RecyclerView.ViewHolder(root) {
            var binding = false
            var chapter: ChapterRef? = null
        }

        override fun getItemCount(): Int = visible.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val box = CheckBox(activity)
            val titleField = EditText(activity).apply {
                textSize = NOTE_SIZE + 1
                inputType = InputType.TYPE_CLASS_TEXT
                setSingleLine(true)
            }
            val urlText = ui.note("")
            val column = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(titleField)
                addView(urlText)
            }
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(ui.dp(8), ui.dp(2), ui.dp(8), ui.dp(2))
                addView(box)
                addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val holder = Holder(box, titleField, urlText, row)
            box.setOnCheckedChangeListener { _, checked ->
                if (!holder.binding) {
                    holder.chapter?.selected = checked
                    counter.text = "${analysis.chapters.count { it.selected }} of ${analysis.chapters.size} selected"
                }
            }
            titleField.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (!holder.binding) holder.chapter?.title = s?.toString().orEmpty()
                }
            })
            return holder
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val chapter = analysis.chapters[visible[position]]
            holder.binding = true
            holder.chapter = chapter
            holder.box.isChecked = chapter.selected
            holder.titleField.setText(chapter.title)
            holder.urlText.text = chapter.url
            holder.urlText.visibility = if (showUrls) View.VISIBLE else View.GONE
            holder.binding = false
        }
    }

    private companion object {
        const val NOTE_SIZE = 14f
    }
}
