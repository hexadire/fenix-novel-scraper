/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.novel

import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Writes the downloaded chapters of a library entry as an EPUB file (version 3 or 2, as the options say). */
class EpubWriter(private val library: NovelLibrary, private val prefs: NovelPrefs) {
    private class Page(val fileName: String, val id: String, val title: String)

    @Throws(IOException::class, NovelException::class)
    fun write(entry: LibraryEntry, target: OutputStream) {
        val chapters = entry.chapters.filter { it.file != null }
        if (chapters.isEmpty()) throw NovelException("No chapter was downloaded, so there is nothing to put in an EPUB.")
        val epub3 = prefs.epub3
        val info = entry.info
        val language = info.language.ifBlank { "en" }
        val chapterDirectory = library.chaptersDirectory(entry.id)
        val imageDirectory = library.imagesDirectory(entry.id)

        val pages = ArrayList<Page>()
        chapters.forEachIndexed { index, chapter ->
            pages.add(Page("chapter%05d.xhtml".format(index + 1), "chapter%05d".format(index + 1), chapter.title.ifBlank { "Chapter ${index + 1}" }))
        }
        val coverFile = entry.coverFile?.let { File(imageDirectory, it) }?.takeIf { it.isFile }
        val coverType = coverFile?.let { NovelImages.mediaType(it.extension) }
        val usedImages = LinkedHashSet<String>()

        ZipOutputStream(BufferedOutputStream(target)).use { zip ->
            val mimetype = "application/epub+zip".toByteArray(Charsets.US_ASCII)
            val first = ZipEntry("mimetype")
            first.method = ZipEntry.STORED
            first.size = mimetype.size.toLong()
            first.compressedSize = mimetype.size.toLong()
            val crc = CRC32()
            crc.update(mimetype)
            first.crc = crc.value
            zip.putNextEntry(first)
            zip.write(mimetype)
            zip.closeEntry()

            put(zip, "META-INF/container.xml", CONTAINER)
            put(zip, "OEBPS/Styles/stylesheet.css", prefs.stylesheet)

            if (coverFile != null) {
                putFile(zip, "OEBPS/Images/cover.${coverFile.extension}", coverFile)
                put(zip, "OEBPS/Text/cover.xhtml", xhtmlPage(epub3, language, info.title, coverBody(coverFile.extension)))
            }
            if (prefs.infoPage) {
                put(zip, "OEBPS/Text/info.xhtml", xhtmlPage(epub3, language, "Information", infoBody(entry)))
            }
            chapters.forEachIndexed { index, chapter ->
                val fragment = File(chapterDirectory, chapter.file.orEmpty()).takeIf { it.isFile }?.readText(Charsets.UTF_8).orEmpty()
                IMAGE_REFERENCE.findAll(fragment).forEach { usedImages.add(it.groupValues[1]) }
                val current = pages[index]
                val heading = if (HEADING_START.containsMatchIn(fragment.take(HEADING_LOOKAHEAD))) "" else "<h1>${NovelText.escapeXml(current.title)}</h1>\n"
                put(zip, "OEBPS/Text/${current.fileName}", xhtmlPage(epub3, language, current.title, heading + fragment))
            }
            for (name in usedImages) {
                val file = File(imageDirectory, name)
                if (file.isFile) putFile(zip, "OEBPS/Images/$name", file)
            }
            put(zip, "OEBPS/toc.ncx", ncx(entry, pages, coverFile != null))
            if (epub3) put(zip, "OEBPS/nav.xhtml", nav(language, pages))
            put(zip, "OEBPS/content.opf", opf(entry, pages, epub3, language, coverFile?.extension, coverType, usedImages))
        }
    }

    // region Parts of the book

    private fun xhtmlPage(epub3: Boolean, language: String, title: String, body: String): String {
        val doctype = if (epub3) {
            "<!DOCTYPE html>"
        } else {
            "<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.1//EN\" \"http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd\">"
        }
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n$doctype\n" +
            "<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\" xml:lang=\"$language\" lang=\"$language\">\n" +
            "<head>\n<title>${NovelText.escapeXml(title)}</title>\n" +
            "<link rel=\"stylesheet\" type=\"text/css\" href=\"../Styles/stylesheet.css\" />\n</head>\n" +
            "<body>\n$body\n</body>\n</html>\n"
    }

    private fun coverBody(extension: String): String =
        "<div style=\"text-align: center;\"><img src=\"../Images/cover.$extension\" alt=\"Cover\" style=\"max-width: 100%;\" /></div>"

    private fun infoBody(entry: LibraryEntry): String {
        val info = entry.info
        val out = StringBuilder()
        out.append("<h1>${NovelText.escapeXml(info.title)}</h1>\n")
        if (info.author.isNotBlank()) out.append("<p><b>Author:</b> ${NovelText.escapeXml(info.author)}</p>\n")
        if (info.translator.isNotBlank()) out.append("<p><b>Translator:</b> ${NovelText.escapeXml(info.translator)}</p>\n")
        if (info.sourceUrl.isNotBlank()) {
            val url = NovelText.escapeXml(info.sourceUrl)
            out.append("<p><b>Source:</b> <a href=\"$url\">$url</a></p>\n")
        }
        out.append("<p><b>Chapters:</b> ${entry.downloadedCount()}</p>\n")
        if (info.description.isNotBlank()) {
            out.append("<hr />\n")
            info.description.split(Regex("\\r?\\n+")).filter { it.isNotBlank() }.forEach {
                out.append("<p>${NovelText.escapeXml(it.trim())}</p>\n")
            }
        }
        return out.toString()
    }

    private fun nav(language: String, pages: List<Page>): String {
        val items = pages.joinToString("\n") { "<li><a href=\"Text/${it.fileName}\">${NovelText.escapeXml(it.title)}</a></li>" }
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<!DOCTYPE html>\n" +
            "<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\" xml:lang=\"$language\" lang=\"$language\">\n" +
            "<head><title>Contents</title></head>\n<body>\n<nav epub:type=\"toc\" id=\"toc\">\n<h1>Contents</h1>\n<ol>\n$items\n</ol>\n</nav>\n</body>\n</html>\n"
    }

    private fun ncx(entry: LibraryEntry, pages: List<Page>, hasCover: Boolean): String {
        val points = StringBuilder()
        var order = 1
        if (hasCover) {
            points.append(navPoint(order++, "Cover", "Text/cover.xhtml"))
        }
        if (prefs.infoPage) {
            points.append(navPoint(order++, "Information", "Text/info.xhtml"))
        }
        for (page in pages) points.append(navPoint(order++, page.title, "Text/${page.fileName}"))
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" version=\"2005-1\">\n<head>\n" +
            "<meta name=\"dtb:uid\" content=\"urn:uuid:${entry.uuid}\" />\n<meta name=\"dtb:depth\" content=\"1\" />\n" +
            "<meta name=\"dtb:totalPageCount\" content=\"0\" />\n<meta name=\"dtb:maxPageNumber\" content=\"0\" />\n</head>\n" +
            "<docTitle><text>${NovelText.escapeXml(entry.info.title)}</text></docTitle>\n<navMap>\n$points</navMap>\n</ncx>\n"
    }

    private fun navPoint(order: Int, title: String, source: String): String =
        "<navPoint id=\"navPoint$order\" playOrder=\"$order\"><navLabel><text>${NovelText.escapeXml(title)}</text></navLabel>" +
            "<content src=\"$source\" /></navPoint>\n"

    private fun opf(
        entry: LibraryEntry,
        pages: List<Page>,
        epub3: Boolean,
        language: String,
        coverExtension: String?,
        coverType: String?,
        images: Set<String>,
    ): String {
        val info = entry.info
        val metadata = StringBuilder()
        metadata.append("<dc:identifier id=\"BookId\">urn:uuid:${entry.uuid}</dc:identifier>\n")
        metadata.append("<dc:title>${NovelText.escapeXml(info.title)}</dc:title>\n")
        if (info.author.isNotBlank()) metadata.append("<dc:creator>${NovelText.escapeXml(info.author)}</dc:creator>\n")
        metadata.append("<dc:language>${NovelText.escapeXml(language)}</dc:language>\n")
        if (info.publisher.isNotBlank()) metadata.append("<dc:publisher>${NovelText.escapeXml(info.publisher)}</dc:publisher>\n")
        if (info.description.isNotBlank()) metadata.append("<dc:description>${NovelText.escapeXml(info.description)}</dc:description>\n")
        info.subjects.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach {
            metadata.append("<dc:subject>${NovelText.escapeXml(it)}</dc:subject>\n")
        }
        if (info.translator.isNotBlank()) {
            metadata.append("<dc:contributor id=\"translator\">${NovelText.escapeXml(info.translator)}</dc:contributor>\n")
            if (epub3) metadata.append("<meta refines=\"#translator\" property=\"role\" scheme=\"marc:relators\">trl</meta>\n")
        }
        if (info.sourceUrl.isNotBlank()) metadata.append("<dc:source>${NovelText.escapeXml(info.sourceUrl)}</dc:source>\n")
        metadata.append("<dc:date>${isoDate("yyyy-MM-dd")}</dc:date>\n")
        if (epub3) metadata.append("<meta property=\"dcterms:modified\">${isoDate("yyyy-MM-dd'T'HH:mm:ss'Z'")}</meta>\n")
        if (info.seriesName.isNotBlank()) {
            metadata.append("<meta name=\"calibre:series\" content=\"${NovelText.escapeXml(info.seriesName)}\" />\n")
            if (info.seriesIndex.isNotBlank()) {
                metadata.append("<meta name=\"calibre:series_index\" content=\"${NovelText.escapeXml(info.seriesIndex)}\" />\n")
            }
            if (epub3) {
                metadata.append("<meta property=\"belongs-to-collection\" id=\"series\">${NovelText.escapeXml(info.seriesName)}</meta>\n")
                metadata.append("<meta refines=\"#series\" property=\"collection-type\">series</meta>\n")
                if (info.seriesIndex.isNotBlank()) {
                    metadata.append("<meta refines=\"#series\" property=\"group-position\">${NovelText.escapeXml(info.seriesIndex)}</meta>\n")
                }
            }
        }
        if (coverExtension != null) metadata.append("<meta name=\"cover\" content=\"cover-image\" />\n")

        val manifest = StringBuilder()
        val spine = StringBuilder()
        manifest.append("<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\" />\n")
        if (epub3) manifest.append("<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\" />\n")
        manifest.append("<item id=\"css\" href=\"Styles/stylesheet.css\" media-type=\"text/css\" />\n")
        if (coverExtension != null) {
            val properties = if (epub3) " properties=\"cover-image\"" else ""
            manifest.append("<item id=\"cover-image\" href=\"Images/cover.$coverExtension\" media-type=\"$coverType\"$properties />\n")
            manifest.append("<item id=\"cover-page\" href=\"Text/cover.xhtml\" media-type=\"application/xhtml+xml\" />\n")
            spine.append("<itemref idref=\"cover-page\" />\n")
        }
        if (prefs.infoPage) {
            manifest.append("<item id=\"info\" href=\"Text/info.xhtml\" media-type=\"application/xhtml+xml\" />\n")
            spine.append("<itemref idref=\"info\" />\n")
        }
        for (page in pages) {
            manifest.append("<item id=\"${page.id}\" href=\"Text/${page.fileName}\" media-type=\"application/xhtml+xml\" />\n")
            spine.append("<itemref idref=\"${page.id}\" />\n")
        }
        images.forEachIndexed { index, name ->
            manifest.append("<item id=\"img$index\" href=\"Images/$name\" media-type=\"${NovelImages.mediaType(name.substringAfterLast('.'))}\" />\n")
        }
        val guide = if (coverExtension != null && !epub3) {
            "<guide>\n<reference type=\"cover\" title=\"Cover\" href=\"Text/cover.xhtml\" />\n</guide>\n"
        } else {
            ""
        }
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"${if (epub3) "3.0" else "2.0"}\" unique-identifier=\"BookId\">\n" +
            "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:opf=\"http://www.idpf.org/2007/opf\">\n$metadata</metadata>\n" +
            "<manifest>\n$manifest</manifest>\n<spine toc=\"ncx\">\n$spine</spine>\n$guide</package>\n"
    }

    private fun isoDate(pattern: String): String {
        val format = SimpleDateFormat(pattern, Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date())
    }

    // endregion

    private fun put(zip: ZipOutputStream, path: String, text: String) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun putFile(zip: ZipOutputStream, path: String, file: File) {
        zip.putNextEntry(ZipEntry(path))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    private companion object {
        const val CONTAINER = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">\n<rootfiles>\n" +
            "<rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\" />\n</rootfiles>\n</container>\n"
        val IMAGE_REFERENCE = Regex("\\.\\./Images/([A-Za-z0-9._-]+)")
        val HEADING_START = Regex("^\\s*<h[1-3][ >]", RegexOption.IGNORE_CASE)
        const val HEADING_LOOKAHEAD = 40
    }
}
