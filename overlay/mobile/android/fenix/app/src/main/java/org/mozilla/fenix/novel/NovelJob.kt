/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.novel

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** What the screen shows about the download. */
class JobStatus(
    val running: Boolean,
    val done: Int,
    val total: Int,
    val message: String,
    /** Set when the job ended with a problem; the downloaded chapters are kept, so it can be resumed. */
    val error: String?,
    val finished: Boolean,
)

/** What to download. */
class DownloadRequest(
    val info: NovelInfo,
    val chapters: List<ChapterRef>,
    val plugin: SitePlugin?,
    /** Where the EPUB is written. */
    val output: Uri,
)

/**
 * The download of a novel: reads the chapters one by one (the ones that are in the library already are not read again),
 * then writes the EPUB. It lives in the process, not in a screen, so it goes on when the screen is closed and rotated.
 */
object NovelJob {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val handler = Handler(Looper.getMainLooper())
    private var job: Job? = null
    private val lines = ArrayList<String>()

    @Volatile
    var status: JobStatus = JobStatus(false, 0, 0, "", null, false)
        private set

    /** Called on the main thread whenever the status or the log changes. */
    @Volatile
    var listener: (() -> Unit)? = null

    fun log(): List<String> = synchronized(lines) { ArrayList(lines) }

    fun isRunning(): Boolean = status.running

    fun cancel() {
        job?.cancel()
    }

    fun start(context: Context, request: DownloadRequest) {
        if (status.running) return
        val app = context.applicationContext
        synchronized(lines) { lines.clear() }
        publish(JobStatus(true, 0, request.chapters.size, "Starting...", null, false))
        job = scope.launch {
            try {
                run(app, request)
                publish(JobStatus(false, status.total, status.total, "The EPUB was saved.", null, true))
            } catch (e: CancellationException) {
                addLog("Stopped. Downloaded chapters are kept; start again to continue.")
                publish(JobStatus(false, status.done, status.total, "Stopped.", null, true))
                throw e
            } catch (e: NovelException) {
                addLog("Problem: ${e.message}")
                publish(JobStatus(false, status.done, status.total, "Stopped.", e.message, true))
            } catch (e: IOException) {
                addLog("Problem: ${e.message}")
                publish(JobStatus(false, status.done, status.total, "Stopped.", e.message ?: "Writing the file failed.", true))
            } catch (e: Exception) {
                val text = e.javaClass.simpleName + ": " + (e.message ?: "")
                addLog("Problem: $text")
                publish(JobStatus(false, status.done, status.total, "Stopped.", text, true))
            }
        }
    }

    private suspend fun run(app: Context, request: DownloadRequest) {
        val prefs = NovelPrefs(app)
        val fetcher = NovelFetcher(app)
        val plugins = NovelPluginStore(app)
        val engine = NovelEngine(fetcher, plugins, prefs)
        val library = NovelLibrary(app)
        val plugin = request.plugin

        val entry = library.load(library.idFor(request.info.sourceUrl)) ?: library.newEntry(request.info, plugin?.name)
        entry.info = request.info.copy()
        entry.pluginName = plugin?.name
        val earlier = entry.chapters.associateBy { it.url }
        entry.chapters.clear()
        for (chapter in request.chapters) {
            val kept = earlier[chapter.url]?.file?.takeIf { File(library.chaptersDirectory(entry.id), it).isFile }
            entry.chapters.add(StoredChapter(chapter.url, chapter.title, kept))
        }
        withContext(Dispatchers.IO) { library.save(entry) }

        val images = NovelImages(library.imagesDirectory(entry.id), fetcher, NovelPrefs(app))
        loadCover(entry, images)

        val total = entry.chapters.size
        val pending = entry.chapters.count { it.file == null }
        addLog("$total chapters selected, $pending to download.")
        for ((index, stored) in entry.chapters.withIndex()) {
            currentCoroutineContext().ensureActive()
            publish(JobStatus(true, index, total, "Chapter ${index + 1} of $total: ${stored.title}", null, false))
            if (stored.file != null) continue
            val adjacent = setOfNotNull(entry.chapters.getOrNull(index - 1)?.url, entry.chapters.getOrNull(index + 1)?.url)
            try {
                val result = retrying { engine.readChapter(plugin, ChapterRef(stored.url, stored.title), adjacent) }
                for ((element, address) in result.prepared.images) {
                    val name = images.load(address, stored.url)
                    if (name != null) element.attr("src", "../Images/$name") else element.remove()
                }
                val name = NovelImages.hash(stored.url) + ".xhtml"
                withContext(Dispatchers.IO) {
                    val folder = library.chaptersDirectory(entry.id)
                    if (!folder.isDirectory) folder.mkdirs()
                    File(folder, name).writeText(result.prepared.xhtml(), Charsets.UTF_8)
                }
                if (stored.title.isBlank()) stored.title = result.title
                stored.file = name
            } catch (e: NovelException) {
                if (!prefs.skipFailedChapters) {
                    withContext(Dispatchers.IO) { library.save(entry) }
                    throw NovelException("Chapter ${index + 1} (${stored.title}) failed: ${e.message}", e)
                }
                addLog("Skipped chapter ${index + 1}: ${e.message}")
            }
            if ((index + 1) % SAVE_EVERY == 0) withContext(Dispatchers.IO) { library.save(entry) }
            delay(engine.delayFor(plugin))
        }

        publish(JobStatus(true, total, total, "Building the EPUB...", null, false))
        entry.updatedAt = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            library.save(entry)
            val temporary = File(app.cacheDir, "novel-${entry.id}.epub")
            try {
                FileOutputStream(temporary).use { EpubWriter(library, prefs).write(entry, it) }
                val output = app.contentResolver.openOutputStream(request.output, "wt")
                    ?: throw NovelException("The file could not be opened for writing.")
                output.use { stream -> temporary.inputStream().use { it.copyTo(stream) } }
            } finally {
                temporary.delete()
            }
            if (!prefs.keepInLibrary) library.delete(entry.id)
        }
        addLog("Done: ${entry.downloadedCount()} chapters in the EPUB.")
    }

    private suspend fun loadCover(entry: LibraryEntry, images: NovelImages) {
        val address = entry.info.coverUrl
        if (address.isBlank() || !address.startsWith("http")) {
            entry.coverFile = null
            return
        }
        entry.coverFile = images.load(address, entry.info.sourceUrl)
        if (entry.coverFile == null) addLog("The cover image could not be loaded; the EPUB has no cover.")
    }

    private suspend fun <T> retrying(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: RetryLaterException) {
                attempt++
                if (attempt > MAX_ATTEMPTS) throw NovelException("The site keeps answering that it is busy (HTTP ${e.status}).", e)
                val seconds = (e.retryAfterSeconds ?: (attempt * BUSY_STEP_SECONDS)).coerceIn(1L, MAX_WAIT_SECONDS)
                addLog("The site is busy; waiting $seconds seconds.")
                delay(seconds * 1000L)
            } catch (e: NovelException) {
                attempt++
                if (attempt >= MAX_ATTEMPTS) throw e
                delay(RETRY_PAUSE_MS * attempt)
            }
        }
    }

    private fun addLog(line: String) {
        synchronized(lines) {
            lines.add(line)
            while (lines.size > MAX_LOG_LINES) lines.removeAt(0)
        }
        handler.post { listener?.invoke() }
    }

    private fun publish(newStatus: JobStatus) {
        status = newStatus
        handler.post { listener?.invoke() }
    }

    private const val SAVE_EVERY = 10
    private const val MAX_ATTEMPTS = 3
    private const val BUSY_STEP_SECONDS = 10L
    private const val MAX_WAIT_SECONDS = 120L
    private const val RETRY_PAUSE_MS = 1500L
    private const val MAX_LOG_LINES = 200
}
