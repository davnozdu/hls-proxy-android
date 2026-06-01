package com.hlsproxy.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Быстрая настройка из приложения: пишет URL плейлиста и EPG прямо в local.json,
 * который hls-proxy накладывает поверх default.json. Прочие настройки правятся в веб-интерфейсе
 * и здесь не затрагиваются (read-modify-write).
 */
object ConfigStore {

    private fun workDir(c: Context) = File(c.filesDir, "hls").apply { mkdirs() }
    private fun localJson(c: Context) = File(workDir(c), "local.json")

    /** Текущие (URL плейлиста, URL EPG). Пустые строки, если не заданы. */
    fun read(c: Context): Pair<String, String> {
        return try {
            val f = localJson(c)
            if (!f.exists()) return "" to ""
            val o = JSONObject(f.readText())
            val uri = o.optJSONArray("playlists")?.optJSONObject(0)?.optString("uri") ?: ""
            val epgArr = o.optJSONObject("epg")?.optJSONArray("tvGuideUrl")
            val epg = if (epgArr != null && epgArr.length() > 0) epgArr.optString(0) else ""
            uri to epg
        } catch (e: Exception) {
            "" to ""
        }
    }

    /** Сохраняет плейлист и EPG, сохраняя остальные ключи. Возвращает true, если URL плейлиста изменился. */
    fun save(c: Context, playlistUri: String, epgUrl: String): Boolean {
        val f = localJson(c)
        val o = try {
            if (f.exists()) JSONObject(f.readText()) else JSONObject()
        } catch (e: Exception) {
            JSONObject()
        }

        // playlists[0] — изменяем на месте, остальные плейлисты (если есть) не трогаем
        val arr = o.optJSONArray("playlists") ?: JSONArray()
        val pl = if (arr.length() > 0) arr.getJSONObject(0) else JSONObject()
        val oldUri = pl.optString("uri", "")
        if (pl.optString("name", "").isEmpty()) pl.put("name", "IPTV")
        pl.put("type", "link")
        pl.put("uri", playlistUri.trim())
        if (pl.optString("localCopy", "").isEmpty()) pl.put("localCopy", "./iptv.m3u8")
        if (!pl.has("useLogos")) pl.put("useLogos", true)
        pl.put("isEnabled", true)
        if (arr.length() == 0) arr.put(pl)
        o.put("playlists", arr)

        // epg.tvGuideUrl
        if (epgUrl.isBlank()) {
            o.remove("epg")
        } else {
            val epg = o.optJSONObject("epg") ?: JSONObject()
            epg.put("tvGuideUrl", JSONArray().put(epgUrl.trim()))
            o.put("epg", epg)
        }

        f.writeText(o.toString(2))

        val changed = oldUri != playlistUri.trim()
        if (changed) {
            // сбросить кэш плейлиста, чтобы подтянулся новый
            File(workDir(c), "iptv.m3u8").delete()
        }
        return changed
    }
}
