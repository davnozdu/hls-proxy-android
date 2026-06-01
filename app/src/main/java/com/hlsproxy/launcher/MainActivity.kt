package com.hlsproxy.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvAddress: TextView
    private lateinit var tvPlaylist: TextView
    private lateinit var tvEpg: TextView
    private lateinit var tvLog: TextView
    private lateinit var etPort: EditText
    private lateinit var btnApplyPort: Button
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnRestart: Button
    private lateinit var swAutostart: MaterialSwitch
    private lateinit var btnBackground: Button
    private lateinit var tvStats: TextView
    private lateinit var ivQr: ImageView
    private lateinit var tvQrHint: TextView
    private lateinit var btnShare: Button
    private lateinit var btnOpenWeb: Button
    private lateinit var tvUpdate: TextView
    private lateinit var tvVersion: TextView
    private lateinit var btnClearLog: Button
    private lateinit var btnShareLog: Button
    private var lastQrUrl: String? = null

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvAddress = findViewById(R.id.tvAddress)
        tvPlaylist = findViewById(R.id.tvPlaylist)
        tvEpg = findViewById(R.id.tvEpg)
        tvLog = findViewById(R.id.tvLog)
        etPort = findViewById(R.id.etPort)
        btnApplyPort = findViewById(R.id.btnApplyPort)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnRestart = findViewById(R.id.btnRestart)
        swAutostart = findViewById(R.id.swAutostart)
        btnBackground = findViewById(R.id.btnBackground)
        tvStats = findViewById(R.id.tvStats)
        ivQr = findViewById(R.id.ivQr)
        tvQrHint = findViewById(R.id.tvQrHint)
        btnShare = findViewById(R.id.btnShare)
        btnOpenWeb = findViewById(R.id.btnOpenWeb)
        tvUpdate = findViewById(R.id.tvUpdate)
        tvVersion = findViewById(R.id.tvVersion)
        btnClearLog = findViewById(R.id.btnClearLog)
        btnShareLog = findViewById(R.id.btnShareLog)

        etPort.setText(Prefs.getPort(this).toString())
        swAutostart.isChecked = Prefs.isAutostart(this)

        btnStart.setOnClickListener {
            Prefs.setUserStopped(this, false)
            ProxyController.start(this)
        }
        btnStop.setOnClickListener {
            Prefs.setUserStopped(this, true)
            ProxyController.stop(this)
        }
        btnRestart.setOnClickListener {
            Prefs.setUserStopped(this, false)
            ProxyController.restart(this)
        }
        btnApplyPort.setOnClickListener { applyPort() }
        swAutostart.setOnCheckedChangeListener { _, checked -> Prefs.setAutostart(this, checked) }
        btnBackground.setOnClickListener { openBatterySettings() }
        btnShare.setOnClickListener { sharePlaylist() }
        btnOpenWeb.setOnClickListener { openWebInterface() }
        btnClearLog.setOnClickListener { ProxyStatus.clearLog() }
        btnShareLog.setOnClickListener { shareLog() }
        tvAddress.setOnClickListener { webUrl()?.let { copyToClipboard(it) } }
        tvPlaylist.setOnClickListener { playlistUrl()?.let { copyToClipboard(it) } }
        tvEpg.setOnClickListener { epgUrl()?.let { copyToClipboard(it) } }

        tvVersion.text = getString(R.string.version_format, BuildConfig.VERSION_NAME, "8.4.8")

        observeState()
        requestRuntimePermissions()
        checkForUpdate()

        btnStart.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        // Автостарт при открытии: если пользователь не останавливал сервис вручную
        // и он сейчас не запущен — поднимаем (старт идемпотентен, если уже работает).
        if (!Prefs.isUserStopped(this) && ProxyStatus.state.value == ProxyStatus.State.STOPPED) {
            ProxyController.start(this)
        }
    }

    override fun onResume() {
        super.onResume()
        updateAddress(ProxyStatus.state.value)
    }

    private fun applyPort() {
        val value = etPort.text.toString().toIntOrNull()
        if (value == null || value < Prefs.MIN_PORT || value > Prefs.MAX_PORT) {
            Toast.makeText(this, R.string.port_invalid, Toast.LENGTH_LONG).show()
            return
        }
        Prefs.setPort(this, value)
        Toast.makeText(this, R.string.port_saved, Toast.LENGTH_SHORT).show()
        updateAddress(ProxyStatus.state.value)
        if (ProxyStatus.state.value == ProxyStatus.State.RUNNING) {
            ProxyController.restart(this)
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    ProxyStatus.state.collect { state -> render(state) }
                }
                launch {
                    ProxyStatus.log.collect { lines ->
                        tvLog.text = lines.takeLast(80).joinToString("\n")
                    }
                }
                launch {
                    ProxyStatus.stats.collect { s -> renderStats(s) }
                }
            }
        }
    }

    private fun render(state: ProxyStatus.State) {
        val running = state == ProxyStatus.State.RUNNING
        tvStatus.setText(if (running) R.string.status_running else R.string.status_stopped)
        tvStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (running) android.R.color.holo_green_dark else android.R.color.holo_red_dark
            )
        )
        btnStart.isEnabled = !running
        btnStop.isEnabled = running
        btnRestart.isEnabled = running
        updateAddress(state)
    }

    @SuppressLint("SetTextI18n")
    private fun updateAddress(state: ProxyStatus.State) {
        val ip = NetUtil.localIp(this)
        val port = Prefs.getPort(this)
        if (ip != null) {
            val base = "http://$ip:$port"
            tvAddress.text = "${getString(R.string.webui_label)}: $base"
            tvPlaylist.text = "${getString(R.string.playlist_label)}: $base/playlist.m3u8"
            tvEpg.text = "${getString(R.string.epg_label)}: $base/epg.xml.gz"
            updateQr("$base/playlist.m3u8")
        } else {
            val none = getString(R.string.address_none)
            tvAddress.text = "${getString(R.string.webui_label)}: $none"
            tvPlaylist.text = "${getString(R.string.playlist_label)}: $none"
            tvEpg.text = "${getString(R.string.epg_label)}: $none"
            updateQr(null)
        }
    }

    private fun playlistUrl(): String? {
        val ip = NetUtil.localIp(this) ?: return null
        return "http://$ip:${Prefs.getPort(this)}/playlist.m3u8"
    }

    private fun webUrl(): String? {
        val ip = NetUtil.localIp(this) ?: return null
        return "http://$ip:${Prefs.getPort(this)}"
    }

    private fun epgUrl(): String? {
        val ip = NetUtil.localIp(this) ?: return null
        return "http://$ip:${Prefs.getPort(this)}/epg.xml.gz"
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("HLS Proxy", text))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareLog() {
        val text = ProxyStatus.logSnapshot().joinToString("\n").ifEmpty { "—" }
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        try {
            startActivity(Intent.createChooser(send, getString(R.string.share_log)))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.no_app, Toast.LENGTH_LONG).show()
        }
    }

    private fun checkForUpdate() {
        lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) { fetchLatestRelease() } ?: return@launch
            val (tag, url) = info
            if (isNewer(tag, BuildConfig.VERSION_NAME)) {
                tvUpdate.text = getString(R.string.update_available, tag)
                tvUpdate.visibility = View.VISIBLE
                tvUpdate.setOnClickListener {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, R.string.no_app, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun fetchLatestRelease(): Pair<String, String>? {
        return try {
            val c = URL("https://api.github.com/repos/davnozdu/hls-proxy-android/releases/latest")
                .openConnection() as HttpURLConnection
            c.connectTimeout = 5000
            c.readTimeout = 5000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            if (c.responseCode != 200) {
                c.disconnect()
                return null
            }
            val body = c.inputStream.bufferedReader().use { it.readText() }
            c.disconnect()
            val json = JSONObject(body)
            val tag = json.optString("tag_name").trimStart('v')
            val url = json.optString("html_url")
            if (tag.isEmpty() || url.isEmpty()) null else tag to url
        } catch (e: Exception) {
            null
        }
    }

    private fun isNewer(remote: String, current: String): Boolean {
        fun parts(s: String) = s.trimStart('v').split(".").map { it.toIntOrNull() ?: 0 }
        val r = parts(remote)
        val c = parts(current)
        for (i in 0 until maxOf(r.size, c.size)) {
            val rv = r.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (rv != cv) return rv > cv
        }
        return false
    }

    private fun renderStats(s: ProxyStatus.Stats?) {
        if (s == null || ProxyStatus.state.value != ProxyStatus.State.RUNNING) {
            tvStats.text = ""
            return
        }
        val ramMb = if (s.ramKb > 0) s.ramKb / 1024 else 0
        tvStats.text = getString(R.string.stats_format, ramMb, formatUptime(s.uptimeMs), s.streams)
    }

    private fun formatUptime(ms: Long): String {
        val sec = ms / 1000
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return String.format("%d:%02d:%02d", h, m, s)
    }

    private fun updateQr(url: String?) {
        if (url == null) {
            ivQr.visibility = View.GONE
            tvQrHint.visibility = View.GONE
            lastQrUrl = null
            return
        }
        if (url == lastQrUrl && ivQr.drawable != null) return
        lastQrUrl = url
        val bmp = makeQr(url, 480)
        if (bmp != null) {
            ivQr.setImageBitmap(bmp)
            ivQr.visibility = View.VISIBLE
            tvQrHint.visibility = View.VISIBLE
        } else {
            ivQr.visibility = View.GONE
            tvQrHint.visibility = View.GONE
        }
    }

    private fun makeQr(text: String, size: Int): Bitmap? {
        return try {
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
            val w = matrix.width
            val h = matrix.height
            val pixels = IntArray(w * h)
            for (y in 0 until h) {
                val off = y * w
                for (x in 0 until w) {
                    pixels[off + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, w, 0, 0, w, h)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun sharePlaylist() {
        val url = playlistUrl()
        if (url == null) {
            Toast.makeText(this, R.string.address_none, Toast.LENGTH_SHORT).show()
            return
        }
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, url)
        try {
            startActivity(Intent.createChooser(send, getString(R.string.share_playlist)))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.no_app, Toast.LENGTH_LONG).show()
        }
    }

    private fun openWebInterface() {
        val url = webUrl()
        if (url == null) {
            Toast.makeText(this, R.string.address_none, Toast.LENGTH_SHORT).show()
            return
        }
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        if (view.resolveActivity(packageManager) != null) {
            try {
                startActivity(view)
                return
            } catch (_: Exception) {
            }
        }
        Toast.makeText(this, R.string.no_app, Toast.LENGTH_LONG).show()
    }

    private fun requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        requestIgnoreBatteryOptimizations()
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        openBatterySettings()
    }

    /** Открывает экран разрешения фоновой работы. */
    @SuppressLint("BatteryLife")
    private fun openBatterySettings() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val alreadyAllowed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            pm.isIgnoringBatteryOptimizations(packageName)

        // Если разрешение ещё не выдано — показываем системный запрос. Если уже выдано,
        // системный диалог ничего не покажет, поэтому открываем «О приложении».
        val intents = ArrayList<Intent>()
        if (!alreadyAllowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            intents.add(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        }
        intents.add(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:$packageName"))
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            intents.add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }

        for (intent in intents) {
            if (intent.resolveActivity(packageManager) != null) {
                try {
                    startActivity(intent)
                    return
                } catch (_: Exception) {
                    // пробуем следующий вариант
                }
            }
        }
        Toast.makeText(this, R.string.settings_unavailable, Toast.LENGTH_LONG).show()
    }
}
