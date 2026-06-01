package com.hlsproxy.launcher

import android.content.Context
import android.net.ConnectivityManager
import java.net.Inet4Address
import java.net.NetworkInterface

object NetUtil {
    /**
     * Локальный IPv4-адрес устройства в LAN.
     *
     * Сначала пробуем ConnectivityManager (разрешённый для приложений API),
     * затем — перечисление интерфейсов (может не работать на Android 11+).
     * Возвращает null, если адрес определить не удалось.
     */
    fun localIp(context: Context): String? {
        viaConnectivityManager(context)?.let { return it }
        return viaNetworkInterfaces()
    }

    private fun viaConnectivityManager(context: Context): String? {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return null
            val lp = cm.getLinkProperties(network) ?: return null
            lp.linkAddresses
                .map { it.address }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
                ?.hostAddress
        } catch (e: Exception) {
            null
        }
    }

    private fun viaNetworkInterfaces(): String? {
        return try {
            val candidates = ArrayList<Pair<String, String>>()
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (addr in ni.inetAddresses) {
                    if (addr.isLoopbackAddress) continue
                    if (addr is Inet4Address) {
                        addr.hostAddress?.let { candidates.add(ni.name to it) }
                    }
                }
            }
            candidates.minByOrNull {
                when {
                    it.first.startsWith("eth") -> 0
                    it.first.startsWith("wlan") -> 1
                    else -> 2
                }
            }?.second
        } catch (e: Exception) {
            null
        }
    }
}
