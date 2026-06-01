package com.hlsproxy.launcher

import java.net.Inet4Address
import java.net.NetworkInterface

object NetUtil {
    /**
     * Локальный IPv4-адрес устройства в LAN. Приоритет: проводной (eth) → Wi-Fi (wlan) → прочее.
     * Возвращает null, если активного сетевого адреса нет.
     */
    fun localIp(): String? {
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
