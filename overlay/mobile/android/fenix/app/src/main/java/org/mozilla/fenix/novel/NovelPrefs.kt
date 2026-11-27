/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.novel

import android.content.Context
import android.content.SharedPreferences

/** The options of the novel scraper (the ones of the extension that matter on a phone), kept in the app's preferences. */
class NovelPrefs(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var skipImages: Boolean
        get() = prefs.getBoolean("skip_images", false)
        set(value) = prefs.edit().putBoolean("skip_images", value).apply()

    /** When images are skipped, leave a link to each image in the text. */
    var includeImageUrl: Boolean
        get() = prefs.getBoolean("include_image_url", true)
        set(value) = prefs.edit().putBoolean("include_image_url", value).apply()

    var compressImages: Boolean
        get() = prefs.getBoolean("compress_images", false)
        set(value) = prefs.edit().putBoolean("compress_images", value).apply()

    var maxImageSize: Int
        get() = prefs.getInt("max_image_size", DEFAULT_IMAGE_SIZE)
        set(value) = prefs.edit().putInt("max_image_size", value.coerceIn(100, 8000)).apply()

    /** "jpg", "png" or "webp". */
    var imageFormat: String
        get() = prefs.getString("image_format", "jpg") ?: "jpg"
        set(value) = prefs.edit().putString("image_format", value).apply()

    var epub3: Boolean
        get() = prefs.getBoolean("epub3", true)
        set(value) = prefs.edit().putBoolean("epub3", value).apply()

    var infoPage: Boolean
        get() = prefs.getBoolean("info_page", true)
        set(value) = prefs.edit().putBoolean("info_page", value).apply()

    var removeNextPrevious: Boolean
        get() = prefs.getBoolean("remove_next_previous", true)
        set(value) = prefs.edit().putBoolean("remove_next_previous", value).apply()

    var skipFailedChapters: Boolean
        get() = prefs.getBoolean("skip_failed", false)
        set(value) = prefs.edit().putBoolean("skip_failed", value).apply()

    /** Pause between two chapters, in milliseconds; 0 means the value of the plugin (or the default). */
    var delayMs: Long
        get() = prefs.getLong("delay_ms", 0L)
        set(value) = prefs.edit().putLong("delay_ms", value.coerceIn(0L, 60_000L)).apply()

    var keepInLibrary: Boolean
        get() = prefs.getBoolean("keep_library", true)
        set(value) = prefs.edit().putBoolean("keep_library", value).apply()

    var stylesheet: String
        get() = prefs.getString("stylesheet", null) ?: DEFAULT_STYLESHEET
        set(value) = prefs.edit().putString("stylesheet", value).apply()

    /** `%Filename%` is replaced by the file name of the novel; `%Title%`, `%Author%` and `%Count%` also work. */
    var filenameTemplate: String
        get() = prefs.getString("filename_template", null) ?: "%Filename%"
        set(value) = prefs.edit().putString("filename_template", value).apply()

    var lastUrl: String
        get() = prefs.getString("last_url", "") ?: ""
        set(value) = prefs.edit().putString("last_url", value).apply()

    /** Hosts for which the person said "do not ask again" about creating a plugin. */
    var noPromptHosts: Set<String>
        get() = prefs.getStringSet("no_prompt_hosts", emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet("no_prompt_hosts", HashSet(value)).apply()

    /** Ids of built-in plugins that the person switched off. */
    var disabledBuiltIns: Set<String>
        get() = prefs.getStringSet("disabled_builtin", emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet("disabled_builtin", HashSet(value)).apply()

    fun resetStylesheet() = prefs.edit().remove("stylesheet").apply()

    companion object {
        private const val FILE = "novel_scraper_prefs"
        const val DEFAULT_IMAGE_SIZE = 1080
        const val DEFAULT_STYLESHEET = "body {\n    margin: 0;\n    padding: 0 0.5em;\n    line-height: 1.5;\n    text-align: left;\n}\n" +
            "h1, h2, h3 {\n    text-align: center;\n    margin: 1em 0;\n}\n" +
            "p {\n    margin: 0 0 0.9em 0;\n}\n" +
            "img {\n    max-width: 100%;\n    height: auto;\n}\n" +
            "hr {\n    margin: 1.5em 0;\n}\n"
    }
}
