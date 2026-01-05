package com.mw2311.stadiaconversiontool

import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import java.io.InputStream
import java.util.concurrent.Executors

class FlashFragment : Fragment(), MainActivity.OnStatusChangedListener {

    private var selectedFirmwareUri: Uri? = null
    private val backgroundExecutor = Executors.newSingleThreadExecutor()
    private val ACTION_USB_PERMISSION = "com.mw2311.USB_PERMISSION"

    private var txtFileStatus: TextView? = null
    private var progressBar: ProgressBar? = null
    private var txtFlashStatus: TextView? = null
    private var txtWarning: TextView? = null
    private var btnFlash: Button? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (ACTION_USB_PERMISSION == intent?.action) {
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (granted) {
                    backgroundExecutor.execute { validateAndShowDialog() }
                } else {
                    Toast.makeText(context, "Permission Denied", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            selectedFirmwareUri = it
            txtFileStatus?.text = "Firmware: ${it.lastPathSegment}"
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_flash, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        ContextCompat.registerReceiver(
            requireContext(),
            usbReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        txtFileStatus = view.findViewById(R.id.txt_file_status)
        progressBar = view.findViewById(R.id.progress_bar_flash)
        txtFlashStatus = view.findViewById(R.id.txt_flash_status)
        txtWarning = view.findViewById(R.id.txt_warning_flash)
        btnFlash = view.findViewById(R.id.btn_start_flash)

        view.findViewById<Button>(R.id.btn_select_firmware).setOnClickListener {
            filePickerLauncher.launch("*/*")
        }

        btnFlash?.setOnClickListener { handleFlashButtonClick() }
        (activity as? MainActivity)?.setOnStatusChangedListener(this)
    }

    private fun handleFlashButtonClick() {
        val mainActivity = activity as? MainActivity ?: return
        val device = mainActivity.getActiveDevice() ?: return
        val usbManager = requireContext().getSystemService(Context.USB_SERVICE) as UsbManager

        if (!usbManager.hasPermission(device)) {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val intent = Intent(ACTION_USB_PERMISSION).apply { `package` = requireContext().packageName }
            val permissionIntent = PendingIntent.getBroadcast(requireContext(), 0, intent, flags)
            usbManager.requestPermission(device, permissionIntent)
        } else {
            backgroundExecutor.execute { validateAndShowDialog() }
        }
    }

    private fun validateAndShowDialog() {
        val uri = selectedFirmwareUri ?: return
        val handler = Handler(Looper.getMainLooper())

        val fileSize = try {
            requireContext().contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0
        } catch (e: Exception) { 0L }

        handler.post {
            AlertDialog.Builder(requireContext())
                .setTitle("⚠️ HARDWARE DEPLOYMENT")
                .setMessage("Firmware ready ($fileSize bytes). Commencing sector erase and write.")
                .setPositiveButton("EXECUTE") { _, _ -> runUsbFlash(uri, fileSize.toInt()) }
                .setNegativeButton("ABORT", null)
                .show()
        }
    }

    private fun runUsbFlash(uri: Uri, totalSize: Int) {
        val mainActivity = activity as? MainActivity ?: return
        val device = mainActivity.getActiveDevice() ?: return
        val handler = Handler(Looper.getMainLooper())

        progressBar?.visibility = View.VISIBLE
        progressBar?.progress = 0
        txtFlashStatus?.visibility = View.VISIBLE
        txtWarning?.visibility = View.VISIBLE

        val anim = AlphaAnimation(0.0f, 1.0f).apply {
            duration = 500
            repeatMode = Animation.REVERSE
            repeatCount = Animation.INFINITE
        }
        txtWarning?.startAnimation(anim)

        Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            val usbManager = requireContext().getSystemService(Context.USB_SERVICE) as UsbManager

            val connection = usbManager.openDevice(device) ?: run {
                handler.post { txtFlashStatus?.text = "Error: Bus Access Denied" }
                return@Thread
            }

            val usbInterface = device.getInterface(0)
            try {
                connection.claimInterface(usbInterface, true)

                // STEP 1: INITIAL SYNC
                handler.post { txtFlashStatus?.text = "Syncing with hardware..." }
                connection.controlTransfer(0x40, 0x01, 0x0000, 0, null, 0, 1000)
                Thread.sleep(1200)

                var hardwareAwake = false
                for (i in 1..40) {
                    val res = connection.controlTransfer(0x40, 0x01, 0x0001, 0, ByteArray(1), 1, 1000)
                    if (res >= 0) { hardwareAwake = true; break }
                    Thread.sleep(100)
                }

                if (!hardwareAwake) throw Exception("Hardware Sync Failed")

                // STEP 2: SECTOR ERASE WARM-UP
                handler.post { txtFlashStatus?.text = "Erasing sectors (may take 10s)..." }
                // Send a small dummy packet to trigger the internal erase cycle
                connection.controlTransfer(0x40, 0x00, 0, 0, null, 0, 500)
                Thread.sleep(2000)

                val inputStream: InputStream? = requireContext().contentResolver.openInputStream(uri)
                var written = 0
                var lastWrite = System.currentTimeMillis()

                inputStream?.use { stream ->
                    // Reduced chunk size to 512 for the absolute highest stability
                    val buffer = ByteArray(512)
                    while (written < totalSize) {
                        // PROFESSIONAL WATCHDOG: Extended to 90 seconds for bulk erases
                        if (System.currentTimeMillis() - lastWrite > 90000) throw Exception("Sector Erase Timeout")

                        val read = stream.read(buffer)
                        if (read == -1) break

                        var blockSent = false
                        var retryCount = 0
                        // Ultra-persistent retry loop
                        while (!blockSent && retryCount < 50) {
                            val result = connection.controlTransfer(0x40, 0x02, (written shr 16) and 0xFFFF, written and 0xFFFF, buffer, read, 30000)

                            if (result >= 0) {
                                blockSent = true
                                written += read
                                lastWrite = System.currentTimeMillis()
                                handler.post {
                                    val progress = (written.toFloat() / totalSize * 100).toInt()
                                    progressBar?.progress = progress
                                    txtFlashStatus?.text = "Writing: $progress%"
                                }
                            } else {
                                // Pulse NOP to keep the pipe open during erase stalls
                                connection.controlTransfer(0x40, 0x00, 0, 0, null, 0, 500)
                                retryCount++
                                // Increase sleep time as we retry to allow for slower erases
                                Thread.sleep(400 + (retryCount * 20))
                            }
                        }
                        if (!blockSent) throw Exception("Hardware Stall at $written")
                    }
                }

                // STEP 3: FINALIZATION
                connection.controlTransfer(0x40, 0x03, 0, 0, null, 0, 2000)
                handler.post {
                    txtWarning?.clearAnimation()
                    txtFlashStatus?.text = "SUCCESS: Flash Finished"
                    Toast.makeText(context, "Controller Updated!", Toast.LENGTH_LONG).show()
                }

            } catch (e: Exception) {
                handler.post {
                    txtWarning?.clearAnimation()
                    txtFlashStatus?.text = "Error: ${e.message}"
                }
            } finally {
                try { connection.releaseInterface(usbInterface) } catch (e: Exception) {}
                connection.close()
            }
        }.start()
    }

    override fun onStatusUpdated(state: Int, info: String) {
        btnFlash?.isEnabled = (state == 2)
    }
}