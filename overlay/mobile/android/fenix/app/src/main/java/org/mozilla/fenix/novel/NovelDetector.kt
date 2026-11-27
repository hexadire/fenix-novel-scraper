/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.novel

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Automatic detection for sites that have no plugin (or whose plugin leaves something out): which links are the
 * chapters, where the text of a chapter is, and the details of the novel from the meta tags of the page.
 */
object NovelDetector {
    /** The chapters found on a table of contents page, and the container that holds them. */
    class Chapters(val links: List<ChapterRef>, val container: Element?, val selector: String?)

    fun chapters(document: Document, pageUrl: String): Chapters {
        val pageHost = NovelText.hostOf(pageUrl).removePrefix("www.")
        val anchors = ArrayList<Pair<Element, String>>()
        for (a in document.select("a[href]")) {
            val href = a.absUrl("href")
            if (!href.startsWith("http")) continue
            val text = a.text().trim()
            if (text.isEmpty() || text.length > MAX_TITLE) continue
            if (NovelText.hostOf(href).removePrefix("www.") != pageHost) continue
            anchors.add(a to href.substringBefore('#'))
        }
        val byShape = LinkedHashMap<String, MutableList<Pair<Element, String>>>()
        val byFolder = LinkedHashMap<String, MutableList<Pair<Element, String>>>()
        for (item in anchors) {
            byShape.getOrPut(shapeOf(item.second)) { ArrayList() }.add(item)
            byFolder.getOrPut(folderOf(item.second)) { ArrayList() }.add(item)
        }
        var best: List<Pair<Element, String>> = emptyList()
        for (group in byShape.values + byFolder.values) {
            if (group.size > best.size) best = group
        }
        if (best.size < MIN_CHAPTERS) return Chapters(emptyList(), null, null)

        val seen = HashSet<String>()
        val links = ArrayList<ChapterRef>()
        for ((element, href) in best) {
            if (seen.add(href)) links.add(ChapterRef(href, element.text().trim()))
        }
        val container = commonAncestor(best.map { it.first })
        return Chapters(links, container, container?.let { selectorFor(it) })
    }

    /** The address with numbers and long slugs replaced, so that the links of chapters have the same shape. */
    private fun shapeOf(url: String): String {
        val withoutQuery = url.substringBefore('?')
        val query = if (url.contains('?')) "?" + url.substringAfter('?').replace(Regex("=[^&]*"), "=*") else ""
        val host = NovelText.hostOf(url)
        val path = withoutQuery.substringAfter("://").substringAfter('/', "")
        val segments = path.split('/').filter { it.isNotEmpty() }.map { segment ->
            when {
                segment.any { it.isDigit() } -> segment.replace(Regex("\\d+"), "#")
                segment.length > LONG_SLUG || segment.count { it == '-' } >= SLUG_DASHES -> "*"
                else -> segment
            }
        }
        return host + "/" + segments.joinToString("/") + query
    }

    private fun folderOf(url: String): String {
        val path = url.substringBefore('?').substringAfter("://").substringAfter('/', "")
        val segments = path.split('/').filter { it.isNotEmpty() }
        return "folder:" + NovelText.hostOf(url) + "/" + segments.dropLast(1).joinToString("/")
    }

    private fun commonAncestor(nodes: List<Element>): Element? {
        var ancestor: Element? = nodes[0].parent()
        while (ancestor != null) {
            val candidate: Element = ancestor
            if (nodes.all { isInside(it, candidate) }) return candidate
            ancestor = candidate.parent()
        }
        return null
    }

    private fun isInside(node: Element, ancestor: Element): Boolean {
        var current: Element? = node
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent()
        }
        return false
    }

    /** A CSS selector for [node] that is short but good enough to find it again: tag, id or classes, up to four levels. */
    fun selectorFor(node: Element): String {
        val parts = ArrayList<String>()
        var current: Element? = node
        while (current != null && parts.size < MAX_SELECTOR_DEPTH) {
            var part = current.tagName()
            if (current.id().isNotEmpty() && current.id().matches(SAFE_NAME)) {
                parts.add(0, "$part#${current.id()}")
                break
            }
            val classes = current.classNames().filter { it.matches(SAFE_NAME) }.take(2)
            if (classes.isNotEmpty()) part += classes.joinToString("") { ".$it" }
            parts.add(0, part)
            if (part == "body" || part == "html") break
            current = current.parent()
        }
        return parts.joinToString(" > ")
    }

    private val SAFE_NAME = Regex("[A-Za-z_][A-Za-z0-9_-]*")

    /**
     * The element that holds most of the text of a chapter: every paragraph adds the length of its text to its parent
     * and half of it to its grandparent. Pages without paragraphs are judged by their line breaks.
     */
    fun content(document: Document): Element? {
        val body = document.body()
        val scores = LinkedHashMap<Element, Double>()
        fun add(element: Element?, amount: Double) {
            if (element != null && element !== body && element.tagName() != "html") {
                scores[element] = (scores[element] ?: 0.0) + amount
            }
        }
        for (p in body.select("p")) {
            val length = p.text().trim().length
            if (length > MIN_PARAGRAPH) {
                add(p.parent(), length.toDouble())
                add(p.parent()?.parent(), length / 2.0)
            }
        }
        if (scores.isEmpty()) {
            for (br in body.select("br")) add(br.parent(), BR_WEIGHT)
        }
        var best: Element? = null
        var bestScore = 0.0
        for ((element, score) in scores) {
            if (score > bestScore) {
                best = element
                bestScore = score
            }
        }
        return best
    }

    /** The title of a chapter page: the first heading inside the text or before it, else the title of the page. */
    fun chapterTitle(document: Document, content: Element?): String {
        val heading = content?.selectFirst("h1, h2, h3")?.text()?.trim().orEmpty()
        if (heading.isNotEmpty()) return heading
        val first = document.body().selectFirst("h1, h2")?.text()?.trim().orEmpty()
        if (first.isNotEmpty()) return first
        return document.title().trim()
    }

    /** The details of the novel from meta tags and headings of the table of contents page. */
    fun info(document: Document, pageUrl: String): NovelInfo {
        fun meta(vararg names: String): String {
            for (name in names) {
                val element = document.selectFirst("meta[property=\"$name\"], meta[name=\"$name\"]") ?: continue
                val content = element.attr("content").trim()
                if (content.isNotEmpty()) return content
            }
            return ""
        }
        val info = NovelInfo(sourceUrl = pageUrl)
        var title = meta("og:title", "twitter:title")
        if (title.isEmpty()) title = document.selectFirst("h1")?.text()?.trim().orEmpty()
        if (title.isEmpty()) title = document.title().trim()
        info.title = title
        info.author = meta("author", "article:author", "book:author", "og:novel:author")
        info.description = meta("og:description", "description", "twitter:description")
        val image = document.selectFirst("meta[property=\"og:image\"], meta[name=\"twitter:image\"]")?.absUrl("content").orEmpty()
        info.coverUrl = image
        val language = document.selectFirst("html")?.attr("lang").orEmpty().trim()
        info.language = language.ifEmpty { "en" }.substringBefore('-').lowercase()
        info.subjects = document.select("meta[property=\"article:tag\"], meta[property=\"book:tag\"]")
            .joinToString(", ") { it.attr("content") }
        return info
    }

    private const val MAX_TITLE = 200
    private const val MIN_CHAPTERS = 3
    private const val LONG_SLUG = 20
    private const val SLUG_DASHES = 3
    private const val MAX_SELECTOR_DEPTH = 4
    private const val MIN_PARAGRAPH = 20
    private const val BR_WEIGHT = 40.0
}
