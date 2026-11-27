/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.novel

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Downloads the images of a novel into a folder, once each, and shrinks them when the person asked for it. */
class NovelImages(
    private val directory: File,
    private val fetcher: NovelFetcher,
    private val prefs: NovelPrefs,
) {
    /**
     * Returns the name of the file the image is kept in (inside the folder), or null when it could not be loaded.
     * An image that was loaded before is not loaded again.
     */
    suspend fun load(url: String, referer: String?): String? {
        val key = hash(url)
        directory.listFiles()?.firstOrNull { it.name.startsWith("$key.") }?.let { return it.name }
        return try {
            val headers = if (referer != null) mapOf("Referer" to referer) else emptyMap()
            val answer = fetcher.raw(url, headers)
            var bytes = answer.bytes
            var type = sniff(bytes) ?: return null
            if (prefs.compressImages && type != "gif" && type != "svg") {
                shrink(bytes)?.let { (smaller, newType) ->
                    bytes = smaller
                    type = newType
                }
            }
            if (!directory.isDirectory && !directory.mkdirs()) return null
            val name = "$key.$type"
            File(directory, name).writeBytes(bytes)
            name
        } catch (e: NovelException) {
            null
        } catch (e: RetryLaterException) {
            null
        } catch (e: IOException) {
            null
        }
    }

    private fun shrink(bytes: ByteArray): Pair<ByteArray, String>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        val limit = prefs.maxImageSize
        val options = BitmapFactory.Options()
        var sample = 1
        while (longest / (sample * 2) >= limit) sample *= 2
        options.inSampleSize = sample
        var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val side = maxOf(bitmap.width, bitmap.height)
        if (side > limit) {
            val scale = limit.toFloat() / side
            val scaled = Bitmap.createScaledBitmap(
                bitmap,
                maxOf(1, (bitmap.width * scale).toInt()),
                maxOf(1, (bitmap.height * scale).toInt()),
                true,
            )
            if (scaled !== bitmap) bitmap.recycle()
            bitmap = scaled
        }
        val format = prefs.imageFormat
        if (format == "jpg" && bitmap.hasAlpha()) {
            val flat = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(flat)
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(bitmap, 0f, 0f, null)
            bitmap.recycle()
            bitmap = flat
        }
        val output = ByteArrayOutputStream()
        val compressFormat = when (format) {
            "png" -> Bitmap.CompressFormat.PNG
            "webp" -> webp()
            else -> Bitmap.CompressFormat.JPEG
        }
        bitmap.compress(compressFormat, QUALITY, output)
        bitmap.recycle()
        return output.toByteArray() to (if (format == "png" || format == "webp") format else "jpg")
    }

    @Suppress("DEPRECATION")
    private fun webp(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP

    companion object {
        private const val QUALITY = 85

        fun hash(text: String): String =
            MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(16)

        /** The kind of image in [bytes] by its first bytes: "jpg", "png", "gif", "webp" or "svg"; null for anything else. */
        fun sniff(bytes: ByteArray): String? {
            if (bytes.size < MIN_SNIFF) return null
            fun at(index: Int) = bytes[index].toInt() and 0xFF
            return when {
                at(0) == 0xFF && at(1) == 0xD8 -> "jpg"
                at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 -> "png"
                at(0) == 'G'.code && at(1) == 'I'.code && at(2) == 'F'.code -> "gif"
                at(0) == 'R'.code && at(1) == 'I'.code && at(2) == 'F'.code && at(3) == 'F'.code &&
                    at(8) == 'W'.code && at(9) == 'E'.code && at(10) == 'B'.code && at(11) == 'P'.code -> "webp"
                String(bytes, 0, minOf(bytes.size, SVG_PREFIX), Charsets.UTF_8).contains("<svg") -> "svg"
                else -> null
            }
        }

        fun mediaType(extension: String): String = when (extension) {
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            else -> "image/jpeg"
        }

        private const val MIN_SNIFF = 12
        private const val SVG_PREFIX = 512
    }
}
