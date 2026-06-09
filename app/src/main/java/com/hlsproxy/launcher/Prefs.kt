package com.hlsproxy.launcher

import android.content.Context

object Prefs {
    private const val NAME = "hlsproxy"
    const val DEFAULT_PORT = 9393
    const val MIN_PORT = 1024
    const val MAX_PORT = 65535

    private fun sp(c: Context) = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun getPort(c: Context): Int = sp(c).getInt("port", DEFAULT_PORT)
    fun setPort(c: Context, port: Int) = sp(c).edit().putInt("port", port).apply()

    fun isAutostart(c: Context): Boolean = sp(c).getBoolean("autostart", true)
    fun setAutostart(c: Context, value: Boolean) = sp(c).edit().putBoolean("autostart", value).apply()

    /** Был ли сервис остановлен пользователем вручную (тогда не поднимаем его сами при открытии). */
    fun isUserStopped(c: Context): Boolean = sp(c).getBoolean("userStopped", false)
    fun setUserStopped(c: Context, value: Boolean) = sp(c).edit().putBoolean("userStopped", value).apply()

    /** Слать тихое уведомление о статусе/RAM раз в день. По умолчанию выключено. */
    fun isNotifyStatus(c: Context): Boolean = sp(c).getBoolean("notifyStatus", false)
    fun setNotifyStatus(c: Context, value: Boolean) = sp(c).edit().putBoolean("notifyStatus", value).apply()

    fun getLastDailyNotif(c: Context): Long = sp(c).getLong("lastDailyNotif", 0L)
    fun setLastDailyNotif(c: Context, value: Long) = sp(c).edit().putLong("lastDailyNotif", value).apply()

    /**
     * Метка установки (nativeLibraryDir), для которой ассеты (default.json/плагины)
     * уже распакованы. Меняется при обновлении/переустановке — тогда же меняется
     * путь к ffmpeg, поэтому default.json надо освежить.
     */
    fun getAssetsStamp(c: Context): String? = sp(c).getString("assetsStamp", null)
    fun setAssetsStamp(c: Context, value: String) = sp(c).edit().putString("assetsStamp", value).apply()
}
