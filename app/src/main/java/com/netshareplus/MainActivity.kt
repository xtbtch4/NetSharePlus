package com.netshareplus

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import java.net.NetworkInterface
import java.util.Collections

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var start: Button
    private lateinit var stop: Button
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        manager = getSystemService(WIFI_P2P_SERVICE) as WifiP2pManager
        channel = manager?.initialize(this, mainLooper, null)
        requestNeededPermissions()
        registerP2pReceiver()
        refreshNetworkInfo()
    }

    private fun buildUi() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24, 32, 24, 24)
            setBackgroundColor(0xFFF4F7FB.toInt())
        }
        val title = TextView(this).apply {
            text = "NetShare Plus"
            textSize = 28f
            setTextColor(0xFF12345A.toInt())
            gravity = Gravity.CENTER
        }
        status = TextView(this).apply {
            text = "Готово к запуску"
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, 22, 0, 12)
        }
        details = TextView(this).apply {
            textSize = 15f
            setTextColor(0xFF334155.toInt())
            setPadding(0, 8, 0, 20)
        }
        start = Button(this).apply {
            text = "Запустить раздачу"
            setOnClickListener { startSharing() }
        }
        stop = Button(this).apply {
            text = "Остановить"
            isEnabled = false
            setOnClickListener { stopSharing() }
        }
        val note = TextView(this).apply {
            text = "Подключённому устройству может понадобиться вручную задать HTTP-прокси.\n\nПрокси: порт 8282. HTTPS поддерживается через CONNECT.\n\nЭто экспериментальная версия: Android и производитель устройства могут ограничивать Wi‑Fi Direct и передачу трафика."
            textSize = 13f
            setTextColor(0xFF64748B.toInt())
            setPadding(0, 20, 0, 0)
        }
        listOf(title, status, details, start, stop, note).forEach {
            layout.addView(it, LinearLayout.LayoutParams(-1, if (it == title) 58 else -2).apply { bottomMargin = 8 })
        }
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    private fun requestNeededPermissions() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 101)
        }
    }

    private fun registerP2pReceiver() {
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val enabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                        status.text = if (enabled) "Wi‑Fi Direct доступен" else "Включите Wi‑Fi"
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        manager?.requestConnectionInfo(channel) { info ->
                            if (info.groupFormed) {
                                status.text = "Wi‑Fi Direct группа создана"
                                refreshNetworkInfo()
                            }
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(receiver, filter)
    }

    private fun startSharing() {
        val m = manager ?: return
        val c = channel ?: return
        status.text = "Создание Wi‑Fi Direct группы…"
        start.isEnabled = false
        m.createGroup(c, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                startService(Intent(this@MainActivity, ProxyService::class.java))
                status.text = "Запущена группа. Ожидаю подключение…"
                stop.isEnabled = true
                refreshNetworkInfo()
            }
            override fun onFailure(reason: Int) {
                start.isEnabled = true
                status.text = "Не удалось создать группу (код $reason)"
                details.text = "Проверьте, что Wi‑Fi включён и устройство поддерживает Wi‑Fi Direct. На некоторых прошивках создание группы ограничено."
            }
        })
    }

    private fun stopSharing() {
        stopService(Intent(this, ProxyService::class.java))
        manager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { status.text = "Раздача остановлена" }
            override fun onFailure(reason: Int) { status.text = "Прокси остановлен; Wi‑Fi Direct: $reason" }
        })
        start.isEnabled = true
        stop.isEnabled = false
    }

    private fun refreshNetworkInfo() {
        val ip = try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            interfaces.flatMap { Collections.list(it.inetAddresses) }
                .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }?.hostAddress ?: "не определён"
        } catch (_: Exception) { "не определён" }
        details.text = "IP телефона: $ip\nПорт прокси: 8282\n\nПодключение: настройки Wi‑Fi подключённого устройства → прокси вручную.\nХост часто 192.168.49.1, но зависит от устройства."
    }

    override fun onDestroy() {
        try { receiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        super.onDestroy()
    }
}