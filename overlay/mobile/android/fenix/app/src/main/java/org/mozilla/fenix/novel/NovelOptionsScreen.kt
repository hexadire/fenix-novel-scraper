/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.novel

import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner

/** The options of the scraper: images, the EPUB file, chapters and the library. */
class NovelOptionsScreen(activity: NovelScraperActivity) : NovelScreen(activity) {
    override val title: String = "Options"

    override fun build(): View {
        val prefs = activity.prefs
        val formats = listOf("jpg", "png", "webp")
        val formatSpinner = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, formats)
            setSelection(formats.indexOf(prefs.imageFormat).coerceAtLeast(0))
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    prefs.imageFormat = formats[position]
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }
        }
        val sizeField = ui.editText(prefs.maxImageSize.toString()) { prefs.maxImageSize = it.toIntOrNull() ?: NovelPrefs.DEFAULT_IMAGE_SIZE }
        sizeField.inputType = InputType.TYPE_CLASS_NUMBER
        val delayField = ui.editText(prefs.delayMs.toString()) { prefs.delayMs = it.toLongOrNull() ?: 0L }
        delayField.inputType = InputType.TYPE_CLASS_NUMBER
        val stylesheet: EditText = ui.editText(prefs.stylesheet, multiline = true) { prefs.stylesheet = it }
        stylesheet.minLines = 8

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(NovelUi.MARGIN), ui.dp(NovelUi.MARGIN), ui.dp(NovelUi.MARGIN), ui.dp(NovelUi.MARGIN))

            addView(ui.heading("Images"))
            addView(ui.switch("Skip images", prefs.skipImages) { prefs.skipImages = it }, ui.params(top = 8))
            addView(ui.switch("When skipping, keep a link to each image", prefs.includeImageUrl) { prefs.includeImageUrl = it }, ui.params(top = 4))
            addView(ui.switch("Compress images", prefs.compressImages) { prefs.compressImages = it }, ui.params(top = 4))
            addView(ui.note("Longest side of a compressed image, in pixels"), ui.params(top = 8))
            addView(sizeField)
            addView(ui.note("Format of compressed images"), ui.params(top = 8))
            addView(formatSpinner)

            addView(ui.heading("EPUB file"), ui.params(top = NovelUi.GAP * 2))
            addView(ui.switch("EPUB 3 (turn off for EPUB 2, for old readers)", prefs.epub3) { prefs.epub3 = it }, ui.params(top = 8))
            addView(ui.switch("Add an information page", prefs.infoPage) { prefs.infoPage = it }, ui.params(top = 4))
            addView(ui.note("File name: %Filename%, %Title%, %Author% and %Count% are replaced"), ui.params(top = 8))
            addView(ui.editText(prefs.filenameTemplate) { prefs.filenameTemplate = it })
            addView(ui.note("Style sheet"), ui.params(top = 8))
            addView(stylesheet)
            addView(
                ui.button("Reset the style sheet") {
                    prefs.resetStylesheet()
                    stylesheet.setText(prefs.stylesheet)
                },
                ui.params(top = 4),
            )

            addView(ui.heading("Chapters"), ui.params(top = NovelUi.GAP * 2))
            addView(ui.switch("Remove links to the next and previous chapter", prefs.removeNextPrevious) { prefs.removeNextPrevious = it }, ui.params(top = 8))
            addView(ui.switch("Skip chapters that cannot be read (otherwise the download stops there)", prefs.skipFailedChapters) { prefs.skipFailedChapters = it }, ui.params(top = 4))
            addView(ui.note("Pause between two chapters in milliseconds (0 = the plugin's value, else 400)"), ui.params(top = 8))
            addView(delayField)

            addView(ui.heading("Library"), ui.params(top = NovelUi.GAP * 2))
            addView(ui.switch("Keep downloaded chapters (lets a download resume and new chapters be added)", prefs.keepInLibrary) { prefs.keepInLibrary = it }, ui.params(top = 8))
        }
        return ScrollView(activity).apply { addView(content) }
    }
}
