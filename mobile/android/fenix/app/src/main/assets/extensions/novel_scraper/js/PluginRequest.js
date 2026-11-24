/*
    "No plugin for this site" flow.

    When the page the person opened has no plugin (the scraper would fall back to the default parser), a popup asks
    whether to create one. If the person agrees, this file collects what Claude needs to write the plugin:

      1. the table of contents page (the page that is open),
      2. the first chapter page (found automatically, the person can change the address),

    and writes it, together with the instructions and the reference of the parser format, into one Markdown file.
    The person saves or copies the file, gives it to Claude, and imports the JavaScript Claude answers with in
    Settings > Novel scraper plugins. NovelPlugins.js then loads it the next time this page opens.
*/
"use strict";

class PluginRequest {
    static OFF_KEY = "novelScraper.noPluginPromptHosts";
    static MAX_TOC_CHARS = 70000;
    static MAX_CHAPTER_CHARS = 60000;

    // ---------------------------------------------------------------- entry point

    /**
     * Called with the page the person opened. Resolves when the popup is closed (at once when there is a plugin for
     * the site, or the person asked not to be asked again). Never throws: a problem here must not stop the scraper.
     */
    static async offerIfNoPlugin(url, dom) {
        try {
            if (!PluginRequest.needsPlugin(url, dom)) {
                return;
            }
            let host = PluginRequest.hostOf(url);
            if (PluginRequest.isPromptOff(host)) {
                return;
            }
            await PluginRequest.showFlow(url, dom, host);
        } catch (e) {
            console.log("PluginRequest: " + e);
        }
    }

    static needsPlugin(url, dom) {
        let parser = parserFactory.fetch(url, dom);
        return parser instanceof DefaultParser;
    }

    static hostOf(url) {
        return ParserFactory.hostNameForParserSelection(url);
    }

    static isPromptOff(host) {
        try {
            return JSON.parse(localStorage.getItem(PluginRequest.OFF_KEY) || "[]").includes(host);
        } catch (e) {
            return false;
        }
    }

    static setPromptOff(host) {
        try {
            let hosts = JSON.parse(localStorage.getItem(PluginRequest.OFF_KEY) || "[]");
            if (!hosts.includes(host)) {
                hosts.push(host);
            }
            localStorage.setItem(PluginRequest.OFF_KEY, JSON.stringify(hosts));
        } catch (e) {
            console.log("PluginRequest: " + e);
        }
    }

    // ---------------------------------------------------------------- popup

    static h(tag, attrs, ...children) {
        let element = document.createElement(tag);
        for (let [key, value] of Object.entries(attrs || {})) {
            if (key === "class") {
                element.className = value;
            } else if (key.startsWith("on")) {
                element.addEventListener(key.substring(2), value);
            } else if (value === true) {
                element.setAttribute(key, "");
            } else if (value !== false && value != null) {
                element.setAttribute(key, value);
            }
        }
        for (let child of children.flat()) {
            element.append(child instanceof Node ? child : document.createTextNode(String(child)));
        }
        return element;
    }

    static showFlow(url, dom, host) {
        return new Promise(resolve => {
            let root = document.getElementById("pluginRequestOverlay");
            if (root == null) {
                root = PluginRequest.h("div", { id: "pluginRequestOverlay", class: "novelOverlay" });
                document.body.appendChild(root);
            }
            root.hidden = false;
            let close = () => {
                root.hidden = true;
                root.replaceChildren();
                resolve();
            };
            PluginRequest.renderAsk(root, url, dom, host, close);
        });
    }

    static render(root, title, bodyNodes, buttons) {
        root.replaceChildren(
            PluginRequest.h("div", { class: "novelDialog" },
                PluginRequest.h("h2", {}, title),
                PluginRequest.h("div", { class: "novelDialogBody" }, bodyNodes),
                PluginRequest.h("div", { class: "novelButtons" }, buttons)
            )
        );
    }

    static renderAsk(root, url, dom, host, close) {
        let dontAsk = PluginRequest.h("input", { type: "checkbox", id: "pluginRequestDontAsk" });
        PluginRequest.render(root, "No plugin for " + host,
            [
                PluginRequest.h("p", {}, "This site has no plugin yet, so chapters cannot be read reliably."),
                PluginRequest.h("p", {}, "Create one? The app reads this table of contents page and the first chapter " +
                    "and prepares a request file for Claude. Claude writes the plugin, and you import it in " +
                    "Settings > Novel scraper plugins."),
                PluginRequest.h("label", {}, dontAsk, " Do not ask again for this site"),
            ],
            [
                PluginRequest.h("button", {
                    class: "novelPrimary",
                    onclick: () => {
                        if (dontAsk.checked) {
                            PluginRequest.setPromptOff(host);
                        }
                        PluginRequest.renderCollect(root, url, dom, host, close);
                    },
                }, "Yes, create a plugin"),
                PluginRequest.h("button", {
                    onclick: () => {
                        if (dontAsk.checked) {
                            PluginRequest.setPromptOff(host);
                        }
                        close();
                    },
                }, "No, use the default parser"),
            ]
        );
    }

    static renderCollect(root, url, dom, host, close) {
        let toc;
        try {
            toc = PluginRequest.captureToc(url, dom);
        } catch (e) {
            PluginRequest.renderMessage(root, "Could not read this page", String(e && e.message || e), close);
            return;
        }
        let chapterUrl = PluginRequest.h("input", {
            type: "url",
            id: "pluginRequestChapterUrl",
            class: "novelWide",
            value: toc.chapters.first ? toc.chapters.first.href : "",
            placeholder: "https://...",
        });
        let status = PluginRequest.h("pre", { class: "novelStatusLine" });
        let build = PluginRequest.h("button", { class: "novelPrimary" }, "Read chapter 1 and build the file");
        let cancel = PluginRequest.h("button", { onclick: close }, "Cancel");

        let found = toc.chapters.count > 0
            ? toc.chapters.count + " chapter links found" + (toc.chapters.containerSelector
                ? " (list: " + toc.chapters.containerSelector + ")" : "") + "."
            : "No list of chapter links could be recognised. The request will still include the page.";
        PluginRequest.render(root, "Create a plugin for " + host,
            [
                PluginRequest.h("p", {}, "Step 1 of 2: table of contents. Captured " +
                    Math.round(toc.html.length / 1024) + " KB. " + found),
                PluginRequest.h("p", {}, "Step 2 of 2: the first chapter. Check the address and change it if it is " +
                    "not chapter 1."),
                chapterUrl,
                status,
            ],
            [build, cancel]
        );

        build.addEventListener("click", async () => {
            build.disabled = true;
            cancel.disabled = true;
            let chapter = null;
            let chosenUrl = chapterUrl.value.trim();
            try {
                if (chosenUrl === "") {
                    throw new Error("No chapter address.");
                }
                status.textContent = "Reading chapter 1...";
                chapter = await PluginRequest.captureChapter(chosenUrl);
            } catch (e) {
                status.textContent = "Chapter 1 could not be read: " + String(e && e.message || e) +
                    "\nChange the address and try again, or build the file without it (Claude then has less to " +
                    "work with).";
                build.disabled = false;
                cancel.disabled = false;
                build.textContent = "Try again";
                if (PluginRequest.noChapterButton == null) {
                    PluginRequest.noChapterButton = PluginRequest.h("button", {
                        onclick: () => PluginRequest.finish(root, host, toc, null, close),
                    }, "Build without chapter 1");
                    cancel.before(PluginRequest.noChapterButton);
                }
                return;
            }
            PluginRequest.noChapterButton = null;
            status.textContent = "Building the request file...";
            await PluginRequest.finish(root, host, toc, chapter, close);
        });
    }

    static async finish(root, host, toc, chapter, close) {
        PluginRequest.noChapterButton = null;
        let text;
        try {
            let reference = await PluginRequest.loadReference();
            text = PluginRequest.buildMarkdown(host, toc, chapter, reference);
        } catch (e) {
            PluginRequest.renderMessage(root, "The file could not be built", String(e && e.message || e), close);
            return;
        }
        let fileName = "claude-plugin-request-" + host.replace(/[^a-z0-9.-]/gi, "_") + ".md";
        let note = PluginRequest.h("p", { class: "novelStatusLine" });
        PluginRequest.render(root, "Request file ready",
            [
                PluginRequest.h("p", {}, fileName + " (" + Math.round(text.length / 1024) + " KB)"),
                PluginRequest.h("ol", {},
                    PluginRequest.h("li", {}, "Save the file, or copy the text."),
                    PluginRequest.h("li", {}, "Give it to Claude (attach the file or paste the text) and ask for the plugin."),
                    PluginRequest.h("li", {}, "Copy the JavaScript Claude answers with."),
                    PluginRequest.h("li", {}, "Open Settings > Novel scraper plugins and import it (file or paste)."),
                    PluginRequest.h("li", {}, "Open the novel page in the scraper again.")
                ),
                note,
            ],
            [
                PluginRequest.h("button", {
                    class: "novelPrimary",
                    onclick: () => {
                        PluginRequest.saveText(text, fileName);
                        note.textContent = "Saving " + fileName + " (see the downloads of the browser).";
                    },
                }, "Save file"),
                PluginRequest.h("button", {
                    onclick: async () => {
                        let copied = await PluginRequest.copyText(text);
                        note.textContent = copied ? "Copied." : "Copying is not allowed here. Use Save file.";
                    },
                }, "Copy text"),
                PluginRequest.h("button", { onclick: close }, "Close"),
            ]
        );
    }

    static renderMessage(root, title, message, close) {
        PluginRequest.render(root, title,
            [PluginRequest.h("p", {}, message)],
            [PluginRequest.h("button", { class: "novelPrimary", onclick: close }, "OK")]
        );
    }

    static saveText(text, fileName) {
        let blob = new Blob([text], { type: "text/markdown" });
        let link = document.createElement("a");
        link.href = URL.createObjectURL(blob);
        link.download = fileName;
        link.style.display = "none";
        document.body.appendChild(link);
        try {
            link.click();
        } finally {
            document.body.removeChild(link);
        }
        setTimeout(() => URL.revokeObjectURL(link.href), 60000);
    }

    static async copyText(text) {
        try {
            await navigator.clipboard.writeText(text);
            return true;
        } catch (e) {
            // Fall through to the old way.
        }
        let area = document.createElement("textarea");
        area.value = text;
        area.style.position = "fixed";
        area.style.opacity = "0";
        document.body.appendChild(area);
        area.select();
        let copied = false;
        try {
            copied = document.execCommand("copy");
        } catch (e) {
            copied = false;
        }
        document.body.removeChild(area);
        return copied;
    }

    // ---------------------------------------------------------------- capture: table of contents

    static captureToc(url, dom) {
        let chapters = PluginRequest.findChapterLinks(dom, url);
        return {
            url: url,
            title: dom.title || "",
            lang: dom.documentElement ? dom.documentElement.getAttribute("lang") : null,
            canonical: PluginRequest.attr(dom, "link[rel='canonical']", "href"),
            metas: PluginRequest.metaTags(dom),
            jsonLd: PluginRequest.jsonLd(dom),
            scriptHints: PluginRequest.scriptHints(dom),
            pagination: PluginRequest.paginationHints(dom),
            chapters: chapters,
            html: PluginRequest.slim(dom.body, PluginRequest.MAX_TOC_CHARS),
        };
    }

    static attr(dom, selector, name) {
        let element = dom.querySelector(selector);
        return element ? element.getAttribute(name) : null;
    }

    static metaTags(dom) {
        let result = [];
        for (let meta of dom.querySelectorAll("meta[property], meta[name]")) {
            let key = meta.getAttribute("property") || meta.getAttribute("name");
            let value = meta.getAttribute("content");
            if (key && value && result.length < 40) {
                result.push(key + " = " + value.replace(/\s+/g, " ").substring(0, 300));
            }
        }
        return result;
    }

    static jsonLd(dom) {
        return [...dom.querySelectorAll("script[type='application/ld+json']")]
            .slice(0, 2)
            .map(s => s.textContent.trim().substring(0, 2500));
    }

    /** Hints that content or the chapter list is loaded by scripts: API-looking URLs and framework data. */
    static scriptHints(dom) {
        let hints = [];
        let next = dom.querySelector("script#__NEXT_DATA__");
        if (next) {
            hints.push("Next.js page data (script#__NEXT_DATA__), first 4000 characters:\n" +
                next.textContent.trim().substring(0, 4000));
        }
        let urls = new Set();
        let mentionsNuxt = false;
        for (let script of dom.querySelectorAll("script:not([src])")) {
            let text = script.textContent || "";
            if (text.includes("__NUXT__")) {
                mentionsNuxt = true;
            }
            let matches = text.match(/["'`](?:https?:)?\/\/?[^"'`\s]*(?:api|ajax|chapter|json|graphql)[^"'`\s]*["'`]/gi) || [];
            for (let m of matches) {
                if (urls.size < 12) {
                    urls.add(m.substring(1, m.length - 1).substring(0, 200));
                }
            }
        }
        if (mentionsNuxt) {
            hints.push("Nuxt page data (window.__NUXT__) is present.");
        }
        if (dom.querySelector("[data-reactroot], #root:empty, #app:empty, #__next")) {
            hints.push("The page looks like a single page application (React/Vue root element).");
        }
        if (urls.size > 0) {
            hints.push("URLs that look like APIs in inline scripts:\n" + [...urls].map(u => "- " + u).join("\n"));
        }
        let ajaxUrl = dom.documentElement.innerHTML.includes("admin-ajax.php");
        if (ajaxUrl) {
            hints.push("The page mentions admin-ajax.php (WordPress / Madara style AJAX).");
        }
        return hints;
    }

    static paginationHints(dom) {
        let found = [];
        let selectors = [
            "link[rel='next']", "a[rel='next']", "[class*='pagination']", "[class*='pager']",
            "button[class*='more']", "a[class*='more']", "[class*='load-more']", "[class*='loadmore']",
        ];
        for (let selector of selectors) {
            for (let element of dom.querySelectorAll(selector)) {
                let html = PluginRequest.slim(element, 600);
                if (found.length < 8 && !found.includes(html)) {
                    found.push(html);
                }
            }
        }
        return found;
    }

    /** Finds the group of links that looks like the chapter list and the likely first chapter. */
    static findChapterLinks(dom, pageUrl) {
        let pageHost = PluginRequest.hostOf(pageUrl);
        let anchors = [];
        for (let a of dom.querySelectorAll("a[href]")) {
            let href = PluginRequest.absolute(a.getAttribute("href"), dom.baseURI || pageUrl);
            if (href == null) {
                continue;
            }
            let text = a.textContent.replace(/\s+/g, " ").trim();
            if (text.length === 0 || text.length > 200) {
                continue;
            }
            if (ParserFactory.hostNameForParserSelection(href) !== pageHost) {
                continue;
            }
            anchors.push({ a: a, href: href, text: text });
        }

        let byShape = new Map();
        let byFolder = new Map();
        for (let item of anchors) {
            let shape = PluginRequest.shapeOf(item.href);
            let folder = PluginRequest.folderOf(item.href);
            if (!byShape.has(shape)) {
                byShape.set(shape, []);
            }
            byShape.get(shape).push(item);
            if (!byFolder.has(folder)) {
                byFolder.set(folder, []);
            }
            byFolder.get(folder).push(item);
        }
        let best = [];
        for (let group of [...byShape.values(), ...byFolder.values()]) {
            if (group.length > best.length) {
                best = group;
            }
        }
        if (best.length < 3) {
            return { count: 0, order: "unknown", first: null, last: null, listStart: null, containerSelector: null, samples: [] };
        }

        let container = PluginRequest.commonAncestor(best.map(i => i.a));
        let numbered = best.map((item, index) => ({ item: item, index: index, number: PluginRequest.numberOf(item) }));
        let withNumber = numbered.filter(n => !Number.isNaN(n.number));
        let first = best[0];
        if (withNumber.length >= best.length / 2) {
            withNumber.sort((x, y) => (x.number - y.number) || (x.index - y.index));
            first = withNumber[0].item;
        }
        let describe = item => ({ href: item.href, text: item.text });
        let startNumber = PluginRequest.numberOf(best[0]);
        let endNumber = PluginRequest.numberOf(best[best.length - 1]);
        let order = "unknown";
        if (!Number.isNaN(startNumber) && !Number.isNaN(endNumber) && startNumber !== endNumber) {
            order = startNumber < endNumber ? "oldest first" : "newest first";
        }
        return {
            count: best.length,
            order: order,
            first: describe(first),
            last: describe(best[best.length - 1]),
            listStart: describe(best[0]),
            containerSelector: container ? PluginRequest.selectorFor(container) : null,
            samples: best.slice(0, 5).concat(best.slice(-2)).map(describe),
        };
    }

    static absolute(href, base) {
        try {
            let url = new URL(href, base);
            return /^https?:$/.test(url.protocol) ? url.href.split("#")[0] : null;
        } catch (e) {
            return null;
        }
    }

    /** The address with numbers and long slugs replaced, so the links of chapters have the same shape. */
    static shapeOf(href) {
        let url = new URL(href);
        let segments = url.pathname.split("/").filter(s => s !== "").map(s => {
            if (/\d/.test(s)) {
                return s.replace(/\d+/g, "#");
            }
            return (s.length > 20 || (s.match(/-/g) || []).length >= 3) ? "*" : s;
        });
        let query = url.search ? "?" + url.search.substring(1).replace(/=[^&]*/g, "=*") : "";
        return url.hostname + "/" + segments.join("/") + query;
    }

    static folderOf(href) {
        let url = new URL(href);
        let segments = url.pathname.split("/").filter(s => s !== "");
        segments.pop();
        return "folder:" + url.hostname + "/" + segments.join("/");
    }

    static numberOf(item) {
        let match = item.text.match(/(\d+(?:\.\d+)?)/) || item.href.match(/(\d+)(?!.*\d)/);
        return match ? parseFloat(match[1]) : NaN;
    }

    static commonAncestor(nodes) {
        let ancestor = nodes[0].parentElement;
        while (ancestor != null && !nodes.every(n => ancestor.contains(n))) {
            ancestor = ancestor.parentElement;
        }
        return ancestor;
    }

    static selectorFor(node) {
        let parts = [];
        let current = node;
        while (current != null && current.tagName && parts.length < 4) {
            let part = current.tagName.toLowerCase();
            if (current.id) {
                parts.unshift(part + "#" + CSS.escape(current.id));
                break;
            }
            let classes = (typeof current.className === "string" ? current.className : "")
                .trim().split(/\s+/).filter(c => c !== "").slice(0, 2);
            if (classes.length > 0) {
                part += "." + classes.map(c => CSS.escape(c)).join(".");
            }
            parts.unshift(part);
            if (part === "body" || part === "html") {
                break;
            }
            current = current.parentElement;
        }
        return parts.join(" > ");
    }

    // ---------------------------------------------------------------- capture: chapter

    static async captureChapter(url) {
        let errorHandler = {
            onFetchError: (failedUrl, error) => { throw error; },
            onResponseError: (failedUrl, wrapOptions, response, message) => {
                throw new Error(message || ("HTTP " + (response ? response.status : "error")));
            },
        };
        let result = await HttpClient.wrapFetch(url, { errorHandler: errorHandler });
        let doc = result.responseXML;
        if (doc == null || doc.body == null) {
            throw new Error("The address did not return a web page.");
        }
        let headers = result.response ? result.response.headers : null;
        let content = PluginRequest.findContentNode(doc) || HeuristicScanner.findContentNode(doc);
        let paragraphs = content ? content.getElementsByTagName("p").length : 0;
        let textLength = content ? content.textContent.trim().length : 0;
        return {
            url: url,
            finalUrl: result.response ? result.response.url : url,
            status: result.response ? result.response.status : null,
            server: headers ? headers.get("server") : null,
            cloudflare: headers ? (headers.get("cf-ray") != null || /cloudflare/i.test(headers.get("server") || "")) : false,
            title: doc.title || "",
            challenge: /just a moment|attention required|checking your browser|verify you are human|captcha/i.test(doc.title || ""),
            lang: doc.documentElement ? doc.documentElement.getAttribute("lang") : null,
            contentSelector: content ? PluginRequest.selectorFor(content) : null,
            paragraphs: paragraphs,
            textLength: textLength,
            titleSelector: (() => {
                let node = HeuristicScanner.findTitleNode(doc);
                return node ? PluginRequest.selectorFor(node) : null;
            })(),
            navLinks: PluginRequest.navigationLinks(doc),
            scriptHints: PluginRequest.scriptHints(doc),
            contentSample: content ? PluginRequest.slim(content, 6000) : null,
            html: PluginRequest.slim(doc.body, PluginRequest.MAX_CHAPTER_CHARS),
        };
    }

    /**
     * The element that holds most of the text in paragraphs: every paragraph adds the length of its text to its parent
     * and half of it to its grandparent. Falls back to line breaks for pages that do not use paragraphs.
     */
    static findContentNode(doc) {
        let scores = new Map();
        let add = (element, amount) => {
            if (element != null && element !== doc.body && element !== doc.documentElement) {
                scores.set(element, (scores.get(element) || 0) + amount);
            }
        };
        for (let p of doc.body.querySelectorAll("p")) {
            let length = p.textContent.trim().length;
            if (length > 20) {
                add(p.parentElement, length);
                add(p.parentElement ? p.parentElement.parentElement : null, length / 2);
            }
        }
        if (scores.size === 0) {
            for (let br of doc.body.querySelectorAll("br")) {
                add(br.parentElement, 40);
            }
        }
        let best = null;
        let bestScore = 0;
        for (let [element, score] of scores) {
            if (score > bestScore) {
                best = element;
                bestScore = score;
            }
        }
        return best;
    }

    static navigationLinks(doc) {
        let found = [];
        let candidates = doc.querySelectorAll("a[rel='next'], a[rel='prev'], a[id*='next' i], a[id*='prev' i], " +
            "a[class*='next' i], a[class*='prev' i], a[href]");
        for (let a of candidates) {
            let text = a.textContent.replace(/\s+/g, " ").trim();
            let marker = (a.getAttribute("rel") || "") + " " + a.id + " " + (typeof a.className === "string" ? a.className : "");
            if (/(next|prev|previous|chapter|下一|上一|次|前)/i.test(marker + " " + text) && text.length < 60) {
                let html = PluginRequest.slim(a, 400);
                if (found.length < 6 && !found.includes(html)) {
                    found.push(html);
                }
            }
        }
        return found;
    }

    // ---------------------------------------------------------------- shrinking HTML

    /**
     * A copy of the element as HTML, made small enough to give to Claude: no scripts, styles or images data, short
     * attribute values and texts, and long runs of similar siblings (chapter list items, paragraphs) cut down to the
     * first and last few, with a comment that says how many were left out.
     */
    static slim(root, maxChars) {
        let attempts = [
            { keepFirst: 5, keepLast: 2, text: 160 },
            { keepFirst: 3, keepLast: 1, text: 100 },
            { keepFirst: 2, keepLast: 1, text: 60 },
        ];
        let html = "";
        for (let options of attempts) {
            let clone = root.cloneNode(true);
            for (let junk of clone.querySelectorAll("script, style, noscript, svg, iframe, link, template, canvas, " +
                "video, audio, source, object, embed")) {
                junk.remove();
            }
            PluginRequest.removeComments(clone);
            PluginRequest.trimAttributes(clone);
            PluginRequest.trimTexts(clone, options.text);
            PluginRequest.collapseRuns(clone, options.keepFirst, options.keepLast);
            html = clone.outerHTML.replace(/[ \t]*\n[ \t\n]*/g, "\n").replace(/[ \t]{2,}/g, " ").trim();
            if (html.length <= maxChars) {
                return html;
            }
        }
        return html.substring(0, maxChars) + "\n<!-- cut: the page is longer than this -->";
    }

    static removeComments(node) {
        let walker = document.createTreeWalker(node, NodeFilter.SHOW_COMMENT);
        let comments = [];
        while (walker.nextNode()) {
            comments.push(walker.currentNode);
        }
        comments.forEach(c => c.remove());
    }

    static trimAttributes(root) {
        const keep = new Set(["class", "id", "href", "src", "rel", "itemprop", "itemtype", "itemscope", "property",
            "name", "content", "type", "value", "lang", "datetime", "role", "aria-label", "title", "alt", "data-src"]);
        for (let element of [root, ...root.querySelectorAll("*")]) {
            for (let attribute of [...element.attributes]) {
                let name = attribute.name;
                if (!(keep.has(name) || name.startsWith("data-"))) {
                    element.removeAttribute(name);
                    continue;
                }
                let value = attribute.value;
                if (value.startsWith("data:")) {
                    value = "data:...";
                }
                let limit = name === "class" ? 120 : (name.startsWith("data-") ? 80 : 160);
                if (value.length > limit) {
                    value = value.substring(0, limit) + "...";
                }
                if (value !== attribute.value) {
                    element.setAttribute(name, value);
                }
            }
        }
    }

    static trimTexts(root, limit) {
        let walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
        let texts = [];
        while (walker.nextNode()) {
            texts.push(walker.currentNode);
        }
        for (let node of texts) {
            let value = node.nodeValue;
            if (value.length > limit) {
                node.nodeValue = value.substring(0, limit) + "...[+" + (value.length - limit) + " characters]";
            }
        }
    }

    static signature(element) {
        let classes = (typeof element.className === "string" ? element.className : "")
            .split(/\s+/).filter(c => c !== "" && !/^(odd|even|first|last|active|current|selected)$/i.test(c));
        return element.tagName + "." + (classes[0] || "");
    }

    static collapseRuns(root, keepFirst, keepLast) {
        let parents = [root, ...root.querySelectorAll("*")];
        for (let parent of parents) {
            let children = [...parent.children];
            let index = 0;
            while (index < children.length) {
                let end = index;
                let signature = PluginRequest.signature(children[index]);
                while (end + 1 < children.length && PluginRequest.signature(children[end + 1]) === signature) {
                    end++;
                }
                let runLength = end - index + 1;
                if (runLength > keepFirst + keepLast + 1) {
                    let omitted = children.slice(index + keepFirst, end - keepLast + 1);
                    let note = document.createComment(" " + omitted.length + " similar <" +
                        children[index].tagName.toLowerCase() + "> elements left out ");
                    omitted[0].before(note);
                    omitted.forEach(e => e.remove());
                }
                index = end + 1;
            }
        }
    }

    // ---------------------------------------------------------------- the request file

    static async loadReference() {
        let read = async path => {
            try {
                let response = await fetch(browser.runtime.getURL(path));
                return response.ok ? await response.text() : "(not available)";
            } catch (e) {
                return "(not available)";
            }
        };
        return {
            guide: await read("claude/AGENTS.reference.md"),
            template: await read("js/parsers/Template.js"),
            examples: [
                { name: "MznovelsParser.js (server rendered, several table of contents pages)", code: await read("js/parsers/MznovelsParser.js") },
                { name: "BotitranslationParser.js (JSON API, builds the chapter from data)", code: await read("js/parsers/BotitranslationParser.js") },
                { name: "ScribblehubParser.js (walks table of contents pages, sets request headers)", code: await read("js/parsers/ScribblehubParser.js") },
            ],
        };
    }

    static fence(text, language) {
        let longest = 2;
        for (let run of (text.match(/`+/g) || [])) {
            longest = Math.max(longest, run.length);
        }
        let marks = "`".repeat(longest + 1);
        return marks + language + "\n" + text + "\n" + marks;
    }

    static className(host) {
        let name = host.replace(/[^a-z0-9]+(.)?/gi, (m, c) => c ? c.toUpperCase() : "");
        name = name.charAt(0).toUpperCase() + name.slice(1);
        if (/^\d/.test(name)) {
            name = "Site" + name;
        }
        return name + "Parser";
    }

    static buildMarkdown(host, toc, chapter, reference) {
        let f = PluginRequest.fence;
        let name = PluginRequest.className(host);
        let out = [];
        out.push("# Task: write a Novel Scraper plugin for " + host);
        out.push("");
        out.push("I use a browser app that downloads web novels as EPUB files. It contains the WebToEpub engine. " +
            "A \"plugin\" is one JavaScript file that teaches the engine to read one website. " +
            "The site below has no plugin. Write it. Everything you need is in this file: facts about the site, the " +
            "HTML of its table of contents page and of its first chapter, and the reference of the parser format.");
        out.push("");
        out.push("## What to deliver");
        out.push("");
        out.push("1. Exactly one JavaScript file named `" + name + ".js` in a single ```javascript code block. " +
            "Complete and ready to import: no placeholders, no other files.");
        out.push("2. After the code block, 3 to 6 short lines: what you assumed, and what could break " +
            "(Cloudflare, content loaded by scripts, pagination, locked chapters).");
        out.push("");
        out.push("## Rules for the plugin");
        out.push("");
        out.push("- First line `// Ai generated Claude`, second line `\"use strict\";`.");
        out.push("- Register with `parserFactory.register(\"" + host + "\", () => new " + name + "());`. " +
            "Add more host names (mirrors, `www.`-less or mobile domains) only when the data shows them.");
        out.push("- `class " + name + " extends Parser`. Implement `getChapterUrls(dom, chapterUrlsUI)` " +
            "(returns `[{ sourceUrl, title }]`, **oldest chapter first**; follow every table of contents page or API " +
            "page so the list is complete), `findContent(dom)`, `extractTitleImpl(dom)` and `extractAuthor(dom)`. " +
            "Add the optional methods that this site needs (cover, description, chapter title, cleanup of ads and " +
            "navigation, request throttle).");
        out.push("- The file is run as a plain script inside the scraper page, where these globals exist: `Parser`, " +
            "`parserFactory`, `util`, `HttpClient`, `ErrorLog`, `UIText` and the helpers described below. " +
            "No `import`/`export`/`require`, no libraries, no browser extension APIs (`chrome.*`, `browser.*`). " +
            "The `document` of the scraper page is not the novel page: always work on the `dom` you are given.");
        out.push("- Base every selector on the HTML below. When something cannot be known from the data (an API " +
            "address, a header), choose the most robust option and say so in your notes.");
        out.push("- If the chapter text is not in the chapter HTML (it is loaded by scripts), find the address in the " +
            "data below and fetch it with `HttpClient.fetchJson` / `HttpClient.fetchText`.");
        out.push("- Do not write tests. Do not change anything outside the plugin file.");
        out.push("");

        out.push("## Facts captured by the app");
        out.push("");
        out.push("- Site: `" + host + "`");
        out.push("- Table of contents page: " + toc.url);
        out.push("- Page title: " + (toc.title || "(none)") + (toc.lang ? " | language: " + toc.lang : ""));
        if (toc.canonical) {
            out.push("- Canonical address: " + toc.canonical);
        }
        out.push("- Chapter links recognised on the table of contents page: " + toc.chapters.count +
            (toc.chapters.containerSelector ? " inside `" + toc.chapters.containerSelector + "`" : ""));
        if (toc.chapters.first) {
            out.push("- Probably chapter 1: " + toc.chapters.first.href + " (\"" + toc.chapters.first.text + "\")");
            out.push("- The list starts with \"" + toc.chapters.listStart.text + "\" and ends with \"" +
                toc.chapters.last.text + "\" (" + toc.chapters.order + ")." +
                (toc.chapters.order === "newest first" ? " `getChapterUrls` must return the chapters oldest first, " +
                    "so reverse this list." : ""));
        }
        if (chapter) {
            out.push("- Chapter 1 page fetched from: " + chapter.url +
                (chapter.finalUrl && chapter.finalUrl !== chapter.url ? " (ended at " + chapter.finalUrl + ")" : "") +
                " | HTTP " + chapter.status);
            let protection = [];
            if (chapter.cloudflare) {
                protection.push("Cloudflare headers present");
            }
            if (chapter.challenge) {
                protection.push("the page looks like a challenge page (\"" + chapter.title + "\")");
            }
            out.push("- Protection: " + (protection.length > 0 ? protection.join("; ") +
                ". Consider `this.minimumThrottle = 3000` or higher." : "none seen"));
            if (chapter.contentSelector) {
                out.push("- Content container guessed from the density of paragraphs: `" + chapter.contentSelector + "` (" +
                    chapter.paragraphs + " paragraphs, " + chapter.textLength + " characters)");
            }
            if (chapter.titleSelector) {
                out.push("- Chapter title element guessed: `" + chapter.titleSelector + "`");
            }
            if (chapter.paragraphs < 3 && chapter.textLength < 500) {
                out.push("- WARNING: very little text was found in the chapter HTML. The text is probably loaded by " +
                    "scripts or an API. Look at the script hints below.");
            }
        } else {
            out.push("- Chapter 1 page: not available (it could not be fetched).");
        }
        out.push("");

        out.push("## Table of contents page");
        out.push("");
        if (toc.metas.length > 0) {
            out.push("### Meta tags");
            out.push(f(toc.metas.join("\n"), "text"));
        }
        if (toc.jsonLd.length > 0) {
            out.push("### JSON-LD");
            toc.jsonLd.forEach(j => out.push(f(j, "json")));
        }
        if (toc.chapters.samples.length > 0) {
            out.push("### Chapter links (first five and last two of the recognised list)");
            out.push(toc.chapters.samples.map(s => "- " + s.text + " -> " + s.href).join("\n"));
        }
        if (toc.pagination.length > 0) {
            out.push("### Pagination / load more elements");
            toc.pagination.forEach(p => out.push(f(p, "html")));
        }
        if (toc.scriptHints.length > 0) {
            out.push("### Script hints");
            toc.scriptHints.forEach(h => out.push(f(h, "text")));
        }
        out.push("### HTML of the page (shortened: scripts and styles removed, long runs of similar elements cut)");
        out.push(f(toc.html, "html"));
        out.push("");

        out.push("## Chapter 1 page");
        out.push("");
        if (chapter) {
            if (chapter.navLinks.length > 0) {
                out.push("### Previous / next links");
                chapter.navLinks.forEach(n => out.push(f(n, "html")));
            }
            if (chapter.scriptHints.length > 0) {
                out.push("### Script hints");
                chapter.scriptHints.forEach(h => out.push(f(h, "text")));
            }
            if (chapter.contentSample) {
                out.push("### Content container as guessed from the density of paragraphs (shortened)");
                out.push(f(chapter.contentSample, "html"));
            }
            out.push("### HTML of the page (shortened: scripts and styles removed, long runs of similar elements cut)");
            out.push(f(chapter.html, "html"));
        } else {
            out.push("(not available)");
        }
        out.push("");

        out.push("## Reference: the parser format");
        out.push("");
        out.push(reference.guide);
        out.push("");
        out.push("## Reference: template of a parser");
        out.push("");
        out.push(f(reference.template, "javascript"));
        out.push("");
        out.push("## Reference: examples of finished parsers");
        out.push("");
        for (let example of reference.examples) {
            out.push("### " + example.name);
            out.push(f(example.code, "javascript"));
        }
        out.push("");
        return out.join("\n");
    }
}
