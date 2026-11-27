/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.novel

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.jsoup.nodes.Element
import org.jsoup.select.Selector

/**
 * Reads one value (or several) from an HTML element, the way a plugin describes it.
 *
 * In JSON an extractor is a CSS selector string, a list of selectors (the first one that matches is used), or an object:
 * `{ "css": "div.cover img", "attr": "src", "regex": "(\\d+)", "all": false, "html": false, "value": "fixed" }`.
 */
class Extractor(
    val css: List<String>,
    val attr: String?,
    val regex: String?,
    val all: Boolean,
    val html: Boolean,
    val value: String?,
) {
    private val compiled: Regex? = regex?.let { Regex(it) }

    /** The nodes the selectors find below [root]: the result of the first selector that finds something. */
    fun nodes(root: Element): List<Element> {
        for (selector in css) {
            val found = try {
                root.select(selector).toList()
            } catch (e: RuntimeException) {
                emptyList()
            }
            if (found.isNotEmpty()) return if (all) found else listOf(found[0])
        }
        return emptyList()
    }

    /** The values read below [root]: nothing when the selectors find nothing. */
    fun values(root: Element): List<String> {
        if (value != null) return listOf(value)
        val result = ArrayList<String>()
        for (node in nodes(root)) {
            val raw = when {
                attr != null -> if (attr in URL_ATTRIBUTES) node.absUrl(attr).ifEmpty { node.attr(attr) } else node.attr(attr)
                html -> node.html()
                else -> node.text()
            }
            val text = apply(raw.trim()) ?: continue
            if (text.isNotEmpty()) result.add(text)
        }
        return result
    }

    fun first(root: Element): String? = values(root).firstOrNull()

    private fun apply(text: String): String? {
        val pattern = compiled ?: return text
        val match = pattern.find(text) ?: return null
        return (if (match.groupValues.size > 1) match.groupValues[1] else match.value).trim()
    }

    companion object {
        val URL_ATTRIBUTES = setOf("href", "src", "data-src", "data-lazy-src", "data-original", "content")

        /** Reads an extractor from JSON. Returns null when [raw] is null; throws [NovelException] when it is not valid. */
        fun parse(raw: Any?, where: String): Extractor? {
            when (raw) {
                null, JSONObject.NULL -> return null
                is String -> return Extractor(listOf(checked(raw, where)), null, null, false, false, null)
                is JSONArray -> {
                    val list = (0 until raw.length()).map { checked(raw.getString(it), where) }
                    return Extractor(list, null, null, false, false, null)
                }
                is JSONObject -> {
                    val css = when (val c = raw.opt("css")) {
                        null, JSONObject.NULL -> emptyList()
                        is String -> listOf(checked(c, where))
                        is JSONArray -> (0 until c.length()).map { checked(c.getString(it), where) }
                        else -> throw NovelException("$where: \"css\" must be a text or a list of texts.")
                    }
                    val value = if (raw.has("value")) raw.optString("value") else null
                    if (css.isEmpty() && value == null) {
                        throw NovelException("$where: needs \"css\" (or \"value\").")
                    }
                    val regex = raw.optString("regex", "").ifEmpty { null }
                    if (regex != null) {
                        try {
                            Regex(regex)
                        } catch (e: RuntimeException) {
                            throw NovelException("$where: the regex is not valid (${e.message}).")
                        }
                    }
                    val attr = raw.optString("attr", "").ifEmpty { null }
                    return Extractor(css, attr, regex, raw.optBoolean("all", false), raw.optBoolean("html", false), value)
                }
                else -> throw NovelException("$where: must be a CSS selector, a list of them, or an object.")
            }
        }

        /** The same extractor, reading [attribute] when it does not say what to read (links and images). */
        fun withDefaultAttr(extractor: Extractor?, attribute: String): Extractor? {
            if (extractor == null || extractor.attr != null || extractor.value != null || extractor.html) return extractor
            return Extractor(extractor.css, attribute, extractor.regex, extractor.all, extractor.html, null)
        }

        private fun checked(selector: String, where: String): String {
            try {
                Selector.select(selector, Element("div"))
            } catch (e: RuntimeException) {
                throw NovelException("$where: the CSS selector \"$selector\" is not valid.")
            }
            return selector
        }
    }
}

/** Where a variable of an API address comes from. */
class VarSpec(val from: String, val regex: Regex, val group: Int)

/** A JSON API (for the list of chapters, or for the text of one chapter). */
class ApiSpec(
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val vars: Map<String, VarSpec>,
    /** Path to the list of chapters in the answer (tables of contents) or to the text (chapters). */
    val path: String,
    val titlePath: String?,
    /** Address of a chapter, with `{item.id}` style placeholders (tables of contents only). */
    val chapterUrl: String?,
    /** "html" or "text" (chapters only). */
    val format: String,
    /** Page number placeholder: when the address has `{n}` the pages are read until one is empty (tables of contents). */
    val maxPages: Int,
)

enum class ChapterOrder { OLDEST_FIRST, NEWEST_FIRST, AUTO }

class ChapterListSpec(val css: List<String>, val title: Extractor?, val urlAttr: String)

class TocSpec(
    val chapters: ChapterListSpec?,
    val order: ChapterOrder,
    /** The link to the next page of the table of contents, when it has several pages. */
    val next: Extractor?,
    /** `{base}?page={n}` style address of the pages of the table of contents (the first page is the one that was opened). */
    val pageTemplate: String?,
    val pageFrom: Int,
    /** The number of pages, read from the first page. */
    val pageCount: Extractor?,
    val maxPages: Int,
    val api: ApiSpec?,
)

class ChapterSpec(
    val title: Extractor?,
    val content: Extractor?,
    val remove: List<String>,
    /** Paragraphs that contain text matching one of these regular expressions are removed (advertisements, watermarks). */
    val removeText: List<Regex>,
    /** Replacements applied to the text of the chapter: pairs of regex and replacement. */
    val replace: List<Pair<Regex, String>>,
    /** The link to the next part when a chapter is split over several pages. */
    val next: Extractor?,
    val api: ApiSpec?,
)

/**
 * What the scraper knows about one website. A plugin is a JSON file (see `assets/novel/plugin_guide.md`); every part
 * of it is optional except the name and the hosts, and what a plugin leaves out is found by automatic detection.
 */
class SitePlugin(
    val id: String,
    val name: String,
    val hosts: List<String>,
    val language: String?,
    val delayMs: Long?,
    val headers: Map<String, String>,
    val info: Map<String, Extractor>,
    val toc: TocSpec,
    val chapter: ChapterSpec,
    val builtIn: Boolean,
    /** The JSON the plugin was made from. */
    val source: String,
) {
    fun handles(host: String): Boolean {
        val wanted = normalizeHost(host)
        return hosts.any { normalizeHost(it) == wanted }
    }

    companion object {
        val INFO_FIELDS = listOf("title", "author", "cover", "description", "subjects", "language", "translator", "publisher")

        fun normalizeHost(host: String): String = host.trim().lowercase().removePrefix("www.")

        /** Reads and checks a plugin. Throws [NovelException] with a message that says what is wrong and where. */
        fun parse(text: String, builtIn: Boolean, idHint: String? = null): SitePlugin {
            val json = try {
                JSONObject(text.trim())
            } catch (e: JSONException) {
                throw NovelException("This is not valid JSON: ${e.message}")
            }
            val name = json.optString("name", "").trim()
            if (name.isEmpty()) throw NovelException("The plugin needs a \"name\".")
            val hostsJson = json.optJSONArray("hosts") ?: throw NovelException("The plugin needs \"hosts\": a list of host names.")
            val hosts = (0 until hostsJson.length()).map { hostsJson.optString(it, "").trim() }.filter { it.isNotEmpty() }
            if (hosts.isEmpty()) throw NovelException("The plugin needs at least one host name in \"hosts\".")
            if (hosts.any { it.contains("/") || it.contains(" ") }) {
                throw NovelException("\"hosts\" holds host names such as \"example.com\", not addresses.")
            }

            val info = LinkedHashMap<String, Extractor>()
            val infoJson = json.optJSONObject("info")
            if (infoJson != null) {
                for (field in INFO_FIELDS) {
                    var extractor = Extractor.parse(infoJson.opt(field), "info.$field")
                    if (field == "cover") extractor = Extractor.withDefaultAttr(extractor, "src")
                    if (extractor != null) info[field] = extractor
                }
            }

            val toc = parseToc(json.optJSONObject("toc"))
            val chapter = parseChapter(json.optJSONObject("chapter"))
            if (toc.chapters == null && toc.api == null && chapter.content == null && chapter.api == null && info.isEmpty()) {
                throw NovelException("The plugin does not describe anything: add \"toc\", \"chapter\" or \"info\".")
            }
            val headers = parseStringMap(json.optJSONObject("headers"))
            val id = idHint ?: name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "plugin" }.take(MAX_ID)
            return SitePlugin(
                id = id,
                name = name,
                hosts = hosts,
                language = json.optString("language", "").ifEmpty { null },
                delayMs = if (json.has("delayMs")) json.optLong("delayMs") else null,
                headers = headers,
                info = info,
                toc = toc,
                chapter = chapter,
                builtIn = builtIn,
                source = text.trim(),
            )
        }

        private fun parseStringMap(json: JSONObject?): Map<String, String> {
            if (json == null) return emptyMap()
            val result = LinkedHashMap<String, String>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                result[key] = json.optString(key, "")
            }
            return result
        }

        private fun parseToc(json: JSONObject?): TocSpec {
            if (json == null) return TocSpec(null, ChapterOrder.AUTO, null, null, 1, null, DEFAULT_MAX_PAGES, null)
            var list: ChapterListSpec? = null
            val chapters = json.opt("chapters")
            when (chapters) {
                null, JSONObject.NULL -> Unit
                is String, is JSONArray -> {
                    val extractor = Extractor.parse(chapters, "toc.chapters")
                    list = ChapterListSpec(extractor?.css.orEmpty(), null, "href")
                }
                is JSONObject -> {
                    val extractor = Extractor.parse(chapters.opt("css"), "toc.chapters.css")
                        ?: throw NovelException("toc.chapters: needs \"css\".")
                    list = ChapterListSpec(
                        css = extractor.css,
                        title = Extractor.parse(chapters.opt("title"), "toc.chapters.title"),
                        urlAttr = chapters.optString("urlAttr", "href"),
                    )
                }
                else -> throw NovelException("toc.chapters: must be a CSS selector or an object.")
            }
            val order = when (json.optString("order", "auto").lowercase()) {
                "oldest-first", "oldest_first", "ascending" -> ChapterOrder.OLDEST_FIRST
                "newest-first", "newest_first", "descending" -> ChapterOrder.NEWEST_FIRST
                "auto" -> ChapterOrder.AUTO
                else -> throw NovelException("toc.order: use \"oldest-first\", \"newest-first\" or \"auto\".")
            }
            val pages = json.optJSONObject("pages")
            return TocSpec(
                chapters = list,
                order = order,
                next = Extractor.withDefaultAttr(Extractor.parse(pages?.opt("next"), "toc.pages.next"), "href"),
                pageTemplate = pages?.optString("template", "")?.ifEmpty { null },
                pageFrom = pages?.optInt("from", 1) ?: 1,
                pageCount = Extractor.parse(pages?.opt("count"), "toc.pages.count"),
                maxPages = (pages?.optInt("max", DEFAULT_MAX_PAGES) ?: DEFAULT_MAX_PAGES).coerceIn(1, 2000),
                api = parseApi(json.optJSONObject("api"), "toc.api", isToc = true),
            )
        }

        private fun parseChapter(json: JSONObject?): ChapterSpec {
            if (json == null) return ChapterSpec(null, null, emptyList(), emptyList(), emptyList(), null, null)
            val remove = ArrayList<String>()
            val removeJson = json.optJSONArray("remove")
            if (removeJson != null) {
                for (i in 0 until removeJson.length()) {
                    val selector = removeJson.optString(i, "")
                    Extractor.parse(selector, "chapter.remove")
                    remove.add(selector)
                }
            }
            val removeText = ArrayList<Regex>()
            val textJson = json.optJSONArray("removeText")
            if (textJson != null) {
                for (i in 0 until textJson.length()) removeText.add(regexOf(textJson.optString(i, ""), "chapter.removeText"))
            }
            val replace = ArrayList<Pair<Regex, String>>()
            val replaceJson = json.optJSONArray("replace")
            if (replaceJson != null) {
                for (i in 0 until replaceJson.length()) {
                    val pair = replaceJson.optJSONObject(i) ?: throw NovelException("chapter.replace: entries are objects with \"regex\" and \"with\".")
                    replace.add(regexOf(pair.optString("regex", ""), "chapter.replace") to pair.optString("with", ""))
                }
            }
            return ChapterSpec(
                title = Extractor.parse(json.opt("title"), "chapter.title"),
                content = Extractor.parse(json.opt("content"), "chapter.content"),
                remove = remove,
                removeText = removeText,
                replace = replace,
                next = Extractor.withDefaultAttr(Extractor.parse(json.opt("next"), "chapter.next"), "href"),
                api = parseApi(json.optJSONObject("api"), "chapter.api", isToc = false),
            )
        }

        private fun regexOf(pattern: String, where: String): Regex {
            if (pattern.isEmpty()) throw NovelException("$where: a regex is empty.")
            return try {
                Regex(pattern, setOf(RegexOption.IGNORE_CASE))
            } catch (e: RuntimeException) {
                throw NovelException("$where: the regex \"$pattern\" is not valid.")
            }
        }

        private fun parseApi(json: JSONObject?, where: String, isToc: Boolean): ApiSpec? {
            if (json == null) return null
            val url = json.optString("url", "")
            if (url.isEmpty()) throw NovelException("$where: needs \"url\".")
            val path = json.optString(if (isToc) "list" else "content", "")
            if (path.isEmpty()) throw NovelException("$where: needs \"${if (isToc) "list" else "content"}\" (the path in the answer).")
            if (isToc && json.optString("chapterUrl", "").isEmpty()) throw NovelException("$where: needs \"chapterUrl\".")
            val vars = LinkedHashMap<String, VarSpec>()
            val varsJson = json.optJSONObject("vars")
            if (varsJson != null) {
                val names = varsJson.keys()
                while (names.hasNext()) {
                    val name = names.next()
                    val spec = varsJson.optJSONObject(name) ?: throw NovelException("$where.vars.$name: must be an object.")
                    vars[name] = VarSpec(
                        from = spec.optString("from", if (isToc) "url" else "chapter-url"),
                        regex = regexOf(spec.optString("regex", ""), "$where.vars.$name"),
                        group = spec.optInt("group", 1),
                    )
                }
            }
            return ApiSpec(
                url = url,
                method = json.optString("method", "GET").uppercase(),
                headers = parseStringMap(json.optJSONObject("headers")),
                vars = vars,
                path = path,
                titlePath = json.optString("title", "").ifEmpty { null },
                chapterUrl = json.optString("chapterUrl", "").ifEmpty { null },
                format = json.optString("format", "html").lowercase(),
                maxPages = json.optInt("maxPages", DEFAULT_MAX_PAGES).coerceIn(1, 2000),
            )
        }

        const val DEFAULT_MAX_PAGES = 200
        private const val MAX_ID = 60
    }
}
