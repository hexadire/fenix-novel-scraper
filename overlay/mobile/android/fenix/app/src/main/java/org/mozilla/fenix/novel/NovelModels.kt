/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.novel

import org.jsoup.nodes.Document

/** One chapter of the table of contents. [title] can be edited by the person, [selected] says whether it is downloaded. */
data class ChapterRef(
    val url: String,
    var title: String,
    var selected: Boolean = true,
)

/** What the person sees and can edit about a novel; it ends up in the metadata of the EPUB. */
data class NovelInfo(
    var sourceUrl: String = "",
    var title: String = "",
    var author: String = "",
    var language: String = "en",
    var fileName: String = "",
    var coverUrl: String = "",
    var description: String = "",
    var subjects: String = "",
    var translator: String = "",
    var publisher: String = "",
    var seriesName: String = "",
    var seriesIndex: String = "",
)

/** The result of reading a novel page: its details, its chapters and how they were found. */
class Analysis(
    val info: NovelInfo,
    val chapters: MutableList<ChapterRef>,
    /** The plugin that was used, or null when the page was read by automatic detection. */
    val plugin: SitePlugin?,
    val warnings: List<String>,
    /** The page the chapters were read from and its address; kept so that it can go into a plugin request. */
    val tocDocumentUrl: String,
    val tocDocument: Document,
)

/** Thrown for problems that the person can understand and act on; [message] is shown as it is. */
class NovelException(message: String, cause: Throwable? = null) : Exception(message, cause)
