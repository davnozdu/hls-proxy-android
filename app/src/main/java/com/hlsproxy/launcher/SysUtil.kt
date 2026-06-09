package com.hlsproxy.launcher

import java.io.File

/** Чтение метрик процесса прокси из /proc (доступно для процессов своего UID, root не нужен). */
object SysUtil {
    /** PID процесса прокси по имени бинарника в cmdline. -1, если не найден. */
    fun findPid(binName: String): Int {
        val procDir = File("/proc")
        val entries = procDir.listFiles() ?: return -1
        for (d in entries) {
            val pid = d.name.toIntOrNull() ?: continue
            try {
                val cmd = File(d, "cmdline").readText()
                if (cmd.contains(binName)) return pid
            } catch (_: Exception) {
                // /proc может меняться на лету — пропускаем
            }
        }
        return -1
    }

    /** RSS процесса в килобайтах из /proc/<pid>/status. -1 при ошибке. */
    fun readRssKb(pid: Int): Long {
        if (pid <= 0) return -1
        return try {
            File("/proc/$pid/status").useLines { lines ->
                lines.firstOrNull { it.startsWith("VmRSS:") }
                    ?.filter { it.isDigit() }
                    ?.toLongOrNull() ?: -1
            }
        } catch (_: Exception) {
            -1
        }
    }
}
