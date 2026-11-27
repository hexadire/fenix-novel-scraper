/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.novel

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities

/** The text of a chapter after cleaning: ready to be written, once the images have been dealt with. */
class Prepared(
    private val body: Element,
    private val replacements: List<Pair<Regex, String>>,
    /** The image elements that stay in the text, with their absolute addresses. */
    val images: List<Pair<Element, String>>,
) {
    /** The text as XHTML (a fragment, to be put into the body of a page). */
    fun xhtml(): String {
        val document: Document? = body.ownerDocument()
        document?.outputSettings()
            ?.syntax(Document.OutputSettings.Syntax.xml)
            ?.escapeMode(Entities.EscapeMode.xhtml)
            ?.charset(Charsets.UTF_8)
            ?.prettyPrint(false)
        var html = body.html()
        for ((pattern, replacement) in replacements) html = pattern.replace(html, replacement)
        return NovelText.xmlSafe(html).trim()
    }

    fun hasText(): Boolean = body.text().isNotBlank() || body.select("img").isNotEmpty()
}

/** Turns the element that holds a chapter into clean text: no scripts, advertisements or navigation, simple markup. */
object NovelCleaner {
    private const val JUNK = "script, style, noscript, iframe, form, input, button, select, textarea, object, embed, " +
        "link, meta, template, canvas, audio, video, source, nav, svg"
    private val AD_TOKENS = setOf(
        "ad", "ads", "adsbygoogle", "advert", "advertisement", "advertisements", "sponsor", "sponsored", "banner",
        "social", "share", "sharedaddy", "addtoany", "comments", "comment", "respond", "breadcrumb", "breadcrumbs",
    )
    private val NEXT_PREVIOUS = Regex(
        "^[\\s«»<>←→‹›-]*(next|prev|previous|prior|back|home|index|table of contents|toc|chapter list)" +
            "(\\s+(chapter|page|part))?[\\s«»<>←→‹›.!-]*$",
        RegexOption.IGNORE_CASE,
    )
    private val IMAGE_ATTRIBUTES = listOf("data-src", "data-lazy-src", "data-original", "data-lazy", "src")
    private const val MAX_AD_LENGTH = 300

    fun prepare(
        nodes: List<Element>,
        baseUrl: String,
        spec: ChapterSpec?,
        prefs: NovelPrefs,
        adjacentChapterUrls: Set<String>,
    ): Prepared {
        val document = Jsoup.parseBodyFragment(nodes.joinToString("\n") { it.outerHtml() }, baseUrl)
        val body = document.body()

        body.select(JUNK).remove()
        spec?.remove?.forEach { selector ->
            try {
                body.select(selector).remove()
            } catch (e: RuntimeException) {
                // A selector that does not work is left out; plugins are checked when they are imported.
            }
        }
        for (element in body.select("[class], [id]").toList()) {
            val tokens = (element.classNames() + element.id()).flatMap { it.lowercase().split('-', '_', ' ') + it.lowercase() }
            if (tokens.any { it in AD_TOKENS } && element.text().length < MAX_AD_LENGTH * 3) element.remove()
        }
        val removeText = spec?.removeText.orEmpty()
        if (removeText.isNotEmpty()) {
            for (element in body.select("p, li, div, span, h1, h2, h3, h4").toList()) {
                val text = element.text()
                if (text.length < MAX_AD_LENGTH && removeText.any { it.containsMatchIn(text) }) element.remove()
            }
        }
        if (prefs.removeNextPrevious) {
            for (link in body.select("a[href]").toList()) {
                val target = link.absUrl("href").substringBefore('#')
                if (target in adjacentChapterUrls || NEXT_PREVIOUS.matches(link.text().trim())) {
                    val parent = link.parent()
                    link.remove()
                    if (parent != null && parent !== body && parent.text().isBlank() && parent.select("img").isEmpty()) parent.remove()
                }
            }
        }

        val images = ArrayList<Pair<Element, String>>()
        for (image in body.select("img").toList()) {
            var address = ""
            for (attribute in IMAGE_ATTRIBUTES) {
                val value = image.attr(attribute).trim()
                if (value.isNotEmpty() && !value.startsWith("data:")) {
                    address = image.absUrl(attribute)
                    break
                }
            }
            if (address.isEmpty() || !address.startsWith("http")) {
                image.remove()
            } else if (prefs.skipImages) {
                if (prefs.includeImageUrl) {
                    val paragraph = Element("p")
                    paragraph.appendElement("a").attr("href", address).text("[image] $address")
                    image.replaceWith(paragraph)
                } else {
                    image.remove()
                }
            } else {
                image.attr("src", address)
                images.add(image to address)
            }
        }

        for (element in body.allElements.toList()) {
            val keep = when (element.tagName()) {
                "a" -> setOf("href")
                "img" -> setOf("src", "alt")
                "td", "th" -> setOf("colspan", "rowspan")
                "ol" -> setOf("start")
                else -> emptySet()
            }
            for (attribute in element.attributes().asList().map { it.key }) {
                if (attribute !in keep) element.removeAttr(attribute)
            }
            if (element.tagName() == "a" && !element.attr("href").startsWith("http")) element.unwrap()
        }
        for (paragraph in body.select("p").toList()) {
            if (paragraph.text().isBlank() && paragraph.select("img, br, hr").isEmpty()) paragraph.remove()
        }
        return Prepared(body, spec?.replace.orEmpty(), images)
    }
}
