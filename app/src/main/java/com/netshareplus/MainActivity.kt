package com.netshareplus

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.*
import java.net.NetworkInterface
import java.util.Collections

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var mobileMode: RadioButton
    private lateinit var wifiMode: RadioButton
    private lateinit var start: Button
    private lateinit var stop: Button
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var groupRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manager = getSystemService(WIFI_P2P_SERVICE) as WifiP2pManager
        channel = manager?.initialize(this, mainLooper, null)
        buildUi()
        requestNeededPermissions()
        registerP2pReceiver()
        refreshNetworkInfo()
    }

    private fun buildUi() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(22, 24, 22, 24)
            setBackgroundColor(0xFFF4F7FB.toInt())
        }
        val title = TextView(this).apply {
            text = "NetShare Plus"
            textSize = 27f
            setTextColor(0xFF12345A.toInt())
            gravity = Gravity.CENTER
        }
        status = TextView(this).apply {
            text = "Выберите источник интернета"
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, 18, 0, 12)
        }
        val modes = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        mobileMode = RadioButton(this).apply { text = "Мобильный интернет → другие устройства"; isChecked = true }
        wifiMode = RadioButton(this).apply { text = "Подключённый Wi‑Fi → другие устройства" }
        modes.addView(mobileMode)
        modes.addView(wifiMode)
        details = TextView(this).apply {
            textSize = 15f
            setTextColor(0xFF334155.toInt())
            setPadding(0, 16, 0, 18)
        }
        start = Button(this).apply {
            text = "Настроить раздачу"
            setOnClickListener { startSharing() }
        }
        stop = Button(this).apply {
            text = "Остановить прокси"
            isEnabled = false
            setOnClickListener { stopSharing() }
        }
        val note = TextView(this).apply {
            text = "Важно: Android не разрешает обычному приложению включать системную точку доступа на всех моделях. В мобильном режиме включите её в настройках вручную. В режиме Wi‑Fi приложение использует Wi‑Fi Direct, который некоторые прошивки не позволяют запускать одновременно с клиентским Wi‑Fi.\n\nHTTP/HTTPS-прокси: порт 8282. На подключённом устройстве укажите адрес телефона и порт вручную. Это экспериментальный прокси, не прозрачная системная раздача."
            textSize = 13f
            setTextColor(0xFF64748B.toInt())
            setPadding(0, 18, 0, 0)
        }
        listOf(title, status, modes, details, start, stop, note).forEach {
            layout.addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 7 })
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
                        if (!groupRequested) status.text = if (enabled) "Wi‑Fi Direct доступен" else "Wi‑Fi Direct выключен"
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
        if (mobileMode.isChecked) {
            startProxy()
            status.text = "Прокси запущен. Включите точку доступа телефона"
            try { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
            catch (_: Exception) { try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) {} }
            details.text = "1. Включите мобильные данные.\n2. В настройках включите точку доступа Wi‑Fi.\n3. Подключите второе устройство к ней.\n4. На втором устройстве задайте прокси вручную: IP шлюза телефона, порт 8282.\n\nЕсли система не позволяет одновременно использовать точку доступа и мобильные данные, это ограничение прошивки."
            return
        }

        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val wifiConnected = if (Build.VERSION.SDK_INT >= 23) {
            cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } else {
            @Suppress("DEPRECATION")
            cm.activeNetworkInfo?.type == ConnectivityManager.TYPE_WIFI
        }
        if (!wifiConnected) {
            status.text = "Сначала подключитесь к Wi‑Fi"
            details.text = "Выбран режим передачи интернета из существующей Wi‑Fi-сети, но активное Wi‑Fi-соединение не обнаружено."
            return
        }
        val m = manager
        val c = channel
        if (m == null || c == null) {
            status.text = "Wi‑Fi Direct недоступен на этом устройстве"
            return
        }
        groupRequested = true
        status.text = "Запрашиваю Wi‑Fi Direct группу…"
        start.isEnabled = false
        m.createGroup(c, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                startProxy()
                status.text = "Группа создана; проверьте подключение второго устройства"
                details.text = "Подключите второе устройство к группе Wi‑Fi Direct. Если телефон отключит исходный Wi‑Fi, интернет через исходную сеть может быть недоступен — зависит от прошивки."
                refreshNetworkInfo()
            }
            override fun onFailure(reason: Int) {
                groupRequested = false
                start.isEnabled = true
                status.text = "Wi‑Fi Direct не создал группу (код $reason)"
                details.text = "Wi‑Fi Direct отклонён системой (код $reason). Это может быть ограничение прошивки или конфликт с активным Wi‑Fi. Обычное Android-приложение не может гарантировать одновременную работу клиентского Wi‑Fi и отдельной точки доступа на каждом устройстве."
            }
        })
    }

    private fun startProxy() {
        startService(Intent(this, ProxyService::class.java))
        stop.isEnabled = true
    }

    private fun stopSharing() {
        stopService(Intent(this, ProxyService::class.java))
        if (groupRequested) {
            manager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { status.text = "Прокси и группа остановлены" }
                override fun onFailure(reason: Int) { status.text = "Прокси остановлен; код закрытия группы: $reason" }
            })
        } else status.text = "Прокси остановлен"
        groupRequested = false
        start.isEnabled = true
        stop.isEnabled = false
    }

    private fun refreshNetworkInfo() {
        val ip = try {
            Collections.list(NetworkInterface.getNetworkInterfaces()).flatMap { Collections.list(it.inetAddresses) }
                .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }?.hostAddress ?: "не определён"
        } catch (_: Exception) { "не определён" }
        val wifi = try {
            @Suppress("DEPRECATION")
            (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager).connectionInfo?.ssid ?: "не определён"
        } catch (_: Exception) { "не определён" }
        details.text = "Текущий IP: $ip\nWi‑Fi: $wifi\nПрокси: порт 8282\n\nДля HTTP-прокси укажите IP телефона и порт 8282 на подключённом устройстве."
    }

    override fun onDestroy() {
        try { receiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        super.onDestroy()
    }
}