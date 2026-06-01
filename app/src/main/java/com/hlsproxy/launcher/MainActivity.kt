package com.hlsproxy.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvAddress: TextView
    private lateinit var tvLog: TextView
    private lateinit var etPort: EditText
    private lateinit var btnApplyPort: Button
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnRestart: Button
    private lateinit var swAutostart: MaterialSwitch

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvAddress = findViewById(R.id.tvAddress)
        tvLog = findViewById(R.id.tvLog)
        etPort = findViewById(R.id.etPort)
        btnApplyPort = findViewById(R.id.btnApplyPort)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnRestart = findViewById(R.id.btnRestart)
        swAutostart = findViewById(R.id.swAutostart)

        etPort.setText(Prefs.getPort(this).toString())
        swAutostart.isChecked = Prefs.isAutostart(this)

        btnStart.setOnClickListener { ProxyController.start(this) }
        btnStop.setOnClickListener { ProxyController.stop(this) }
        btnRestart.setOnClickListener { ProxyController.restart(this) }
        btnApplyPort.setOnClickListener { applyPort() }
        swAutostart.setOnCheckedChangeListener { _, checked -> Prefs.setAutostart(this, checked) }

        observeState()
        requestRuntimePermissions()

        btnStart.requestFocus()
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
        tvAddress.text = if (ip != null) "http://$ip:$port" else getString(R.string.address_none)
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
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
            // На некоторых Android TV прошивках экрана нет — не критично.
        }
    }
}
