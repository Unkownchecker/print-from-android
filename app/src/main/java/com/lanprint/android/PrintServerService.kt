package com.lanprint.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

class PrintServerService : Service(), PrinterBackend {

    companion object {
        const val CHANNEL_ID = "lan_print_status"
        const val JOBS_CHANNEL_ID = "lan_print_jobs"
        const val NOTIFICATION_ID = 1
        const val PREFS_NAME = "lan_print_prefs"
        const val KEY_RELAY_URL = "relay_url"
        const val KEY_PAIRING_ID = "pairing_id"
        private const val ID_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // no ambiguous chars
        private const val FIRMWARE_TAG = "FirmwareUpload"

        fun generateId(): String {
            val random = SecureRandom()
            return (1..6).map { ID_CHARS[random.nextInt(ID_CHARS.length)] }.joinToString("")
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): PrintServerService = this@PrintServerService
    }

    private val binder = LocalBinder()
    private lateinit var prefs: SharedPreferences
    private lateinit var usbManager: UsbPrinterManager
    private lateinit var manualDuplexManager: ManualDuplexManager
    private lateinit var relayClient: RelayClient
    private val scope = CoroutineScope(Dispatchers.IO)

    private var connectedPrinter: UsbPrinterManager.ConnectedPrinter? = null
    private var connectionStatus = "unconfigured"
    private var nextReconnectAt = 0L
    private var firmwareSentThisConnection = false
    private var firmwareStatus = "Firmware has not been checked for this connection"
    private val recentJobs = mutableListOf<JobEntry>()

    data class JobEntry(val name: String, val printer: String, val ok: Boolean, val time: Long)

    // ---- UI-facing state, polled/observed by MainActivity ----
    var onStatusUpdate: ((String) -> Unit)? = null
    var onNextReconnectUpdate: ((Long) -> Unit)? = null
    var onJobsUpdate: (() -> Unit)? = null
    var onUsbStatusUpdate: ((String) -> Unit)? = null

    fun getRelayUrl(): String = prefs.getString(KEY_RELAY_URL, "") ?: ""
    fun getPairingId(): String = prefs.getString(KEY_PAIRING_ID, "") ?: ""
    fun getConnectionStatus(): String = connectionStatus
    fun getNextReconnectAt(): Long = nextReconnectAt
    fun getRecentJobs(): List<JobEntry> = recentJobs.toList()
    fun getConnectedPrinterName(): String? = connectedPrinter?.model?.displayName
    fun getFirmwareStatus(): String {
        val printer = connectedPrinter ?: return "No printer connected"
        val fw = firmwareFile(printer.model)
        return if (!fw.exists()) {
            "No firmware file loaded for ${printer.model.firmwareModelName} (see README)"
        } else firmwareStatus
    }

    /** Where a firmware file for [model]'s required firmware name is stored, if the user has provided one. */
    fun firmwareFile(model: PrinterModel): File =
        File(filesDir, "firmware-${model.firmwareModelName}.dl")

    /**
     * Called by the Activity after the user picks a firmware file for the
     * currently connected model. Accepts either a raw .img file, which it
     * converts with arm2hpdl, or an already converted HP .dl file.
     */
    fun installFirmwareFile(bytes: ByteArray): String? {
        val model = connectedPrinter?.model ?: return "Connect a printer first, then load its firmware."

        val dlFile = firmwareFile(model)
        Log.i(
            FIRMWARE_TAG,
            "Selected firmware for ${model.displayName}/${model.firmwareModelName}: " +
                "${bytes.size} bytes, header=${hexPrefix(bytes)}",
        )

        if (isHpDownloadFile(bytes)) {
            dlFile.writeBytes(bytes)
            Log.i(FIRMWARE_TAG, "Input is already HP .dl format")
        } else {
            val workDir = File(cacheDir, "firmware-work").apply { mkdirs() }
            val rawFile = File(workDir, "raw-${System.currentTimeMillis()}.img")
            rawFile.writeBytes(bytes)
            val rc = try {
                Foo2xqx.nativeConvertFirmware(rawFile.absolutePath, dlFile.absolutePath)
            } finally {
                rawFile.delete()
            }
            Log.i(
                FIRMWARE_TAG,
                "arm2hpdl conversion returned $rc; output=${dlFile.length()} bytes, " +
                    "header=${if (dlFile.exists()) hexPrefix(dlFile.readBytes()) else "<missing>"}",
            )

            if (rc != 0) {
                dlFile.delete()
                return "Firmware conversion failed (exit code $rc) — is this the right file for ${model.firmwareModelName}?"
            }
        }

        if (!dlFile.exists() || !isHpDownloadFile(dlFile.readBytes())) {
            dlFile.delete()
            Log.e(FIRMWARE_TAG, "Rejected firmware: output is empty or missing the HP download header")
            return "Firmware file is invalid: expected an HP .dl file or a raw .img for ${model.firmwareModelName}."
        }

        val printer = connectedPrinter
            ?: return "Firmware was prepared, but the printer disconnected before it could be sent."
        firmwareSentThisConnection = false
        firmwareStatus = "Firmware ready; preparing to send"
        return sendFirmwareIfNeeded(printer)
    }

    private fun isHpDownloadFile(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            (bytes[0].toInt() and 0xFF) == 0xBE &&
            (bytes[1].toInt() and 0xFF) == 0xEF &&
            (bytes[2].toInt() and 0xFF) == 0x41 &&
            (bytes[3].toInt() and 0xFF) == 0x42

    private fun sendFirmwareIfNeeded(printer: UsbPrinterManager.ConnectedPrinter): String? {
        if (firmwareSentThisConnection) return null
        val fw = firmwareFile(printer.model)
        if (!fw.exists()) {
            firmwareStatus = "No firmware loaded; load ${printer.model.firmwareModelName} firmware"
            onUsbStatusUpdate?.invoke("Connected: ${printer.model.displayName} — $firmwareStatus (see README)")
            return firmwareStatus
        }
        val firmwareBytes = fw.readBytes()
        firmwareStatus = "Waiting for printer to become ready before firmware upload"
        onUsbStatusUpdate?.invoke("Connected: ${printer.model.displayName} — $firmwareStatus")
        try {
            Thread.sleep(3000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            firmwareStatus = "Firmware upload cancelled while waiting for printer"
            onUsbStatusUpdate?.invoke("Connected: ${printer.model.displayName} — $firmwareStatus")
            return firmwareStatus
        }
        val result = usbManager.sendFirmware(printer, firmwareBytes)
        if (!result.transferComplete) {
            firmwareSentThisConnection = false
            firmwareStatus = "Firmware transfer stopped at ${result.bytesTransferred}/${result.expectedBytes} bytes"
            onUsbStatusUpdate?.invoke("Connected: ${printer.model.displayName} — $firmwareStatus")
            return firmwareStatus
        }
        firmwareSentThisConnection = true
        firmwareStatus = if (result.firmwareReported) {
            "Firmware data sent; printer reports ${result.printerDeviceId}"
        } else {
            "Firmware data sent to printer (${result.bytesTransferred} bytes). No FWVER response; try a test print to confirm readiness."
        }
        Log.i(FIRMWARE_TAG, "Upload status: $firmwareStatus")
        onUsbStatusUpdate?.invoke("Connected: ${printer.model.displayName} — $firmwareStatus")
        return null
    }

    private fun hexPrefix(bytes: ByteArray): String = bytes.take(8).joinToString("") {
        (it.toInt() and 0xFF).toString(16).padStart(2, '0')
    }

    fun setRelayUrl(url: String) {
        var id = getPairingId()
        if (id.isBlank()) {
            id = generateId()
            prefs.edit().putString(KEY_PAIRING_ID, id).apply()
        }
        prefs.edit().putString(KEY_RELAY_URL, url).apply()
        relayClient.configure(url, id)
    }

    fun regenerateId(): String {
        val id = generateId()
        prefs.edit().putString(KEY_PAIRING_ID, id).apply()
        relayClient.configure(getRelayUrl(), id)
        return id
    }

    private val usbAttachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                "android.hardware.usb.action.USB_DEVICE_ATTACHED" -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (device != null) tryConnectDevice(device)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (device != null && device == connectedPrinter?.device) {
                        connectedPrinter?.let { usbManager.disconnect(it) }
                        connectedPrinter = null
                        firmwareSentThisConnection = false
                        onUsbStatusUpdate?.invoke("No printer connected")
                        updateNotification()
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        usbManager = UsbPrinterManager(this)
        manualDuplexManager = ManualDuplexManager(this)
        relayClient = RelayClient(
            backend = this,
            onStatusChange = { status ->
                connectionStatus = status
                onStatusUpdate?.invoke(status)
                updateNotification()
            },
            onNextReconnectChange = { ts ->
                nextReconnectAt = ts
                onNextReconnectUpdate?.invoke(ts)
            },
            onJobRecorded = { name, printer, ok ->
                recentJobs.add(0, JobEntry(name, printer, ok, System.currentTimeMillis()))
                while (recentJobs.size > 30) recentJobs.removeAt(recentJobs.size - 1)
                onJobsUpdate?.invoke()
                notifyJobResult(name, printer, ok)
            },
        )

        createNotificationChannels()
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbAttachReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbAttachReceiver, filter)
        }

        startForeground(NOTIFICATION_ID, buildNotification())

        val savedUrl = getRelayUrl()
        val savedId = getPairingId()
        if (savedUrl.isNotBlank() && savedId.isNotBlank()) {
            relayClient.configure(savedUrl, savedId)
        }
        relayClient.start()

        scope.launch { autoConnectExistingDevice() }
    }

    private fun autoConnectExistingDevice() {
        usbManager.findCandidateDevices().firstOrNull()?.let { tryConnectDevice(it) }
    }

    /** Called both from USB-attach broadcasts and from the Activity after it obtains permission. */
    fun tryConnectDevice(device: UsbDevice) {
        if (!usbManager.hasPermission(device)) {
            onUsbStatusUpdate?.invoke("Waiting for USB permission…")
            return
        }
        val printer = usbManager.connect(device) { reason ->
            onUsbStatusUpdate?.invoke(reason)
        }
        if (printer != null) {
            connectedPrinter?.let { usbManager.disconnect(it) }
            connectedPrinter = printer
            firmwareSentThisConnection = false
            firmwareStatus = "Firmware has not been checked for this connection"
            onUsbStatusUpdate?.invoke("Connected: ${printer.model.displayName}")
            updateNotification()
            scope.launch {
                if (connectedPrinter === printer) sendFirmwareIfNeeded(printer)
            }
        }
    }

    fun getUsbManagerHelper(): UsbPrinterManager = usbManager

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        relayClient.stop()
        connectedPrinter?.let { usbManager.disconnect(it) }
        try { unregisterReceiver(usbAttachReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    // ---- PrinterBackend ----

    override fun listPrinters(): List<PrinterBackend.PrinterInfo> {
        val printer = connectedPrinter ?: return emptyList()
        return listOf(PrinterBackend.PrinterInfo(printer.model.displayName, PAPER_SIZES.keys.toList()))
    }

    override fun print(filename: String, fileBytes: ByteArray, options: JSONObject): PrinterBackend.PrintResult {
        val printer = connectedPrinter
            ?: return PrinterBackend.PrintResult(ok = false, error = "No printer connected to this device.")

        if (!firmwareSentThisConnection) {
            return PrinterBackend.PrintResult(
                ok = false,
                error = "This printer's firmware hasn't been loaded yet for this connection — " +
                    "open the app and load the firmware file for ${printer.model.firmwareModelName} first (see README)."
            )
        }

        val workDir = File(cacheDir, "incoming").apply { mkdirs() }
        val pdfFile = File(workDir, "job-${System.currentTimeMillis()}.pdf")

        val printOptions = XqxPrinter.PrintOptions(
            paperSize = options.optString("paperSize", DEFAULT_PAPER_SIZE).let {
                if (it.isBlank() || it == "default") DEFAULT_PAPER_SIZE else it
            },
            orientationLandscape = options.optString("orientation").equals("landscape", ignoreCase = true) ||
                options.optBoolean("landscape", false),
            copies = options.optString("copies", "1").toIntOrNull() ?: 1,
            scalePercent = options.optString("scalePercent", options.optString("scale", "100"))
                .toIntOrNull()?.coerceIn(10, 200) ?: 100,
        )

        try {
            if (hasPdfSignature(fileBytes)) {
                pdfFile.writeBytes(fileBytes)
            } else if (!writeImagePdf(fileBytes, pdfFile, printOptions)) {
                return PrinterBackend.PrintResult(
                    ok = false,
                    error = "Unsupported file. Send a PDF or an image supported by Android (JPEG, PNG, GIF, BMP, or WebP)."
                )
            }

            val pageCount = XqxPrinter.pdfPageCount(pdfFile.absolutePath)
            val requestedPageRange = options.optString("pageRange", options.optString("pages"))
            val selectedPages = parsePageRange(requestedPageRange, pageCount)
                ?: return PrinterBackend.PrintResult(
                    ok = false,
                    error = "Invalid page range '$requestedPageRange'. Use values such as 1-3,5."
                )
            if (selectedPages.isEmpty()) {
                return PrinterBackend.PrintResult(ok = false, error = "The selected page range contains no pages.")
            }

            val hasBothPageSides = selectedPages.any { it % 2 == 1 } && selectedPages.any { it % 2 == 0 }
            if (isManualDuplexRequested(options) && hasBothPageSides) {
                val start = manualDuplexManager.start(
                    pdfFile.absolutePath,
                    printer.model,
                    printOptions,
                    selectedPages,
                )
                    ?: return PrinterBackend.PrintResult(ok = false, error = "This print needs at least two pages for manual two-sided printing.")
                val oddPages = start.oddPages
                val err = XqxPrinter.printPages(this, usbManager, printer, pdfFile.absolutePath, oddPages, printOptions)
                if (err != null) {
                    manualDuplexManager.cancel(start.jobId)
                    return PrinterBackend.PrintResult(ok = false, error = err)
                }
                return PrinterBackend.PrintResult(
                    ok = true, method = "manual-duplex", phase = 1, jobId = start.jobId,
                    totalPages = start.totalPages, oddCount = start.oddCount, evenCount = start.evenCount,
                )
            }

            val err = XqxPrinter.printPages(this, usbManager, printer, pdfFile.absolutePath, selectedPages, printOptions)
            return if (err != null) PrinterBackend.PrintResult(ok = false, error = err)
            else PrinterBackend.PrintResult(ok = true, method = "direct")
        } finally {
            pdfFile.delete()
        }
    }

    private fun hasPdfSignature(bytes: ByteArray): Boolean =
        bytes.size >= 5 && bytes.copyOfRange(0, 5).toString(Charsets.US_ASCII) == "%PDF-"

    private fun parsePageRange(range: String, pageCount: Int): List<Int>? {
        if (range.isBlank() || range.equals("all", ignoreCase = true)) return (1..pageCount).toList()
        val pages = mutableListOf<Int>()
        for (part in range.split(',')) {
            val token = part.trim()
            val match = Regex("^(\\d+)(?:\\s*-\\s*(\\d+))?$").matchEntire(token) ?: return null
            val first = match.groupValues[1].toIntOrNull() ?: return null
            val last = match.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: first
            if (first !in 1..pageCount || last !in 1..pageCount) return null
            val step = if (first <= last) 1 else -1
            var page = first
            while (true) {
                pages += page
                if (page == last) break
                page += step
            }
        }
        return pages
    }

    private fun isManualDuplexRequested(options: JSONObject): Boolean {
        val side = options.optString("sides", options.optString("side")).lowercase()
        return side == "manual" || side.contains("duplex") || side.contains("two-sided") ||
            side == "long-edge" || side == "short-edge" ||
            options.optBoolean("duplex", false)
    }

    private fun writeImagePdf(bytes: ByteArray, output: File, options: XqxPrinter.PrintOptions): Boolean {
        if (bytes.isEmpty()) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

        val largestDimension = maxOf(bounds.outWidth, bounds.outHeight)
        var sampleSize = 1
        while (largestDimension / sampleSize > 2400) sampleSize *= 2
        val bitmap = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sampleSize }
        ) ?: return false

        val geometry = PAPER_SIZES[options.paperSize.lowercase()] ?: PAPER_SIZES[DEFAULT_PAPER_SIZE]!!
        val portraitWidth = (geometry.widthPx * 72.0 / 1200.0).toInt()
        val portraitHeight = (geometry.heightPx * 72.0 / 600.0).toInt()
        val pageWidth = if (options.orientationLandscape) portraitHeight else portraitWidth
        val pageHeight = if (options.orientationLandscape) portraitWidth else portraitHeight
        val document = PdfDocument()
        return try {
            val page = document.startPage(
                PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
            )
            val rotate = options.orientationLandscape != (bitmap.width > bitmap.height)
            val sourceW = if (rotate) bitmap.height else bitmap.width
            val sourceH = if (rotate) bitmap.width else bitmap.height
            val fitScale = minOf(pageWidth.toFloat() / sourceW, pageHeight.toFloat() / sourceH)
            val drawWidth = (bitmap.width * fitScale).toInt().coerceAtLeast(1)
            val drawHeight = (bitmap.height * fitScale).toInt().coerceAtLeast(1)
            val left = (pageWidth - drawWidth) / 2
            val top = (pageHeight - drawHeight) / 2
            page.canvas.drawColor(android.graphics.Color.WHITE)
            if (rotate) {
                page.canvas.save()
                page.canvas.rotate(90f, pageWidth / 2f, pageHeight / 2f)
            }
            page.canvas.drawBitmap(bitmap, null, Rect(left, top, left + drawWidth, top + drawHeight), null)
            if (rotate) page.canvas.restore()
            document.finishPage(page)
            output.outputStream().use { document.writeTo(it) }
            true
        } finally {
            document.close()
            bitmap.recycle()
        }
    }

    override fun continueManual(jobId: String, reverseEven: Boolean): PrinterBackend.PrintResult {
        val job = manualDuplexManager.get(jobId)
            ?: return PrinterBackend.PrintResult(ok = false, error = "This print job has expired or already finished. Start again from the beginning.")
        val printer = connectedPrinter
            ?: return PrinterBackend.PrintResult(ok = false, error = "No printer connected to this device.")

        val even = manualDuplexManager.evenPages(job.selectedPages, reverseEven)
        val err = XqxPrinter.printPages(this, usbManager, printer, job.pdfPath, even, job.options)
        manualDuplexManager.finish(jobId)
        return if (err != null) PrinterBackend.PrintResult(ok = false, error = err)
        else PrinterBackend.PrintResult(ok = true, method = "manual-duplex", phase = 2, evenCount = even.size)
    }

    override fun cancelManual(jobId: String) {
        manualDuplexManager.cancel(jobId)
    }

    // ---- Notifications ----

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "LAN Print status", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(JOBS_CHANNEL_ID, "LAN Print jobs", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun buildNotification(): Notification {
        val statusText = when (connectionStatus) {
            "connected" -> "Connected, ready to print"
            "connecting" -> "Connecting…"
            "disconnected" -> "Reconnecting…"
            else -> "Print from anywhere not set up"
        }
        val printerText = connectedPrinter?.model?.displayName ?: "No printer connected"
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LAN Print — $printerText")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_menu_send) // replaced by a real icon resource at build time
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun notifyJobResult(name: String, printer: String, ok: Boolean) {
        val title = if (ok) "Print job printed" else "Print job failed"
        val printerPart = if (printer.isNotBlank() && printer != "-") " on $printer" else ""
        val body = if (ok) "$name — printed$printerPart" else "$name — failed to print$printerPart"
        val notification = NotificationCompat.Builder(this, JOBS_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setAutoCancel(true)
            .build()
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(System.currentTimeMillis().toInt(), notification)
    }
}
