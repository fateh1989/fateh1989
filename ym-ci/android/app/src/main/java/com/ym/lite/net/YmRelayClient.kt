package com.ym.lite.net

import android.content.ContentResolver
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL

class YmRelayClient(private val baseUrl: String, private val token: String) {
    data class Account(val id: String, val label: String)
    data class UploadTarget(val uploadUrl: String, val mediaUrl: String)
    data class PostResult(val success: Boolean, val url: String?, val error: String?)

    private fun request(path: String, method: String = "GET", body: JSONObject? = null): String {
        val c = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 20_000
        c.readTimeout = 60_000
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Accept", "application/json")
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        c.disconnect()
        if (code !in 200..299) throw IllegalStateException("HTTP $code: $text")
        return text
    }

    fun createTikTokAuthUrl(): String {
        val text = request("/api/auth-url", "POST", JSONObject().put("platform", "tiktok"))
        return JSONObject(text).getString("url")
    }

    fun listTikTokAccounts(): List<Account> {
        val root = JSONObject(request("/api/accounts?platform=tiktok"))
        val array = root.optJSONArray("data") ?: JSONArray()
        return buildList {
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isBlank()) continue
                val username = o.optString("username")
                add(Account(id, username.takeIf { it.isNotBlank() } ?: id))
            }
        }.take(3)
    }

    fun createUploadTarget(): UploadTarget {
        val root = JSONObject(request("/api/media/upload-url", "POST", JSONObject()))
        fun find(obj: JSONObject, key: String): String? {
            obj.optString(key).takeIf { it.isNotBlank() }?.let { return it }
            val data = obj.optJSONObject("data") ?: return null
            return data.optString(key).takeIf { it.isNotBlank() }
        }
        return UploadTarget(
            uploadUrl = find(root, "upload_url") ?: error("upload_url missing"),
            mediaUrl = find(root, "media_url") ?: error("media_url missing"),
        )
    }

    fun uploadSigned(resolver: ContentResolver, uri: Uri, uploadUrl: String, mimeType: String?) {
        val c = URL(uploadUrl).openConnection() as HttpURLConnection
        c.requestMethod = "PUT"
        c.doOutput = true
        c.connectTimeout = 30_000
        c.readTimeout = 120_000
        c.setRequestProperty("Content-Type", mimeType ?: "video/mp4")
        resolver.openInputStream(uri)?.use { input ->
            BufferedInputStream(input).use { buffered -> c.outputStream.use { out -> buffered.copyTo(out, 256 * 1024) } }
        } ?: error("Cannot open selected media")
        val code = c.responseCode
        c.disconnect()
        if (code !in 200..299) throw IllegalStateException("Media upload failed: HTTP $code")
    }

    fun createScheduledPost(accountId: String, caption: String, mediaUrl: String, scheduledAtIso: String): String {
        val payload = JSONObject()
            .put("account_id", accountId)
            .put("caption", caption)
            .put("media_url", mediaUrl)
            .put("scheduled_at", scheduledAtIso)
        val root = JSONObject(request("/api/posts", "POST", payload))
        return root.optString("id").ifBlank { error("post id missing") }
    }

    fun getPostResults(postId: String): List<PostResult> {
        val root = JSONObject(request("/api/post-results?post_id=${java.net.URLEncoder.encode(postId, "UTF-8")}"))
        val rows = root.optJSONArray("data") ?: JSONArray()
        return buildList {
            for (i in 0 until rows.length()) {
                val o = rows.optJSONObject(i) ?: continue
                add(
                    PostResult(
                        success = o.optBoolean("success", false),
                        url = o.optString("url").takeIf { it.isNotBlank() && it != "null" },
                        error = o.optString("error").takeIf { it.isNotBlank() && it != "null" },
                    )
                )
            }
        }
    }
}
