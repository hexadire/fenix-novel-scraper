/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.novel

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mozilla.components.concept.fetch.Client
import mozilla.components.concept.fetch.MutableHeaders
import mozilla.components.concept.fetch.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.mozilla.fenix.ext.components
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/** An answer that is not a web page. */
class RawAnswer(val bytes: ByteArray, val contentType: String, val finalUrl: String)

/**
 * Reads addresses over the network of the browser itself (the same stack and the same cookies as the tabs), so a
 * site that was opened in the browser first, for example to pass a "checking your browser" page, can be read.
 */
class NovelFetcher(context: Context, private val userAgent: String? = null) {
    private val client: Client = context.applicationContext.components.core.client

    /** Reads a web page. */
    suspend fun page(url: String, headers: Map<String, String> = emptyMap()): Document = withContext(Dispatchers.IO) {
        val answer = request(url, "GET", headers, null)
        val charset = charsetOf(answer.contentType)
        val document = try {
            Jsoup.parse(answer.bytes.inputStream(), charset, answer.finalUrl)
        } catch (e: IOException) {
            throw NovelException("The page could not be read: ${e.message}", e)
        }
        document
    }

    /** Reads an answer that is data, for example JSON or an image. */
    suspend fun raw(
        url: String,
        headers: Map<String, String> = emptyMap(),
        method: String = "GET",
        body: String? = null,
    ): RawAnswer = withContext(Dispatchers.IO) { request(url, method, headers, body) }

    private fun request(url: String, method: String, extra: Map<String, String>, body: String?): RawAnswer {
        val headers = MutableHeaders()
        headers.append("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,image/*;q=0.8,*/*;q=0.5")
        headers.append("Accept-Language", "en-US,en;q=0.8")
        if (!userAgent.isNullOrBlank()) headers.set("User-Agent", userAgent)
        for ((name, value) in extra) headers.set(name, value)
        val request = Request(
            url = url,
            method = if (method == "POST") Request.Method.POST else Request.Method.GET,
            headers = headers,
            connectTimeout = Pair(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            readTimeout = Pair(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            body = body?.let { Request.Body.fromString(it) },
            redirect = Request.Redirect.FOLLOW,
            cookiePolicy = Request.CookiePolicy.INCLUDE,
            useCaches = false,
        )
        val response = try {
            client.fetch(request)
        } catch (e: IOException) {
            throw NovelException("The network request failed: ${e.message ?: e.javaClass.simpleName}", e)
        }
        return response.use { answer ->
            val status = answer.status
            if (status == HTTP_TOO_MANY_REQUESTS || status == HTTP_UNAVAILABLE) {
                throw RetryLaterException(answer.headers["Retry-After"]?.toLongOrNull(), status)
            }
            if (status !in HTTP_OK_RANGE) {
                val hint = if (status == HTTP_FORBIDDEN) " Open the site in the browser first (to pass a check or log in), then try again." else ""
                throw NovelException("The site answered with HTTP $status for $url.$hint")
            }
            val output = ByteArrayOutputStream()
            answer.body.useStream { stream ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    if (output.size() > MAX_BYTES) throw NovelException("The answer from $url is too large.")
                }
            }
            RawAnswer(output.toByteArray(), answer.headers["Content-Type"].orEmpty(), answer.url)
        }
    }

    private fun charsetOf(contentType: String): String? {
        val match = Regex("charset=([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE).find(contentType)
        return match?.groupValues?.get(1)
    }

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 20L
        const val READ_TIMEOUT_SECONDS = 40L
        const val HTTP_FORBIDDEN = 403
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_UNAVAILABLE = 503
        val HTTP_OK_RANGE = 200..299
        const val BUFFER_SIZE = 16 * 1024
        const val MAX_BYTES = 40 * 1024 * 1024
    }
}

/** The site asked to try again later (HTTP 429 or 503). */
class RetryLaterException(val retryAfterSeconds: Long?, val status: Int) :
    Exception("The site is busy (HTTP $status).")
