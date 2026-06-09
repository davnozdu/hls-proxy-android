package com.hlsproxy.launcher

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Удобные обёртки для управления сервисом из UI и ресивера. */
object ProxyController {
    fun start(c: Context, fromBoot: Boolean = false) =
        send(c, ProxyService.ACTION_START, fromBoot)
    fun stop(c: Context) = send(c, ProxyService.ACTION_STOP, false)
    fun restart(c: Context) = send(c, ProxyService.ACTION_RESTART, false)

    private fun send(c: Context, action: String, fromBoot: Boolean) {
        val i = Intent(c, ProxyService::class.java).setAction(action)
        if (fromBoot) i.putExtra(ProxyService.EXTRA_FROM_BOOT, true)
        ContextCompat.startForegroundService(c, i)
    }
}
