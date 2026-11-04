package com.omerek.officeviewer.data

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

data class RecentFile(val uri: Uri, val name: String, val openedAt: Long, val size: Long)

/** A small MRU list kept in SharedPreferences. Only URIs we hold a persistable grant for are stored. */
class RecentFiles(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("recent_files", Context.MODE_PRIVATE)

    fun load(): List<RecentFile> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                RecentFile(Uri.parse(o.getString("uri")), o.getString("name"), o.getLong("at"), o.optLong("size", -1))
            }
        }.getOrDefault(emptyList())
    }

    fun add(file: RecentFile): List<RecentFile> {
        val list = (listOf(file) + load().filter { it.uri != file.uri }).take(MAX)
        save(list)
        return list
    }

    fun remove(uri: Uri): List<RecentFile> {
        val list = load().filter { it.uri != uri }
        save(list)
        return list
    }

    fun clear(): List<RecentFile> {
        save(emptyList())
        return emptyList()
    }

    private fun save(list: List<RecentFile>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("uri", it.uri.toString()).put("name", it.name).put("at", it.openedAt).put("size", it.size))
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private companion object {
        const val KEY = "items"
        const val MAX = 30
    }
}
