package com.ym.lite.core

import android.content.Context
import android.net.Uri
import org.json.JSONArray

class LibraryStore(context: Context) {
    private val prefs = context.getSharedPreferences("ym_local", Context.MODE_PRIVATE)

    fun readUris(): MutableList<Uri> = buildList {
        val raw = prefs.getString("wheel_uris", "[]").orEmpty()
        runCatching {
            val a = JSONArray(raw)
            for (i in 0 until a.length()) {
                a.optString(i).takeIf { it.isNotBlank() }?.let { add(Uri.parse(it)) }
            }
        }
    }.toMutableList()

    fun writeUris(uris: List<Uri>) {
        val a = JSONArray()
        uris.forEach { a.put(it.toString()) }
        prefs.edit().putString("wheel_uris", a.toString()).apply()
    }

    fun appendUris(uris: List<Uri>) {
        val current = readUris()
        uris.forEach { uri -> if (current.none { it == uri }) current += uri }
        writeUris(current)
    }

    fun removeAt(index: Int) {
        val current = readUris()
        if (index in current.indices) {
            current.removeAt(index)
            writeUris(current)
        }
    }

    fun caption(): String = prefs.getString("caption", "").orEmpty()

    fun comments(): List<String> = prefs.getString("comment_pool", "").orEmpty()
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toList()

    fun accountLabel(): String = prefs.getString("selected_account_label", "@YM").orEmpty().ifBlank { "@YM" }

    fun feedIndex(): Int = prefs.getInt("feed_index", 0).coerceAtLeast(0)
    fun setFeedIndex(index: Int) = prefs.edit().putInt("feed_index", index.coerceAtLeast(0)).apply()
}
