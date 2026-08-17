package io.github.fairyxh.VirEnvDetector

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Intent
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.telephony.CellInfo
import android.telephony.CellInfoCdma
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * VirEnvDetector：普通 App 视角的环境虚拟化检测器。
 *
 * 独立应用（与模块同包族）。加入模块 scope.list 后，模块的 FrameworkEnvHookAdapter
 * 会在本进程生效（虚拟位置/基站/BLE/WiFi/传感器/GNSS），用于验证 Hook 效果——
 * 尤其是传感器注入，因为模块不能 Hook 自己。
 *
 * 同时本检测器直接调用模块 ApiServer（127.0.0.1:18790，X-ZVE-Token 鉴权）：
 * 拉取虚拟期望配置，与本进程实读数据比较，生成 PASS/FAIL/NOT_ENABLED 检测报告，
 * 上报到 /api/test/report 供自动化验证。
 */
class MainActivity : Activity() {

    companion object {
        private const val TAG = "VirEnvDetector"
        private const val REFRESH_MS = 1000L
        private const val BLE_RESULTS_LIMIT = 20
        private const val BASE_URL = "http://127.0.0.1:18790"

        /** 上次检测是否运行中（进程被杀后重开自动恢复）。 */
        private const val PREFS_NAME = "vir_env_detector"
        private const val KEY_WAS_RUNNING = "was_running"
        private val REQUIRED_PERMS = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.ACCESS_WIFI_STATE)
            add(Manifest.permission.READ_PHONE_STATE)
            // 计步器/步态传感器注册需要 ACTIVITY_RECOGNITION（Android 10+ 强制）
            add(Manifest.permission.ACTIVITY_RECOGNITION)
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
    }

    private enum class Verdict { PASS, FAIL, SYNCING, NOT_ENABLED, UNKNOWN }

    private var apiToken: String = ""

    private lateinit var statusView: TextView
    private lateinit var locationView: TextView
    private lateinit var locationStatus: TextView
    private lateinit var cellView: TextView
    private lateinit var cellStatus: TextView
    private lateinit var bleView: TextView
    private lateinit var bleStatus: TextView
    private lateinit var wifiView: TextView
    private lateinit var wifiStatus: TextView
    private lateinit var sensorView: TextView
    private lateinit var sensorStatus: TextView
    private lateinit var gnssView: TextView
    private lateinit var gnssStatus: TextView
    private lateinit var simView: TextView
    private lateinit var simStatus: TextView
    private lateinit var playbackView: TextView
    private lateinit var playbackStatus: TextView
    private lateinit var hookObserveView: TextView
    private lateinit var hookObserveStatus: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var randomButton: Button
    private lateinit var exportButton: Button

    private val running = AtomicBoolean(false)
    private val pendingRandom = AtomicBoolean(false)
    private val refreshExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ZVE-DetectorRefresh").apply { isDaemon = true }
    }
    @Volatile
    private var refreshFuture: java.util.concurrent.ScheduledFuture<*>? = null

    /** 最近一次完整检测报告（导出用）。 */
    @Volatile
    private var latestReport: JSONObject? = null
    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (!running.get()) return
            refreshAll()
        }
    }

    private var locationManager: LocationManager? = null
    private var telephonyManager: TelephonyManager? = null
    private var wifiManager: WifiManager? = null
    private var sensorManager: SensorManager? = null
    private var bleScanner: android.bluetooth.le.BluetoothLeScanner? = null

    @Volatile
    private var lastLocation: Location? = null
    @Volatile
    private var lastCellText: String = "无基站（等待读取）"
    @Volatile
    private var lastWifiText: String = "无扫描结果"
    @Volatile
    private var lastSimText: String = "无 SIM 数据（等待读取）"
    @Volatile
    private var lastStepCount: Long = -1L
    @Volatile
    private var lastGnssStatus: GnssStatus? = null

    /** 最近一次 NMEA 句子（GNSS NMEA 检测）。 */
    @Volatile
    private var lastNmeaText = ""

    /** 蓝牙适配器身份文本（MAC/名称，BLE 身份检测）。 */
    @Volatile
    private var lastBtIdentityText = ""
    private val sensorRaw = ConcurrentHashMap<Int, String>()
    private val bleFound = LinkedHashMap<String, String>()
    /** 经典发现（ACTION_FOUND）收集结果：address -> "name address rssi class" */
    private val classicFound = LinkedHashMap<String, String>()
    private val classicReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action != BluetoothDevice.ACTION_FOUND) return
            val device: BluetoothDevice? = if (android.os.Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
            if (device == null) return
            val name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME) ?: device.name ?: "(no name)"
            val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
            val cls = if (android.os.Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_CLASS, android.bluetooth.BluetoothClass::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_CLASS)
            }
            val line = "$name ${device.address} ${rssi}dBm" + (if (cls != null) " class=${cls.deviceClass}" else "")
            synchronized(bleFound) {
                classicFound[device.address] = line
                while (classicFound.size > BLE_RESULTS_LIMIT) {
                    val it = classicFound.entries.iterator()
                    if (it.hasNext()) it.remove()
                }
            }
            onRealtimeBle()
        }
    }

    // ---- 虚拟期望（拉自 ApiServer） ----
    @Volatile
    private var expectEnv: JSONObject? = null
    @Volatile
    private var expectLocation: JSONObject? = null
    @Volatile
    private var expectRoute: JSONObject? = null

    // ---- 录像/回放状态（模块 /api/recording/status） ----
    @Volatile
    private var playbackStatusJson: JSONObject? = null
    @Volatile
    private var lastPlaybackFrame = -1

    // ---- Hook 层观测（模块 /api/debug/observe/snapshot） ----
    @Volatile
    private var hookObserveJson: JSONObject? = null

    // ---- 实时刷新节流（高频回调不每次刷 UI） ----
    @Volatile
    private var lastSensorUiMs = 0L
    @Volatile
    private var lastBleUiMs = 0L

    // ---- 配置变更感知：期望配置变化后给 Hook 层 EnvStateCache 同步留宽限期 ----
    @Volatile
    private var configChangedAtMs: Long = 0L
    private var lastExpectFingerprint: String = ""
    private val SYNC_GRACE_MS = 2000L

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastLocation = location
            onRealtimeLocation(location)
        }
    }
    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            lastGnssStatus = status
            onRealtimeGnss(status)
        }
    }

    /** NMEA 监听（虚拟定位启用时由模块注入虚拟 $GPRMC；OnNmeaMessageListener 需 API 30+）。 */
    private val nmeaListener = object : android.location.OnNmeaMessageListener {
        override fun onNmeaMessage(nmea: String, timestamp: Long) {
            lastNmeaText = nmea
            onRealtimeNmea()
        }
    }
    private val stepListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type == Sensor.TYPE_STEP_COUNTER && event.values.isNotEmpty()) {
                lastStepCount = event.values[0].toLong()
                onRealtimeSensor()
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }
    private val rawSensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val vals = event.values.joinToString(", ") { String.format(Locale.US, "%.3f", it) }
            sensorRaw[event.sensor.type] = "${event.sensor.name} [$vals]"
            onRealtimeSensor()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }
    private val bleScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = result.scanRecord?.deviceName ?: device.name ?: "(no name)"
            val rawHex = result.scanRecord?.bytes?.take(16)?.joinToString("") { "%02X".format(it) }
            val line = "$name ${device.address} ${result.rssi}dBm" + (if (!rawHex.isNullOrEmpty()) " raw=$rawHex" else "")
            synchronized(bleFound) {
                bleFound[device.address] = line
                while (bleFound.size > BLE_RESULTS_LIMIT) {
                    val it = bleFound.entries.iterator()
                    if (it.hasNext()) it.remove()
                }
            }
            onRealtimeBle()
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "ble scan failed errorCode=$errorCode")
        }
    }

    @Volatile
    private var rootAvailable: Boolean = false
    @Volatile
    private var rootChecked: Boolean = false
    private lateinit var rootView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        apiToken = try {
            assets.open("api_token.txt").bufferedReader().use { it.readText().trim() }
        } catch (t: Throwable) {
            Log.w(TAG, "api token load failed", t)
            ""
        }
        setContentView(buildUi())
        Log.i(TAG, "VirEnvDetector started pkg=${packageName} token=${if (apiToken.isEmpty()) "MISSING" else "loaded len=${apiToken.length} head=${apiToken.take(8)}"}")
        checkRootAsync()
        // 自动恢复上次检测状态：进程被划掉/被杀后重开，无需手动重新点「开始」。
        // 传感器/计步数据从系统层全局通道持续推送，这里只负责重新注册监听器继续展示。
        try {
            val wasRunning = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_WAS_RUNNING, false)
            if (wasRunning) {
                Log.i(TAG, "auto-resume detector from saved running state")
                onStartDetect()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "auto-resume failed", t)
        }
    }

    /** 后台检测 Root 可用性（su -c id）。 */
    private fun checkRootAsync() {
        Thread {
            val ok = try {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
                val out = p.inputStream.bufferedReader().use { it.readText().trim() }
                p.waitFor()
                out.contains("uid=0")
            } catch (t: Throwable) {
                false
            }
            rootAvailable = ok
            rootChecked = true
            runOnUiThread {
                rootView.text = if (ok) "Root: 可用（可验证模块存在）" else "Root: 不可用（检测器无法感知被 HMA 隐藏的模块，建议授予 Root）"
                rootView.setTextColor(if (ok) Color.parseColor("#2E7D32") else Color.parseColor("#C62828"))
            }
        }.apply { name = "ZVE-RootCheck"; isDaemon = true }.start()
    }

    /** Root 执行命令并返回输出；无 Root 返回 null。 */
    private fun rootExec(cmd: String): String? {
        if (!rootAvailable) return null
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val out = p.inputStream.bufferedReader().use { it.readText().trim() }
            p.waitFor()
            out
        } catch (t: Throwable) {
            Log.w(TAG, "root exec failed: $cmd", t)
            null
        }
    }

    private fun buildUi(): ScrollView {
        val root = ScrollView(this).apply {
            isFillViewport = true
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        root.addView(container, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        statusView = sectionTitle(container, "VirEnvDetector 环境虚拟化检测")
        rootView = TextView(this).apply {
            text = "Root: 检测中…"
            textSize = 13f
            setTextColor(Color.parseColor("#666666"))
        }
        container.addView(rootView)
        val hint = TextView(this).apply {
            text = "普通 App 视角读取环境 + 调用模块 API 比较期望配置。模块可能被 HideMyAppList 隐藏，建议授予 Root 以直接验证模块存在（读 LSPosed scope 与模块持久化配置）。随机模拟会覆盖现有配置，请做好备份。"
            textSize = 13f
            setTextColor(Color.parseColor("#666666"))
        }
        container.addView(hint)

        startButton = Button(this).apply { text = "开始检测" }
        stopButton = Button(this).apply { text = "结束" ; isEnabled = false }
        randomButton = Button(this).apply { text = "随机模拟" }
        exportButton = Button(this).apply { text = "导出报告" }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(startButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(randomButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(exportButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(stopButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        container.addView(row)
        startButton.setOnClickListener { onStartDetect() }
        randomButton.setOnClickListener { onRandomSimulate() }
        stopButton.setOnClickListener {
            // 用户主动结束：清除自动恢复标记（区别于进程被划掉/杀死的 onDestroy）
            try {
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit().putBoolean(KEY_WAS_RUNNING, false).apply()
            } catch (t: Throwable) {
                Log.w(TAG, "clear running state failed", t)
            }
            onStopDetect()
        }
        exportButton.setOnClickListener { onExportReport() }

        val loc = section(container, "位置")
        locationView = loc.first
        locationStatus = loc.second
        val cell = section(container, "基站")
        cellView = cell.first
        cellStatus = cell.second
        val ble = section(container, "蓝牙 BLE")
        bleView = ble.first
        bleStatus = ble.second
        val wifi = section(container, "WiFi")
        wifiView = wifi.first
        wifiStatus = wifi.second
        val sensor = section(container, "传感器")
        sensorView = sensor.first
        sensorStatus = sensor.second
        val gnss = section(container, "GNSS")
        gnssView = gnss.first
        gnssStatus = gnss.second
        val sim = section(container, "SIM")
        simView = sim.first
        simStatus = sim.second
        val play = section(container, "录像/回放状态")
        playbackView = play.first
        playbackStatus = play.second
        val observe = section(container, "Hook 层观测")
        hookObserveView = observe.first
        hookObserveStatus = observe.second
        return root
    }

    private fun sectionTitle(container: LinearLayout, title: String): TextView {
        val tv = TextView(this).apply {
            text = title
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
        }
        container.addView(tv)
        return tv
    }

    private fun section(container: LinearLayout, label: String): Pair<TextView, TextView> {
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(2))
        }
        val lbl = TextView(this).apply {
            text = label
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
        }
        val st = TextView(this).apply {
            text = "-"
            textSize = 12f
            gravity = Gravity.END
        }
        head.addView(lbl, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(st, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        container.addView(head)
        val tv = TextView(this).apply {
            text = "未开始"
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#333333"))
        }
        container.addView(tv)
        return tv to st
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun onStartDetect() {
        if (running.get()) return
        pendingRandom.set(false)
        if (!hasPermissions()) {
            requestPermissions(REQUIRED_PERMS.toTypedArray(), 100)
            return
        }
        doStart()
    }

    /** 随机模拟：调用模块调试 API 生成全套随机虚拟环境并启用，然后自动开始检测。 */
    private fun onRandomSimulate() {
        if (running.get()) return
        pendingRandom.set(true)
        if (!hasPermissions()) {
            requestPermissions(REQUIRED_PERMS.toTypedArray(), 100)
            return
        }
        doRandomAndStart()
    }

    /** 网络调用在后台线程执行，UI 更新回主线程。 */
    private fun doRandomAndStart() {
        statusView.text = "正在生成随机环境…"
        refreshExecutor.execute {
            val data = try {
                apiRequest("POST", "/api/debug/random-env", "{}")
            } catch (t: Throwable) {
                Log.w(TAG, "random env call failed", t)
                null
            }
            if (data == null) {
                runOnUiThread {
                    statusView.text = "随机模拟失败：API 不可达或未授权（检查 token/模块状态）"
                    pendingRandom.set(false)
                }
                return@execute
            }
            Log.i(TAG, "random env applied: ${data.toString().take(500)}")
            // 配置已切换：立即开启同步宽限期，让 Hook 层 EnvStateCache 追平
            configChangedAtMs = SystemClock.elapsedRealtime()
            lastExpectFingerprint = ""
            runOnUiThread {
                statusView.text = "随机环境已生成并启用，等待配置同步…"
            }
            // 等待模块 EnvStateCache（500ms 轮询）完成同步，再开始注册监听，
            // 避免 startScan/GNSS 注册发生在配置就绪前导致真实数据放行
            try {
                Thread.sleep(900)
            } catch (_: InterruptedException) {
            }
            runOnUiThread {
                statusView.text = "随机环境已生成并启用，开始检测…"
                doStart()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            if (hasPermissions()) {
                if (pendingRandom.get()) doRandomAndStart() else doStart()
            } else {
                statusView.text = "缺少权限，无法检测"
                pendingRandom.set(false)
            }
        }
    }

    private fun hasPermissions(): Boolean = REQUIRED_PERMS.all {
        checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    private fun doStart() {
        running.set(true)
        // 持久化运行状态：进程被划掉/被杀后重开自动恢复检测
        try {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().putBoolean(KEY_WAS_RUNNING, true).apply()
        } catch (t: Throwable) {
            Log.w(TAG, "save running state failed", t)
        }
        startButton.isEnabled = false
        stopButton.isEnabled = true
        statusView.text = "检测中…（每秒刷新 + 上报报告）"

        val ctx: Context = applicationContext
        locationManager = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        telephonyManager = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        wifiManager = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        sensorManager = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        bleScanner = BluetoothAdapter.getDefaultAdapter()?.bluetoothLeScanner

        sensorRaw.clear()
        synchronized(bleFound) { bleFound.clear() }
        lastStepCount = -1L
        lastGnssStatus = null

        val sm = sensorManager
        try {
            sm?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let {
                sm.registerListener(stepListener, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "step sensor register failed", t)
        }
        for (type in intArrayOf(
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_MAGNETIC_FIELD,
            Sensor.TYPE_LIGHT,
            Sensor.TYPE_PROXIMITY
        )) {
            try {
                sm?.getDefaultSensor(type)?.let {
                    sm.registerListener(rawSensorListener, it, SensorManager.SENSOR_DELAY_NORMAL)
                }
            } catch (_: Throwable) {
            }
        }

        val lm = locationManager
        try {
            lm?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener, Looper.getMainLooper()
            )
        } catch (_: Throwable) {
        }
        try {
            lm?.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER, 1000L, 0f, locationListener, Looper.getMainLooper()
            )
        } catch (_: Throwable) {
        }
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                lm?.registerGnssStatusCallback(
                    java.util.concurrent.Executors.newSingleThreadExecutor(),
                    gnssCallback
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "gnss register failed", t)
        }
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                lm?.addNmeaListener(nmeaListener)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "nmea register failed", t)
        }
        try {
            bleScanner?.startScan(bleScanCallback)
        } catch (t: Throwable) {
            Log.w(TAG, "ble startScan failed", t)
        }
        // 经典发现：注册 ACTION_FOUND 并主动 startDiscovery（蓝牙栈 Hook 会投递虚拟经典设备）
        try {
            val filter = android.content.IntentFilter(BluetoothDevice.ACTION_FOUND)
            registerReceiver(classicReceiver, filter)
        } catch (_: Throwable) {
        }
        synchronized(bleFound) { classicFound.clear() }
        try {
            BluetoothAdapter.getDefaultAdapter()?.startDiscovery()
        } catch (t: Throwable) {
            Log.w(TAG, "classic startDiscovery failed", t)
        }
        try {
            wifiManager?.startScan()
        } catch (_: Throwable) {
        }

        Log.i(TAG, "detector listeners registered")
        // 立即拉取期望配置 + 开启 Hook 层观测（后台执行，不阻塞 UI）
        refreshExecutor.execute {
            apiPost("/api/debug/observe/start", JSONObject())
            refreshExpectations()
        }
        refreshFuture = refreshExecutor.scheduleWithFixedDelay(
            refreshRunnable,
            100,
            REFRESH_MS,
            java.util.concurrent.TimeUnit.MILLISECONDS
        )
    }

    private fun onStopDetect() {
        if (!running.get()) return
        running.set(false)
        refreshFuture?.cancel(false)
        refreshFuture = null
        startButton.isEnabled = true
        stopButton.isEnabled = false
        try {
            sensorManager?.unregisterListener(stepListener)
            sensorManager?.unregisterListener(rawSensorListener)
        } catch (_: Throwable) {
        }
        try {
            bleScanner?.stopScan(bleScanCallback)
        } catch (_: Throwable) {
        }
        try {
            BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()
        } catch (_: Throwable) {
        }
        try {
            unregisterReceiver(classicReceiver)
        } catch (_: Throwable) {
        }
        try {
            locationManager?.removeUpdates(locationListener)
        } catch (_: Throwable) {
        }
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                locationManager?.removeNmeaListener(nmeaListener)
            }
        } catch (_: Throwable) {
        }
        // 结束按钮：清空全部数据结果，并结束 Hook 层观测
        clearResults()
        refreshExecutor.execute {
            apiPost("/api/debug/observe/end", JSONObject())
        }
        Log.i(TAG, "detector stopped and results cleared")
    }

    override fun onDestroy() {
        onStopDetect()
        super.onDestroy()
    }

    private fun refreshAll() {
        // 实时同步期望配置（失败保留上次，判 NOT_ENABLED）
        refreshExpectations()
        try {
            val obs = apiGet("/api/debug/observe/snapshot")
            if (obs != null) hookObserveJson = obs
        } catch (_: Throwable) {
        }
        renderObserve()

        val report = JSONObject().apply {
            put("timestamp", System.currentTimeMillis())
            put("running", true)
        }
        val sb = StringBuilder()

        try {
            val loc = readLastLocation()
            lastLocation = loc
            val text = formatLocation(loc)
            val v = settleVerdict(judgeLocation(loc))
            sb.append("location: ").append(v.name).append(" | ").append(text).append('\n')
            runOnUiThread {
                locationView.text = text
                renderVerdict(locationStatus, v)
            }
            report.put("location", JSONObject().apply {
                put("verdict", v.name)
                put("data", text)
            })
        } catch (t: Throwable) {
            Log.w(TAG, "location read failed", t)
        }
        try {
            val text = formatCell()
            lastCellText = text
            val v = settleVerdict(judgeCell())
            sb.append("cell: ").append(v.name).append(" | ").append(text).append('\n')
            runOnUiThread {
                cellView.text = text
                renderVerdict(cellStatus, v)
            }
            report.put("cell", JSONObject().apply {
                put("verdict", v.name)
                put("data", text)
            })
        } catch (t: Throwable) {
            Log.w(TAG, "cell read failed", t)
        }
        try {
            val text = formatBle()
            val v = settleVerdict(judgeBle())
            sb.append("ble: ").append(v.name).append(" | ").append(text).append('\n')
            runOnUiThread {
                bleView.text = text
                renderVerdict(bleStatus, v)
            }
            report.put("ble", JSONObject().apply {
                put("verdict", v.name)
                put("data", text)
            })
        } catch (t: Throwable) {
            Log.w(TAG, "ble read failed", t)
        }
        try {
            val text = formatWifi()
            lastWifiText = text
            val v = settleVerdict(judgeWifi())
            sb.append("wifi: ").append(v.name).append(" | ").append(text).append('\n')
            runOnUiThread {
                wifiView.text = text
                renderVerdict(wifiStatus, v)
            }
            report.put("wifi", JSONObject().apply {
                put("verdict", v.name)
                put("data", text)
            })
        } catch (t: Throwable) {
            Log.w(TAG, "wifi read failed", t)
        }
        try {
            val text = formatSensor()
            val v = settleVerdict(judgeSensor())
            sb.append("sensor: ").append(v.name).append(" | ").append(text).append('\n')
            runOnUiThread {
                sensorView.text = text
                renderVerdict(sensorStatus, v)
            }
            report.put("sensor", JSONObject().apply {
                put("verdict", v.name)
                put("data", text)
            })
        } catch (t: Throwable) {
            Log.w(TAG, "sensor read failed", t)
        }
        try {
            val text = formatGnss()
            val v = settleVerdict(judgeGnss())
            sb.append("gnss: ").append(v.name).append(" | ").append(text).append('\n')
            runOnUiThread {
                gnssView.text = text
                renderVerdict(gnssStatus, v)
            }
            report.put("gnss", JSONObject().apply {
                put("verdict", v.name)
                put("data", text)
            })
        } catch (t: Throwable) {
            Log.w(TAG, "gnss read failed", t)
        }
        try {
            val text = formatSim()
            lastSimText = text
            val v = settleVerdict(judgeSim())
            sb.append("sim: ").append(v.name).append(" | ").append(text).append('\n')
            runOnUiThread {
                simView.text = text
                renderVerdict(simStatus, v)
            }
            report.put("sim", JSONObject().apply {
                put("verdict", v.name)
                put("data", text)
            })
        } catch (t: Throwable) {
            Log.w(TAG, "sim read failed", t)
        }
        renderPlayback(report)
        report.put("hookObserve", hookObserveJson ?: JSONObject())
        latestReport = report
        Log.i(TAG, sb.toString().trim())
        // 上报报告到模块 ApiServer
        try {
            apiPost("/api/test/report", report)
        } catch (_: Throwable) {
        }
    }

    // ---------- 录像/回放状态识别 ----------

    /**
     * 识别模块当前录像/回放状态并刷新 UI：
     * - 回放中：显示第几段/帧进度/是否暂停/平滑插值开关，判定容差放宽（帧间插值+抖动）
     * - 录制中：显示录制 id 与帧数
     * - 空闲：显示未回放
     * 该状态同时进入上报报告，供自动化检查“正在回放”是否正确识别。
     */
    private fun renderPlayback(report: JSONObject) {
        val ps = playbackStatusJson ?: run {
            runOnUiThread {
                playbackView.text = "未获取模块录像状态（API 不可达）"
                playbackStatus.text = "未知"
                playbackStatus.setTextColor(Color.parseColor("#9E9E9E"))
            }
            report.put("playback", JSONObject().apply { put("verdict", "UNKNOWN") })
            return
        }
        val playing = ps.optBoolean("playing", false)
        val paused = ps.optBoolean("paused", false)
        val recording = ps.optBoolean("recording", false)
        val smooth = ps.optBoolean("smoothLocation", false)
        val frameProgress = ps.optInt("frameProgress", 0)
        val frameCount = ps.optInt("frameCount", 0)
        val playIndex = ps.optInt("playIndex", 0) + 1
        val playlistSize = ps.optInt("playlistSize", 1).coerceAtLeast(1)
        val currentId = ps.optLong("currentRecordingId", -1L)

        val text: String
        val verdict: String
        val color: String
        when {
            recording -> {
                text = "模块正在录制 · id=$currentId · 已累计帧（后端计数）"
                verdict = "RECORDING"
                color = "#1565C0"
            }
            playing && paused -> {
                text = "录像回放中（已暂停） · 第 $playIndex/$playlistSize 段 · 帧 $frameProgress/$frameCount" +
                    if (smooth) " · 平滑插值开" else " · 平滑插值关"
                verdict = "PAUSED"
                color = "#EF6C00"
            }
            playing -> {
                text = "录像回放中 · 第 $playIndex/$playlistSize 段 · 帧 $frameProgress/$frameCount" +
                    if (smooth) " · 平滑插值开" else " · 平滑插值关"
                verdict = "PLAYING"
                color = "#2E7D32"
            }
            else -> {
                text = "未在回放"
                verdict = "IDLE"
                color = "#9E9E9E"
            }
        }
        lastPlaybackFrame = if (playing) frameProgress else -1
        runOnUiThread {
            playbackView.text = text
            playbackStatus.text = verdict
            playbackStatus.setTextColor(Color.parseColor(color))
        }
        report.put(
            "playback",
            JSONObject().apply {
                put("verdict", verdict)
                put("playing", playing)
                put("paused", paused)
                put("recording", recording)
                put("frameProgress", frameProgress)
                put("frameCount", frameCount)
                put("playIndex", playIndex)
                put("playlistSize", playlistSize)
                put("currentRecordingId", currentId)
                put("smoothLocation", smooth)
            }
        )
    }

    // ---------- 判定 ----------

    /** 检测期望配置是否变化：random-env/手动切换后 Hook 层 EnvStateCache 需要数百 ms 同步。 */
    private fun trackConfigChange() {
        val env = expectEnv
        val loc = expectLocation
        val route = expectRoute
        if (env == null && loc == null && route == null) return
        // 录像回放中：期望配置随帧推进变化是预期行为，不应每帧刷新同步宽限，
        // 否则 FAIL 永远被降级为 SYNCING，掩盖真实回放失败
        val ps = playbackStatusJson
        if (ps?.optBoolean("playing", false) == true) {
            val frame = ps.optInt("frameProgress", -1)
            if (frame >= 0 && frame != lastPlaybackFrame) {
                lastPlaybackFrame = frame
                return
            }
        }
        val fp = (env?.toString() ?: "") + "|" + (loc?.toString() ?: "") + "|" + (route?.toString() ?: "")
        if (fp != lastExpectFingerprint) {
            lastExpectFingerprint = fp
            configChangedAtMs = SystemClock.elapsedRealtime()
            Log.i(TAG, "expect config changed, grace until +${SYNC_GRACE_MS}ms")
        }
    }

    /** 配置刚切换的宽限期内，把 FAIL 降级为 SYNCING（避免 Hook 缓存未同步导致的瞬时误判）。 */
    private fun settleVerdict(v: Verdict): Verdict {
        if (v != Verdict.FAIL) return v
        if (configChangedAtMs == 0L) return v
        if (SystemClock.elapsedRealtime() - configChangedAtMs < SYNC_GRACE_MS) {
            return Verdict.SYNCING
        }
        return v
    }

    private fun renderVerdict(view: TextView, v: Verdict) {
        val color = when (v) {
            Verdict.PASS -> Color.parseColor("#2E7D32")
            Verdict.FAIL -> Color.parseColor("#C62828")
            Verdict.SYNCING -> Color.parseColor("#EF6C00")
            Verdict.NOT_ENABLED -> Color.parseColor("#9E9E9E")
            Verdict.UNKNOWN -> Color.parseColor("#9E9E9E")
        }
        val text = when (v) {
            Verdict.PASS -> "通过"
            Verdict.FAIL -> "未通过"
            Verdict.SYNCING -> "同步中"
            Verdict.NOT_ENABLED -> "未启用模拟"
            Verdict.UNKNOWN -> "未知"
        }
        view.text = text
        view.setTextColor(color)
    }

    private fun envEnabled(type: String): Boolean {
        val env = expectEnv ?: return false
        return env.optJSONObject(type)?.optBoolean("enabled", false) == true
    }

    private fun envData(type: String): JSONObject? {
        val env = expectEnv ?: return null
        return if (env.optJSONObject(type)?.optBoolean("enabled", false) == true) {
            env.optJSONObject(type)?.optJSONObject("data")
        } else null
    }

    private fun judgeLocation(loc: Location?): Verdict {
        val expected = expectLocation ?: return Verdict.NOT_ENABLED
        val enabled = expected.optBoolean("enabled", false)
        val mode = expected.optString("mode", "none")
        if (!enabled || mode == "none") return Verdict.NOT_ENABLED
        val expLat = expected.optDouble("latitude", Double.NaN)
        val expLon = expected.optDouble("longitude", Double.NaN)
        if (expLat.isNaN() || expLon.isNaN()) return Verdict.FAIL
        if (loc == null) return Verdict.FAIL
        val results = FloatArray(1)
        Location.distanceBetween(expLat, expLon, loc.latitude, loc.longitude, results)
        // 容差：普通定位 300m、路线 500m；录像回放中因帧间插值+随机抖动放宽到 800m，
        // 且回放进行时按当前帧期望判（期望配置每帧更新，无需额外宽限）
        val ps = playbackStatusJson
        val playing = ps?.optBoolean("playing", false) == true
        val tolerance = when {
            mode == "route" -> 500f
            playing -> 800f
            else -> 300f
        }
        return if (results[0] <= tolerance) Verdict.PASS else Verdict.FAIL
    }

    private fun judgeCell(): Verdict {
        val data = envData("cell") ?: return Verdict.NOT_ENABLED
        val entries = data.optJSONArray("entries") ?: return Verdict.FAIL
        if (entries.length() == 0) {
            // 空基站配置合法：App 读到 0 基站（无基站/空列表）才算通过
            return if (lastCellText.contains("无基站") || lastCellText.isBlank()) Verdict.PASS else Verdict.FAIL
        }
        // 哨兵值兜底：框架 UNAVAILABLE 哨兵泄漏视为 FAIL（nci=unavail 等）
        if (lastCellText.contains("nci=unavail") || lastCellText.contains("ss=unavail") ||
            lastCellText.contains("ci=unavail") || lastCellText.contains("tac=unavail")
        ) {
            return Verdict.FAIL
        }
        for (i in 0 until entries.length()) {
            val e = entries.optJSONObject(i) ?: continue
            val mcc = e.optInt("mcc", -1)
            val mnc = e.optInt("mnc", -1)
            val type = e.optString("type", "LTE").uppercase()
            if (mcc >= 0 && mnc >= 0 &&
                lastCellText.contains("mcc=$mcc") && lastCellText.contains("mnc=$mnc")
            ) {
                val hit = when (type) {
                    "NR" -> {
                        val nci = e.optLong("nci", -1L)
                        if (nci < 0) true
                        else lastCellText.contains("nci=$nci")
                    }
                    "GSM", "WCDMA" -> {
                        val cid = e.optLong("cid", -1L)
                        val lac = e.optLong("lac", -1L)
                        if (cid < 0 && lac < 0) true
                        else (cid < 0 || lastCellText.contains("cid=$cid")) &&
                            (lac < 0 || lastCellText.contains("lac=$lac"))
                    }
                    "CDMA" -> {
                        val sid = e.optInt("sid", -1)
                        val nid = e.optInt("nid", -1)
                        val bid = e.optInt("bid", -1)
                        if (sid < 0 && nid < 0 && bid < 0) true
                        else (sid < 0 || lastCellText.contains("sid=$sid")) &&
                            (nid < 0 || lastCellText.contains("nid=$nid")) &&
                            (bid < 0 || lastCellText.contains("bid=$bid"))
                    }
                    else -> {
                        val tac = e.optLong("tac", -1L)
                        val ci = e.optLong("ci", -1L)
                        if (tac < 0 && ci < 0) true
                        else (tac < 0 || lastCellText.contains("tac=$tac")) &&
                            (ci < 0 || lastCellText.contains("ci=$ci"))
                    }
                }
                if (hit) {
                    // 可选新字段：配置了才校验（earfcn/psc/bsic 等）
                    if (e.has("earfcn") && e.optInt("earfcn") >= 0 &&
                        !lastCellText.contains("earfcn=${e.optInt("earfcn")}")
                    ) return Verdict.FAIL
                    if (e.has("psc") && e.optInt("psc") >= 0 &&
                        !lastCellText.contains("psc=${e.optInt("psc")}")
                    ) return Verdict.FAIL
                    if (e.has("bsic") && e.optInt("bsic") >= 0 &&
                        !lastCellText.contains("bsic=${e.optInt("bsic")}")
                    ) return Verdict.FAIL
                    if (e.has("nrArfcn") && e.optInt("nrArfcn") >= 0 &&
                        !lastCellText.contains("nrArfcn=${e.optInt("nrArfcn")}")
                    ) return Verdict.FAIL
                    return Verdict.PASS
                }
            }
        }
        return Verdict.FAIL
    }

    private fun judgeBle(): Verdict {
        val data = envData("ble") ?: return Verdict.NOT_ENABLED
        val devices = data.optJSONArray("devices") ?: return Verdict.FAIL
        if (devices.length() == 0) return Verdict.FAIL
        val found: Set<String> = synchronized(bleFound) { bleFound.keys.toSet() }
        val classic: Set<String> = synchronized(bleFound) { classicFound.keys.toSet() }
        var scanHit = false
        for (i in 0 until devices.length()) {
            val d = devices.optJSONObject(i) ?: continue
            val address = d.optString("address", "").uppercase()
            if (address.isBlank()) continue
            val mode = d.optString("mode", "ble").lowercase()
            if (found.contains(address)) {
                scanHit = true
                break
            }
            if ((mode == "classic" || mode == "dual") && classic.contains(address)) {
                scanHit = true
                break
            }
        }
        if (!scanHit) return Verdict.FAIL
        // 蓝牙适配器身份：配置了 adapterMac/adapterName 时必须命中（BluetoothAdapter.getAddress/getName 虚拟化）
        val mac = data.optString("adapterMac", "").uppercase()
        val name = data.optString("adapterName", "")
        if (mac.isEmpty() && name.isEmpty()) return Verdict.PASS
        val text = lastBtIdentityText.uppercase()
        val hit = (mac.isNotEmpty() && text.contains(mac)) || (name.isNotEmpty() && text.contains(name))
        return if (hit) Verdict.PASS else Verdict.FAIL
    }

    private fun judgeWifi(): Verdict {
        val data = envData("wifi") ?: return Verdict.NOT_ENABLED
        val networks = data.optJSONArray("networks") ?: return Verdict.FAIL
        if (networks.length() == 0) return Verdict.FAIL
        for (i in 0 until networks.length()) {
            val n = networks.optJSONObject(i) ?: continue
            val ssid = n.optString("ssid", "")
            val bssid = n.optString("bssid", "").uppercase()
            if (ssid.isNotEmpty() && lastWifiText.contains(ssid)) return Verdict.PASS
            if (bssid.isNotEmpty() && lastWifiText.contains(bssid)) return Verdict.PASS
            // 已连接状态：条目 connected=true 时要求检测文本出现 [已连接] + 同 ssid/bssid
            if (n.optBoolean("connected", false)) {
                if (!lastWifiText.contains("[已连接]")) return Verdict.FAIL
                if (ssid.isNotEmpty() && lastWifiText.contains(ssid)) return Verdict.PASS
                if (bssid.isNotEmpty() && lastWifiText.contains(bssid)) return Verdict.PASS
            }
        }
        return Verdict.FAIL
    }

    private fun judgeSensor(): Verdict {
        val data = envData("sensor") ?: return Verdict.NOT_ENABLED
        val stepFreq = data.optInt("stepFrequency", 0)
        val hasEvents = data.optJSONArray("events")?.length() ?: 0
        if (stepFreq <= 0 && hasEvents <= 0) return Verdict.NOT_ENABLED
        return if (lastStepCount >= 0) Verdict.PASS else Verdict.FAIL
    }

    private fun judgeGnss(): Verdict {
        val data = envData("gnss") ?: return Verdict.NOT_ENABLED
        val expectSat = data.optInt("satelliteCount", 0)
        val expectUsed = data.optInt("usedInFix", 0)
        val expectNmea = data.optBoolean("nmeaEnabled", false)
        if (expectSat <= 0 && expectUsed <= 0 && !expectNmea) return Verdict.NOT_ENABLED
        // 虚拟 NMEA：期望启用时必须收到 $GPRMC（模块在 registerGnssNmeaCallback 拦截层注入）
        if (expectNmea && !lastNmeaText.contains("\$GPRMC")) return Verdict.FAIL
        if (expectSat > 0 || expectUsed > 0) {
            val status = lastGnssStatus ?: return Verdict.FAIL
            val used = (0 until status.satelliteCount).count { status.usedInFix(it) }
            val satOk = expectSat <= 0 || status.satelliteCount >= (expectSat * 0.8).toInt()
            val usedOk = expectUsed <= 0 || used >= (expectUsed * 0.8).toInt()
            if (!satOk || !usedOk) return Verdict.FAIL
        }
        return Verdict.PASS
    }

    /** SIM 判定：对配置中每个设置了虚拟身份的卡槽，在其对应卡槽分段内比对 mcc/mnc/运营商/IMSI/ICCID。 */
    private fun judgeSim(): Verdict {
        val data = envData("sim") ?: return Verdict.NOT_ENABLED
        val slots = data.optJSONArray("slots") ?: return Verdict.FAIL
        if (slots.length() == 0) return Verdict.FAIL
        if (lastSimText.isBlank() || lastSimText.contains("无 SIM 数据")) return Verdict.FAIL
        // 按卡槽分段：每个 "== 卡槽 X (subId=Y) ==" 到下一个分隔符之间的文本
        val segments = splitSimSegments(lastSimText)
        var anyConfigured = false
        for (i in 0 until slots.length()) {
            val s = slots.optJSONObject(i) ?: continue
            val slotIndex = s.optInt("slotIndex", -1)
            val subId = s.optInt("subId", -1)
            val segText = findSegmentFor(segments, slotIndex, subId)
            if (segText == null) {
                // 配置的卡槽在设备上不存在：不判失败（无此卡槽，真实 SIM 不存在）
                continue
            }
            var hit = 0
            var total = 0
            val mcc = s.optString("mcc", "")
            if (mcc.isNotEmpty()) {
                total++
                if (segText.contains(mcc)) hit++
            }
            val mnc = s.optString("mnc", "")
            if (mnc.isNotEmpty()) {
                total++
                if (segText.contains(mnc)) hit++
            }
            val operator = s.optString("simOperatorName", "").ifEmpty { s.optString("carrier", "") }
            if (operator.isNotEmpty()) {
                total++
                if (segText.contains(operator)) hit++
            }
            val imsi = s.optString("subscriberId", "")
            if (imsi.isNotEmpty()) {
                total++
                if (segText.contains(imsi)) hit++
            }
            val iccid = s.optString("simSerialNumber", "")
            if (iccid.isNotEmpty()) {
                total++
                if (segText.contains(iccid)) hit++
            }
            if (total == 0) continue
            anyConfigured = true
            // 至少 2 项命中视为生效（运营商名称可能被 ROM 截断）
            if (total >= 2 && hit >= 2) return Verdict.PASS
            if (total == 1 && hit == 1) return Verdict.PASS
        }
        return if (anyConfigured) Verdict.FAIL else Verdict.NOT_ENABLED
    }

    /** 按 "== 卡槽 N (subId=Y) ==" 分隔符拆分 SIM 文本段。 */
    private fun splitSimSegments(text: String): List<String> {
        val segments = mutableListOf<String>()
        val lines = text.lines()
        var current = StringBuilder()
        for (line in lines) {
            if (line.startsWith("== 卡槽")) {
                if (current.isNotEmpty()) segments.add(current.toString())
                current = StringBuilder()
            }
            if (current.isNotEmpty() || line.startsWith("== 卡槽")) current.append(line).append('\n')
        }
        if (current.isNotEmpty()) segments.add(current.toString())
        return segments
    }

    /** 找到匹配 slotIndex 或 subId 的卡槽分段。 */
    private fun findSegmentFor(segments: List<String>, slotIndex: Int, subId: Int): String? {
        if (segments.isEmpty()) return null
        for (seg in segments) {
            val head = seg.lineSequence().firstOrNull() ?: continue
            val hasSlot = slotIndex >= 0 && head.contains("卡槽 $slotIndex")
            val hasSub = subId >= 0 && head.contains("subId=$subId")
            if (hasSlot || hasSub) return seg
        }
        // 没有明确匹配时：若配置的是默认卡槽（slotIndex=0/subId=1），匹配第一段
        if (slotIndex == 0 && segments.isNotEmpty()) return segments[0]
        return null
    }

    // ---------- 实时同步与 Hook 层观测 ----------

    /** 拉取期望配置（env/location/route/recording）并跟踪变更。 */
    private fun refreshExpectations() {
        try {
            val env = apiGet("/api/env/status")
            if (env != null) expectEnv = env
        } catch (_: Throwable) {
        }
        try {
            val loc = apiGet("/api/location/status")
            if (loc != null) expectLocation = loc
        } catch (_: Throwable) {
        }
        try {
            val route = apiGet("/api/route/status")
            if (route != null) expectRoute = route
        } catch (_: Throwable) {
        }
        try {
            val ps = apiGet("/api/recording/status")
            if (ps != null) playbackStatusJson = ps
        } catch (_: Throwable) {
        }
        trackConfigChange()
    }

    /** 位置回调实时计算判定（不等待 1s 刷新周期）。 */
    private fun onRealtimeLocation(location: Location) {
        runOnUiThread {
            if (!running.get()) return@runOnUiThread
            val text = formatLocation(location)
            val v = settleVerdict(judgeLocation(location))
            locationView.text = text
            renderVerdict(locationStatus, v)
        }
    }

    /** GNSS 回调实时计算判定。 */
    private fun onRealtimeGnss(status: GnssStatus) {
        runOnUiThread {
            if (!running.get()) return@runOnUiThread
            val text = formatGnss()
            val v = settleVerdict(judgeGnss())
            gnssView.text = text
            renderVerdict(gnssStatus, v)
        }
    }

    /** NMEA 回调实时计算判定（不等待刷新周期）。 */
    private fun onRealtimeNmea() {
        runOnUiThread {
            if (!running.get()) return@runOnUiThread
            val text = formatGnss()
            val v = settleVerdict(judgeGnss())
            gnssView.text = text
            renderVerdict(gnssStatus, v)
        }
    }

    /** 传感器回调实时计算判定（高频事件节流到 400ms）。 */
    private fun onRealtimeSensor() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSensorUiMs < 400L) return
        lastSensorUiMs = now
        runOnUiThread {
            if (!running.get()) return@runOnUiThread
            val text = formatSensor()
            val v = settleVerdict(judgeSensor())
            sensorView.text = text
            renderVerdict(sensorStatus, v)
        }
    }

    /** BLE 回调实时计算判定（高频扫描节流到 500ms）。 */
    private fun onRealtimeBle() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBleUiMs < 500L) return
        lastBleUiMs = now
        runOnUiThread {
            if (!running.get()) return@runOnUiThread
            val text = formatBle()
            val v = settleVerdict(judgeBle())
            bleView.text = text
            renderVerdict(bleStatus, v)
        }
    }

    /** 渲染 Hook 层观测快照（Hook 点最近经手的真实数据）。 */
    private fun renderObserve() {
        val obs = hookObserveJson ?: run {
            runOnUiThread {
                hookObserveView.text = "未获取 Hook 层观测（API 不可达）"
                hookObserveStatus.text = "未知"
                hookObserveStatus.setTextColor(Color.parseColor("#9E9E9E"))
            }
            return
        }
        val active = obs.optBoolean("active", false)
        val loc = obs.optJSONObject("location")
        val cells = obs.optJSONArray("cells")
        val wifi = obs.optJSONArray("wifi")
        val gnss = obs.optJSONObject("gnss")
        val sb = StringBuilder()
        if (loc != null) {
            sb.append("真实位置: ")
                .append(String.format(
                    Locale.US,
                    "%.6f, %.6f",
                    loc.optDouble("latitude"),
                    loc.optDouble("longitude")
                ))
                .append(" (").append(loc.optString("provider")).append(")\n")
        }
        if (cells != null && cells.length() > 0) {
            val c0 = cells.optJSONObject(0)
            sb.append("基站: ").append(cells.length()).append(" 个")
            if (c0 != null) {
                sb.append(" (").append(c0.optString("type"))
                    .append(" mcc=").append(c0.optInt("mcc"))
                    .append(" mnc=").append(c0.optInt("mnc")).append(')')
            }
            sb.append('\n')
        }
        if (wifi != null && wifi.length() > 0) {
            sb.append("WiFi: ").append(wifi.length()).append(" 个\n")
        }
        if (gnss != null) {
            sb.append("GNSS: ").append(gnss.optInt("satelliteCount"))
                .append(" 颗 / 使用 ").append(gnss.optInt("usedInFix")).append('\n')
        }
        if (sb.isEmpty()) {
            sb.append("暂无观测数据（开始检测后 Hook 点持续记录真实数据）")
        }
        val text = sb.toString().trim()
        val statusText = if (active) "观测中" else "已停止"
        val statusColor = if (active) Color.parseColor("#EF6C00") else Color.parseColor("#9E9E9E")
        runOnUiThread {
            hookObserveView.text = text
            hookObserveStatus.text = statusText
            hookObserveStatus.setTextColor(statusColor)
        }
    }

    /** 结束检测后清空全部数据结果（修复：结束按钮应清空而非保留最后一次快照）。 */
    private fun clearResults() {
        lastLocation = null
        lastCellText = "无基站（等待读取）"
        lastWifiText = "无扫描结果"
        lastSimText = "无 SIM 数据（等待读取）"
        lastStepCount = -1L
        lastGnssStatus = null
        sensorRaw.clear()
        synchronized(bleFound) { bleFound.clear() }
        expectEnv = null
        expectLocation = null
        expectRoute = null
        playbackStatusJson = null
        hookObserveJson = null
        lastPlaybackFrame = -1
        lastExpectFingerprint = ""
        configChangedAtMs = 0L
        lastSensorUiMs = 0L
        lastBleUiMs = 0L
        lastNmeaText = ""
        lastBtIdentityText = ""
        runOnUiThread {
            val gray = Color.parseColor("#9E9E9E")
            fun reset(view: TextView, status: TextView) {
                view.text = "未开始"
                status.text = "-"
                status.setTextColor(gray)
            }
            reset(locationView, locationStatus)
            reset(cellView, cellStatus)
            reset(bleView, bleStatus)
            reset(wifiView, wifiStatus)
            reset(sensorView, sensorStatus)
            reset(gnssView, gnssStatus)
            reset(simView, simStatus)
            playbackView.text = "未开始"
            playbackStatus.text = "-"
            playbackStatus.setTextColor(gray)
            hookObserveView.text = "未开始"
            hookObserveStatus.text = "-"
            hookObserveStatus.setTextColor(gray)
            statusView.text = "已停止，结果已清空"
        }
    }

    /** 导出完整检测报告：本地结果 + Hook 层观测 + 模块调试报告 + 设备信息。 */
    private fun onExportReport() {
        val report = latestReport
        if (report == null) {
            Toast.makeText(this, "暂无检测报告（请先开始检测）", Toast.LENGTH_SHORT).show()
            return
        }
        refreshExecutor.execute {
            try {
                val moduleReport = try {
                    apiGet("/api/report/export")
                } catch (t: Throwable) {
                    null
                }
                val full = JSONObject().apply {
                    put("reportType", "detector")
                    put("exportedAt", System.currentTimeMillis())
                    put("device", JSONObject().apply {
                        put("model", Build.MODEL)
                        put("manufacturer", Build.MANUFACTURER)
                        put("device", Build.DEVICE)
                        put("product", Build.PRODUCT)
                        put("fingerprint", Build.FINGERPRINT)
                        put("sdk", Build.VERSION.SDK_INT)
                        put("release", Build.VERSION.RELEASE)
                    })
                    put("detectorVersion", runCatching {
                        packageManager.getPackageInfo(packageName, 0).versionName
                    }.getOrDefault(""))
                    moduleReport?.let { put("moduleReport", it) }
                    put("result", report)
                }
                val name = "ZVE_DetectorReport_${System.currentTimeMillis()}.json"
                val written = writeReportToDownloads(full, name)
                runOnUiThread {
                    Toast.makeText(
                        this,
                        if (written) "报告已导出：$name" else "导出失败（请检查存储/权限）",
                        Toast.LENGTH_LONG
                    ).show()
                }
                Log.i(TAG, "report exported name=$name ok=$written size=${full.toString().length}")
            } catch (t: Throwable) {
                Log.w(TAG, "report export failed", t)
            }
        }
    }

    /** 写入 Download 目录：API 29+ 走 MediaStore，26-28 写应用专属外部目录（免权限）。 */
    private fun writeReportToDownloads(json: JSONObject, fileName: String): Boolean {
        return try {
            val text = json.toString(2)
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/json")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return false
                contentResolver.openOutputStream(uri)?.use {
                    it.write(text.toByteArray(StandardCharsets.UTF_8))
                }
                true
            } else {
                val dir = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "")
                if (!dir.exists()) dir.mkdirs()
                val f = File(dir, fileName)
                f.writeText(text, StandardCharsets.UTF_8)
                true
            }
        } catch (t: Throwable) {
            Log.w(TAG, "write report file failed", t)
            false
        }
    }

    // ---------- 实读 ----------

    private fun readLastLocation(): Location? {
        val lm = locationManager ?: return null
        var best: Location? = null
        for (provider in arrayOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )) {
            try {
                val loc = lm.getLastKnownLocation(provider) ?: continue
                if (best == null || loc.time > best.time) best = loc
            } catch (_: Throwable) {
            }
        }
        return best
    }

    private fun formatLocation(loc: Location?): String {
        if (loc == null) return "无位置（等待定位）"
        return "provider=${loc.provider}\n" +
            String.format(Locale.US, "lat=%.6f lon=%.6f", loc.latitude, loc.longitude) +
            "\nacc=" + loc.accuracy +
            " alt=" + String.format(Locale.US, "%.1f", loc.altitude) +
            " speed=" + String.format(Locale.US, "%.1f", loc.speed) +
            "\ntime=" + loc.time
    }

    /** MCC/MNC 字符串：0..999 合法，越界/哨兵显示 unavail。 */
    private fun fmtCellMccStr(v: String?): String {
        val n = v?.toIntOrNull() ?: return "unavail"
        return if (n in 0..999) n.toString() else "unavail"
    }

    /** int 哨兵值（Integer.MAX_VALUE）显示为 unavail。 */
    private fun fmtInt(v: Int?): String {
        if (v == null || v == Int.MAX_VALUE) return "unavail"
        return v.toString()
    }

    /** long 哨兵值（Long.MAX_VALUE / CellInfo.UNAVAILABLE_LONG）显示为 unavail。 */
    private fun fmtLong(v: Long): String {
        if (v == Long.MAX_VALUE || v == 2147483647L) return "unavail"
        return v.toString()
    }

    /** 反射读取 int getter（不同 ROM/API 隐藏方法兼容），失败返回 null。 */
    private fun reflectCellInt(target: Any, methodName: String): Int? {
        return try {
            target.javaClass.getMethod(methodName).invoke(target) as? Int
        } catch (_: Throwable) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun formatCell(): String {
        val tm = telephonyManager ?: return "TelephonyManager 不可用"
        val cells: List<CellInfo> = try {
            tm.allCellInfo ?: emptyList()
        } catch (t: Throwable) {
            return "读取失败: ${t.message}"
        }
        if (cells.isEmpty()) return "无基站（虚拟基站未启用或权限不足）"
        val sb = StringBuilder()
        cells.forEach { info ->
            when (info) {
                is CellInfoLte -> {
                    val id = info.cellIdentity
                    val sig = info.cellSignalStrength
                    sb.append("LTE mcc=").append(fmtCellMccStr(id.mccString))
                        .append(" mnc=").append(fmtCellMccStr(id.mncString))
                        .append(" tac=").append(fmtInt(id.tac))
                        .append(" ci=").append(fmtLong(id.ci.toLong()))
                        .append(" pci=").append(fmtInt(id.pci))
                    reflectCellInt(id, "getEarfcn")?.let { if (it in 0..262143) sb.append(" earfcn=").append(it) }
                    if (sig != null) {
                        sb.append(" rsrp=").append(fmtInt(reflectCellInt(sig, "getRsrp")))
                        sb.append(" rsrq=").append(fmtInt(reflectCellInt(sig, "getRsrq")))
                        sb.append(" sinr=").append(fmtInt(reflectCellInt(sig, "getRssnr")))
                        sb.append(" ta=").append(fmtInt(reflectCellInt(sig, "getTimingAdvance")))
                    }
                    sb.append('\n')
                }
                is CellInfoNr -> {
                    val id = info.cellIdentity as? android.telephony.CellIdentityNr
                    val sig = info.cellSignalStrength
                    sb.append("NR")
                    if (id != null) {
                        sb.append(" mcc=").append(id.mccString)
                            .append(" mnc=").append(id.mncString)
                            .append(" tac=").append(fmtInt(id.tac))
                            .append(" nci=").append(fmtLong(id.nci))
                            .append(" pci=").append(fmtInt(id.pci))
                        reflectCellInt(id, "getNrArfcn")?.let { if (it in 0..3279165) sb.append(" nrArfcn=").append(it) }
                    }
                    if (sig != null) {
                        sb.append(" ssRsrp=").append(fmtInt(reflectCellInt(sig, "getSsRsrp")))
                        sb.append(" ssRsrq=").append(fmtInt(reflectCellInt(sig, "getSsRsrq")))
                        sb.append(" ssSinr=").append(fmtInt(reflectCellInt(sig, "getSsSinr")))
                    }
                    sb.append('\n')
                }
                is CellInfoGsm -> {
                    val id = info.cellIdentity
                    val sig = info.cellSignalStrength
                    sb.append("GSM mcc=").append(fmtCellMccStr(id.mccString))
                        .append(" mnc=").append(fmtCellMccStr(id.mncString))
                        .append(" lac=").append(fmtInt(id.lac))
                        .append(" cid=").append(fmtInt(id.cid))
                    reflectCellInt(id, "getBsic")?.let { if (it >= 0) sb.append(" bsic=").append(it) }
                    if (sig != null) {
                        sb.append(" rssi=").append(fmtInt(reflectCellInt(sig, "getDbm")))
                        sb.append(" ta=").append(fmtInt(reflectCellInt(sig, "getTimingAdvance")))
                    }
                    sb.append('\n')
                }
                is CellInfoCdma -> {
                    val id = info.cellIdentity
                    sb.append("CDMA lat=").append(id.latitude)
                        .append(" lon=").append(id.longitude)
                    reflectCellInt(id, "getSid")?.let { if (it >= 0) sb.append(" sid=").append(it) }
                    reflectCellInt(id, "getNid")?.let { if (it >= 0) sb.append(" nid=").append(it) }
                    reflectCellInt(id, "getBid")?.let { if (it >= 0) sb.append(" bid=").append(it) }
                    sb.append('\n')
                }
                is CellInfoWcdma -> {
                    val id = info.cellIdentity
                    val sig = info.cellSignalStrength
                    sb.append("WCDMA mcc=").append(fmtCellMccStr(id.mccString))
                        .append(" mnc=").append(fmtCellMccStr(id.mncString))
                        .append(" lac=").append(fmtInt(id.lac))
                        .append(" cid=").append(fmtInt(id.cid))
                    reflectCellInt(id, "getPsc")?.let { if (it >= 0) sb.append(" psc=").append(it) }
                    if (sig != null) {
                        sb.append(" rssi=").append(fmtInt(reflectCellInt(sig, "getDbm")))
                        sb.append(" rscp=").append(fmtInt(reflectCellInt(sig, "getRscp")))
                        sb.append(" ecno=").append(fmtInt(reflectCellInt(sig, "getEcNo")))
                    }
                    sb.append('\n')
                }
                else -> {
                    sb.append(info.javaClass.simpleName).append('\n')
                }
            }
        }
        return sb.toString().trim()
    }

    private fun formatBle(): String {
        val found: List<String> = synchronized(bleFound) { bleFound.values.toList() }
        val classic: List<String> = synchronized(bleFound) { classicFound.values.toList() }
        val sb = StringBuilder()
        // 蓝牙适配器身份：MAC/名称（虚拟化时由 system_server BluetoothManagerService 拦截）
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter != null) {
                val addr = runCatching { adapter.address }.getOrDefault("")
                val name = runCatching { adapter.name }.getOrDefault("")
                sb.append("适配器: ").append(name).append(" / ").append(addr).append('\n')
            }
        } catch (t: Throwable) {
            sb.append("适配器读取失败: ").append(t.message).append('\n')
        }
        if (found.isNotEmpty()) sb.append(found.take(10).joinToString("\n"))
        if (classic.isNotEmpty()) sb.append("\n[经典] ").append(classic.take(10).joinToString("\n[经典] "))
        lastBtIdentityText = sb.toString()
        return sb.toString().trim().ifEmpty { "无 BLE 结果（等待扫描回调）" }
    }

    private fun formatWifi(): String {
        val wm = wifiManager ?: return "WifiManager 不可用"
        val results: List<android.net.wifi.ScanResult> = try {
            wm.scanResults
        } catch (t: Throwable) {
            return "读取失败: ${t.message}"
        }
        val sb = StringBuilder()
        // 已连接状态（虚拟 WiFi 已连接模拟时 connectionInfo 返回 COMPLETED/IP）
        try {
            val conn = wm.connectionInfo
            if (conn != null && !conn.ssid.isNullOrBlank()) {
                sb.append("[已连接] ").append(conn.ssid?.removeSurrounding("\"")).append(" ").append(conn.bssid)
                    .append(" ").append(conn.rssi).append("dBm")
                    .append(" ").append(conn.linkSpeed).append("Mbps")
                    .append(" ").append(conn.supplicantState?.name)
                    .append(" ").append(android.text.format.Formatter.formatIpAddress(conn.ipAddress))
                    .append('\n')
            }
        } catch (_: Throwable) {
        }
        if (results.isNotEmpty()) {
            sb.append(results.take(10).joinToString("\n") {
                "${it.SSID} ${it.BSSID} ${it.level}dBm"
            })
        }
        return sb.toString().trim().ifEmpty { "无扫描结果" }
    }

    private fun formatSensor(): String {
        val sb = StringBuilder()
        if (lastStepCount >= 0) {
            sb.append("计步器步数: ").append(lastStepCount).append('\n')
        } else {
            sb.append("计步器: 未收到事件\n")
        }
        val raws = sensorRaw.values.take(5)
        if (raws.isNotEmpty()) {
            sb.append(raws.joinToString("\n"))
        }
        return sb.toString().trim().ifEmpty { "无传感器数据" }
    }

    private fun formatGnss(): String {
        val sb = StringBuilder()
        val status = lastGnssStatus
        if (status != null) {
            val used = (0 until status.satelliteCount).count { status.usedInFix(it) }
            sb.append("卫星总数: ").append(status.satelliteCount)
                .append(" 使用: ").append(used).append('\n')
            val top = (0 until status.satelliteCount).take(12).joinToString("\n") { i ->
                val cn0 = status.getCn0DbHz(i)
                val svid = status.getSvid(i)
                String.format(
                    Locale.US,
                    "sv%02d cn0=%.1f used=%s",
                    svid, cn0, if (status.usedInFix(i)) "Y" else "N"
                )
            }
            if (top.isNotEmpty()) sb.append(top).append('\n')
        } else {
            sb.append("卫星: 无回调\n")
        }
        val nmea = lastNmeaText
        sb.append(if (nmea.isBlank()) "NMEA: 未收到" else "NMEA: ").append(nmea.trim().take(80))
        return sb.toString()
    }

    @Suppress("DEPRECATION")
    private fun formatSim(): String {
        val tm = telephonyManager ?: return "TelephonyManager 不可用"
        val sb = StringBuilder()
        // 读取所有活跃卡槽（SubscriptionManager），逐个展示 SIM 身份/信号
        val subs: List<android.telephony.SubscriptionInfo> = try {
            val sm = getSystemService(android.telephony.SubscriptionManager::class.java)
            sm.activeSubscriptionInfoList ?: emptyList()
        } catch (t: Throwable) {
            emptyList()
        }
        if (subs.isEmpty()) {
            sb.append("无活跃订阅（无卡或权限不足）\n")
        } else {
            for (sub in subs) {
                val slotIdx = try { sub.simSlotIndex } catch (t: Throwable) { -1 }
                val subId = try { sub.subscriptionId } catch (t: Throwable) { -1 }
                sb.append("== 卡槽 ").append(slotIdx).append(" (subId=").append(subId).append(") ==\n")
                try {
                    val subTm = tm.createForSubscriptionId(subId)
                    sb.append("国家码: ").append(runCatching { subTm.simCountryIso }.getOrDefault("")).append('\n')
                    sb.append("运营商: ").append(runCatching { subTm.simOperatorName }.getOrDefault("")).append('\n')
                    sb.append("网络运营商: ").append(runCatching { subTm.networkOperatorName }.getOrDefault("")).append('\n')
                    sb.append("SIM 运营商代码: ").append(runCatching { subTm.simOperator }.getOrDefault("")).append('\n')
                    sb.append("网络代码: ").append(runCatching { subTm.networkOperator }.getOrDefault("")).append('\n')
                    sb.append("IMSI: ").append(runCatching { subTm.subscriberId }.getOrDefault("")).append('\n')
                    sb.append("ICCID: ").append(runCatching { subTm.simSerialNumber }.getOrDefault("")).append('\n')
                    sb.append("号码: ").append(runCatching { subTm.line1Number }.getOrDefault("")).append('\n')
                } catch (t: Throwable) {
                    sb.append("卡槽读取失败: ").append(t.message).append('\n')
                }
            }
        }
        sb.append("状态: ").append(runCatching { tm.simState }.getOrDefault(-1)).append('\n')
        try {
            val ss = tm.signalStrength
            if (ss != null) {
                sb.append("信号 Lv:").append(runCatching { ss.level }.getOrDefault(-1))
                sb.append(" GSM:").append(runCatching { ss.gsmSignalStrength }.getOrDefault(Int.MIN_VALUE))
                if (Build.VERSION.SDK_INT >= 28) {
                    val lte = ss.getCellSignalStrengths(android.telephony.CellSignalStrengthLte::class.java)
                    if (lte.isNotEmpty()) sb.append(" LTE rsrp:").append(lte[0].dbm)
                }
                if (Build.VERSION.SDK_INT >= 29) {
                    val nr = ss.getCellSignalStrengths(android.telephony.CellSignalStrengthNr::class.java)
                    if (nr.isNotEmpty()) sb.append(" NR rsrp:").append(nr[0].dbm)
                }
                sb.append('\n')
            }
        } catch (_: Throwable) {
        }
        return sb.toString().trim().ifEmpty { "无 SIM 数据（无卡或权限不足）" }
    }

    // ---------- 模块 ApiServer 客户端（带 token，raw TCP 绕开 Tun 代理） ----------

    /** GET /api/... 返回 data 对象；未授权/失败返回 null。 */
    private fun apiGet(path: String): JSONObject? {
        return apiRequest("GET", path, null)
    }

    private fun apiPost(path: String, body: JSONObject): Boolean {
        return apiRequest("POST", path, body.toString()) != null
    }

    /**
     * raw TCP HTTP 请求（GET/POST）。
     *
     * 不用 HttpURLConnection：设备上 Box for Magisk Tun 代理会劫持
     * HttpURLConnection（EOF/空响应），而 raw Socket 直连 127.0.0.1 可绕过。
     */
    private fun apiRequest(method: String, path: String, body: String?): JSONObject? {
        if (apiToken.isEmpty()) return null
        var socket: java.net.Socket? = null
        return try {
            socket = java.net.Socket()
            socket.connect(java.net.InetSocketAddress("127.0.0.1", 18790), 3000)
            socket.soTimeout = 5000
            val bodyBytes = body?.toByteArray(StandardCharsets.UTF_8)
            val header = buildString {
                append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                append("Host: 127.0.0.1\r\n")
                append("X-ZVE-Token: ").append(apiToken).append("\r\n")
                if (bodyBytes != null) append("Content-Length: ").append(bodyBytes.size).append("\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
            val out = socket.getOutputStream()
            out.write(header.toByteArray(StandardCharsets.UTF_8))
            if (bodyBytes != null) out.write(bodyBytes)
            out.flush()

            val input = java.io.BufferedInputStream(socket.getInputStream())
            val statusLine = readLineBytes(input) ?: return null
            if (!statusLine.contains("200")) return null
            var contentLength = 0
            while (true) {
                val line = readLineBytes(input) ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                }
            }
            val bytes = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = input.read(bytes, read, contentLength - read)
                if (n < 0) break
                read += n
            }
            val text = String(bytes, 0, read, StandardCharsets.UTF_8)
            if (text.isBlank()) return null
            JSONObject(text).optJSONObject("data")
        } catch (t: Throwable) {
            Log.w(TAG, "api $method $path failed: ${t.javaClass.name}: ${t.message}", t)
            null
        } finally {
            try {
                socket?.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun readLineBytes(input: java.io.BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
        }
        return sb.toString()
    }
}
