package com.grabit

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

private class StopException : Exception()

private class HttpError(val code: Int) : IOException("HTTP $code")

object Downloader {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    fun saveDir(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "GrabIt")

    fun deletePart(item: Item) {
        if (item.name.isNotEmpty()) {
            try { File(saveDir(), item.name + ".part").delete() } catch (_: Exception) {}
        }
    }

    private fun finishStopped(item: Item) {
        item.speed = 0
        if (item.cancel) {
            deletePart(item)
            item.status = Status.CANCELLED
        } else {
            item.status = Status.PAUSED
        }
    }

    fun run(ctx: Context, item: Item) {
        item.status = Status.DOWNLOADING
        item.error = ""
        var attempts = 0
        while (true) {
            try {
                fetch(ctx, item)
                item.speed = 0
                item.error = ""
                item.status = Status.DONE
                return
            } catch (e: StopException) {
                finishStopped(item)
                return
            } catch (e: Exception) {
                attempts++
                val fatal = e is HttpError && e.code in 400..499 && e.code != 408 && e.code != 429
                if (fatal || attempts > 10) {
                    item.error = e.message ?: "Download failed"
                    item.speed = 0
                    item.status = Status.FAILED
                    return
                }
                item.error = "Retrying ($attempts): " + (e.message ?: "network error")
                var waited = 0
                val limit = minOf(3000 * attempts, 15000)
                while (waited < limit && !item.pause && !item.cancel) {
                    try { Thread.sleep(250) } catch (_: InterruptedException) { item.pause = true }
                    waited += 250
                }
                if (item.pause || item.cancel) {
                    finishStopped(item)
                    return
                }
            }
        }
    }

    private fun fetch(ctx: Context, item: Item) {
        if (item.pause || item.cancel) throw StopException()
        val dir = saveDir()
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException("Cannot create Download/GrabIt (allow file access)")
        }
        if (item.name.isEmpty()) {
            val c = open(item.url, 0)
            try {
                item.name = pickName(c, item.url)
            } finally {
                c.disconnect()
            }
        }
        val part = File(dir, item.name + ".part")
        var offset = if (part.exists()) part.length() else 0L
        val conn = open(item.url, offset)
        try {
            val code = conn.responseCode
            if (code == 416 && offset > 0) {
                val cr = conn.getHeaderField("Content-Range")
                val m = if (cr != null) Regex("/(\\d+)\\s*$").find(cr) else null
                if (m != null && m.groupValues[1].toLong() == offset) {
                    item.total = offset
                    item.done = offset
                    finish(ctx, item, part)
                    return
                }
                part.delete()
                throw IOException("Restarting partial file")
            }
            if (code != 200 && code != 206) throw HttpError(code)
            var append = true
            if (code == 200) {
                offset = 0
                append = false
            }
            item.total = totalFrom(conn, code, offset)
            item.done = offset
            val buf = ByteArray(256 * 1024)
            conn.inputStream.use { input ->
                FileOutputStream(part, append).use { out ->
                    var lastT = System.currentTimeMillis()
                    var lastB = item.done
                    while (true) {
                        if (item.pause || item.cancel) throw StopException()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        item.done += n
                        val now = System.currentTimeMillis()
                        if (now - lastT >= 1000) {
                            item.speed = (item.done - lastB) * 1000 / (now - lastT)
                            lastT = now
                            lastB = item.done
                        }
                    }
                }
            }
            if (item.total > 0 && part.length() != item.total) {
                throw IOException("Connection dropped at ${part.length()} of ${item.total} bytes")
            }
            finish(ctx, item, part)
        } finally {
            conn.disconnect()
        }
    }

    private fun totalFrom(c: HttpURLConnection, code: Int, offset: Long): Long {
        if (code == 206) {
            val cr = c.getHeaderField("Content-Range")
            val m = if (cr != null) Regex("/(\\d+)\\s*$").find(cr) else null
            if (m != null) return m.groupValues[1].toLong()
            val len = c.contentLengthLong
            return if (len >= 0) len + offset else -1
        }
        return c.contentLengthLong
    }

    private fun open(start: String, offset: Long): HttpURLConnection {
        var u = URL(start)
        var hops = 0
        while (true) {
            val c = u.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept", "*/*")
            c.setRequestProperty("Accept-Encoding", "identity")
            if (offset > 0) c.setRequestProperty("Range", "bytes=$offset-")
            val code = c.responseCode
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                hops++
                if (loc == null || hops > 8) throw IOException("Too many redirects")
                u = URL(u, loc)
                continue
            }
            return c
        }
    }

    private fun pickName(c: HttpURLConnection, url: String): String {
        var name: String? = null
        val cd = c.getHeaderField("Content-Disposition")
        if (cd != null) {
            val m = Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(cd)
            if (m != null) {
                val raw = m.groupValues[1].trim()
                name = try { URLDecoder.decode(raw, "UTF-8") } catch (_: Exception) { raw }
            }
        }
        if (name.isNullOrBlank()) {
            val seg = c.url.path.substringAfterLast('/')
            name = try { URLDecoder.decode(seg, "UTF-8") } catch (_: Exception) { seg }
        }
        var n: String = name ?: ""
        n = n.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().trim('.')
        if (n.isEmpty()) n = "download"
        if (n.length > 150) n = n.takeLast(150)
        if (!n.contains('.')) {
            val type = (c.contentType ?: "").substringBefore(';').trim().lowercase()
            val ext = when (type) {
                "video/mp4" -> ".mp4"
                "video/webm" -> ".webm"
                "video/x-matroska" -> ".mkv"
                "audio/mpeg" -> ".mp3"
                "application/zip" -> ".zip"
                "application/pdf" -> ".pdf"
                "image/jpeg" -> ".jpg"
                "image/png" -> ".png"
                "application/vnd.android.package-archive" -> ".apk"
                else -> ""
            }
            n += ext
        }
        return n
    }

    private fun finish(ctx: Context, item: Item, part: File) {
        val dir = part.parentFile ?: saveDir()
        val base = item.name.substringBeforeLast('.', item.name)
        val ext = if (item.name.contains('.')) "." + item.name.substringAfterLast('.') else ""
        var target = File(dir, item.name)
        var i = 1
        while (target.exists()) {
            target = File(dir, "$base ($i)$ext")
            i++
        }
        if (!part.renameTo(target)) throw IOException("Could not save the file")
        item.savedAs = target.name
        item.done = target.length()
        try {
            MediaScannerConnection.scanFile(ctx.applicationContext, arrayOf(target.absolutePath), null, null)
        } catch (_: Exception) {}
    }
}
