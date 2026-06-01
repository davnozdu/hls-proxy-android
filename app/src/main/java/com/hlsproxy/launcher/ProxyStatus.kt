package com.hlsproxy.launcher

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Глобальное состояние прокси. Живёт в том же процессе, что и сервис и активити,
 * поэтому они видят одно и то же состояние без биндинга.
 */
object ProxyStatus {
    enum class State { STOPPED, RUNNING }

    /** Метрики работающего процесса. */
    data class Stats(val ramKb: Long, val uptimeMs: Long, val streams: Int)

    // Журнал ограничен — хранится только последний хвост, без бесконечного роста.
    private const val MAX_LOG_LINES = 200

    private val _state = MutableStateFlow(State.STOPPED)
    val state: StateFlow<State> = _state

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log

    private val _stats = MutableStateFlow<Stats?>(null)
    val stats: StateFlow<Stats?> = _stats

    /** Время старта процесса (для аптайма). */
    @Volatile var startTimeMs: Long = 0L

    fun setState(s: State) {
        _state.value = s
    }

    fun setStats(s: Stats?) {
        _stats.value = s
    }

    fun logSnapshot(): List<String> = _log.value

    fun clearLog() {
        _log.value = emptyList()
    }

    @Synchronized
    fun appendLog(line: String) {
        val cur = ArrayList(_log.value)
        cur.add(line)
        while (cur.size > MAX_LOG_LINES) cur.removeAt(0)
        _log.value = cur
    }
}
