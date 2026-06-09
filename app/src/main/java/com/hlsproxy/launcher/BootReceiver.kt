package com.hlsproxy.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Автозапуск сервиса после загрузки устройства, если включён в настройках. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            if (Prefs.isAutostart(context)) {
                Prefs.setUserStopped(context, false)
                ProxyController.start(context, fromBoot = true)
            }
        }
    }
}
