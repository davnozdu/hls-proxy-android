package com.hlsproxy.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Foreground-сервис: запускает встроенный бинарник hls-proxy и держит его живым.
 * Бинарник лежит в nativeLibraryDir (единственное место, откуда Android разрешает
 * исполнение), а рабочие файлы (конфиг, кэш) — в filesDir/hls.
 */
class ProxyService : Service() {

    companion object {
        const val ACTION_START = "com.hlsproxy.launcher.START"
        const val ACTION_STOP = "com.hlsproxy.launcher.STOP"
        const val ACTION_RESTART = "com.hlsproxy.launcher.RESTART"

        private const val CHANNEL_ID = "hls_proxy"
        private const val NOTIF_ID = 1
        private const val BIN_NAME = "libhlsproxy.so"
        private const val WORK_DIR = "hls"
        // resolv.conf с публичными DNS — кладётся в рабочий каталог, т.к. бинарник
        // пропатчен на относительный путь "resolv.conf" (на Android нет
        // /etc/resolv.conf, без него getaddrinfo падает с EAI_AGAIN).
        private const val RESOLV_CONTENT = "nameserver 8.8.8.8\nnameserver 8.8.4.4\nnameserver 1.1.1.1\n"

        // Защита от crash-loop: не более стольких автоперезапусков в окне.
        private const val RESTART_WINDOW_MS = 120_000L
        private const val MAX_RESTARTS_IN_WINDOW = 5
        private const val RESTART_DELAY_MS = 5_000L
        private const val STATS_INTERVAL_MS = 4_000L
        private const val HEALTH_GRACE_MS = 15_000L
        private const val HEALTH_INTERVAL_MS = 30_000L
        private const val HEALTH_MAX_FAILS = 2
        private const val FAILED_NOTIF_ID = 2
        private const val STATUS_NOTIF_ID = 3
        private const val DAILY_NOTIF_MS = 24 * 60 * 60 * 1000L

        // Ожидание готовности сети перед стартом (актуально после перезагрузки:
        // сеть на ТВ-боксах поднимается с задержкой, и плейлист иначе не качается).
        private const val NET_POLL_MS = 3_000L          // как часто перепроверять сеть
        private const val NET_WAIT_MAX_MS = 120_000L    // через сколько стартовать всё равно
    }

    private val handler = Handler(Looper.getMainLooper())
    private val control = Executors.newSingleThreadExecutor()
    private var process: Process? = null
    private var readerThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile private var userStopped = false
    private val restartTimestamps = ArrayDeque<Long>()
    private var pendingRestart: Runnable? = null
    @Volatile private var waitingForNet = false
    private var netWaitStartMs = 0L

    @Volatile private var lastBoundIp: String? = null
    private var healthFails = 0
    private var lastHealthMs = 0L
    private var netCallbackRegistered = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground обязателен в течение 5 с после startForegroundService — делаем синхронно.
        startForegroundNow()
        when (intent?.action) {
            ACTION_STOP -> {
                control.execute {
                    stopProxy(userInitiated = true)
                    handler.post {
                        stopForegroundCompat()
                        stopSelf()
                    }
                }
                return START_NOT_STICKY
            }
            ACTION_RESTART -> control.execute {
                stopProxy(userInitiated = false)
                startProxy()
            }
            else -> control.execute { requestStart() } // ACTION_START или повторная доставка
        }
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterNetworkCallback()
        control.execute { stopProxy(userInitiated = true) }
        control.shutdown()
        super.onDestroy()
    }

    // ---- Запуск / остановка процесса ----

    /**
     * Запрос на старт: не поднимаем сервер, пока сеть реально не готова
     * (есть интернет). После перезагрузки приставки сеть инициализируется
     * с задержкой — если стартовать раньше, hls-proxy не скачивает плейлист
     * и больше не пытается, пока его не перезапустишь вручную. Поэтому ждём
     * подтверждения сети (с периодическими проверками и таймаут-фолбэком).
     */
    @Synchronized
    private fun requestStart() {
        if (process?.isAlive == true) {
            updateNotification()
            return
        }
        userStopped = false
        if (isNetworkReady()) {
            waitingForNet = false
            handler.removeCallbacks(netStartPoll)
            startProxy()
            return
        }
        if (!waitingForNet) {
            waitingForNet = true
            netWaitStartMs = System.currentTimeMillis()
            ProxyStatus.appendLog("Ожидание готовности сети перед запуском…")
            ProxyStatus.setState(ProxyStatus.State.STOPPED)
        } else if (System.currentTimeMillis() - netWaitStartMs > NET_WAIT_MAX_MS) {
            // Сеть так и не подтвердилась — стартуем всё равно, чтобы не висеть вечно.
            waitingForNet = false
            handler.removeCallbacks(netStartPoll)
            ProxyStatus.appendLog("Сеть не подтвердилась за ${NET_WAIT_MAX_MS / 1000} с — запуск всё равно")
            startProxy()
            return
        }
        handler.removeCallbacks(netStartPoll)
        handler.postDelayed(netStartPoll, NET_POLL_MS)
    }

    private val netStartPoll = Runnable {
        if (!userStopped) control.execute { requestStart() }
    }

    /** Готова ли сеть: есть интернет-сеть, подтверждённая системой или реальным коннектом. */
    private fun isNetworkReady(): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val n = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(n) ?: return false
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
            // Быстрый путь: система сама проверила, что интернет работает.
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return true
            // Capability ещё не подтверждена — пробуем реально достучаться до DNS-сервера
            // по IP (без резолва), это надёжно подтверждает наличие интернета.
            canReach("8.8.8.8", 53) || canReach("1.1.1.1", 53)
        } catch (_: Exception) {
            false
        }
    }

    private fun canReach(host: String, port: Int): Boolean = try {
        java.net.Socket().use {
            it.connect(java.net.InetSocketAddress(host, port), 2500)
            true
        }
    } catch (_: Exception) {
        false
    }

    @Synchronized
    private fun startProxy() {
        if (process?.isAlive == true) {
            updateNotification()
            return
        }
        userStopped = false
        cancelPendingRestart()

        val bin = File(applicationInfo.nativeLibraryDir, BIN_NAME)
        if (!bin.exists()) {
            ProxyStatus.appendLog("ОШИБКА: бинарник не найден: ${bin.absolutePath}")
            ProxyStatus.setState(ProxyStatus.State.STOPPED)
            return
        }
        bin.setExecutable(true)

        val workDir = File(filesDir, WORK_DIR).apply { mkdirs() }
        try {
            copyAssets(workDir)
            configureFfmpeg(workDir)
        } catch (e: Exception) {
            ProxyStatus.appendLog("ОШИБКА подготовки конфигов: ${e.message}")
        }
        val tmpDir = File(workDir, "tmp").apply { mkdirs() }

        val port = Prefs.getPort(this)
        val cmd = listOf(bin.absolutePath, "-address", "0.0.0.0", "-port", port.toString())

        // Фикс DNS: бинарник пропатчен так, что путь к DNS-конфигу — относительный
        // "resolv.conf" (на Android нет /etc/resolv.conf, из-за чего getaddrinfo
        // падал с EAI_AGAIN). Кладём его в рабочий каталог (cwd процесса) с
        // публичными DNS, чтобы glibc-резолвер увидел серверы имён.
        try {
            File(workDir, "resolv.conf").writeText(RESOLV_CONTENT)
        } catch (e: Exception) {
            ProxyStatus.appendLog("ОШИБКА записи resolv.conf: ${e.message}")
        }

        try {
            val pb = ProcessBuilder(cmd)
            pb.directory(workDir)
            pb.redirectErrorStream(true)
            pb.environment()["HOME"] = workDir.absolutePath
            pb.environment()["TMPDIR"] = tmpDir.absolutePath
            // Бинарник пропатчен: networkInterfaces() берёт адрес отсюда
            // (Android запрещает перечисление интерфейсов внутри приложения).
            lastBoundIp = NetUtil.localIp(this)
            healthFails = 0
            lastHealthMs = 0
            pb.environment()["HLSPROXY_IP"] = lastBoundIp ?: "127.0.0.1"
            val p = pb.start()
            process = p
            ProxyStatus.appendLog("Запуск: порт $port")
            ProxyStatus.startTimeMs = System.currentTimeMillis()
            ProxyStatus.setState(ProxyStatus.State.RUNNING)
            acquireWakeLock()
            updateNotification()
            startReader(p)
            startStatsUpdates()
        } catch (e: Exception) {
            ProxyStatus.appendLog("ОШИБКА запуска: ${e.message}")
            ProxyStatus.setState(ProxyStatus.State.STOPPED)
            releaseWakeLock()
        }
    }

    private fun startReader(p: Process) {
        val t = Thread {
            try {
                BufferedReader(InputStreamReader(p.inputStream)).use { br ->
                    var line: String?
                    while (br.readLine().also { line = it } != null) {
                        ProxyStatus.appendLog(line!!)
                    }
                }
            } catch (_: Exception) {
                // поток закрылся вместе с процессом
            }
            val code = try { p.waitFor() } catch (_: Exception) { -1 }
            onProcessExited(p, code)
        }
        t.isDaemon = true
        readerThread = t
        t.start()
    }

    @Synchronized
    private fun onProcessExited(p: Process, code: Int) {
        // Если поле уже указывает на другой процесс (или null после Стоп/Перезапуск) —
        // это завершение уже обработано вручную, ничего не делаем.
        if (process !== p) return
        process = null
        stopStatsUpdates()
        ProxyStatus.setStats(null)
        ProxyStatus.setState(ProxyStatus.State.STOPPED)
        ProxyStatus.appendLog("Процесс завершён (код $code)")

        if (userStopped) {
            releaseWakeLock()
            handler.post {
                stopForegroundCompat()
                stopSelf()
            }
            return
        }

        // Непредвиденное завершение — пробуем перезапустить с защитой от петли.
        val now = System.currentTimeMillis()
        restartTimestamps.addLast(now)
        while (restartTimestamps.isNotEmpty() && now - restartTimestamps.first() > RESTART_WINDOW_MS) {
            restartTimestamps.removeFirst()
        }
        if (restartTimestamps.size > MAX_RESTARTS_IN_WINDOW) {
            ProxyStatus.appendLog("Слишком много перезапусков — остановлено. Нажмите «Пуск».")
            restartTimestamps.clear()
            releaseWakeLock()
            notifyFailed()
            handler.post {
                stopForegroundCompat()
                stopSelf()
            }
            return
        }
        ProxyStatus.appendLog("Перезапуск через ${RESTART_DELAY_MS / 1000} с…")
        val r = Runnable {
            if (!userStopped && !control.isShutdown) {
                control.execute { if (!userStopped) startProxy() }
            }
        }
        pendingRestart = r
        handler.postDelayed(r, RESTART_DELAY_MS)
    }

    @Synchronized
    private fun stopProxy(userInitiated: Boolean) {
        if (userInitiated) userStopped = true
        // Отменяем ожидание сети, если оно было запущено.
        waitingForNet = false
        handler.removeCallbacks(netStartPoll)
        stopStatsUpdates()
        cancelPendingRestart()
        val p = process
        process = null
        if (p != null && p.isAlive) {
            try {
                p.destroy()
                // дать SIGTERM время на корректное завершение
                val deadline = System.currentTimeMillis() + 3000
                while (p.isAlive && System.currentTimeMillis() < deadline) {
                    Thread.sleep(50)
                }
                if (p.isAlive) p.destroyForcibly()
            } catch (_: Exception) {
            }
        }
        ProxyStatus.setState(ProxyStatus.State.STOPPED)
        releaseWakeLock()
    }

    private fun cancelPendingRestart() {
        pendingRestart?.let { handler.removeCallbacks(it) }
        pendingRestart = null
    }

    // ---- Метрики (RAM / аптайм / потоки) ----

    private val statsTick = object : Runnable {
        override fun run() {
            control.execute { updateStats() }
            handler.postDelayed(this, STATS_INTERVAL_MS)
        }
    }

    private fun startStatsUpdates() {
        handler.removeCallbacks(statsTick)
        handler.post(statsTick)
    }

    private fun stopStatsUpdates() {
        handler.removeCallbacks(statsTick)
    }

    private fun updateStats() {
        if (process?.isAlive != true) return
        val pid = SysUtil.findPid(BIN_NAME)
        val ramKb = SysUtil.readRssKb(pid)
        val now = System.currentTimeMillis()
        val uptime = if (ProxyStatus.startTimeMs > 0) now - ProxyStatus.startTimeMs else 0
        val stats = ProxyStatus.Stats(ramKb, uptime, countActiveStreams())
        ProxyStatus.setStats(stats)
        // Постоянное уведомление сервиса НЕ обновляем каждые 4 с — RAM смотрят в приложении.

        // Тихое уведомление о статусе раз в день (если включено в настройках).
        if (Prefs.isNotifyStatus(this) && now - Prefs.getLastDailyNotif(this) >= DAILY_NOTIF_MS) {
            Prefs.setLastDailyNotif(this, now)
            postDailyStatus(stats)
        }

        // Watchdog: даём серверу время подняться, затем периодически проверяем ответ.
        // Проверка редкая (раз в 30 с), чтобы не засорять журнал прокси запросами с 127.0.0.1.
        if (uptime > HEALTH_GRACE_MS && now - lastHealthMs >= HEALTH_INTERVAL_MS) {
            lastHealthMs = now
            if (healthOk(Prefs.getPort(this))) {
                healthFails = 0
            } else {
                healthFails++
                if (healthFails >= HEALTH_MAX_FAILS) {
                    ProxyStatus.appendLog("Сервер не отвечает — перезапуск")
                    healthFails = 0
                    restartProxyInternal()
                }
            }
        }
    }

    /** Проверка живости: любой HTTP-ответ от сервера считается успехом. */
    private fun healthOk(port: Int): Boolean {
        return try {
            val c = URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection
            c.connectTimeout = 3000
            c.readTimeout = 3000
            c.requestMethod = "GET"
            val code = c.responseCode
            c.disconnect()
            code > 0
        } catch (e: Exception) {
            false
        }
    }

    @Synchronized
    private fun restartProxyInternal() {
        stopStatsUpdates()
        stopProxy(userInitiated = false)
        requestStart()
    }

    /** Приблизительное число активных потоков: уникальные ID каналов в свежем хвосте журнала. */
    private fun countActiveStreams(): Int {
        val tail = ProxyStatus.logSnapshot().takeLast(40)
        val ids = HashSet<String>()
        val re = Regex("""for stream .*\(([A-Za-z0-9]+)\)""")
        for (line in tail) {
            re.find(line)?.let { ids.add(it.groupValues[1]) }
        }
        return ids.size
    }

    // ---- Копирование ассетов в рабочую папку ----

    /**
     * Копирует встроенные default.json, groups.json, favicon.png, plugins/ рядом с бинарником.
     * default.json и плагины перезаписываются (это ресурсы версии приложения),
     * пользовательские файлы (local.json, groups.json, кэш) не трогаются.
     */
    private fun copyAssets(workDir: File) {
        copyAssetTree("hls", workDir)
    }

    private fun copyAssetTree(assetPath: String, outDir: File) {
        val entries = assets.list(assetPath) ?: return
        if (entries.isEmpty()) return // не должно случиться для каталога
        outDir.mkdirs()
        for (name in entries) {
            val childAsset = "$assetPath/$name"
            val sub = assets.list(childAsset)
            if (sub != null && sub.isNotEmpty()) {
                copyAssetTree(childAsset, File(outDir, name))
            } else {
                val outFile = File(outDir, name)
                val alwaysOverwrite = name == "default.json" || assetPath.contains("plugins")
                if (outFile.exists() && !alwaysOverwrite) continue
                assets.open(childAsset).use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    /**
     * Прописывает в default.json абсолютный путь к встроенному ffmpeg
     * (lib-файл в nativeLibraryDir — единственное место, откуда разрешён запуск).
     * Включённый по умолчанию remux (-c copy) теперь работает; путь динамический,
     * поэтому подставляется при каждом старте.
     */
    private fun configureFfmpeg(workDir: File) {
        val ffmpeg = File(applicationInfo.nativeLibraryDir, "libffmpeg.so")
        File(workDir, "ffmpeg").mkdirs()
        val cfg = File(workDir, "default.json")
        if (!ffmpeg.exists() || !cfg.exists()) return
        val text = cfg.readText()
        val replaced = text.replace(
            "\"executable\": \"ffmpeg\"",
            "\"executable\": \"${ffmpeg.absolutePath}\""
        )
        if (replaced != text) cfg.writeText(replaced)
    }

    // ---- Реакция на смену сети/IP ----

    private val netCheck = Runnable {
        control.execute {
            if (process?.isAlive == true && !userStopped) {
                val ip = NetUtil.localIp(this)
                if (ip != null && ip != lastBoundIp) {
                    ProxyStatus.appendLog("Сменился IP: $lastBoundIp → $ip — перезапуск")
                    restartProxyInternal()
                }
            }
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { onNetEvent() }
        override fun onLost(network: Network) { onNetEvent() }
        override fun onLinkPropertiesChanged(network: Network, lp: android.net.LinkProperties) { onNetEvent() }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { onNetEvent() }
    }

    private fun onNetEvent() {
        // Ждём старта и сеть появилась — пробуем подняться сразу, не дожидаясь поллинга.
        if (waitingForNet && !userStopped) control.execute { requestStart() }
        // Уже работаем — отслеживаем смену IP для перезапуска.
        scheduleNetCheck()
    }

    private fun scheduleNetCheck() {
        handler.removeCallbacks(netCheck)
        handler.postDelayed(netCheck, 2500)
    }

    private fun registerNetworkCallback() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.registerDefaultNetworkCallback(networkCallback)
            netCallbackRegistered = true
        } catch (_: Exception) {
        }
    }

    private fun unregisterNetworkCallback() {
        if (!netCallbackRegistered) return
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
        }
        netCallbackRegistered = false
    }

    private fun notifyFailed() {
        val nm = getSystemService(NotificationManager::class.java)
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_failed))
            .setStyle(NotificationCompat.BigTextStyle().bigText(getString(R.string.notif_failed)))
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java), pendingFlags()
                )
            )
            .build()
        nm.notify(FAILED_NOTIF_ID, n)
    }

    private fun postDailyStatus(s: ProxyStatus.Stats) {
        val nm = getSystemService(NotificationManager::class.java)
        val ramMb = if (s.ramKb > 0) s.ramKb / 1024 else 0
        val text = getString(R.string.notif_status_format, ramMb, formatUptime(s.uptimeMs), s.streams)
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_status_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java), pendingFlags()
                )
            )
            .build()
        nm.notify(STATUS_NOTIF_ID, n)
    }

    private fun formatUptime(ms: Long): String {
        val sec = ms / 1000
        return String.format("%d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60)
    }

    // ---- WakeLock ----

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HlsProxy::wakelock").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    // ---- Уведомление ----

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val ip = NetUtil.localIp(this) ?: "127.0.0.1"
        val port = Prefs.getPort(this)
        val running = ProxyStatus.state.value == ProxyStatus.State.RUNNING
        // Статичный текст (без частых обновлений): адрес или «остановлен».
        val text = if (running) "http://$ip:$port" else getString(R.string.status_stopped)

        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            pendingFlags()
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ProxyService::class.java).setAction(ACTION_STOP),
            pendingFlags()
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_running))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(running)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.notif_stop_action), stopIntent)
            .build()
    }

    private fun pendingFlags(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
    }

    private fun startForegroundNow() {
        // Приложение таргетит SDK 28 → тип FGS на Android 14 не требуется,
        // используем классический вызов (работает на всех версиях).
        @Suppress("DEPRECATION")
        startForeground(NOTIF_ID, buildNotification())
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification())
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }
}
