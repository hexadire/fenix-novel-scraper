/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.novel

import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.button.MaterialButton

/**
 * Small builders for the screens of the novel scraper. The screens are made in code (like the backup screen), so they do
 * not depend on the layouts of the rest of the app.
 */
class NovelUi(private val context: Context) {
    fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private fun themeColor(attribute: Int): Int {
        val value = TypedValue()
        context.theme.resolveAttribute(attribute, value, true)
        return if (value.resourceId != 0) context.getColor(value.resourceId) else value.data
    }

    /** Layout parameters that fill the width, with [top] dp of space above. */
    fun params(top: Int = 0): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    fun text(text: String, size: Float = BODY, bold: Boolean = false): TextView = TextView(context).apply {
        this.text = text
        textSize = size
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    fun note(message: String): TextView = text(message, NOTE).apply {
        setTextColor(themeColor(android.R.attr.textColorSecondary))
    }

    fun heading(message: String): TextView = text(message, HEADING, bold = true)

    fun button(label: String, primary: Boolean = false, onClick: () -> Unit): MaterialButton {
        val button = if (primary) {
            MaterialButton(context)
        } else {
            MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        }
        return button.apply {
            text = label
            setAllCaps(false)
            setOnClickListener { onClick() }
        }
    }

    fun column(vararg children: View): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        for (child in children) addView(child)
    }

    /** Buttons side by side, sharing the width. */
    fun row(vararg children: View): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        for (child in children) {
            addView(child, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    fun editText(value: String, hint: String = "", multiline: Boolean = false, onChange: (String) -> Unit): EditText =
        EditText(context).apply {
            setText(value)
            this.hint = hint
            textSize = BODY
            inputType = if (multiline) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            } else {
                InputType.TYPE_CLASS_TEXT
            }
            if (multiline) {
                minLines = 3
                gravity = Gravity.TOP
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) = onChange(s?.toString().orEmpty())
            })
        }

    /** A small label above an edit field. */
    fun field(label: String, value: String, multiline: Boolean = false, onChange: (String) -> Unit): LinearLayout =
        column(note(label), editText(value, multiline = multiline, onChange = onChange))

    fun switch(label: String, checked: Boolean, onChange: (Boolean) -> Unit): SwitchCompat = SwitchCompat(context).apply {
        text = label
        textSize = BODY
        isChecked = checked
        setOnCheckedChangeListener { _, value -> onChange(value) }
    }

    companion object {
        const val BODY = 16f
        const val NOTE = 13f
        const val HEADING = 18f
        const val MARGIN = 16
        const val GAP = 12
    }
}
