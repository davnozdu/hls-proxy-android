package com.hlsproxy.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Быстрая настройка из приложения: пишет источник плейлиста (ссылка или локальный файл)
 * и URL EPG прямо в local.json, который hls-proxy накладывает поверх default.json.
 * Прочие настройки правятся в веб-интерфейсе и не затрагиваются (read-modify-write).
 *
 * Плейлист — это playlists[0] (один активный источник: link ИЛИ file). EPG (epg.tvGuideUrl)
 * независим и работает с любым источником. Поэтому ссылка/файл и EPG не конфликтуют.
 */
object ConfigStore {

    const val LOCAL_NAME = "local.m3u"

    private fun workDir(c: Context) = File(c.filesDir, "hls").apply { mkdirs() }
    private fun localJson(c: Context) = File(workDir(c), "local.json")
    fun localPlaylistFile(c: Context) = File(workDir(c), LOCAL_NAME)

    private fun load(c: Context): JSONObject {
        return try {
            val f = localJson(c)
            if (f.exists()) JSONObject(f.readText()) else JSONObject()
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun playlist0(o: JSONObject): JSONObject? = o.optJSONArray("playlists")?.optJSONObject(0)

    /** URL для поля (только если активна ссылка), и URL EPG. Для локального файла URL пустой. */
    fun read(c: Context): Pair<String, String> {
        val o = load(c)
        val pl = playlist0(o)
        val url = if (pl?.optString("type") == "link") pl.optString("uri") else ""
        val epgArr = o.optJSONObject("epg")?.optJSONArray("tvGuideUrl")
        val epg = if (epgArr != null && epgArr.length() > 0) epgArr.optString(0) else ""
        return url to epg
    }

    /** Тип текущего источника: "link" | "file" | "" (нет). */
    fun sourceType(c: Context): String {
        val pl = playlist0(load(c)) ?: return ""
        val t = pl.optString("type")
        if (t == "link" && pl.optString("uri").isBlank()) return ""
        return t
    }

    private fun ensurePlaylist0(o: JSONObject): JSONObject {
        val arr = o.optJSONArray("playlists") ?: JSONArray().also { o.put("playlists", it) }
        val pl = if (arr.length() > 0) arr.getJSONObject(0) else JSONObject().also { arr.put(it) }
        if (pl.optString("name", "").isEmpty()) pl.put("name", "IPTV")
        if (!pl.has("useLogos")) pl.put("useLogos", true)
        pl.put("isEnabled", true)
        return pl
    }

    private fun setEpg(o: JSONObject, epgUrl: String) {
        if (epgUrl.isBlank()) {
            o.remove("epg")
        } else {
            val epg = o.optJSONObject("epg") ?: JSONObject()
            epg.put("tvGuideUrl", JSONArray().put(epgUrl.trim()))
            o.put("epg", epg)
        }
    }

    /**
     * Сохраняет EPG всегда. Плейлист-ссылку — только если URL непустой
     * (пустой URL не трогает текущий источник, поэтому к локальному файлу можно задать EPG).
     */
    fun saveUrlAndEpg(c: Context, playlistUrl: String, epgUrl: String) {
        val o = load(c)
        val url = playlistUrl.trim()
        if (url.isNotEmpty()) {
            val pl = ensurePlaylist0(o)
            val oldUri = pl.optString("uri", "")
            val oldType = pl.optString("type", "")
            pl.put("type", "link")
            pl.put("uri", url)
            if (pl.optString("localCopy", "").isEmpty()) pl.put("localCopy", "./iptv.m3u8")
            if (oldUri != url || oldType != "link") File(workDir(c), "iptv.m3u8").delete()
        }
        setEpg(o, epgUrl)
        localJson(c).writeText(o.toString(2))
    }

    /** Переключает активный плейлист на локальный файл (он уже скопирован в local.m3u). EPG не трогается. */
    fun saveLocalFile(c: Context) {
        val o = load(c)
        val pl = ensurePlaylist0(o)
        pl.put("type", "file")
        pl.put("uri", "./$LOCAL_NAME")
        pl.put("localCopy", "")
        File(workDir(c), "iptv.m3u8").delete()
        localJson(c).writeText(o.toString(2))
    }
}
