# Novel Scraper plugin format (version 1)

A plugin is ONE JSON object. Only `name` and `hosts` are required, plus at least one of `info`, `toc`, `chapter`.
Everything a plugin leaves out is found by the app's automatic detection (chapter links, text of the chapter, details of the
novel from meta tags), so write only what the site needs.

## How the app uses a plugin

1. The person opens the novel's page (the page that lists the chapters). The app downloads its HTML.
2. `info` reads the details of the novel from that page (title, author, cover, description...).
3. `toc` finds the chapter links on that page (and on more pages, or in a JSON API).
4. For every chapter the app downloads the chapter page (or calls `chapter.api`), takes the text with `chapter.content`,
   removes what `chapter.remove` / `chapter.removeText` say, and puts the text in the EPUB.

Only the HTML the server sends is seen. JavaScript is NOT run. When a site fills its page with scripts, use `toc.api` /
`chapter.api` with the JSON address that the page calls.

## Top level

| field | type | meaning |
|---|---|---|
| `name` | text | Name of the site, shown in the app. Required. |
| `hosts` | list of text | Host names the plugin is for, such as `["example.com", "m.example.com"]` (no `https://`, no paths). Required. `www.` is ignored. |
| `language` | text | Language code of the novels (`"en"`), used when the page does not say. Optional. |
| `delayMs` | number | Pause between two chapters in milliseconds (default 400). Raise it for sites that block fast readers. Optional. |
| `headers` | object | Extra request headers, for example `{"Referer": "https://example.com/"}`. Optional. |
| `info` | object | Details of the novel (see below). |
| `toc` | object | The list of chapters. |
| `chapter` | object | The text of a chapter. |

## Extractors (used for `info.*`, `chapter.title`, `chapter.content`, `toc.pages.*`)

An extractor says where to read a value on a page. It is either

* a CSS selector text: `"h1.title"`,
* a list of selectors (the first one that matches is used): `["div.content", "div#chapter-body"]`,
* or an object:

| field | meaning |
|---|---|
| `css` | selector text, or list of selectors (first match wins) |
| `attr` | read this attribute instead of the text (`"href"`, `"src"`, `"content"`, `"data-src"`). Addresses are made absolute. |
| `regex` | applied to the value; the first group `( )` is kept (or the whole match). Not matching = no value. |
| `all` | `true` = use every match, not only the first (for `subjects`, and for `chapter.content` when the text is in several elements) |
| `html` | `true` = the inner HTML instead of the text (rarely needed) |
| `value` | a fixed text, no selector needed (for example `{"value": "en"}`) |

CSS selectors are evaluated by Jsoup: CSS3 plus `:has(...)`, `:not(...)`, `:contains(text)`, `:matches(regex)`, `:nth-child(n)`,
`:eq(n)`, attribute selectors like `a[href*=chapter]`.

## `info` (read from the novel page)

Fields: `title`, `author`, `cover` (a selector of an `img` reads its `src`; for `meta[property=og:image]` write `{"css": "meta[property=og:image]", "attr": "content"}`),
`description`, `subjects` (use `"all": true`), `language`, `translator`, `publisher`. Each is an extractor.

## `toc` (the list of chapters)

```json
"toc": {
  "chapters": { "css": "ul.chapter-list li a", "title": null, "urlAttr": "href" },
  "order": "oldest-first",
  "pages": { "next": "a.next", "template": "{base}?page={n}", "from": 1, "count": {"css": "ul.pagination li:nth-last-child(2) a"}, "max": 200 },
  "api": { ... }
}
```

* `chapters`: a selector text (the matching `a` elements, or elements containing an `a`) or an object with
  `css` (selector or list), `title` (an extractor evaluated on each matched element; default: its text), `urlAttr`
  (default `"href"`).
* `order`: `"oldest-first"`, `"newest-first"` (the app reverses the list) or `"auto"` (default: decided from the numbers in the titles).
  The EPUB always ends up oldest first.
* `pages`: only when the list is spread over several pages.
  * `next`: an extractor for the link to the next page, used until there is none (a plain selector of an `a` element reads its `href`).
  * OR `template` + `from` + `count`: page addresses built from a template. Placeholders: `{base}` (address of the opened page without query), `{url}`, `{origin}`, `{n}` (page number). `count` is an extractor whose number is the last page (if omitted the app goes on until a page has no new chapters). `from` is the number of the first page (the opened page counts as page `from`).
  * `max`: highest number of pages to read (default 200).
* `api`: use this instead of `chapters` when the list comes from a JSON address:

```json
"api": {
  "url": "{origin}/api/novels/{novelId}/chapters?page={n}",
  "method": "GET",
  "headers": { "Accept": "application/json" },
  "vars": { "novelId": { "from": "url", "regex": "/novel/(\\d+)", "group": 1 } },
  "list": "data.chapters",
  "title": "name",
  "chapterUrl": "{origin}/novel/{novelId}/chapter/{item.id}",
  "maxPages": 100
}
```

  `vars` define placeholders from the opened page (`"from": "url"` or `"from": "html"`) with a regex. `list` is the path to the array in the answer (`a.b[0].c` style; empty = the answer itself is the array). `{item.xxx}` is a field of one entry. If the address contains `{n}`, pages 1, 2, 3 ... are read until one is empty.

## `chapter` (the text of a chapter)

```json
"chapter": {
  "title": "h1.chapter-title",
  "content": ["div.reading-content", "div#content"],
  "remove": ["div.ads", "a[href*=discord]", "script"],
  "removeText": ["Read the latest chapters at", "translated by .* team"],
  "replace": [ { "regex": "\\s+Next chapter\\s*$", "with": "" } ],
  "next": "a.next-part",
  "api": { ... }
}
```

* `title`: extractor for the chapter title on the chapter page. Leave it out to use the title from the list of chapters.
* `content`: extractor for the element that holds the text. The app already removes scripts, forms, navigation and elements whose class names look like advertisements, so keep `remove` for what is specific to the site. Use `"all": true` (object form) when the text is split over several elements.
* `remove`: CSS selectors of elements to delete from the text.
* `removeText`: regular expressions (case insensitive). A short paragraph (under 300 characters) that matches is deleted.
* `replace`: regex replacements applied to the HTML of the finished text.
* `next`: extractor for the link to the next part when one chapter is split over several pages.
* `api`: use this when the text comes from a JSON address:

```json
"api": {
  "url": "{origin}/api/chapter/{chapterId}",
  "vars": { "chapterId": { "from": "chapter-url", "regex": "/chapter/(\\d+)", "group": 1 } },
  "content": "data.content",
  "title": "data.title",
  "format": "html"
}
```

  `format` is `"html"` (the value is HTML) or `"text"` (plain text, one paragraph per line). `{url}`, `{origin}`, `{base}` and the `vars` (read from the address of the chapter) can be used in `url`.

## Example 1: a simple site (everything on the pages)

```json
{
  "name": "Example Translations",
  "hosts": ["exampletranslations.com"],
  "language": "en",
  "info": {
    "title": "h1.entry-title",
    "author": { "css": "div.novel-info span.author", "regex": "Author:\\s*(.+)" },
    "cover": { "css": "div.thumb img", "attr": "src" },
    "description": "div.summary"
  },
  "toc": {
    "chapters": "div.chapter-list li a",
    "order": "newest-first"
  },
  "chapter": {
    "title": "h1.chapter-title",
    "content": "div.entry-content",
    "remove": ["div.sharedaddy", "p.ad-note"],
    "removeText": ["Support the translator"]
  }
}
```

## Example 2: a list over several pages

```json
{
  "name": "Paged Novels",
  "hosts": ["pagednovels.net"],
  "toc": {
    "chapters": "table.chapters td.title a",
    "order": "oldest-first",
    "pages": { "next": "ul.pagination a[rel=next]" }
  },
  "chapter": { "content": "div#chapter-content" }
}
```

## Example 3: a site that uses a JSON API

```json
{
  "name": "Api Novels",
  "hosts": ["apinovels.io"],
  "delayMs": 800,
  "headers": { "Referer": "https://apinovels.io/" },
  "info": { "title": { "css": "meta[property=og:title]", "attr": "content" } },
  "toc": {
    "order": "oldest-first",
    "api": {
      "url": "{origin}/api/novel/{slug}/chapters",
      "vars": { "slug": { "from": "url", "regex": "/novel/([^/?#]+)" } },
      "list": "chapters",
      "title": "title",
      "chapterUrl": "{origin}/novel/{slug}/chapter-{item.number}"
    }
  },
  "chapter": {
    "api": {
      "url": "{origin}/api/novel/{slug}/chapter/{number}",
      "vars": {
        "slug": { "from": "chapter-url", "regex": "/novel/([^/?#]+)" },
        "number": { "from": "chapter-url", "regex": "chapter-(\\d+)" }
      },
      "content": "content",
      "title": "title",
      "format": "html"
    }
  }
}
```
