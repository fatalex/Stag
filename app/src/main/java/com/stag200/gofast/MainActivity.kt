package com.stag200.gofast

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvFuelBadge: TextView
    private lateinit var tvRpm: TextView
    private lateinit var tvVolt: TextView
    private lateinit var tvGasPress: TextView
    private lateinit var tvMapPress: TextView
    private lateinit var tvTemps: TextView
    private lateinit var tvInjAvg: TextView
    private lateinit var tvCylinders: TextView
    private lateinit var tvEcuMeta: TextView
    private lateinit var tvHexLog: TextView
    private lateinit var oscView: OscilloscopeView
    private lateinit var btnConnect: Button
    private lateinit var btnPoll: Button

    private lateinit var btManager: BluetoothSppManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val logLines = ArrayDeque<String>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private var ecuFwDate = "—"
    private var ecuRtc = "—"
    private var errSummary = "—"
    private var polling = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (polling && btManager.isConnected) {
                sendCommand(UgicProtocol.CMD_LIVE_DUMP, 0x00, "Live (0x64)")
                mainHandler.postDelayed(this, 450L)
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            showPairedDevicesDialog()
        } else {
            Toast.makeText(this, "Нужны разрешения Bluetooth", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvFuelBadge = findViewById(R.id.tvFuelBadge)
        tvRpm = findViewById(R.id.tvRpm)
        tvVolt = findViewById(R.id.tvVolt)
        tvGasPress = findViewById(R.id.tvGasPress)
        tvMapPress = findViewById(R.id.tvMapPress)
        tvTemps = findViewById(R.id.tvTemps)
        tvInjAvg = findViewById(R.id.tvInjAvg)
        tvCylinders = findViewById(R.id.tvCylinders)
        tvEcuMeta = findViewById(R.id.tvEcuMeta)
        tvHexLog = findViewById(R.id.tvHexLog)
        oscView = findViewById(R.id.oscView)
        btnConnect = findViewById(R.id.btnConnect)
        btnPoll = findViewById(R.id.btnPoll)

        btManager = BluetoothSppManager(
            onConnected = { name ->
                runOnUiThread {
                    tvStatus.text = "Подключено: $name"
                    btnConnect.text = "Отключить"
                    btnPoll.isEnabled = true
                    appendLog("SYS", "Подключено к $name")
                    // Start with 0x03 handshake, then read errors, clock, and start live polling
                    sendCommand(UgicProtocol.CMD_SUPPORTED_CMDS, 0x00, "Handshake (0x03)")
                    mainHandler.postDelayed({ sendCommand(UgicProtocol.CMD_ERR_COUNTERS, 0x00, "Ошибки (0x40)") }, 250L)
                    mainHandler.postDelayed({ sendCommand(UgicProtocol.CMD_RTC_CLOCK, 0x00, "Часы (0x49)") }, 500L)
                    mainHandler.postDelayed({ startPolling() }, 800L)
                }
            },
            onDisconnected = { reason ->
                runOnUiThread {
                    stopPolling()
                    tvStatus.text = reason
                    btnConnect.text = "Подключить BT"
                    btnPoll.isEnabled = false
                    tvFuelBadge.text = "ОЖИДАНИЕ"
                    tvFuelBadge.setBackgroundColor(Color.parseColor("#1E293B"))
                    tvFuelBadge.setTextColor(Color.parseColor("#CBD5E1"))
                    appendLog("SYS", reason)
                }
            },
            onFrameReceived = { frame, crcOk ->
                runOnUiThread {
                    val cmd = frame[4].toInt() and 0xFF
                    val payload = frame.copyOfRange(6, frame.size - 1)
                    if (cmd != 0xE4) {
                        appendLog("RX", "${UgicProtocol.toHex(frame)} [${if (crcOk) "OK" else "CRC ERR"}]")
                    }
                    if (!crcOk) return@runOnUiThread

                    when (cmd) {
                        0x80 -> {
                            UgicProtocol.parseFwDate00(payload)?.let { ecuFwDate = it }
                            updateMetaLine()
                        }
                        0xC0 -> {
                            val total = payload.sumOf { it.toInt() and 0xFF }
                            errSummary = if (total == 0) "0 (нет)" else "$total шт."
                            updateMetaLine()
                        }
                        0xC9 -> {
                            UgicProtocol.parseRtc49(payload)?.let { ecuRtc = it }
                            updateMetaLine()
                        }
                        0xE4 -> {
                            UgicProtocol.parseLiveDump64(payload)?.let { renderTelemetry(it) }
                        }
                    }
                }
            },
            onRawBytesReceived = { _ -> }
        )

        btnConnect.setOnClickListener {
            if (btManager.isConnected) {
                btManager.disconnect("Отключено")
            } else {
                ensureBluetoothPermissionsAndPick()
            }
        }

        btnPoll.setOnClickListener {
            if (polling) stopPolling() else startPolling()
        }

        findViewById<Button>(R.id.btnReadErrors).setOnClickListener {
            sendCommand(UgicProtocol.CMD_ERR_COUNTERS, 0x00, "Ошибки (0x40)")
        }
        findViewById<Button>(R.id.btnReadClock).setOnClickListener {
            sendCommand(UgicProtocol.CMD_RTC_CLOCK, 0x00, "Часы (0x49)")
        }
        findViewById<Button>(R.id.btnReadFw).setOnClickListener {
            sendCommand(UgicProtocol.CMD_DEVICE_INFO, 0x00, "Паспорт (0x00)")
        }

        findViewById<Button>(R.id.btnCopyLog).setOnClickListener {
            val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cb.setPrimaryClip(ClipData.newPlainText("STAG200_HEX_LOG", logLines.joinToString("\n")))
            Toast.makeText(this, "Лог скопирован", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnClearLog).setOnClickListener {
            logLines.clear()
            oscView.clear()
            tvHexLog.text = "Лог очищен."
        }
    }

    private fun renderTelemetry(t: UgicProtocol.LiveTelemetry) {
        tvRpm.text = "${t.rpm}"
        tvVolt.text = "АКБ: %.1f В".format(t.voltageV)
        tvGasPress.text = "%.2f бар".format(t.gasPressureBar)
        tvMapPress.text = "MAP: %.2f бар".format(t.mapBar)
        tvTemps.text = "Ред: ${t.tempReducerC}°C\nГаз: ${t.tempGasC}°C"

        val avgPetrol = t.petrolInjMs.average()
        val avgGas = t.gasInjMs.average()
        val multStr = if (avgPetrol > 0.2 && avgGas > 0.2) "%.2f".format(avgGas / avgPetrol) else "—"

        tvInjAvg.text = "Бенз: %.2f мс\nГаз:  %.2f мс".format(avgPetrol, avgGas)
        tvCylinders.text = buildString {
            appendLine("Ц1-4 Бенз: ${t.petrolInjMs.joinToString(" / ") { "%.2f".format(it) }} мс")
            appendLine("Ц1-4 Газ:  ${t.gasInjMs.joinToString(" / ") { "%.2f".format(it) }} мс")
            append("Клапан: ${t.valveCurrentMa}  |  Коэфф: $multStr")
        }

        if (t.isGasActive) {
            tvFuelBadge.text = "ГАЗ (LPG)"
            tvFuelBadge.setBackgroundColor(Color.parseColor("#065F46"))
            tvFuelBadge.setTextColor(Color.parseColor("#6EE7B7"))
        } else {
            tvFuelBadge.text = "БЕНЗИН"
            tvFuelBadge.setBackgroundColor(Color.parseColor("#78350F"))
            tvFuelBadge.setTextColor(Color.parseColor("#FDE68A"))
        }

        oscView.addSample(avgPetrol, avgGas, t.gasPressureBar)
    }

    private fun updateMetaLine() {
        tvEcuMeta.text = "Прошивка: $ecuFwDate  |  Ошибки: $errSummary\nЧасы ЭБУ: $ecuRtc"
    }

    private fun sendCommand(cmd: Byte, sub: Byte, label: String) {
        if (!btManager.isConnected) {
            Toast.makeText(this, "Сначала подключитесь к адаптеру", Toast.LENGTH_SHORT).show()
            return
        }
        val frame = UgicProtocol.buildFrame(cmd, sub)
        if (cmd != UgicProtocol.CMD_LIVE_DUMP) {
            appendLog("TX", "${UgicProtocol.toHex(frame)} // $label")
        }
        btManager.send(frame)
    }

    private fun startPolling() {
        polling = true
        btnPoll.text = "Стоп опрос"
        mainHandler.removeCallbacks(pollRunnable)
        mainHandler.post(pollRunnable)
    }

    private fun stopPolling() {
        polling = false
        btnPoll.text = "Автоопрос"
        mainHandler.removeCallbacks(pollRunnable)
    }

    private fun appendLog(tag: String, msg: String) {
        val line = "${timeFmt.format(Date())} [$tag] $msg"
        logLines.addFirst(line)
        while (logLines.size > 120) logLines.removeLast()
        tvHexLog.text = logLines.joinToString("\n")
    }

    private fun ensureBluetoothPermissionsAndPick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val needed = arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
            val missing = needed.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) {
                permissionLauncher.launch(missing.toTypedArray())
                return
            }
        }
        showPairedDevicesDialog()
    }

    @SuppressLint("MissingPermission")
    private fun showPairedDevicesDialog() {
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter? = manager.adapter
        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, "Включите Bluetooth на телефоне", Toast.LENGTH_LONG).show()
            return
        }
        val paired: List<BluetoothDevice> = adapter.bondedDevices?.toList().orEmpty()
        if (paired.isEmpty()) {
            Toast.makeText(this, "Нет сопряжённых Bluetooth-устройств.", Toast.LENGTH_LONG).show()
            return
        }
        val names = paired.map { "${it.name ?: "Без имени"}\n${it.address}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Выберите адаптер STAG")
            .setItems(names) { _, which ->
                val device = paired[which]
                tvStatus.text = "Подключение к ${device.name ?: device.address}..."
                btManager.connect(device)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPolling()
        btManager.disconnect()
    }
}
