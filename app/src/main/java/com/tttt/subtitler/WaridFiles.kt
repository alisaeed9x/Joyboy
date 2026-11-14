package com.tttt.subtitler

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * (v998 — وارد) رفع الصوت لـ Gemini Files API، للقطع الكبيرة اللي الـ base64 بتاعها ≥ 5MB.
 * القطع الصغيرة بتتبعت inline زي الأول (من غير Files API خالص).
 * خطوتين (resumable upload):
 *  1) POST على هوست الرفع (مختلف عن Api.base) بأمر start → هيدر X-Goog-Upload-URL فيه رابط الجلسة.
 *  2) POST على رابط الجلسة بأمر upload, finalize والبايتات الخام (من غير base64) → file.uri.
 * الملف بيتمسح (DELETE) فورًا بعد ما طلب الـ generateContent يخلص — نجاح أو فشل نهائي.
 * فشل الـ finalize بيعيد الرفع من الصفر في المحاولة اللي بعدها (مقبول لقطع 5 دقايق).
 */
object WaridFiles {
    const val UPLOAD_BASE = "https://upload-generativelanguage.googleapis.com/upload/v1beta/files"
    const val INLINE_BASE64_LIMIT = 5L * 1024L * 1024L

    class Uploaded(val uri: String, val name: String)

    fun base64Len(rawBytes: Int): Long = ((rawBytes + 2) / 3).toLong() * 4

    fun needsFiles(rawBytes: Int): Boolean = base64Len(rawBytes) >= INLINE_BASE64_LIMIT

    private fun resolve(base: String, loc: String): String = try {
        URL(URL(base), loc).toString()
    } catch (_: Exception) {
        loc
    }

    private fun errText(c: HttpURLConnection): String {
        val txt = try {
            (c.errorStream ?: c.inputStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        } catch (_: Throwable) {
            ""
        }
        if (Api.looksHtml(txt)) return Api.htmlMsg(c.responseCode, txt)
        return try {
            JSONObject(txt).getJSONObject("error").getString("message")
        } catch (_: Exception) {
            txt.ifBlank { "HTTP ${c.responseCode}" }.take(160)
        }
    }

    /**
     * POST مع متابعة تحويلات 3xx يدويًا (instanceFollowRedirects = false — نفس شكل ResumableDl.conn).
     * الـ write بيتعاد من الأول في كل hop (البايتات في الذاكرة).
     */
    private fun postFollow(url0: String, headers: Map<String, String>, body: ByteArray, writeBody: (java.io.OutputStream) -> Unit): HttpURLConnection {
        var url = url0
        for (hop in 0..5) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 20000
            c.readTimeout = 120000
            for ((k, v) in headers) c.setRequestProperty(k, v)
            c.setFixedLengthStreamingMode(body.size)
            try {
                c.outputStream.use { writeBody(it) }
            } catch (e: Throwable) {
                try {
                    c.disconnect()
                } catch (_: Throwable) {
                }
                throw e
            }
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location")
                try {
                    c.disconnect()
                } catch (_: Throwable) {
                }
                if (loc.isNullOrBlank()) throw IOException("Files: redirect من غير Location")
                url = resolve(url, loc)
                continue
            }
            return c
        }
        throw IOException("Files: تحويلات كتير")
    }

    /** رفع بايتات WAV خام — بيرجّع الـ uri والـ name (files/xxx). بيرمي IOException/ApiErr لو فشل. */
    fun upload(key: String, bytes: ByteArray, mime: String, displayName: String): Uploaded {
        val startBody = ("{\"file\":{\"display_name\":" + JSONObject.quote(displayName) + "}}").toByteArray(Charsets.UTF_8)
        val startHeaders = mapOf(
            "x-goog-api-key" to key,
            "X-Goog-Upload-Protocol" to "resumable",
            "X-Goog-Upload-Command" to "start",
            "X-Goog-Upload-Header-Content-Length" to bytes.size.toString(),
            "X-Goog-Upload-Header-Content-Type" to mime,
            "Content-Type" to "application/json"
        )
        val session = postFollow(UPLOAD_BASE, startHeaders, startBody) { os -> os.write(startBody) }
        val sessionUrl: String
        try {
            val code = session.responseCode
            if (code >= 300) throw ApiErr(code, errText(session))
            sessionUrl = session.getHeaderField("X-Goog-Upload-URL") ?: ""
            if (sessionUrl.isBlank()) throw IOException("Files: السيرفر مرجّعش X-Goog-Upload-URL")
        } finally {
            try {
                session.disconnect()
            } catch (_: Throwable) {
            }
        }
        val upHeaders = mapOf(
            "x-goog-api-key" to key,
            "X-Goog-Upload-Command" to "upload, finalize",
            "Content-Length" to bytes.size.toString(),
            "Content-Type" to mime
        )
        val done = postFollow(sessionUrl, upHeaders, bytes) { os ->
            var off = 0
            while (off < bytes.size) {
                val n = minOf(65536, bytes.size - off)
                os.write(bytes, off, n)
                off += n
            }
        }
        try {
            val code = done.responseCode
            val txt = try {
                (if (code < 300) done.inputStream else done.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            } catch (_: Throwable) {
                ""
            }
            if (code >= 300) throw ApiErr(code, errText(done))
            val fileObj = try {
                JSONObject(txt).optJSONObject("file")
            } catch (_: Throwable) {
                null
            }
            val uri = fileObj?.optString("uri", "") ?: ""
            val name = fileObj?.optString("name", "") ?: ""
            val finalUri = uri.ifBlank {
                try {
                    JSONObject(txt).optString("uri", "")
                } catch (_: Throwable) {
                    ""
                }
            }
            if (finalUri.isBlank()) throw IOException("Files: الرفع خلص بس مرجّعش file.uri")
            val finalName = name.ifBlank { finalUri.substringAfter("/v1beta/", "") }
            return Uploaded(finalUri, finalName)
        } finally {
            try {
                done.disconnect()
            } catch (_: Throwable) {
            }
        }
    }

    /** مسح ملف من Files API — best-effort: الفشل بيتسجّل في اللوج بس، مبيوقفش التفريغ. */
    fun delete(key: String, nameOrUri: String) {
        try {
            val name = nameOrUri.substringAfter("/v1beta/", nameOrUri).trimStart('/')
            if (name.isBlank()) return
            val c = URL(Api.base + "/" + name).openConnection() as HttpURLConnection
            try {
                c.requestMethod = "DELETE"
                c.connectTimeout = 20000
                c.readTimeout = 30000
                c.setRequestProperty("x-goog-api-key", key)
                val code = c.responseCode
                if (code >= 300) LogStore.err("WaridFiles:delete", IOException("HTTP $code on $name"))
            } finally {
                try {
                    c.disconnect()
                } catch (_: Throwable) {
                }
            }
        } catch (e: Throwable) {
            LogStore.err("WaridFiles:delete", e)
        }
    }
}
