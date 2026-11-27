/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.novel

import android.content.Context
import org.jsoup.Jsoup
import org.jsoup.nodes.Comment
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.io.IOException

/**
 * When a site has no plugin, the person can ask for one: this builds the file to give to Claude. It holds the task, the
 * rules and the format of a plugin, and what the app read from the site: the table of contents page and the first
 * chapter (shortened so that they fit), with hints about pagination, scripts and protection.
 */
object NovelClaudeRequest {
    private const val MAX_TOC_CHARS = 70_000
    private const val MAX_CHAPTER_CHARS = 60_000

    /** What was read from the first chapter. */
    class ChapterFacts(
        val url: String,
        val title: String,
        val contentSelector: String?,
        val paragraphs: Int,
        val textLength: Int,
        val challenge: Boolean,
        val navigation: List<String>,
        val scriptHints: List<String>,
        val contentSample: String?,
        val html: String,
    )

    suspend fun readChapter(fetcher: NovelFetcher, url: String): ChapterFacts {
        val document = fetcher.page(url)
        val content = NovelDetector.content(document)
        return ChapterFacts(
            url = url,
            title = document.title(),
            contentSelector = content?.let { NovelDetector.selectorFor(it) },
            paragraphs = content?.select("p")?.size ?: 0,
            textLength = content?.text()?.length ?: 0,
            challenge = Regex("just a moment|attention required|checking your browser|verify you are human|captcha", RegexOption.IGNORE_CASE)
                .containsMatchIn(document.title()),
            navigation = navigationLinks(document),
            scriptHints = scriptHints(document),
            contentSample = content?.let { slim(it, 6000) },
            html = slim(document.body(), MAX_CHAPTER_CHARS),
        )
    }

    fun build(context: Context, analysis: Analysis, chapter: ChapterFacts?): String {
        val url = analysis.tocDocumentUrl
        val host = NovelText.hostOf(url).removePrefix("www.")
        val document = analysis.tocDocument
        val guide = try {
            context.assets.open("novel/plugin_guide.md").bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: IOException) {
            "(the guide is not available)"
        }
        val found = NovelDetector.chapters(document, url)
        val out = ArrayList<String>()
        out.add("# Task: write a Novel Scraper plugin for $host")
        out.add("")
        out.add(
            "I use a browser app that downloads web novels as EPUB files. A \"plugin\" is one small JSON file that tells the " +
                "app how to read one website. The site below has no plugin yet. Write it. Everything you need is in this " +
                "file: facts about the site, the HTML of its table of contents page and of its first chapter, and the " +
                "complete description of the plugin format.",
        )
        out.add("")
        out.add("## What to deliver")
        out.add("")
        out.add("1. Exactly one JSON file in a single ```json code block. It must be complete and valid JSON (no comments, no trailing commas).")
        out.add("2. After the code block, 3 to 6 short lines: what you assumed, and what could break (Cloudflare, text loaded by scripts, pagination, locked chapters).")
        out.add("")
        out.add("## Rules")
        out.add("")
        out.add("- Follow the format below exactly. Unknown fields are ignored; wrong ones make the import fail.")
        out.add("- `hosts` must contain `$host` (add other host names only when the data shows them, such as mirrors).")
        out.add("- CSS selectors are evaluated by Jsoup (CSS3 plus `:has()`, `:contains()`, `:matches()`, `:nth-child()`). No JavaScript runs: only the HTML the server sends can be used.")
        out.add("- Base every selector on the HTML below. If something cannot be known from the data (for example an API address), choose the most robust option and say so in your notes.")
        out.add("- If the chapter text is not in the chapter HTML (it is loaded by scripts), find the API in the data and use `chapter.api` / `toc.api`.")
        out.add("- Keep it small: leave out everything the app can detect on its own.")
        out.add("")
        out.add("## Facts captured by the app")
        out.add("")
        out.add("- Site: `$host`")
        out.add("- Table of contents page: $url")
        out.add("- Page title: ${document.title()}")
        out.add("- Chapter links recognised on the page: ${found.links.size}" + (found.selector?.let { " inside `$it`" } ?: ""))
        if (found.links.size >= 2) {
            out.add("- The list starts with \"${found.links.first().title}\" and ends with \"${found.links.last().title}\".")
        }
        out.add("- The app listed ${analysis.chapters.size} chapters (oldest first after ordering).")
        if (chapter != null) {
            out.add("- Chapter 1 page: ${chapter.url}")
            if (chapter.challenge) out.add("- Protection: the page looks like a challenge page (\"${chapter.title}\"). The site may need to be opened in the browser first.")
            chapter.contentSelector?.let {
                out.add("- Content container guessed from the density of paragraphs: `$it` (${chapter.paragraphs} paragraphs, ${chapter.textLength} characters)")
            }
            if (chapter.paragraphs < 3 && chapter.textLength < 500) {
                out.add("- WARNING: very little text was found in the chapter HTML. The text is probably loaded by scripts or an API. Look at the script hints.")
            }
        } else {
            out.add("- Chapter 1 page: not available (it could not be read).")
        }
        out.add("")
        out.add("## Table of contents page")
        out.add("")
        val metas = metaTags(document)
        if (metas.isNotEmpty()) {
            out.add("### Meta tags")
            out.add(fence(metas.joinToString("\n"), "text"))
        }
        if (found.links.isNotEmpty()) {
            out.add("### Chapter links (first five and last two)")
            out.add((found.links.take(5) + found.links.takeLast(2)).joinToString("\n") { "- ${it.title} -> ${it.url}" })
        }
        val pagination = paginationHints(document)
        if (pagination.isNotEmpty()) {
            out.add("### Pagination / load more elements")
            pagination.forEach { out.add(fence(it, "html")) }
        }
        val hints = scriptHints(document)
        if (hints.isNotEmpty()) {
            out.add("### Script hints")
            hints.forEach { out.add(fence(it, "text")) }
        }
        out.add("### HTML of the page (shortened: scripts and styles removed, long runs of similar elements cut)")
        out.add(fence(slim(document.body(), MAX_TOC_CHARS), "html"))
        out.add("")
        out.add("## Chapter 1 page")
        out.add("")
        if (chapter != null) {
            if (chapter.navigation.isNotEmpty()) {
                out.add("### Previous / next links")
                chapter.navigation.forEach { out.add(fence(it, "html")) }
            }
            if (chapter.scriptHints.isNotEmpty()) {
                out.add("### Script hints")
                chapter.scriptHints.forEach { out.add(fence(it, "text")) }
            }
            chapter.contentSample?.let {
                out.add("### Content container as guessed (shortened)")
                out.add(fence(it, "html"))
            }
            out.add("### HTML of the page (shortened)")
            out.add(fence(chapter.html, "html"))
        } else {
            out.add("(not available)")
        }
        out.add("")
        out.add("## The plugin format")
        out.add("")
        out.add(guide)
        out.add("")
        return out.joinToString("\n")
    }

    fun fileName(url: String): String = "claude-plugin-request-" + NovelText.hostOf(url).removePrefix("www.").replace(Regex("[^A-Za-z0-9.-]"), "_") + ".md"

    // region What is read from pages

    private fun fence(text: String, language: String): String {
        var longest = 2
        for (run in Regex("`+").findAll(text)) longest = maxOf(longest, run.value.length)
        val marks = "`".repeat(longest + 1)
        return "$marks$language\n$text\n$marks"
    }

    private fun metaTags(document: Document): List<String> {
        val result = ArrayList<String>()
        for (meta in document.select("meta[property], meta[name]")) {
            val key = meta.attr("property").ifEmpty { meta.attr("name") }
            val value = meta.attr("content").replace(Regex("\\s+"), " ").take(300)
            if (key.isNotEmpty() && value.isNotEmpty() && result.size < 40) result.add("$key = $value")
        }
        return result
    }

    private fun scriptHints(document: Document): List<String> {
        val hints = ArrayList<String>()
        val next = document.selectFirst("script#__NEXT_DATA__")
        if (next != null) hints.add("Next.js page data (script#__NEXT_DATA__), first 4000 characters:\n" + next.data().trim().take(4000))
        val urls = LinkedHashSet<String>()
        var nuxt = false
        for (script in document.select("script:not([src])")) {
            val text = script.data()
            if (text.contains("__NUXT__")) nuxt = true
            for (match in Regex("[\"'`]((?:https?:)?//?[^\"'`\\s]*(?:api|ajax|chapter|json|graphql)[^\"'`\\s]*)[\"'`]", RegexOption.IGNORE_CASE).findAll(text)) {
                if (urls.size < 12) urls.add(match.groupValues[1].take(200))
            }
        }
        if (nuxt) hints.add("Nuxt page data (window.__NUXT__) is present.")
        if (document.selectFirst("#__next, [data-reactroot]") != null) hints.add("The page looks like a single page application (React root element).")
        if (urls.isNotEmpty()) hints.add("URLs that look like APIs in inline scripts:\n" + urls.joinToString("\n") { "- $it" })
        if (document.outerHtml().contains("admin-ajax.php")) hints.add("The page mentions admin-ajax.php (WordPress / Madara style AJAX).")
        return hints
    }

    private fun paginationHints(document: Document): List<String> {
        val found = ArrayList<String>()
        val selectors = listOf(
            "link[rel=next]", "a[rel=next]", "[class*=pagination]", "[class*=pager]",
            "button[class*=more]", "a[class*=more]", "[class*=load-more]", "[class*=loadmore]",
        )
        for (selector in selectors) {
            for (element in document.select(selector)) {
                val html = slim(element, 600)
                if (found.size < 8 && html !in found) found.add(html)
            }
        }
        return found
    }

    private fun navigationLinks(document: Document): List<String> {
        val found = ArrayList<String>()
        for (link in document.select("a[href]")) {
            val text = link.text().replace(Regex("\\s+"), " ").trim()
            val marker = link.attr("rel") + " " + link.id() + " " + link.className()
            if (text.length < 60 && Regex("(next|prev|previous|chapter)", RegexOption.IGNORE_CASE).containsMatchIn("$marker $text")) {
                val html = slim(link, 400)
                if (found.size < 6 && html !in found) found.add(html)
            }
        }
        return found
    }

    // endregion

    // region Shrinking HTML

    /**
     * A copy of [root] as HTML, small enough to give to Claude: no scripts, styles or image data, short attribute values and
     * texts, and long runs of similar siblings (chapter list items, paragraphs) cut down to the first and last few, with a
     * comment saying how many were left out.
     */
    fun slim(root: Element, maxChars: Int): String {
        val attempts = listOf(Triple(5, 2, 160), Triple(3, 1, 100), Triple(2, 1, 60))
        var html = ""
        for ((keepFirst, keepLast, textLimit) in attempts) {
            val document = Jsoup.parseBodyFragment(root.outerHtml(), root.baseUri())
            document.outputSettings().prettyPrint(false)
            val body = document.body()
            body.select("script, style, noscript, svg, iframe, link, template, canvas, video, audio, source, object, embed").remove()
            removeComments(body)
            trimAttributes(body)
            trimTexts(body, textLimit)
            collapseRuns(body, keepFirst, keepLast)
            html = body.html().replace(Regex("[ \\t]*\\n[ \\t\\n]*"), "\n").replace(Regex("[ \\t]{2,}"), " ").trim()
            if (html.length <= maxChars) return html
        }
        return html.take(maxChars) + "\n<!-- cut: the page is longer than this -->"
    }

    private fun removeComments(node: Node) {
        for (child in node.childNodes().toList()) {
            if (child is Comment) child.remove() else removeComments(child)
        }
    }

    private val KEEP_ATTRIBUTES = setOf(
        "class", "id", "href", "src", "rel", "itemprop", "itemtype", "itemscope", "property", "name", "content",
        "type", "value", "lang", "datetime", "role", "aria-label", "title", "alt", "data-src",
    )

    private fun trimAttributes(root: Element) {
        for (element in root.allElements.toList()) {
            for (attribute in element.attributes().asList().toList()) {
                val name = attribute.key
                if (name !in KEEP_ATTRIBUTES && !name.startsWith("data-")) {
                    element.removeAttr(name)
                    continue
                }
                var value = attribute.value
                if (value.startsWith("data:")) value = "data:..."
                val limit = if (name == "class") 120 else if (name.startsWith("data-")) 80 else 160
                if (value.length > limit) value = value.take(limit) + "..."
                if (value != attribute.value) element.attr(name, value)
            }
        }
    }

    private fun trimTexts(node: Node, limit: Int) {
        for (child in node.childNodes().toList()) {
            if (child is TextNode) {
                val text = child.wholeText
                if (text.length > limit) child.text(text.take(limit) + "...[+" + (text.length - limit) + " characters]")
            } else {
                trimTexts(child, limit)
            }
        }
    }

    private fun signature(element: Element): String {
        val first = element.classNames().firstOrNull {
            !Regex("^(odd|even|first|last|active|current|selected)$", RegexOption.IGNORE_CASE).matches(it)
        }
        return element.tagName() + "." + (first ?: "")
    }

    private fun collapseRuns(root: Element, keepFirst: Int, keepLast: Int) {
        for (parent in root.allElements.toList()) {
            val children = parent.children().toList()
            var index = 0
            while (index < children.size) {
                var end = index
                val kind = signature(children[index])
                while (end + 1 < children.size && signature(children[end + 1]) == kind) end++
                val run = end - index + 1
                if (run > keepFirst + keepLast + 1) {
                    val omitted = children.subList(index + keepFirst, end - keepLast + 1)
                    omitted[0].before(Comment(" ${omitted.size} similar <${children[index].tagName()}> elements left out "))
                    omitted.forEach { it.remove() }
                }
                index = end + 1
            }
        }
    }

    // endregion
}
