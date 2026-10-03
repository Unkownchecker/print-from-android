package com.lanprint.android

import androidx.activity.result.contract.ActivityResultContracts
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private var service: PrintServerService? = null
    private var bound = false
    private val uiHandler = Handler(Looper.getMainLooper())
    private val mainScope = MainScope()

    private lateinit var statusText: TextView
    private lateinit var usbStatusText: TextView
    private lateinit var pairingIdText: TextView
    private lateinit var relayUrlInput: EditText
    private lateinit var countdownText: TextView
    private lateinit var jobsText: TextView
    private lateinit var firmwareStatusText: TextView

    private val firmwarePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null) {
                Toast.makeText(this, "Couldn't read that file", Toast.LENGTH_LONG).show()
            } else {
                mainScope.launch {
                    val error = withContext(Dispatchers.IO) {
                        service?.installFirmwareFile(bytes)
                    }
                    if (error == null) {
                        val status = service?.getFirmwareStatus() ?: "USB transfer completed"
                        Toast.makeText(
                            this@MainActivity,
                            status,
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
                    }
                    refreshFirmwareStatus()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't read that file: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as PrintServerService.LocalBinder).getService()
            bound = true
            wireCallbacks()
            refreshAll()
            checkForAlreadyAttachedDevice()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            bound = false
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        usbStatusText = findViewById(R.id.usbStatusText)
        pairingIdText = findViewById(R.id.pairingIdText)
        relayUrlInput = findViewById(R.id.relayUrlInput)
        countdownText = findViewById(R.id.countdownText)
        jobsText = findViewById(R.id.jobsText)
        firmwareStatusText = findViewById(R.id.firmwareStatusText)

        findViewById<Button>(R.id.saveRelayBtn).setOnClickListener {
            val url = relayUrlInput.text.toString().trim()
            if (url.isNotBlank()) service?.setRelayUrl(url)
            refreshAll()
        }
        findViewById<Button>(R.id.copyIdBtn).setOnClickListener {
            val id = service?.getPairingId().orEmpty()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Pairing ID", id))
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.regenerateIdBtn).setOnClickListener {
            service?.regenerateId()
            refreshAll()
            Toast.makeText(this, "New pairing ID generated", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.batteryOptBtn).setOnClickListener {
            requestIgnoreBatteryOptimizations()
        }
        findViewById<Button>(R.id.loadFirmwareBtn).setOnClickListener {
            firmwarePicker.launch("*/*")
        }

        requestNotificationPermissionIfNeeded()

        val serviceIntent = Intent(this, PrintServerService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)

        startCountdownTicker()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            device?.let { requestUsbPermissionAndConnect(it) }
        }
    }

    private fun checkForAlreadyAttachedDevice() {
        val svc = service ?: return
        val candidate = svc.getUsbManagerHelper().findCandidateDevices().firstOrNull() ?: return
        requestUsbPermissionAndConnect(candidate)
    }

    private fun requestUsbPermissionAndConnect(device: UsbDevice) {
        val svc = service ?: return
        mainScope.launch {
            val usbHelper = svc.getUsbManagerHelper()
            if (!usbHelper.hasPermission(device)) {
                val granted = usbHelper.requestPermission(device)
                if (!granted) {
                    usbStatusText.text = "USB permission denied — plug the printer back in to try again."
                    return@launch
                }
            }
            svc.tryConnectDevice(device)
        }
    }

    private fun wireCallbacks() {
        val svc = service ?: return
        svc.onStatusUpdate = { uiHandler.post { refreshStatus() } }
        svc.onNextReconnectUpdate = { uiHandler.post { refreshCountdown() } }
        svc.onJobsUpdate = { uiHandler.post { refreshJobs() } }
        svc.onUsbStatusUpdate = { text -> uiHandler.post { usbStatusText.text = text; refreshFirmwareStatus() } }
    }

    private fun refreshAll() {
        refreshStatus()
        refreshCountdown()
        refreshJobs()
        refreshFirmwareStatus()
        relayUrlInput.setText(service?.getRelayUrl().orEmpty())
        pairingIdText.text = service?.getPairingId()?.ifBlank { "——————" } ?: "——————"
        usbStatusText.text = service?.getConnectedPrinterName()?.let { "Connected: $it" } ?: "No printer connected"
    }

    private fun refreshFirmwareStatus() {
        firmwareStatusText.text = service?.getFirmwareStatus() ?: "—"
    }

    private fun refreshStatus() {
        val status = service?.getConnectionStatus() ?: "unconfigured"
        statusText.text = when (status) {
            "connected" -> "Connected — reachable from anywhere"
            "connecting" -> "Connecting to relay…"
            "disconnected" -> "Disconnected — retrying…"
            else -> "Not set up"
        }
        pairingIdText.text = service?.getPairingId()?.ifBlank { "——————" } ?: "——————"
    }

    private fun refreshCountdown() {
        val target = service?.getNextReconnectAt() ?: 0L
        if (target <= 0L) {
            countdownText.text = "Next connection refresh: —"
            return
        }
        val remaining = target - System.currentTimeMillis()
        if (remaining <= 0) {
            countdownText.text = "Next connection refresh: any moment now…"
        } else {
            val totalSeconds = remaining / 1000
            countdownText.text = "Next connection refresh: ${totalSeconds / 60}m ${(totalSeconds % 60).toString().padStart(2, '0')}s"
        }
    }

    private fun startCountdownTicker() {
        uiHandler.postDelayed(object : Runnable {
            override fun run() {
                refreshCountdown()
                uiHandler.postDelayed(this, 1000)
            }
        }, 1000)
    }

    private fun refreshJobs() {
        val jobs = service?.getRecentJobs().orEmpty()
        if (jobs.isEmpty()) {
            jobsText.text = "Nothing printed yet."
            return
        }
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        jobsText.text = jobs.take(10).joinToString("\n") { j ->
            val mark = if (j.ok) "✓" else "✗"
            "$mark ${fmt.format(j.time)}  ${j.name} → ${j.printer}"
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = android.net.Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } else {
            Toast.makeText(this, "Already exempted from battery optimization", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        if (bound) {
            service?.onStatusUpdate = null
            service?.onNextReconnectUpdate = null
            service?.onJobsUpdate = null
            service?.onUsbStatusUpdate = null
            unbindService(connection)
            bound = false
        }
        super.onDestroy()
    }
}
