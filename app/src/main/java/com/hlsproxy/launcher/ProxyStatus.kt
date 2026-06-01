package com.hlsproxy.launcher

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Глобальное состояние прокси. Живёт в том же процессе, что и сервис и активити,
 * поэтому они видят одно и то же состояние без биндинга.
 */
object ProxyStatus {
    enum class State { STOPPED, RUNNING }

    private const val MAX_LOG_LINES = 300

    private val _state = MutableStateFlow(State.STOPPED)
    val state: StateFlow<State> = _state

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log

    fun setState(s: State) {
        _state.value = s
    }

    @Synchronized
    fun appendLog(line: String) {
        val cur = ArrayList(_log.value)
        cur.add(line)
        while (cur.size > MAX_LOG_LINES) cur.removeAt(0)
        _log.value = cur
    }
}
