/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.novel

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** A chapter that was read and cleaned: its title and its text (see [Prepared]). */
class ChapterResult(val title: String, val prepared: Prepared)

/**
 * Reads a novel page and its chapters: with the plugin of the site when there is one, and by automatic detection for
 * everything the plugin does not say.
 */
class NovelEngine(
    private val fetcher: NovelFetcher,
    private val plugins: NovelPluginStore,
    private val prefs: NovelPrefs,
) {
    fun pluginFor(url: String): SitePlugin? = plugins.find(NovelText.hostOf(normalizeUrl(url)))

    /** Pause between two chapters, in milliseconds. */
    fun delayFor(plugin: SitePlugin?): Long {
        val chosen = prefs.delayMs
        return if (chosen > 0L) chosen else (plugin?.delayMs ?: DEFAULT_DELAY_MS)
    }

    fun headersFor(plugin: SitePlugin?): Map<String, String> = plugin?.headers.orEmpty()

    // region The novel page

    /** Reads the page at [rawUrl]: details of the novel and the list of its chapters. */
    suspend fun analyze(rawUrl: String, plugin: SitePlugin?, progress: (String) -> Unit = {}): Analysis {
        val url = normalizeUrl(rawUrl)
        if (NovelText.hostOf(url).isEmpty()) throw NovelException("This is not a web address: $rawUrl")
        val headers = headersFor(plugin)
        progress("Reading the page...")
        val document = fetcher.page(url, headers)
        val warnings = ArrayList<String>()
        val info = readInfo(document, url, plugin)
        var chapters = readChapters(document, url, plugin, headers, warnings, progress)
        chapters = order(chapters, plugin?.toc?.order ?: ChapterOrder.AUTO)
        if (plugin != null && plugin.toc.chapters == null && plugin.toc.api == null) {
            warnings.add("This plugin describes the text of the chapters only; the list of chapters was found by automatic detection.")
        }
        if (chapters.isEmpty()) {
            warnings.add("No chapters were found on this page. Open the page that lists the chapters of the novel.")
        }
        return Analysis(info, chapters.toMutableList(), plugin, warnings, url, document)
    }

    fun normalizeUrl(raw: String): String {
        val trimmed = raw.trim()
        return if (trimmed.contains("://")) trimmed else "https://$trimmed"
    }

    private fun readInfo(document: Document, url: String, plugin: SitePlugin?): NovelInfo {
        val info = NovelDetector.info(document, url)
        if (plugin != null) {
            fun field(name: String): String? {
                val extractor = plugin.info[name] ?: return null
                return if (name == "subjects") {
                    extractor.values(document).joinToString(", ").ifEmpty { null }
                } else {
                    extractor.first(document)
                }
            }
            field("title")?.let { info.title = it }
            field("author")?.let { info.author = it }
            field("cover")?.let { info.coverUrl = it }
            field("description")?.let { info.description = it }
            field("subjects")?.let { info.subjects = it }
            field("translator")?.let { info.translator = it }
            field("publisher")?.let { info.publisher = it }
            val language = field("language") ?: plugin.language
            if (language != null) info.language = language
        }
        info.title = info.title.replace(Regex("\\s+"), " ").trim()
        info.fileName = NovelText.safeFileName(info.title)
        return info
    }

    private suspend fun readChapters(
        document: Document,
        url: String,
        plugin: SitePlugin?,
        headers: Map<String, String>,
        warnings: MutableList<String>,
        progress: (String) -> Unit,
    ): List<ChapterRef> {
        val toc = plugin?.toc
        val tocApi = toc?.api
        if (tocApi != null) return readApiChapters(document, url, tocApi, headers, progress)

        val spec = toc?.chapters
        var usedDetection = spec == null || spec.css.isEmpty()
        val result = LinkedHashMap<String, ChapterRef>()

        fun linksOf(page: Document, pageUrl: String): List<ChapterRef> {
            if (spec != null && spec.css.isNotEmpty()) {
                val found = linksFromSpec(page, spec)
                if (found.isNotEmpty()) return found
                usedDetection = true
            }
            return NovelDetector.chapters(page, pageUrl).links
        }

        fun add(page: Document, pageUrl: String): Int {
            var added = 0
            for (chapter in linksOf(page, pageUrl)) {
                if (result.putIfAbsent(chapter.url, chapter) == null) added++
            }
            return added
        }

        add(document, url)
        if (toc != null) {
            val limit = toc.maxPages
            if (toc.next != null) {
                var current = document
                val seen = HashSet<String>()
                seen.add(url)
                for (index in 1 until limit) {
                    val next = toc.next.first(current)
                    if (next.isNullOrBlank() || !seen.add(next)) break
                    progress("Reading table of contents page ${index + 1}...")
                    current = fetcher.page(next, headers)
                    if (add(current, next) == 0) break
                }
            } else if (toc.pageTemplate != null) {
                val variables = mapOf("url" to url, "origin" to NovelText.origin(url), "base" to NovelText.base(url))
                val count = toc.pageCount?.first(document)?.let { NovelText.numberIn(it)?.toInt() }
                val last = minOf(count ?: (toc.pageFrom + limit), toc.pageFrom + limit - 1)
                for (n in (toc.pageFrom + 1)..last) {
                    val address = NovelText.fill(toc.pageTemplate, variables + ("n" to n.toString()))
                    progress("Reading table of contents page $n...")
                    val page = try {
                        fetcher.page(address, headers)
                    } catch (e: NovelException) {
                        if (count == null) break else throw e
                    }
                    if (add(page, address) == 0 && count == null) break
                }
            }
        }
        if (spec != null && spec.css.isNotEmpty() && usedDetection) {
            warnings.add("The chapter selector of the plugin found nothing here; automatic detection was used.")
        }
        return result.values.toList()
    }

    private fun linksFromSpec(page: Document, spec: ChapterListSpec): List<ChapterRef> {
        val extractor = Extractor(spec.css, null, null, true, false, null)
        val result = ArrayList<ChapterRef>()
        for (element in extractor.nodes(page)) {
            val link: Element? = if (element.tagName() == "a") element else element.selectFirst("a[href]")
            val source = if (spec.urlAttr != "href" && element.hasAttr(spec.urlAttr)) element else link
            if (source == null) continue
            val address = source.absUrl(spec.urlAttr).substringBefore('#')
            if (!address.startsWith("http")) continue
            val title = spec.title?.first(element) ?: element.text().trim()
            result.add(ChapterRef(address, title))
        }
        return result
    }

    private suspend fun readApiChapters(
        document: Document,
        url: String,
        api: ApiSpec,
        headers: Map<String, String>,
        progress: (String) -> Unit,
    ): List<ChapterRef> {
        val variables = variablesFor(api, url, document.outerHtml())
        val result = LinkedHashMap<String, ChapterRef>()
        var page = 1
        while (page <= api.maxPages) {
            val address = NovelText.fill(api.url, variables + ("n" to page.toString()))
            progress("Reading the list of chapters (page $page)...")
            val answer = fetcher.raw(address, headers + api.headers, api.method)
            val root = parseJson(answer.bytes, address)
            val list = (if (api.path.isEmpty()) root else NovelText.path(root, api.path)) as? JSONArray
                ?: throw NovelException("The answer from $address has no list at \"${api.path}\".")
            if (list.length() == 0) break
            for (i in 0 until list.length()) {
                val item = list.opt(i) ?: continue
                val chapterUrl = NovelText.fill(api.chapterUrl.orEmpty(), variables, item)
                if (chapterUrl.isEmpty()) continue
                val title = NovelText.stringify(NovelText.path(item, api.titlePath ?: "title"))
                    .ifEmpty { NovelText.stringify(NovelText.path(item, "name")) }
                result.putIfAbsent(chapterUrl, ChapterRef(chapterUrl, title.ifEmpty { chapterUrl.substringAfterLast('/') }))
            }
            if (!api.url.contains("{n}")) break
            page++
        }
        return result.values.toList()
    }

    private fun variablesFor(api: ApiSpec, url: String, html: String): Map<String, String> {
        val variables = LinkedHashMap<String, String>()
        variables["url"] = url
        variables["origin"] = NovelText.origin(url)
        variables["base"] = NovelText.base(url)
        for ((name, spec) in api.vars) {
            val source = if (spec.from == "html") html else url
            val match = spec.regex.find(source)
            variables[name] = match?.groupValues?.getOrNull(spec.group).orEmpty()
        }
        return variables
    }

    private fun parseJson(bytes: ByteArray, address: String): Any {
        val text = String(bytes, Charsets.UTF_8).trim()
        return try {
            if (text.startsWith("[")) JSONArray(text) else JSONObject(text)
        } catch (e: JSONException) {
            throw NovelException("The answer from $address is not JSON.", e)
        }
    }

    private fun order(chapters: List<ChapterRef>, order: ChapterOrder): List<ChapterRef> {
        if (chapters.size < 2) return chapters
        return when (order) {
            ChapterOrder.OLDEST_FIRST -> chapters
            ChapterOrder.NEWEST_FIRST -> chapters.reversed()
            ChapterOrder.AUTO -> {
                val first = NovelText.numberIn(chapters.first().title)
                val last = NovelText.numberIn(chapters.last().title)
                if (first != null && last != null && first > last) chapters.reversed() else chapters
            }
        }
    }

    // endregion

    // region One chapter

    /** Reads one chapter. [adjacent] are the addresses of the chapters before and after it (for removing their links). */
    suspend fun readChapter(plugin: SitePlugin?, chapter: ChapterRef, adjacent: Set<String>): ChapterResult {
        val spec = plugin?.chapter
        val headers = headersFor(plugin)
        val chapterApi = spec?.api
        if (chapterApi != null) return readApiChapter(plugin, chapterApi, chapter, adjacent)

        val first = fetcher.page(chapter.url, headers)
        val nodes = ArrayList<Element>()
        var page = first
        var pageUrl = chapter.url
        val seen = HashSet<String>()
        seen.add(pageUrl)
        var pluginTitle: String? = null
        for (part in 0 until MAX_PARTS) {
            val content = contentNodes(page, spec)
                ?: throw NovelException("No text was found on $pageUrl. The site may need a login, or the text is loaded by scripts.")
            nodes.addAll(content)
            if (pluginTitle == null) pluginTitle = spec?.title?.first(page)
            val next = spec?.next?.first(page)
            if (next.isNullOrBlank() || !seen.add(next)) break
            pageUrl = next
            page = fetcher.page(next, headers)
        }
        val title = pluginTitle ?: chapter.title.ifBlank { NovelDetector.chapterTitle(first, nodes.firstOrNull()) }
        val prepared = NovelCleaner.prepare(nodes, chapter.url, spec, prefs, adjacent)
        if (!prepared.hasText()) {
            throw NovelException("The text of ${chapter.url} is empty. The site may need a login, or the text is loaded by scripts.")
        }
        return ChapterResult(title.trim(), prepared)
    }

    private fun contentNodes(page: Document, spec: ChapterSpec?): List<Element>? {
        val extractor = spec?.content
        if (extractor != null) {
            val found = extractor.nodes(page)
            if (found.isNotEmpty()) return found
        }
        return NovelDetector.content(page)?.let { listOf(it) }
    }

    private suspend fun readApiChapter(plugin: SitePlugin?, api: ApiSpec, chapter: ChapterRef, adjacent: Set<String>): ChapterResult {
        val variables = LinkedHashMap<String, String>()
        variables["url"] = chapter.url
        variables["origin"] = NovelText.origin(chapter.url)
        variables["base"] = NovelText.base(chapter.url)
        for ((name, spec) in api.vars) {
            variables[name] = spec.regex.find(chapter.url)?.groupValues?.getOrNull(spec.group).orEmpty()
        }
        val address = NovelText.fill(api.url, variables)
        val answer = fetcher.raw(address, headersFor(plugin) + api.headers, api.method)
        val root = parseJson(answer.bytes, address)
        val content = NovelText.stringify(NovelText.path(root, api.path))
        if (content.isBlank()) throw NovelException("The answer from $address has no text at \"${api.path}\".")
        val html = if (api.format == "text") {
            content.split(Regex("\\r?\\n+")).filter { it.isNotBlank() }
                .joinToString("") { "<p>" + NovelText.escapeXml(it.trim()) + "</p>" }
        } else {
            content
        }
        val document = Jsoup.parseBodyFragment(html, chapter.url)
        val prepared = NovelCleaner.prepare(listOf(document.body()), chapter.url, plugin?.chapter, prefs, adjacent)
        val title = api.titlePath?.let { NovelText.stringify(NovelText.path(root, it)) }.orEmpty()
            .ifBlank { chapter.title }
        return ChapterResult(title.trim(), prepared)
    }

    // endregion

    private companion object {
        const val DEFAULT_DELAY_MS = 400L
        const val MAX_PARTS = 20
    }
}
