package com.hlsproxy.launcher

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Удобные обёртки для управления сервисом из UI и ресивера. */
object ProxyController {
    fun start(c: Context) = send(c, ProxyService.ACTION_START)
    fun stop(c: Context) = send(c, ProxyService.ACTION_STOP)
    fun restart(c: Context) = send(c, ProxyService.ACTION_RESTART)

    private fun send(c: Context, action: String) {
        val i = Intent(c, ProxyService::class.java).setAction(action)
        ContextCompat.startForegroundService(c, i)
    }
}
